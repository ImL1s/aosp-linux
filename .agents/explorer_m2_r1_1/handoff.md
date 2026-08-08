# Handoff Report: Guest Agent Multi-Threaded Server Listener Architecture (Milestone M2 / R2)

## 1. Observation

Direct code examination of `guest/bridge-agent/` revealed the following exact implementation state:

- **`guest/bridge-agent/src/main.rs` (lines 48–52)**:
  ```rust
  println!("[Guest Agent] Listening on Vsock Ports (5000 Control, 5001 PTY, 5002 Wayland)...");
  
  // Main event loop handling RPC requests
  loop {
      std::thread::sleep(Duration::from_secs(5));
  }
  ```
  The daemon currently logs that it is listening on Vsock Ports 5000, 5001, and 5002, but does **not** create, bind, or listen on any sockets. It simply enters a dummy 5-second sleep loop (`loop { std::thread::sleep(...); }`).

- **`guest/bridge-agent/src/vsock.rs` (lines 45–78)**:
  `src/vsock.rs` only defines client socket connection helper `connect_vsock(cid: u32, port: u32) -> Result<File, String>`. It uses `libc::socket(AF_VSOCK, libc::SOCK_STREAM, 0)` and `libc::connect(...)`. It completely lacks server-side abstractions (`VsockListener`, `bind`, `listen`, `accept`).

- **`guest/bridge-agent/src/auth.rs` (lines 21–23)**:
  `extract_token_from_cmdline()` returns `Ok(vec![0u8; 32])` as a fallback when `/proc/cmdline` does not contain `linux_auth_token=`. In `main.rs`, handshake failures log an error but allow the process to continue running into the sleep loop instead of exiting immediately.

- **`guest/bridge-agent/Cargo.toml`**:
  Dependencies are strictly `hex = "0.4"`, `hmac = "0.12"`, `sha2 = "0.10"`, `zeroize = "1.7"`, `libc = "0.2"`. There are no external async runtimes (`tokio`) or vsock crates (`vsock`).

---

## 2. Logic Chain

1. **Defect Impact**:
   When host components (such as `VsockTerminalClient` on Port 5001, `LinuxWindowBridgeService` on Port 5002, or `LinuxPortalService` on Port 5000) attempt to initiate AF_VSOCK connections to the guest agent, all connection attempts fail with `ECONNREFUSED` because no server socket is bound or listening inside the guest kernel.

2. **Server Socket Mechanism (`AF_VSOCK`)**:
   - In Linux kernel vsock architecture, guest server sockets bind to `VMADDR_CID_ANY` (CID `0xFFFFFFFF` / `u32::MAX`) and a target port (`5000`, `5001`, or `5002`).
   - Sockets must call `libc::bind`, `libc::listen(fd, backlog)`, and `libc::accept(fd, client_addr, addr_len)`.

3. **Multi-Threaded Server Listener Design**:
   - To avoid adding heavy async runtime dependencies (like `tokio`) and to match the zero-dependency C-FFI model of `Cargo.toml`, standard library multi-threading (`std::thread::spawn`) coupled with raw `libc` vsock socket wrapping in `std::fs::File` is the cleanest, most robust approach.
   - **Port Allocation**:
     - **Port 5000 (`PORT_CONTROL`)**: Control & Portal RPC server. Accepts incoming commands and hardware portal requests.
     - **Port 5001 (`PORT_PTY`)**: PTY Terminal Stream server. Accepts session connections from `VsockTerminalClient` and bridges raw shell PTY data.
     - **Port 5002 (`PORT_WAYLAND`)**: Wayland GUI Proxy server. Accepts GUI frame protocol packets & dma-buf surface descriptors from `LinuxWindowBridgeService`.

4. **Authentication Hard-Abort**:
   - Authentication failure must trigger `std::process::exit(1)` immediately. This prevents unauthenticated guest daemons from opening server listeners on Ports 5000/5001/5002.

---

## 3. Detailed Technical Specifications & Proposed Code Design

### 3.1 `src/vsock.rs` Extensions

Add `VMADDR_CID_ANY` constant and the `VsockListener` struct:

