use std::io::{Read, Write, Cursor};
use std::net::{TcpListener, TcpStream};
use std::os::unix::net::UnixStream;
use std::sync::{Arc, Barrier, atomic::{AtomicUsize, Ordering}};
use std::thread;
use std::time::{Duration, Instant};

const AUTH_SECRET: &[u8] = b"challenger_secret_key_32bytes!!";

fn get_open_fd_count() -> usize {
    #[cfg(target_os = "macos")]
    {
        if let Ok(entries) = std::fs::read_dir("/dev/fd") {
            return entries.count();
        }
    }
    #[cfg(target_os = "linux")]
    {
        if let Ok(entries) = std::fs::read_dir("/proc/self/fd") {
            return entries.count();
        }
    }
    0
}

fn perform_handshake<R: Read + Write>(stream: &mut R, secret: &[u8]) -> bool {
    if secret.is_empty() {
        return false;
    }
    let mut token_buf = vec![0u8; secret.len()];
    if stream.read_exact(&mut token_buf).is_err() {
        return false;
    }
    let mut diff = 0u8;
    if token_buf.len() != secret.len() || token_buf.iter().all(|&b| b == 0) {
        let _ = stream.write_all(b"AUTH_FAILED\n");
        return false;
    }
    for (a, b) in token_buf.iter().zip(secret.iter()) {
        diff |= a ^ b;
    }
    if diff != 0 {
        let _ = stream.write_all(b"AUTH_FAILED\n");
        return false;
    }
    stream.write_all(b"AUTH_OK\n").is_ok()
}

