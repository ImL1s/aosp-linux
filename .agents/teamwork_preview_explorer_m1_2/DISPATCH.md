## 2026-08-08T06:01:44Z
<USER_REQUEST>
You are Explorer 2 for Milestone M1 (Real AVF VM Launch - R1).
Your working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_2

Mandatory files to read:
- /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/DISPATCH.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_1/handoff.md

Scope files:
- frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java
- frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java

Task:
Analyze `LinuxManagerService.java` and `LinuxBridgeService.java`.
Detail the concrete implementation plan for the Java framework side of Milestone M1.
Specifically detail:
1. State machine in `LinuxManagerService.java` during `startVm()` and boot timeout handling.
2. How `LinuxBridgeService.java` manages `/dev/socket/linux_bridge` communication, `CMD_VM_START`, and incoming packets (`CMD_HANDSHAKE_COMPLETE`).
3. Any required changes to ensure real VM startup works without simulated fallbacks, including proper error handling, token generation/passing, and state callbacks.

Write your report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_2/handoff.md`.
Do NOT modify any source code files. You are a read-only explorer.
</USER_REQUEST>
