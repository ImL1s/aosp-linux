## 2026-08-08T06:22:02Z
You are Worker 2 for Milestone M2 Iteration 2 (Production Guest Agent Loop - R2).
Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_r2.

You MUST read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m2_r1_1/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_1/handoff.md
6. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_2/handoff.md
7. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3/handoff.md

Write Ownership (CANONICAL PATH ONLY):
- guest/bridge-agent/src/main.rs
- guest/bridge-agent/src/auth.rs
- guest/bridge-agent/src/vsock.rs
- guest/bridge-agent/src/pty.rs
- guest/bridge-agent/src/wayland.rs
- guest/bridge-agent/src/portal.rs
- guest/bridge-agent/Cargo.toml

Remediation Tasks (Iteration 2):
1. CANONICAL PATH DELIVERY & CLEANUP:
   - All source code MUST be placed and updated directly in `guest/bridge-agent/src/` (main.rs, auth.rs, vsock.rs, pty.rs, wayland.rs, portal.rs).
   - Delete secondary/temporary folders `guest/bridge-agent-m2` and `guest/bridge-agent-link` if present.

2. PTY IO SAFETY DUP FIX (`guest/bridge-agent/src/pty.rs`):
   - In `spawn_shell`: Use `libc::dup(slave_fd)` (or `nix::unistd::dup`) 3 times to generate 3 separate owned file descriptors (`stdin_fd`, `stdout_fd`, `stderr_fd`) before passing each to `Stdio::from_raw_fd`. This fixes the double/triple drop IO Safety violation that caused SIGABRT (-6).
   - Ensure `PtyMaster` drop closes master_fd cleanly without use-after-close or thread leaks.

3. WAYLAND FULL-DUPLEX DEADLOCK FIX (`guest/bridge-agent/src/wayland.rs`):
   - In `proxy_bi_directional`: Remove `Arc<Mutex<Stream>>` holding across blocking `.read()` calls. Use `stream.try_clone()` (or `UnixStream::try_clone` / `VsockStream::try_clone`) to split the stream into separate reader and writer handles.

4. UNBOUNDED PAYLOAD SIZE FIX (`guest/bridge-agent/src/pty.rs` & framers):
   - Cap `payload_len` with `MAX_PAYLOAD_SIZE = 65536` (64KB limit). Return error if incoming payload size exceeds this limit.

5. SOCKET FD LEAK FIX (`guest/bridge-agent/src/vsock.rs`):
   - Implement `Drop` for `VsockListener` to call `libc::close(raw_fd)`.

6. AUTH HARDENING & ABORT (`guest/bridge-agent/src/auth.rs` & `main.rs`):
   - Dynamic secret key extraction (`LINUX_AUTH_SECRET`, `/etc/linux_auth_secret`, or `/proc/cmdline`).
   - Reject empty/all-zero tokens.
   - Execute `std::process::exit(1)` immediately on authentication or secret extraction failure.

7. VERIFICATION:
   - Run `cargo check` and `cargo test` INSIDE `guest/bridge-agent`. Verify 100% test pass rate.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Output report to /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_r2/handoff.md with build and test results, then report back.