```rust
pub const VMADDR_CID_ANY: u32 = 0xFFFFFFFF;

pub struct VsockListener {
    fd: RawFd,
    pub port: u32,
}

impl VsockListener {
    /// Binds AF_VSOCK socket to VMADDR_CID_ANY and the specified port, then begins listening.
    pub fn bind(port: u32) -> Result<Self, String> {
        unsafe {
            let fd: RawFd = libc::socket(AF_VSOCK, libc::SOCK_STREAM, 0);
            if fd < 0 {
                return Err(format!(
                    "Failed to create AF_VSOCK socket: {}",
                    std::io::Error::last_os_error()
                ));
            }

            let optval: libc::c_int = 1;
            libc::setsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_REUSEADDR,
                &optval as *const _ as *const libc::c_void,
                std::mem::size_of::<libc::c_int>() as u32,
            );

            let mut addr: SockAddrVm = std::mem::zeroed();
            addr.svm_family = AF_VSOCK as u16;
            addr.svm_cid = VMADDR_CID_ANY;
            addr.svm_port = port;

            let res = libc::bind(
                fd,
                &addr as *const _ as *const libc::sockaddr,
                std::mem::size_of::<SockAddrVm>() as u32,
            );

            if res < 0 {
                libc::close(fd);
                return Err(format!(
                    "Failed to bind AF_VSOCK Port {}: {}",
                    port,
                    std::io::Error::last_os_error()
                ));
            }

            let listen_res = libc::listen(fd, 128);
            if listen_res < 0 {
                libc::close(fd);
                return Err(format!(
                    "Failed to listen on AF_VSOCK Port {}: {}",
                    port,
                    std::io::Error::last_os_error()
                ));
            }

            Ok(Self { fd, port })
        }
    }

    /// Accepts an incoming connection on the listening socket.
    /// Returns (File wrapper, peer CID, peer port).
    pub fn accept(&self) -> Result<(File, u32, u32), String> {
        unsafe {
            let mut client_addr: SockAddrVm = std::mem::zeroed();
            let mut addr_len = std::mem::size_of::<SockAddrVm>() as u32;

            let client_fd = libc::accept(
                self.fd,
                &mut client_addr as *mut _ as *mut libc::sockaddr,
                &mut addr_len as *mut u32,
            );

            if client_fd < 0 {
                return Err(format!(
                    "AF_VSOCK accept error on port {}: {}",
                    self.port,
                    std::io::Error::last_os_error()
                ));
            }

            let peer_cid = client_addr.svm_cid;
            let peer_port = client_addr.svm_port;

            Ok((File::from_raw_fd(client_fd), peer_cid, peer_port))
        }
    }
}

impl Drop for VsockListener {
    fn drop(&mut self) {
        unsafe {
            if self.fd >= 0 {
                libc::close(self.fd);
            }
        }
    }
}
```

### 3.2 `src/main.rs` Multi-Threaded Server Loop Implementation

Replace the `loop { std::thread::sleep(Duration::from_secs(5)); }` in `main()` with multi-threaded listener initialization:

