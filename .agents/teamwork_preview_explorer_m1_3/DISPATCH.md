## 2026-08-08T06:01:45Z
<USER_REQUEST>
You are Explorer 3 for Milestone M1 (Real AVF VM Launch - R1).
Your working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_3

Mandatory files to read:
- /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/DISPATCH.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_1/handoff.md

Scope files:
- system/linux_bridge/socket_server.cpp
- guest/scripts/launch_vm.sh
- frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java
- frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java

Task:
Analyze build system, existing test infrastructure, and test scripts for M1.
Detail:
1. Exact commands to build and run native unit tests (`socket_server` / `linux_bridge`).
2. Exact commands to test `launch_vm.sh` in TEST_MODE and non-test mode.
3. Concrete unit & integration test strategies for verifying that fake `CMD_HANDSHAKE_COMPLETE` responses are eliminated and real process spawning & VM launch occurs.

Write your report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_3/handoff.md`.
Do NOT modify any source code files. You are a read-only explorer.
</USER_REQUEST>
