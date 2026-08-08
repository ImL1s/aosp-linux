#[cfg(test)]
mod empirical_tests {
    use crate::auth::{verify_token, perform_handshake};
    use crate::portal::{handle_portal_session, MAX_PAYLOAD_SIZE as PORTAL_MAX_PAYLOAD};
    use crate::pty::{handle_pty_session, PtyHeader, MSG_TYPE_DATA, MAX_PAYLOAD_SIZE as PTY_MAX_PAYLOAD};
    use crate::wayland::proxy_split;
    use std::io::{Read, Write};
    use std::os::unix::net::UnixStream;
    use std::sync::{Arc, Barrier};
    use std::thread;

    /// Test 1: PTY shell execution under connection disconnects to verify NO SIGABRT runtime aborts.
    /// Spawns PTY sessions 50 times in rapid succession, sends shell commands, and abruptly closes
    /// the client connection while shell is actively generating output.
    #[test]
    fn test_pty_disconnect_no_sigabrt_stress() {
        for i in 0..50 {
            let (client_stream, server_stream) = UnixStream::pair().expect("UnixStream pair failed");

            let server_handle = thread::spawn(move || {
                let _ = handle_pty_session(server_stream);
            });

            let mut client = client_stream;

            // Send a command to shell that generates output (e.g. echo or yes)
            let cmd = b"echo 'stress_test_start'\nyes 'stress_test_stream'\n";
            let header = PtyHeader {
                session_id: [1u8; 16],
                msg_type: MSG_TYPE_DATA,
                payload_len: cmd.len() as u32,
            };

            if client.write_all(&header.encode()).is_ok() && client.write_all(cmd).is_ok() {
                let _ = client.flush();
            }

            // Read a short snippet of response
            let mut read_buf = [0u8; 64];
            let _ = client.read(&mut read_buf);

            // Abruptly drop client stream to simulate sudden socket disconnect / reset
            drop(client);

            // Ensure server handle finishes cleanly without panic or SIGABRT
            let join_res = server_handle.join();
            assert!(
                join_res.is_ok(),
                "Iteration {}: handle_pty_session thread panicked or aborted on disconnect!",
                i
            );
        }
    }

    /// Test 2: Wayland bi-directional full-duplex traffic to verify NO Mutex deadlocks during blocking reads.
    /// Streams 5 MB of data concurrently in both directions across proxy_split.
    #[test]
    fn test_wayland_full_duplex_no_mutex_deadlock_stress() {
        let (vsock_a, vsock_b) = UnixStream::pair().expect("Vsock UnixStream pair failed");
        let (wayland_a, wayland_b) = UnixStream::pair().expect("Wayland UnixStream pair failed");

        let vsock_read = vsock_a.try_clone().expect("try_clone vsock failed");
        let vsock_write = vsock_a;
        let wayland_read = wayland_a.try_clone().expect("try_clone wayland failed");
        let wayland_write = wayland_a;

        // Spawn proxy_split on background thread
        let proxy_handle = thread::spawn(move || {
            proxy_split(vsock_read, wayland_write, wayland_read, vsock_write)
        });

        let data_size = 2 * 1024 * 1024; // 2 MB each direction
        let barrier = Arc::new(Barrier::new(2));

        // Client side (talking to vsock_b)
        let barrier1 = Arc::clone(&barrier);
        let client_handle = thread::spawn(move || {
            let mut stream = vsock_b;
            barrier1.wait();

            let payload = vec![0xABu8; 8192];
            let mut written = 0;
            let mut read_total = 0;
            let mut read_buf = [0u8; 8192];

            while written < data_size || read_total < data_size {
                if written < data_size {
                    let to_write = std::cmp::min(payload.len(), data_size - written);
                    stream.write_all(&payload[..to_write]).unwrap();
                    written += to_write;
                }
                if read_total < data_size {
                    match stream.read(&mut read_buf) {
                        Ok(0) => break,
                        Ok(n) => read_total += n,
                        Err(e) => panic!("Client read error: {}", e),
                    }
                }
            }
            assert_eq!(read_total, data_size, "Client did not receive all full-duplex data");
        });

        // Server side (talking to wayland_b)
        let barrier2 = Arc::clone(&barrier);
        let server_handle = thread::spawn(move || {
            let mut stream = wayland_b;
            barrier2.wait();

            let payload = vec![0xCDu8; 8192];
            let mut written = 0;
            let mut read_total = 0;
            let mut read_buf = [0u8; 8192];

            while written < data_size || read_total < data_size {
                if read_total < data_size {
                    match stream.read(&mut read_buf) {
                        Ok(0) => break,
                        Ok(n) => read_total += n,
                        Err(e) => panic!("Server read error: {}", e),
                    }
                }
                if written < data_size {
                    let to_write = std::cmp::min(payload.len(), data_size - written);
                    stream.write_all(&payload[..to_write]).unwrap();
                    written += to_write;
                }
            }
            assert_eq!(read_total, data_size, "Server did not receive all full-duplex data");
        });

        client_handle.join().expect("Client thread panicked");
        server_handle.join().expect("Server thread panicked");

        let _ = proxy_handle.join();
    }