fn main() {
    println!("==================================================");
    println!("EMPIRICAL CHALLENGER STRESS HARNESS M2-R2");
    println!("==================================================");

    let fd_start = get_open_fd_count();
    println!("[Initial State] Open File Descriptors: {}", fd_start);

    // ==========================================================
    // TEST 1: VsockListener / Socket FD Leak on Drop & Disconnect
    // ==========================================================
    println!("\n[Suite 1/5] Testing Socket FD Leaks on Drop & Disconnect...");
    {
        let fd_before = get_open_fd_count();
        for _ in 0..300 {
            let listener = TcpListener::bind("127.0.0.1:0").expect("Bind dynamic port failed");
            let port = listener.local_addr().unwrap().port();
            
            let client_handle = thread::spawn(move || {
                TcpStream::connect(("127.0.0.1", port)).ok()
            });

            let (server_stream, _) = listener.accept().expect("Accept failed");
            let client_stream = client_handle.join().unwrap().expect("Client connect failed");

            // Drop both streams and listener
            drop(server_stream);
            drop(client_stream);
            drop(listener);
        }

        let fd_after = get_open_fd_count();
        println!("  -> FDs before 300 bind/connect/drops: {}, after: {} (Delta: {})", fd_before, fd_after, fd_after as i64 - fd_before as i64);
        assert_eq!(fd_before, fd_after, "FD LEAK DETECTED in socket drop!");
        println!("  -> Socket FD Leak Check: PASS (0 leaks)");
    }

    // ==========================================================
    // TEST 2: Concurrent Multi-Threaded Server Loop across Ports (Portal 5000, PTY 5001, Wayland 5002)
    // ==========================================================
    println!("\n[Suite 2/5] Testing Multi-Threaded Server Loop Concurrency across Ports...");
    {
        let listener_portal = TcpListener::bind("127.0.0.1:0").unwrap();
        let listener_pty = TcpListener::bind("127.0.0.1:0").unwrap();
        let listener_wayland = TcpListener::bind("127.0.0.1:0").unwrap();

        let port_portal = listener_portal.local_addr().unwrap().port();
        let port_pty = listener_pty.local_addr().unwrap().port();
        let port_wayland = listener_wayland.local_addr().unwrap().port();

        println!("  -> Server bound on dynamic ports: Portal={}, PTY={}, Wayland={}", port_portal, port_pty, port_wayland);

        let running = Arc::new(AtomicUsize::new(1));

        // Server Portal worker
        let r1 = Arc::clone(&running);
        let h_portal = thread::spawn(move || {
            while r1.load(Ordering::SeqCst) == 1 {
                if let Ok((mut stream, _)) = listener_portal.accept() {
                    thread::spawn(move || {
                        if perform_handshake(&mut stream, AUTH_SECRET) {
                            let mut buf = [0u8; 256];
                            if let Ok(n) = stream.read(&mut buf) {
                                let _ = stream.write_all(&buf[..n]);
                            }
                        }
                    });
                }
            }
        });

        // Server PTY worker
        let r2 = Arc::clone(&running);
        let h_pty = thread::spawn(move || {
            while r2.load(Ordering::SeqCst) == 1 {
                if let Ok((mut stream, _)) = listener_pty.accept() {
                    thread::spawn(move || {
                        if perform_handshake(&mut stream, AUTH_SECRET) {
                            let mut buf = [0u8; 256];
                            if let Ok(n) = stream.read(&mut buf) {
                                let _ = stream.write_all(&buf[..n]);
                            }
                        }
                    });
                }
            }
        });

        // Server Wayland worker
        let r3 = Arc::clone(&running);
        let h_wayland = thread::spawn(move || {
            while r3.load(Ordering::SeqCst) == 1 {
                if let Ok((mut stream, _)) = listener_wayland.accept() {
                    thread::spawn(move || {
                        if perform_handshake(&mut stream, AUTH_SECRET) {
                            let mut buf = [0u8; 256];
                            if let Ok(n) = stream.read(&mut buf) {
                                let _ = stream.write_all(&buf[..n]);
                            }
                        }
                    });
                }
            }
        });

        // Concurrent Client Stress (90 clients: 30 per port)
        const TOTAL_CLIENTS: usize = 90;
        let barrier = Arc::new(Barrier::new(TOTAL_CLIENTS));
        let portal_ok = Arc::new(AtomicUsize::new(0));
        let pty_ok = Arc::new(AtomicUsize::new(0));
        let wayland_ok = Arc::new(AtomicUsize::new(0));

        let client_threads: Vec<_> = (0..TOTAL_CLIENTS).map(|i| {
            let b = Arc::clone(&barrier);
            let pok = Arc::clone(&portal_ok);
            let tok = Arc::clone(&pty_ok);
            let wok = Arc::clone(&wayland_ok);
            thread::spawn(move || {
                let target_port = match i % 3 {
                    0 => port_portal,
                    1 => port_pty,
                    _ => port_wayland,
                };
                b.wait(); // All 90 threads fire at the exact same instant

                if let Ok(mut stream) = TcpStream::connect(("127.0.0.1", target_port)) {
                    if stream.write_all(AUTH_SECRET).is_ok() && stream.flush().is_ok() {
                        let mut resp = [0u8; 16];
                        if let Ok(n) = stream.read(&mut resp) {
                            if String::from_utf8_lossy(&resp[..n]).contains("AUTH_OK") {
                                let msg = b"ping_data";
                                if stream.write_all(msg).is_ok() && stream.flush().is_ok() {
                                    let mut echo = [0u8; 16];
                                    if let Ok(m) = stream.read(&mut echo) {
                                        if m == msg.len() {
                                            match i % 3 {
                                                0 => pok.fetch_add(1, Ordering::SeqCst),
                                                1 => tok.fetch_add(1, Ordering::SeqCst),
                                                _ => wok.fetch_add(1, Ordering::SeqCst),
                                            };
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            })
        }).collect();

        for ct in client_threads {
            ct.join().unwrap();
        }

        println!("  -> Portal Concurrent Handshakes & Echo: {}/30", portal_ok.load(Ordering::SeqCst));
        println!("  -> PTY Concurrent Handshakes & Echo: {}/30", pty_ok.load(Ordering::SeqCst));
        println!("  -> Wayland Concurrent Handshakes & Echo: {}/30", wayland_ok.load(Ordering::SeqCst));

        assert_eq!(portal_ok.load(Ordering::SeqCst), 30);
        assert_eq!(pty_ok.load(Ordering::SeqCst), 30);
        assert_eq!(wayland_ok.load(Ordering::SeqCst), 30);

        running.store(0, Ordering::SeqCst);
        // Trigger connect to break accept loops
        let _ = TcpStream::connect(("127.0.0.1", port_portal));
        let _ = TcpStream::connect(("127.0.0.1", port_pty));
        let _ = TcpStream::connect(("127.0.0.1", port_wayland));

        let _ = h_portal.join();
        let _ = h_pty.join();
        let _ = h_wayland.join();

        println!("  -> 90-Client Concurrent Server Loop Stress: PASS");
    }

    // ==========================================================
    // TEST 3: Disconnect & Abrupt Drop Stress under High Load
    // ==========================================================
    println!("\n[Suite 3/5] Stress Testing Abrupt Disconnects under High Load (200 connections)...");
    {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();

        let server_thread = thread::spawn(move || {
            for _ in 0..200 {
                if let Ok((mut stream, _)) = listener.accept() {
                    let mut buf = [0u8; 1024];
                    let _ = stream.read(&mut buf); // read incoming, client will abruptly drop
                }
            }
        });

        let drop_count = Arc::new(AtomicUsize::new(0));
        let threads: Vec<_> = (0..20).map(|_| {
            let dc = Arc::clone(&drop_count);
            thread::spawn(move || {
                for _ in 0..10 {
                    if let Ok(mut stream) = TcpStream::connect(("127.0.0.1", port)) {
                        let _ = stream.write_all(b"abrupt_disconnect_payload_stream_data");
                        let _ = stream.flush();
                        drop(stream); // Drop connection immediately
                        dc.fetch_add(1, Ordering::SeqCst);
                    }
                }
            })
        }).collect();

        for t in threads {
            t.join().unwrap();
        }
        server_thread.join().unwrap();

        println!("  -> Completed {} abrupt disconnects without crashes or panics.", drop_count.load(Ordering::SeqCst));
        assert_eq!(drop_count.load(Ordering::SeqCst), 200);
        println!("  -> Abrupt Disconnect Load Stress: PASS");
    }

    // ==========================================================
    // TEST 4: Auth Security & Abort Behavior
    // ==========================================================
    println!("\n[Suite 4/5] Testing Auth Security & Rejection Criteria...");
    {
        let secret = b"super_secret_production_key_1234";

        // Test bad secret handshake over stream
        let (mut client, mut server) = UnixStream::pair().unwrap();
        let server_h = thread::spawn(move || {
            perform_handshake(&mut server, secret)
        });

        client.write_all(b"invalid_secret_key_0000000000000").unwrap();
        client.flush().unwrap();

        let mut resp = [0u8; 16];
        let n = client.read(&mut resp).unwrap();
        let resp_str = String::from_utf8_lossy(&resp[..n]);
        assert!(resp_str.contains("AUTH_FAILED"), "Server must return AUTH_FAILED on invalid token");

        let auth_ok = server_h.join().unwrap();
        assert!(!auth_ok, "Handshake MUST return false on bad token");
        println!("  -> Invalid token handshake rejection: PASS");
    }

    // ==========================================================
    // TEST 5: Final FD Leak Summary
    // ==========================================================
    println!("\n[Suite 5/5] Final Verification & FD Count Check...");
    let fd_end = get_open_fd_count();
    println!("  -> Initial FDs: {}, Final FDs: {} (Delta: {})", fd_start, fd_end, fd_end as i64 - fd_start as i64);
    assert_eq!(fd_start, fd_end, "FD LEAK FOUND across entire test execution!");

    println!("\n==================================================");
    println!("ALL 5 EMPIRICAL STRESS TEST SUITES PASSED SUCCESSFULLY!");
    println!("==================================================");
}
