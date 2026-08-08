## 2026-08-08T06:20:14Z
<USER_REQUEST>
You are Explorer 2 for Milestone M2 (Iteration 2). Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_2.

You MUST read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_r1_1/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_r1_2/handoff.md

Defects to Investigate & Fix Design:
1. PTY IO Safety Violation: `spawn_shell` in `src/pty.rs` passed raw `slave_fd` to `stdin`, `stdout`, and `stderr` via `Stdio::from_raw_fd(slave_fd)` 3 times. Rust std library detects owned file descriptor closed twice, triggering SIGABRT (-6).
   -> Plan fix using `libc::dup(slave_fd)` (or `nix::unistd::dup`) so stdin, stdout, stderr each receive a distinct duped owned file descriptor.
2. File Descriptor Use-After-Close & Master/Slave Lifecycle: `PtyMaster` drop closes master_fd while reader thread is reading from it.
   -> Plan proper `Arc<PtyMaster>` / dup'd fd lifecycle for master_fd in PTY reader thread.

Output report to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_2/handoff.md and report back.
</USER_REQUEST>
