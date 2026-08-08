## 2026-08-08T06:30:26Z
You are Worker 3 for Milestone M2 Iteration 3 (Production Guest Agent Loop - R2).
Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_r3.

You MUST read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m2_r2_2/handoff.md

Write Ownership (CANONICAL PATH ONLY):
- guest/bridge-agent/src/main.rs
- guest/bridge-agent/src/auth.rs
- guest/bridge-agent/src/vsock.rs
- guest/bridge-agent/src/pty.rs
- guest/bridge-agent/src/wayland.rs
- guest/bridge-agent/src/portal.rs
- guest/bridge-agent/Cargo.toml

Remediation Tasks (Iteration 3):
1. FIX PTY STREAM LOCKING DEADLOCK (`guest/bridge-agent/src/pty.rs`):
   - In `handle_pty_session`: Do NOT hold `Arc<Mutex<Stream>>` across blocking `stream.read_exact` calls.
   - Use `stream.try_clone()?` to split `stream` into a `read_stream` and a `write_stream` (wrapped in `Arc<Mutex<WriteStream>>` or clone). PTY reader thread writes shell stdout/stderr to `write_stream`. Main PTY input loop reads client commands from `read_stream` without holding the write lock during read.

2. FIX AUTH HANDSHAKE SOCKET HANG (`guest/bridge-agent/src/auth.rs`):
   - Set a 5-second socket read timeout (`set_read_timeout(Some(Duration::from_secs(5)))`) before performing `perform_handshake` / reading token bytes.
   - Reset read timeout to `None` (or zero) after successful authentication. If handshake times out or fails, close stream and return error / abort.

3. DELETE DEAD CODE:
   - Delete `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/ota_rollback.rs` if present.

4. VERIFICATION:
   - Run `cargo check` and `cargo test` INSIDE `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent`. Ensure all tests pass cleanly without hanging.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Output report to /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_r3/handoff.md with build and test results, then report back.
