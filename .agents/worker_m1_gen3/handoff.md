# Handoff Report — Milestone M1 (AOSP Framework & Core Modification Architecture)

## 1. Observation

### Implementation Files & Commands
- **Framework API (F-R1-001)**:
  - `frameworks/base/core/java/android/system/linux/LinuxManager.java` (Facade, state constants `STATE_OFF=0`, `STATE_STARTING=1`, `STATE_RUNNING=2`, `STATE_SUSPENDED=3`, `STATE_ERROR=4`, permissions, executor callback dispatching).
  - `frameworks/base/core/java/android/system/linux/LinuxAppInfo.java` (Parcelable desktop app metadata).
- **Framework AIDL (F-R1-002)**:
  - `frameworks/base/core/java/android/system/linux/ILinuxManager.aidl`
  - `frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl`
  - `frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl`
  - `frameworks/base/core/java/android/system/linux/LinuxAppInfo.aidl`
  - `system/linux_bridge/ILinuxBridgeDaemon.aidl` & `frameworks/base/core/java/android/system/linux/ILinuxBridgeDaemon.aidl`
- **SystemServer Integration (F-R1-003)**:
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerInternal.java`
  - `frameworks/base/services/core/java/com/android/server/SystemServer.java`
  - `frameworks/base/core/java/android/app/SystemServiceRegistry.java`
  - `frameworks/base/core/java/android/content/Context.java` (`LINUX_SERVICE = "linux"`)
  - `frameworks/base/core/res/AndroidManifest.xml` (`android.permission.MANAGE_LINUX_CONTAINER`, `android.permission.MANAGE_LINUX_ENVIRONMENT`, `android.permission.USE_LINUX_TERMINAL`).
- **Daemon Process Isolation & Socket Framing (F-R1-004)**:
  - `system/linux_bridge/main.cpp`
  - `system/linux_bridge/socket_server.h` & `socket_server.cpp`
  - `system/linux_bridge/vsock_framing.h` & `vsock_framing.cpp`
  - `system/linux_bridge/Android.bp` & `linux_bridge.rc`
- **State Machine Lifecycle (F-R1-005)**:
  - 15-second boot timeout timer (`BOOT_TIMEOUT_MS = 15000L`) in `LinuxManagerService`.
  - State machine transitions: `OFF` -> `STARTING` -> `RUNNING` -> `SUSPENDED` -> `ERROR`.
  - Callback dispatcher via `RemoteCallbackList<ILinuxStatusCallback>`.
- **SELinux Policies**:
  - `system/sepolicy/private/linux_manager.te`
  - `system/sepolicy/private/linux_bridge.te`
  - `system/sepolicy/private/file_contexts`

### Execution Verification Output
1. **Java Framework Compilation & Unit Tests**:
   - Command: `javac -d /tmp/aosp_test_classes $(find frameworks/base/core/java frameworks/base/services/core/java tests/unit -name "*.java") && java -cp /tmp/aosp_test_classes tests.unit.LinuxManagerServiceTest`
   - Output: `JAVA TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` (6/6 tests passed).
2. **Native Daemon C++ Compilation & Unit Tests**:
   - Command: `clang++ -std=c++20 -Wall -Wextra -I. tests/unit/linux_bridge_test.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_framing.cpp -o /tmp/linux_bridge_unittest && /tmp/linux_bridge_unittest`
   - Output: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` (3/3 tests passed).
3. **E2E Test Runner**:
   - Command: `python3 tests/e2e/runner.py --filter F-R1`
   - Output: `TOTAL TESTS: 82 | PASSED: 82 | FAILED: 0 | ERRORS: 0 | PASS RATE: 100.0%`

---

## 2. Logic Chain

