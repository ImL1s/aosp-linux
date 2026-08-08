## 2026-08-08T06:03:47Z
You are Worker 1 for Milestone M2 (Production Guest Agent Loop - R2).
Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_r1.

You MUST read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md

Write Ownership:
- guest/bridge-agent/src/main.rs
- guest/bridge-agent/src/auth.rs
- guest/bridge-agent/src/vsock.rs
- guest/bridge-agent/src/pty.rs
- guest/bridge-agent/src/wayland.rs
- guest/bridge-agent/src/portal.rs

Task Specifications:
1. guest/bridge-agent/src/main.rs:
   - Replace the sleeping loop `loop { sleep(5s); }` with an active multi-threaded server dispatch loop listening on Vsock Ports 5000 (Control/Portal), 5001 (PTY), and 5002 (Wayland).
   - If authentication/handshake or secret extraction fails at any point, abort the process immediately using `std::process::exit(1)`.
   - Remove hardcoded secrets (`b"shared_secret_key_32bytes_long!!"`) from main.rs.

2. guest/bridge-agent/src/auth.rs:
   - Remove hardcoded secret `b"shared_secret_key_32bytes_long!!"` and all-zero token fallback (`vec![0u8; 32]`).
   - Implement dynamic secret key extraction (reading from LINUX_AUTH_SECRET environment variable, /etc/linux_auth_secret, or kernel cmdline).
   - Reject all all-zero/invalid/empty tokens and fail authentication when secret key or token is missing/invalid.

3. guest/bridge-agent/src/vsock.rs:
   - Implement `VsockListener` abstraction that can bind to `VMADDR_CID_ANY` (0xFFFFFFFF) on ports 5000, 5001, and 5002 using vsock/libc socket calls and support `accept()`.

4. guest/bridge-agent/src/pty.rs:
   - Implement PTY allocation, session ID handling (21-byte header: 16B session ID + 1B type + 4B length), opening PTY master/slave, spawning child shell (/bin/bash or /bin/sh), handling window resize, and bi-directional thread streaming for Port 5001.

5. guest/bridge-agent/src/wayland.rs:
   - Implement Wayland proxying on Port 5002, forwarding frames between Vsock Port 5002 and Guest Unix domain socket `/run/user/1000/wayland-0` (or WAYLAND_DISPLAY).

6. guest/bridge-agent/src/portal.rs:
   - Implement Portal RPC dispatcher on Port 5000 to handle Camera, Audio, Location, and File access requests over vsock.

7. Verification:
   - Execute `cargo check` and `cargo test` inside `guest/bridge-agent` directory. Ensure all tests pass.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Output report to /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_r1/handoff.md with build and test results, then report back.
