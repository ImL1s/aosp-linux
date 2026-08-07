## 2026-08-06T06:10:32Z

<USER_REQUEST>
You are Worker M1 (Gen 2) (teamwork_preview_worker) replacing a failed predecessor to implement Milestone M1 (AOSP Framework & Core Modification Architecture).
Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_gen2/

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Authoritative Input Documents & Explorer Specifications:
1. ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. SCOPE.md: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md
4. Technical Architecture Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md
5. Explorer 1 Spec (API & AIDL): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m1_1/analysis.md & handoff.md
6. Explorer 2 Spec (SystemServer & Services): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m1_2/analysis.md & handoff.md
7. Explorer 3 Spec (Daemon & StateMachine): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m1_3/analysis.md & handoff.md

Features to Implement (Milestone M1):
- F-R1-001: Framework API Namespace (`android.system.linux`)
  - Create `frameworks/base/core/java/android/system/linux/LinuxManager.java`
  - Create `frameworks/base/core/java/android/system/linux/LinuxAppInfo.java`
  - Create `frameworks/base/core/java/android/system/linux/LinuxAppInfo.aidl`
- F-R1-002: Framework AIDL Interfaces
  - Create `frameworks/base/core/java/android/system/linux/ILinuxManager.aidl`
  - Create `frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl`
  - Create `frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl`
- F-R1-003: SystemServer Integration
  - Create `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - Create `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`
  - Create `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerInternal.java`
  - Wire service registration into `frameworks/base/services/java/com/android/server/SystemServer.java`
- F-R1-004: Daemon Process Isolation (`linux_bridge`)
  - Create `system/vold/linux_bridge/` or `system/core/linux_bridge/` (C++/Rust daemon `linux_bridge` source, Android.bp, binary framing, Unix domain socket server `/dev/socket/linux_bridge`, vsock handler)
  - Create `system/sepolicy/private/linux_bridge.te`
  - Create `system/sepolicy/private/linux_manager.te`
  - Create `frameworks/base/core/java/android/system/linux/ILinuxBridgeDaemon.aidl`
- F-R1-005: State Machine Lifecycle Management
  - Implement full FSM (`OFF=0`, `STARTING=1`, `RUNNING=2`, `SUSPENDED=3`, `ERROR=4`) with synchronized `mStateLock`, 15-second boot timeout timer guard, and `ILinuxStatusCallback` notification dispatching in `LinuxManagerService`.

Instructions:
1. Carefully read all input files and Explorer analysis documents.
2. Implement all required files in their exact target paths under the repository root `/Users/iml1s/Documents/mine/aosp-linux`.
3. Ensure genuine, robust, thread-safe, and compilable implementations.
4. Record all created/modified files and summary of changes in `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_gen2/changes.md`.
5. Write your 5-component handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_gen2/handoff.md`.
6. Send a message to parent notifying that your work is done.
</USER_REQUEST>