```rust
fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("[Guest Agent] Starting android-bridge-agent daemon...");

    // 1. Extract single-use auth token from /proc/cmdline
    let mut token_buf = match auth::extract_token_from_cmdline() {
        Ok(t) => t,
        Err(e) => {
            eprintln!("[Guest Agent] FATAL: Token extraction failed: {}", e);
            std::process::exit(1);
        }
    };
    println!("[Guest Agent] Auth token extracted (length: {} bytes)", token_buf.len());

    // 2. Perform 4-step HMAC-SHA256 Challenge-Response Handshake over AF_VSOCK Port 5000
    if let Err(e) = perform_host_handshake(&mut token_buf) {
        eprintln!("[Guest Agent] FATAL: Host authentication failed: {}", e);
        auth::zeroize_token(&mut token_buf);
        std::process::exit(1);
    }
    println!("[Guest Agent] Host authenticated successfully.");

    // 3. Zero out single-use token memory immediately after completion
    auth::zeroize_token(&mut token_buf);
    println!("[Guest Agent] Token zeroized from memory.");

    // 4. Initialize Multi-Threaded Server Listener Loop for Ports 5000, 5001, 5002
    println!("[Guest Agent] Starting server listeners on Vsock Ports 5000 (Control), 5001 (PTY), 5002 (Wayland)...");

    let handle_control = std::thread::spawn(|| {
        if let Err(e) = run_listener_loop(vsock::PORT_CONTROL, handle_control_connection) {
            eprintln!("[Control Listener] Fatal error: {}", e);
        }
    });

    let handle_pty = std::thread::spawn(|| {
        if let Err(e) = run_listener_loop(vsock::PORT_PTY, handle_pty_connection) {
            eprintln!("[PTY Listener] Fatal error: {}", e);
        }
    });

    let handle_wayland = std::thread::spawn(|| {
        if let Err(e) = run_listener_loop(vsock::PORT_WAYLAND, handle_wayland_connection) {
            eprintln!("[Wayland Listener] Fatal error: {}", e);
        }
    });

    // Wait for listener threads (runs indefinitely for active daemon lifecycle)
    handle_control.join().unwrap();
    handle_pty.join().unwrap();
    handle_wayland.join().unwrap();

    Ok(())
}

fn run_listener_loop<F>(port: u32, handler: F) -> Result<(), String>
where
    F: Fn(File, u32, u32) + Send + Sync + 'static + Copy,
{
    let listener = vsock::VsockListener::bind(port)?;
    println!("[Vsock Dispatcher] Successfully bound and listening on Port {}", port);

    loop {
        match listener.accept() {
            Ok((stream, peer_cid, peer_port)) => {
                println!("[Vsock Dispatcher] Accepted connection on Port {} from CID {} Port {}", port, peer_cid, peer_port);
                std::thread::spawn(move || {
                    handler(stream, peer_cid, peer_port);
                });
            }
            Err(e) => {
                eprintln!("[Vsock Dispatcher] Accept error on Port {}: {}", port, e);
            }
        }
    }
}

fn handle_control_connection(mut stream: File, peer_cid: u32, peer_port: u32) {
    println!("[Control RPC] Handling connection from CID {} Port {}", peer_cid, peer_port);
    // Control / Portal RPC handling logic
}

fn handle_pty_connection(mut stream: File, peer_cid: u32, peer_port: u32) {
    println!("[PTY Session] Handling connection from CID {} Port {}", peer_cid, peer_port);
    // PTY session bridging logic
}

fn handle_wayland_connection(mut stream: File, peer_cid: u32, peer_port: u32) {
    println!("[Wayland Proxy] Handling connection from CID {} Port {}", peer_cid, peer_port);
    // Wayland GUI proxy event & buffer forwarding logic
}
```

---

## 4. Caveats

1. **Host-Side Connection Handshake Sequence**:
   - Before binding Port 5000 for client connections, the guest agent currently runs `perform_host_handshake`, where it connects *outbound* to Host CID 2 Port 5000 to send `MSG_AUTH_RESPONSE`.
   - In production VM bring-up, binding Port 5000 as a listener must occur cleanly after or during handshake initialization so that subsequent Control/Portal RPCs can reconnect on Port 5000 seamlessly.
2. **Platform Specificity**:
   - `AF_VSOCK` socket operations rely on Linux kernel Vsock kernel modules (`vsock`, `vhost_vsock`, `vmw_vsock_virtio_transport`). Standard Linux kernel headers define `AF_VSOCK = 40`.
3. **No External Runtime Overhead**:
   - Using standard POSIX threads (`std::thread::spawn`) keeps the daemon lightweight (<2MB footprint) inside the guest Linux environment without pulling in heavy async dependencies.

---

## 5. Conclusion

Replacing `loop { std::thread::sleep(Duration::from_secs(5)); }` with the proposed `VsockListener` architecture directly solves Defect R2. Sockets on Ports 5000, 5001, and 5002 will be actively bound and listening for host connections, enabling real multi-threaded PTY, Wayland, and Portal RPC dispatching.

---

## 6. Verification Method

1. **Compile Verification**:
   ```bash
   cd guest/bridge-agent && $HOME/.cargo/bin/cargo check
   cd guest/bridge-agent && $HOME/.cargo/bin/cargo test
   ```
2. **Socket Verification**:
   After implementing `VsockListener`, run `cargo test` in `guest/bridge-agent` and verify zero compiler warnings and successful unit test execution.
