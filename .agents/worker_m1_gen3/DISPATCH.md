## 2026-08-06T06:14:55Z
<USER_REQUEST>
You are worker_m1_gen3, assigned to implement Milestone M1 (AOSP Framework & Core Modification Architecture) for the AOSP Dual-OS Project.

Your Working Directory: `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_gen3`
Workspace Root: `/Users/iml1s/Documents/mine/aosp-linux`

MANDATORY READS:
1. `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
3. `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md`
4. Explorer analysis reports:
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m1_1/analysis.md`
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m1_2/analysis.md`
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m1_3/analysis.md`

TASKS TO IMPLEMENT (Milestone M1):
1. F-R1-001: Framework API Namespace
   - Create `android.system.linux.LinuxManager` public System API class.
   - Create `android.system.linux.LinuxAppInfo` parcelable class.
2. F-R1-002: Framework AIDL Interfaces
   - `frameworks/base/core/java/android/system/linux/ILinuxManager.aidl`
   - `frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl`
   - `frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl`
   - `frameworks/base/core/java/android/system/linux/LinuxAppInfo.aidl`
   - `system/linux_bridge/ILinuxBridgeDaemon.aidl` (or appropriate native/framework AIDL package)
3. F-R1-003: SystemServer Integration
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
   - Register in `frameworks/base/services/java/com/android/server/SystemServer.java`
   - Register in `frameworks/base/core/java/android/app/SystemServiceRegistry.java` and `Context.java` (`LINUX_SERVICE = "linux"`)
   - Permissions in `frameworks/base/core/res/AndroidManifest.xml` (`android.permission.MANAGE_LINUX_CONTAINER`)
4. F-R1-004: Daemon Process Isolation & Socket Framing
   - Native daemon `linux_bridge` (C++/Rust skeleton in `system/linux_bridge/`)
   - Unix domain socket `/dev/socket/linux_bridge` creation and listening logic
   - vsock framing support for container/guest VM communication
5. F-R1-005: State Machine Lifecycle
   - FSM states: `OFF` (0) -> `STARTING` (1) -> `RUNNING` (2) -> `SUSPENDED` (3) -> `ERROR` (4)
   - 15-second boot timeout guard timer in `LinuxManagerService`
   - Callback dispatcher for `ILinuxStatusCallback`
6. SELinux Policies & Build Definitions
   - `system/sepolicy/private/linux_manager.te`
   - `system/sepolicy/private/linux_bridge.te`
   - Update `system/sepolicy/private/file_contexts` for `/dev/socket/linux_bridge`
   - Root / target `Android.bp` definitions.

VERIFICATION & TESTING:
- Compile/build all added code/modules or run standalone Java/C++ compilation / test scripts if full AOSP build tree environment is mockable/simulated.
- Run unit tests verifying state transitions, 15s boot timeout guard, AIDL interface stubs, socket handling.
- Document exact build and test commands and execution results in `handoff.md`.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

OUTPUT DELIVERABLES:
1. Write `changes.md` summarizing files created/modified.
2. Write a 5-component `handoff.md` in `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_gen3/handoff.md` (Observation, Logic Chain, Caveats, Conclusion, Verification Method).
3. Send a completion message back to parent when done.
</USER_REQUEST>
