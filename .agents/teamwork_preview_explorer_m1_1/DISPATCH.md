## 2026-08-08T06:01:44Z
<USER_REQUEST>
You are Explorer 1 for Milestone M1 (Real AVF VM Launch - R1).
Your working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_1

Mandatory files to read:
- /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/DISPATCH.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_1/handoff.md

Scope files:
- system/linux_bridge/socket_server.cpp
- guest/scripts/launch_vm.sh

Task:
Analyze `system/linux_bridge/socket_server.cpp` and `guest/scripts/launch_vm.sh`.
Detail the concrete implementation plan to replace the fake `CMD_HANDSHAKE_COMPLETE` response upon receiving `CMD_VM_START` (0x0001).
Specifically detail:
1. How `socket_server.cpp` should launch `guest/scripts/launch_vm.sh` (or invocation parameters, token passing, fork/exec or posix_spawn, child PID tracking, pipe/stdout handling, teardown on CMD_VM_STOP).
2. How `launch_vm.sh` parses args, handles security token, checks crosvm binary, and starts crosvm.
3. How `socket_server.cpp` defers `CMD_HANDSHAKE_COMPLETE` until real Vsock handshake occurs.

Write your report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_1/handoff.md`.
Do NOT modify any source code files. You are a read-only explorer.
</USER_REQUEST>