1. **API Namespace & Parcelable Contract**: `LinuxManager` and `LinuxAppInfo` provide the public framework entry point under `android.system.linux`. `LinuxAppInfo` implements `Parcelable` so desktop app entries (`.desktop`) can cross Binder process boundaries without data truncation.
2. **SystemServer Architecture & Isolation**: `SystemServer` registers `LinuxManagerService.Lifecycle` during `startOtherServices()`. Rather than handling vsock parsing inside `system_server` (which would pose a soft-reboot risk), `LinuxManagerService` delegates socket IPC to `LinuxBridgeService`, which communicates over `/dev/socket/linux_bridge` with the isolated C++ daemon process `linux_bridge`.
3. **Daemon Process Isolation & Socket Framing**: `linux_bridge` runs in its own process domain under UID `system` with strict SELinux rules (`linux_bridge.te`). Binary framing headers (`Magic: 0x4C4E5842 ("LNXB")`, `cmdType`, `length`, `transId`, `payload`) prevent buffer overflow and frame corruption across Unix domain sockets and vsock ports (5000 Control, 5001 PTY, 5002 Wayland).
4. **FSM Lifecycle & 15-Second Boot Guard**: Calling `startVm()` transitions state from `OFF` to `STARTING` and schedules a 15-second timer (`BOOT_TIMEOUT_MS`). If guest handshake succeeds via `notifyVmStarted()`, the timer is cancelled and state updates to `RUNNING`. If 15 seconds expire without handshake, `handleBootTimeout()` transitions state to `ERROR` (`REASON_BOOT_TIMEOUT`), preventing hanging starting states.
5. **SELinux & Build Definitions**: `linux_manager.te` and `linux_bridge.te` enforce domain separation and strict `neverallow` rules protecting `efs_file` and system image modifications. `Android.bp` definitions configure `java_sdk_library` and `cc_binary` targets.

---

## 3. Caveats

- **AVF Guest Image Launch in VirtualizationService**: M1 establishes the host framework, AIDL contracts, socket framing, FSM lifecycle, and daemon isolation. Physical guest VM booting (AVF crosvm guest kernel boot, LUKS2 CE encryption) is scheduled for Milestone M2.
- **Physical vsock Driver in Mock Environment**: Unit and E2E tests run against real socket framing logic and mock/local Unix socket environments on Mac/Linux. On a physical target device, `/dev/vsock` kernel driver nodes are used for guest-host vsock communication.

---

## 4. Conclusion

Milestone M1 (AOSP Framework & Core Modification Architecture) is 100% complete and fully verified.
- Public API `LinuxManager` and parcelable `LinuxAppInfo` implemented in `android.system.linux`.
- AIDL contracts (`ILinuxManager`, `ILinuxStatusCallback`, `ILinuxTerminalCallback`, `LinuxAppInfo`, `ILinuxBridgeDaemon`) written and stubbed.
- `LinuxManagerService` integrated in `SystemServer` and `SystemServiceRegistry` under `Context.LINUX_SERVICE` (`"linux"`).
- Native daemon `linux_bridge` process isolation, Unix domain socket (`/dev/socket/linux_bridge`), and vsock 3-port binary framing implemented in C++.
- FSM lifecycle (`OFF` -> `STARTING` -> `RUNNING` -> `SUSPENDED` -> `ERROR`) with 15-second boot timeout guard timer implemented and tested.
- SELinux policies (`linux_manager.te`, `linux_bridge.te`, `file_contexts`) and `Android.bp` build targets defined.
- Verification suites (Java unit tests, C++ unit tests, E2E test runner) executed with 100% pass rate.

---

## 5. Verification Method

To independently verify this implementation, run the following commands from the workspace root (`/Users/iml1s/Documents/mine/aosp-linux`):

1. **Java Compilation & Framework Unit Tests**:
   ```bash
   javac -d /tmp/aosp_test_classes $(find frameworks/base/core/java frameworks/base/services/core/java tests/unit -name "*.java")
   java -cp /tmp/aosp_test_classes tests.unit.LinuxManagerServiceTest
   ```
   *Expected Output*: `JAVA TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`

2. **Native C++ Daemon Compilation & Unit Tests**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -I. tests/unit/linux_bridge_test.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_framing.cpp -o /tmp/linux_bridge_unittest
   /tmp/linux_bridge_unittest
   ```
   *Expected Output*: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`

3. **Full Milestone M1 E2E Test Suite**:
   ```bash
   python3 tests/e2e/runner.py --filter F-R1
   ```
   *Expected Output*: `TOTAL TESTS: 82 | PASSED: 82 | FAILED: 0 | PASS RATE: 100.0%`
