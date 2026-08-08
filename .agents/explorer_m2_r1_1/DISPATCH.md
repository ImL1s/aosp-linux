## 2026-08-08T06:01:39Z
<USER_REQUEST>
You are Explorer 1 for Milestone M2. Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r1_1.
You MUST read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md

Objective: Investigate guest/bridge-agent codebase (especially src/main.rs and src/vsock.rs).
Analyze how to replace the current loop { sleep(5s); } with a multi-threaded server listener loop listening on Vsock Ports 5000 (Control/Portal), 5001 (PTY), and 5002 (Wayland).
Detail the required Rust structs, thread spawning/async handling, vsock socket creation/binding/listening (vsock::VsockListener or standard vsock sockets), and port multiplexing.
Output report to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r1_1/handoff.md and report back.
</USER_REQUEST>
