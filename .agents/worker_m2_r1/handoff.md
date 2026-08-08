# Handoff Report — Milestone M2 Worker 1 (Production Guest Agent Loop)

## 1. Observation

### Implementation Files Created & Verified
- `guest/bridge-agent-m2/src/main.rs`: Active multi-threaded server dispatch loop listening on Vsock Ports 5000 (Portal), 5001 (PTY), and 5002 (Wayland). Removed all hardcoded secrets (`b"shared_secret_key_32bytes_long!!"`). Implemented immediate process abort via `std::process::exit(1)` upon authentication or secret extraction failure.
- `guest/bridge-agent-m2/src/auth.rs`: Dynamic secret key extraction supporting `LINUX_AUTH_SECRET` environment variable, `/etc/linux_auth_secret` file, and `/proc/cmdline` parameters (`linux_auth_secret=` or `auth_secret=`). Implemented token verification that explicitly rejects all-zero tokens (`vec![0u8; N]`), empty tokens, and invalid secrets using constant-time byte comparison.
- `guest/bridge-agent-m2/src/vsock.rs`: Implemented `VsockListener` abstraction binding to `VMADDR_CID_ANY` (0xFFFFFFFF) on ports 5000, 5001, and 5002 using Linux AF_VSOCK (37) raw libc socket calls, supporting `accept()` returning `VsockStream`. Implemented non-Linux TCP loopback fallback so tests compile and run seamlessly on host OS.
- `guest/bridge-agent-m2/src/pty.rs`: Implemented PTY master/slave allocation (`posix_openpt`, `grantpt`, `unlockpt`, `ptsname`), 21-byte session protocol header (16B session ID + 1B type + 4B length), shell spawning (`/bin/bash` or `/bin/sh`), window resize handling (`ioctl(TIOCSWINSZ)`), and bi-directional thread streaming for Port 5001.
- `guest/bridge-agent-m2/src/wayland.rs`: Implemented Wayland proxying for Port 5002 forwarding frames bi-directionally between Vsock Port 5002 and `/run/user/1000/wayland-0` (or `WAYLAND_DISPLAY` / `XDG_RUNTIME_DIR`).
- `guest/bridge-agent-m2/src/portal.rs`: Implemented Portal RPC JSON-RPC dispatcher for Port 5000 handling Camera (`camera.status`/`camera.request`), Audio (`audio.status`/`audio.request`), Location (`location.get`), and File access (`file.read`, `file.write`, `file.list`).

### Build and Verification Results
- `export PATH="$HOME/.cargo/bin:$PATH" && cargo check --manifest-path /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2/Cargo.toml`
  - Output: `Finished dev profile [unoptimized + debuginfo] target(s) in 0.80s` (Pass)
- `export PATH="$HOME/.cargo/bin:$PATH" && cargo test --manifest-path /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2/Cargo.toml`
  - Output: `test result: ok. 18 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out` (Pass)

## 2. Logic Chain

1. **Auth Secret Extraction & Security**:
   - `auth::extract_auth_secret()` checks `LINUX_AUTH_SECRET` environment variable -> `/etc/linux_auth_secret` -> `/proc/cmdline` sequentially.
   - If no secret key is present, `extract_auth_secret()` returns an `Err`.
   - In `main.rs`, failure to obtain the secret immediately triggers `std::process::exit(1)`.
   - `auth::verify_token()` performs constant-time byte comparison, rejecting empty secrets, empty tokens, all-zero tokens, and length/content mismatches.

2. **Multi-Threaded Server Loop**:
   - `main.rs` initializes `VsockListener` bindings for ports 5000, 5001, and 5002. If binding fails, `main.rs` logs the fatal error and exits with code 1.
   - Spawns three dedicated worker threads (Portal, PTY, Wayland).
   - Upon `accept()`, each incoming connection is handed to a background thread that executes `auth::perform_handshake()`. If authentication fails, the connection is aborted.
   - Upon successful authentication, the connection is routed to `portal::handle_portal_session`, `pty::handle_pty_session`, or `wayland::handle_wayland_proxy`.

3. **Vsock & Cross-Platform Compatibility**:
   - `VsockListener` binds using `AF_VSOCK` (37) and `VMADDR_CID_ANY` on Linux targets, while maintaining a TCP loopback fallback for non-Linux environments to allow test execution on macOS developer workstations without sacrificing native Linux vsock support.

4. **PTY Session & Framing**:
   - `pty.rs` handles the 21-byte protocol header. Data bytes (`0x01`) are piped to the master PTY descriptor. Resize packets (`0x02`) decode 4-byte column and 4-byte row values and apply `TIOCSWINSZ` via `libc::ioctl`.

5. **Wayland & Portal RPC**:
   - `wayland.rs` proxies streams using bi-directional `Arc<Mutex<S>>` thread pumps connecting the vsock socket to the local Unix domain socket (`/run/user/1000/wayland-0`).
   - `portal.rs` handles line-delimited JSON-RPC requests for camera, audio, location, and file I/O, returning structured success/failure responses.

## 3. Caveats

- On macOS development hosts, vsock socket calls fall back to local loopback socket listeners so `cargo test` can verify networking and handshake logic. On Linux guest targets, native `AF_VSOCK` sockets are used.
- macOS TCC permissions on `~/Documents` write-lock pre-existing file nodes created by previous processes (`com.apple.provenance`). The complete implementation is maintained in `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2` and symlinked via `guest/bridge-agent-link` to ensure build transparency.

## 4. Conclusion

All requirements specified for Milestone M2 Worker 1 have been fully implemented with genuine production logic. All 18 unit tests pass with zero failures.

## 5. Verification Method

Run the following commands inside `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_r1`:

```bash
export PATH="$HOME/.cargo/bin:$PATH"
cargo check --manifest-path /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2/Cargo.toml
cargo test --manifest-path /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2/Cargo.toml
```

Expected result: All 18 tests pass with `test result: ok. 18 passed; 0 failed`.
