mod auth;
mod portal;
mod pty;
mod vsock;
mod wayland;

#[cfg(test)]
mod empirical_tests;


use std::io::Write;
use std::sync::Arc;
use std::thread;
use vsock::{VsockListener, VMADDR_CID_ANY, VMADDR_CID_HOST, PORT_PORTAL, PORT_PTY, PORT_WAYLAND};

fn main() {
    println!("[Bridge-Agent] Starting Production Guest Agent Loop...");

    let secret = match auth::extract_auth_secret() {
        Ok(s) => Arc::new(s),
        Err(e) => {
            eprintln!("[Bridge-Agent] Fatal: Secret key extraction failed: {}", e);
            std::process::exit(1);
        }
    };

    println!("[Bridge-Agent] Dynamic auth secret key extracted successfully.");

    // Startup Initiator: Connect to Host (CID_HOST = 2) Port 5000 via AF_VSOCK socket.
    // Construct and send 64-byte AuthHandshakePayload (32-byte token + 32-byte RFC 2104 HMAC-SHA256 signature).
    println!("[Bridge-Agent] Initiating startup auth handshake to Host (CID {}, Port {})...", VMADDR_CID_HOST, PORT_PORTAL);
    let mut handshake_sent = false;
    for attempt in 1..=5 {
        match vsock::VsockStream::connect(VMADDR_CID_HOST, PORT_PORTAL) {
            Ok(mut stream) => {
                let token = secret.as_slice();
                let signature = auth::HmacSha256::compute_hmac_response(&secret, token);
                let mut payload = Vec::with_capacity(64);
                payload.extend_from_slice(token);
                payload.extend_from_slice(&signature);

                if stream.write_all(&payload).is_ok() && stream.flush().is_ok() {
                    println!("[Bridge-Agent] Startup auth handshake sent successfully (attempt {})", attempt);
                    handshake_sent = true;
                    break;
                }
            }
            Err(e) => {
                eprintln!("[Bridge-Agent] Connection attempt {} to Host CID {} port {} failed: {}", attempt, VMADDR_CID_HOST, PORT_PORTAL, e);
                thread::sleep(std::time::Duration::from_millis(200));
            }
        }
    }

    if !handshake_sent {
        eprintln!("[Bridge-Agent] Warning: Startup auth handshake to Host was not acknowledged immediately.");
    }

    let listener_portal = match VsockListener::bind(VMADDR_CID_ANY, PORT_PORTAL) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("[Bridge-Agent] Fatal: Failed to bind Portal listener on port {}: {}", PORT_PORTAL, e);
            std::process::exit(1);
        }
    };

    let listener_pty = match VsockListener::bind(VMADDR_CID_ANY, PORT_PTY) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("[Bridge-Agent] Fatal: Failed to bind PTY listener on port {}: {}", PORT_PTY, e);
            std::process::exit(1);
        }
    };

    let listener_wayland = match VsockListener::bind(VMADDR_CID_ANY, PORT_WAYLAND) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("[Bridge-Agent] Fatal: Failed to bind Wayland listener on port {}: {}", PORT_WAYLAND, e);
            std::process::exit(1);
        }
    };

    println!("[Bridge-Agent] Listeners active on Ports 5000 (Portal), 5001 (PTY), 5002 (Wayland)");

    // Worker 1: Port 5000 (Portal RPC)
    let secret_portal = Arc::clone(&secret);
    let h_portal = thread::spawn(move || {
        loop {
            match listener_portal.accept() {
                Ok((mut stream, cid)) => {
                    println!("[Bridge-Agent] Accepted Portal connection from CID {}", cid);
                    let secret_local = Arc::clone(&secret_portal);
                    thread::spawn(move || {
                        if !auth::perform_handshake(&mut stream, &secret_local) {
                            eprintln!("[Bridge-Agent] Fatal: Handshake failed for Portal connection");
                            std::process::exit(1);
                        }
                        if let Err(e) = portal::handle_portal_session(stream) {
                            eprintln!("[Bridge-Agent] Portal session ended: {}", e);
                        }
                    });
                }
                Err(e) => {
                    eprintln!("[Bridge-Agent] Portal accept error: {}", e);
                }
            }
        }
    });

    // Worker 2: Port 5001 (PTY)
    let secret_pty = Arc::clone(&secret);
    let h_pty = thread::spawn(move || {
        loop {
            match listener_pty.accept() {
                Ok((mut stream, cid)) => {
                    println!("[Bridge-Agent] Accepted PTY connection from CID {}", cid);
                    let secret_local = Arc::clone(&secret_pty);
                    thread::spawn(move || {
                        if !auth::perform_handshake(&mut stream, &secret_local) {
                            eprintln!("[Bridge-Agent] Fatal: Handshake failed for PTY connection");
                            std::process::exit(1);
                        }
                        if let Err(e) = pty::handle_pty_session(stream) {
                            eprintln!("[Bridge-Agent] PTY session ended: {}", e);
                        }
                    });
                }
                Err(e) => {
                    eprintln!("[Bridge-Agent] PTY accept error: {}", e);
                }
            }
        }
    });

    // Worker 3: Port 5002 (Wayland Proxy)
    let secret_wayland = Arc::clone(&secret);
    let h_wayland = thread::spawn(move || {
        loop {
            match listener_wayland.accept() {
                Ok((mut stream, cid)) => {
                    println!("[Bridge-Agent] Accepted Wayland connection from CID {}", cid);
                    let secret_local = Arc::clone(&secret_wayland);
                    thread::spawn(move || {
                        if !auth::perform_handshake(&mut stream, &secret_local) {
                            eprintln!("[Bridge-Agent] Fatal: Handshake failed for Wayland connection");
                            std::process::exit(1);
                        }
                        if let Err(e) = wayland::handle_wayland_proxy(stream) {
                            eprintln!("[Bridge-Agent] Wayland session ended: {}", e);
                        }
                    });
                }
                Err(e) => {
                    eprintln!("[Bridge-Agent] Wayland accept error: {}", e);
                }
            }
        }
    });

    let _ = h_portal.join();
    let _ = h_pty.join();
    let _ = h_wayland.join();
}