    /// Test 3.1: PTY Payload overflow rejection (>64KB).
    /// Sends a PtyHeader claiming 70,000 bytes payload length (> MAX_PAYLOAD_SIZE 65536).
    #[test]
    fn test_pty_payload_overflow_rejection() {
        let (client_stream, server_stream) = UnixStream::pair().expect("UnixStream pair failed");

        let server_handle = thread::spawn(move || {
            handle_pty_session(server_stream)
        });

        let mut client = client_stream;

        // Construct oversized header (70,000 bytes payload)
        let header = PtyHeader {
            session_id: [2u8; 16],
            msg_type: MSG_TYPE_DATA,
            payload_len: (PTY_MAX_PAYLOAD + 4464) as u32, // 70000 bytes
        };

        client.write_all(&header.encode()).unwrap();
        client.flush().unwrap();

        // Server should break out of session loop cleanly due to payload > MAX_PAYLOAD_SIZE
        let result = server_handle.join().expect("Server thread panicked");
        assert!(result.is_ok(), "handle_pty_session should exit cleanly on oversized payload");
    }

    /// Test 3.2: Portal Payload overflow rejection (>64KB).
    /// Sends a JSON line exceeding 64KB to handle_portal_session.
    #[test]
    fn test_portal_payload_overflow_rejection() {
        let mut large_line = vec![b'a'; PORTAL_MAX_PAYLOAD + 1000];
        large_line.push(b'\n');

        let (mut client, server_stream) = UnixStream::pair().expect("UnixStream pair failed");

        let server_handle = thread::spawn(move || {
            handle_portal_session(server_stream)
        });

        client.write_all(&large_line).unwrap();
        client.flush().unwrap();

        let mut resp_buf = Vec::new();
        let _ = client.read_to_end(&mut resp_buf);

        let resp_str = String::from_utf8_lossy(&resp_buf);
        assert!(
            resp_str.contains("Payload length exceeds MAX_PAYLOAD_SIZE"),
            "Portal should respond with MAX_PAYLOAD_SIZE error, got: {}",
            resp_str
        );

        let result = server_handle.join().expect("Server handle panicked");
        assert!(result.is_ok());
    }

    /// Test 4: Auth handling verification (Zero token, empty secret, mismatch rejection).
    #[test]
    fn test_auth_comprehensive_empirical() {
        let secret = b"production_auth_secret_key_12345678";

        // All zero token must be rejected
        let zero_token = vec![0u8; 32];
        assert!(!verify_token(&zero_token, secret));

        // Empty token / secret must be rejected
        assert!(!verify_token(b"", secret));
        assert!(!verify_token(secret, b""));

        // Mismatched token rejected
        let bad_token = b"bad_auth_secret_key_1234567899999";
        assert!(!verify_token(bad_token, secret));

        // Valid token accepted
        assert!(verify_token(secret, secret));

        // Handshake test over socket stream
        let (mut client, mut server) = UnixStream::pair().unwrap();
        let secret_arc = secret.to_vec();

        let server_handle = thread::spawn(move || {
            perform_handshake(&mut server, &secret_arc)
        });

        // Client sends bad token (matching secret length of 35 bytes)
        client.write_all(b"wrong_secret_key_1234567890123456789").unwrap();
        client.flush().unwrap();

        let mut response = [0u8; 12];
        let _ = client.read(&mut response);
        assert_eq!(&response[..12], b"AUTH_FAILED\n");

        let handshake_ok = server_handle.join().unwrap();
        assert!(!handshake_ok, "Handshake should fail on wrong token");
    }

