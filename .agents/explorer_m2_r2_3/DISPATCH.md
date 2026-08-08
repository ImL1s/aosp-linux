## 2026-08-08T06:20:15Z
<USER_REQUEST>
You are Explorer 3 for Milestone M2 (Iteration 2). Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3.

You MUST read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m2_r1_2/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_r1_2/handoff.md

Defects to Investigate & Fix Design:
1. Wayland Full-Duplex Deadlock: `proxy_bi_directional` in `src/wayland.rs` holds `Arc<Mutex<Stream>>` lock during blocking `.read()` calls, blocking the writer thread from sending Wayland events to the client.
   -> Plan fix using `stream.try_clone()` (or `UnixStream::try_clone` / `VsockStream::try_clone`) to split read and write streams independently without holding Mutex across blocking read.
2. Unbounded Memory Allocation (OOM DoS): `pty.rs` reads `payload_len` without upper bound.
   -> Plan adding `MAX_PAYLOAD_SIZE = 65536` check.
3. Socket FD Leak: `VsockListener::Vsock` lacks `Drop` implementation to close raw libc socket fd.
   -> Plan implementing `Drop` for `VsockListener` to call `libc::close(raw_fd)`.

Output report to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3/handoff.md and report back.
</USER_REQUEST>