    /// Test 5: High-concurrency PTY streaming load (20 concurrent PTY sessions).
    #[test]
    fn test_pty_heavy_concurrent_load_stress() {
        let num_threads = 20;
        let mut handles = Vec::new();

        for t_idx in 0..num_threads {
            let handle = thread::spawn(move || {
                let (client_stream, server_stream) = UnixStream::pair().expect("UnixStream pair failed");

                let server_handle = thread::spawn(move || {
                    let _ = handle_pty_session(server_stream);
                });

                let mut client = client_stream;

                // Send command generating streaming output
                let cmd_str = format!("echo 'start_t_{}'\nseq 1 500\necho 'end_t_{}'\n", t_idx, t_idx);
                let header = PtyHeader {
                    session_id: [t_idx as u8; 16],
                    msg_type: MSG_TYPE_DATA,
                    payload_len: cmd_str.len() as u32,
                };

                let _ = client.write_all(&header.encode());
                let _ = client.write_all(cmd_str.as_bytes());
                let _ = client.flush();

                // Send resize command concurrently
                let resize_payload = vec![0, 0, 0, 80, 0, 0, 0, 24]; // 80 cols x 24 rows
                let resize_header = PtyHeader {
                    session_id: [t_idx as u8; 16],
                    msg_type: crate::pty::MSG_TYPE_RESIZE,
                    payload_len: resize_payload.len() as u32,
                };
                let _ = client.write_all(&resize_header.encode());
                let _ = client.write_all(&resize_payload);
                let _ = client.flush();

                // Read output stream
                let mut total_read = 0;
                let mut buf = [0u8; 1024];
                while total_read < 2000 {
                    match client.read(&mut buf) {
                        Ok(0) => break,
                        Ok(n) => total_read += n,
                        Err(_) => break,
                    }
                }

                drop(client);
                let server_res = server_handle.join();
                assert!(server_res.is_ok(), "Thread {} server panicked!", t_idx);
            });
            handles.push(handle);
        }

        for (i, h) in handles.into_iter().enumerate() {
            assert!(h.join().is_ok(), "Thread {} failed to join!", i);
        }
    }

    /// Test 6: Precise timing test for silent socket handshake timeout (5s).
    #[test]
    fn test_silent_socket_handshake_timeout_empirical() {
        use std::time::{Duration, Instant};

        let secret = b"thirty_two_bytes_secret_key_1234"; // 32 bytes

        // Case A: Partially sent token (client sends 5 bytes of 32 expected, then remains silent)
        let (mut client_a, mut server_a) = UnixStream::pair().expect("UnixStream pair failed");
        server_a.set_read_timeout(Some(Duration::from_secs(5))).unwrap();

        let start_a = Instant::now();
        let handle_a = thread::spawn(move || {
            perform_handshake(&mut server_a, secret)
        });

        // Client sends only 5 bytes
        client_a.write_all(b"hello").unwrap();
        client_a.flush().unwrap();

        let result_a = handle_a.join().expect("Handshake thread panicked");
        let elapsed_a = start_a.elapsed();

        assert!(!result_a, "Partial handshake should return false");
        assert!(
            elapsed_a >= Duration::from_millis(4800) && elapsed_a <= Duration::from_millis(6500),
            "Partial token handshake timeout elapsed time {:?} is outside expected 5s window!",
            elapsed_a
        );

        // Case B: Completely silent client (0 bytes sent, connection held open)
        let (_client_b, mut server_b) = UnixStream::pair().expect("UnixStream pair failed");
        server_b.set_read_timeout(Some(Duration::from_secs(5))).unwrap();

        let start_b = Instant::now();
        let handle_b = thread::spawn(move || {
            perform_handshake(&mut server_b, secret)
        });

        let result_b = handle_b.join().expect("Handshake thread B panicked");
        let elapsed_b = start_b.elapsed();

        assert!(!result_b, "Silent handshake should return false");
        assert!(
            elapsed_b >= Duration::from_millis(4800) && elapsed_b <= Duration::from_millis(6500),
            "Silent client handshake timeout elapsed time {:?} is outside expected 5s window!",
            elapsed_b
        );
    }

    /// Test 7: Verify Socket FD drop handling under rapid connection creation and destruction.
    /// Ensures open file descriptors do not leak after 50 connection teardowns.
    #[test]
    fn test_fd_leak_stress() {
        fn list_open_fds() -> Vec<i32> {
            let mut fds = Vec::new();
            for fd in 0..1024 {
                if unsafe { libc::fcntl(fd, libc::F_GETFD) } != -1 {
                    fds.push(fd);
                }
            }
            fds
        }

        let mut fd_counts = Vec::new();

        for _ in 0..50 {
            let (client_stream, server_stream) = UnixStream::pair().expect("UnixStream pair failed");
            let server_handle = thread::spawn(move || {
                let _ = handle_pty_session(server_stream);
            });

            let mut client = client_stream;
            let cmd = b"echo 'fd_test'\n";
            let header = PtyHeader {
                session_id: [1u8; 16],
                msg_type: MSG_TYPE_DATA,
                payload_len: cmd.len() as u32,
            };
            let _ = client.write_all(&header.encode());
            let _ = client.write_all(cmd);
            let _ = client.flush();

            let mut read_buf = [0u8; 64];
            let _ = client.read(&mut read_buf);
            drop(client);
            let _ = server_handle.join();

            fd_counts.push(list_open_fds().len());
        }

        // Verify that FD count stabilizes and does not leak monotonically over 50 iterations
        let first_half_max = fd_counts[..25].iter().copied().max().unwrap_or(0);
        let second_half_max = fd_counts[25..].iter().copied().max().unwrap_or(0);
        assert!(
            second_half_max <= first_half_max + 2,
            "FD leak detected across 50 iterations! First half max: {}, Second half max: {}",
            first_half_max, second_half_max
        );
    }
}



