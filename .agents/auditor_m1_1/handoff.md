# Forensic Audit Report — Milestone M1 (AOSP Framework & Core Modification Architecture)

**Auditor Agent**: `auditor_m1_1`  
**Target Milestone**: Milestone M1 (Framework API, AIDL, SystemServer, C++ Daemon, SELinux Policies, Android.bp)  
**Profile**: General Project  
**Verdict**: `CLEAN`  

---

## 1. Observation

### Source Code Inspection & AST Line-by-Line Findings
1. **Framework Public API (`android.system.linux`)**:
   - `frameworks/base/core/java/android/system/linux/LinuxManager.java` (Lines 1-343): Public SystemApi facade bound to `Context.LINUX_SERVICE` (`"linux"`). Enforces `PERMISSION_MANAGE_LINUX_ENVIRONMENT` on lifecycle methods (`startVm`, `stopVm`, `suspendVm`, `resumeVm`, `installGuestImage`). Delegates IPC directly to `ILinuxManager`.
   - `frameworks/base/core/java/android/system/linux/LinuxAppInfo.java` (Lines 1-168): Immutable parcelable structure representing desktop entries (`.desktop`). Implements `Parcelable.Creator<LinuxAppInfo>` with full field serialization (`writeToParcel` / `readFromParcel`).
2. **SystemServer Services & IPC Dispatching**:
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` (Lines 1-398): Manages FSM states (`STATE_STOPPED`=0, `STATE_STARTING`=1, `STATE_RUNNING`=2, `STATE_SUSPENDED`=3, `STATE_ERROR`=4). Implements 15-second boot timeout timer (`BOOT_TIMEOUT_MS = 15000L`) using `ScheduledExecutorService` and `ScheduledFuture`. Enforces `PERMISSION_MANAGE_LINUX_ENVIRONMENT` and `PERMISSION_USE_LINUX_TERMINAL` across all Binder stub methods. Dispatches status changes via `RemoteCallbackList<ILinuxStatusCallback>`.
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java` (Lines 1-293): Native bridge IPC layer communicating over `/dev/socket/linux_bridge`. Implements binary framing protocol (`MAGIC = 0x4C4E5842` / `"LNXB"`). Serializes `cmdType`, `length`, `transId`, and `payload` with boundary checks (`MAX_PAYLOAD_SIZE = 16MB`).
3. **C++ Native Daemon (`system/linux_bridge/`)**:
   - `main.cpp` (Lines 1-58): Signal handling (`SIGINT`, `SIGTERM`), socket path initialization (`/dev/socket/linux_bridge`).
   - `socket_server.h` & `socket_server.cpp` (Lines 1-213): Unix domain socket server with non-blocking accept loop, thread-per-client pool, network byte order conversion (`htonl`/`ntohl`/`htons`/`ntohs`), `LNXB_MAGIC` validation, partial read loop (`readFull`), and max payload cap (`16MB`).
   - `vsock_framing.h` & `vsock_framing.cpp` (Lines 1-72): Vsock 3-port framing layout (Port 5000 Control, Port 5001 PTY, Port 5002 Wayland), `VSOK_MAGIC` (`0x56534F4B`) header packing & unpacking.
4. **SELinux Hardening Policies**:
   - `system/sepolicy/private/linux_manager.te` (Lines 1-32): Domain definition, binder permissions with `system_server`, kvm device access (`rw_file_perms`), `virtualizationservice` IPC, and strict `neverallow` rules blocking access to `efs_file` and system image writes.
   - `system/sepolicy/private/linux_bridge.te` (Lines 1-27): Isolated daemon domain definition (`linux_bridge`), socket file creation at `/dev/socket/linux_bridge`, vsock socket permissions, and `neverallow` rules on `efs_file`.
   - `system/sepolicy/private/file_contexts` (Lines 1-4): Explicit context labeling for `/dev/socket/linux_bridge`, `/system/bin/linux_bridge`, and `/data/system/linux`.
5. **Build System Blueprint**:
   - Root `Android.bp`: `java_sdk_library` ("android.system.linux"), `java_library` ("framework-linux", "services.linux", "service-linux").
   - `system/linux_bridge/Android.bp`: `cc_binary` ("linux_bridge") compiled with `-Wall -Werror -Wextra -std=c++20`.

### Executed Verification Commands & Outputs
1. **Java Framework & SystemServer Unit Tests**:
   - Command: `javac -d /tmp/aosp_test_classes $(find frameworks/base/core/java frameworks/base/services/core/java tests/unit -name "*.java") && java -cp /tmp/aosp_test_classes tests.unit.LinuxManagerServiceTest`
   - Result: `JAVA TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` (7/7 test cases passed).
2. **Java Empirical Stress & Concurrency Suite**:
   - Command: `javac -d /tmp/aosp_test_classes $(find frameworks/base/core/java frameworks/base/services/core/java tests/unit -name "*.java") && java -cp /tmp/aosp_test_classes tests.unit.LinuxManagerServiceStressTest`
   - Result: `STRESS TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` (9/9 empirical stress cases passed, including 20-thread 10,000 op race test, 15s real-time scheduled timer expiration, 100-listener broadcast, and dead binder recovery).
3. **C++ Native Daemon Unit Tests**:
   - Command: `clang++ -std=c++20 -Wall -Wextra -I. tests/unit/linux_bridge_test.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_framing.cpp -o /tmp/linux_bridge_unittest && /tmp/linux_bridge_unittest`
   - Result: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` (4/4 native test cases passed).
4. **Python E2E Milestone M1 Test Suite**:
   - Command: `python3 tests/e2e/runner.py --filter F-R1`
   - Result: `TOTAL TESTS: 61 | PASSED: 61 | FAILED: 0 | ERRORS: 0 | PASS RATE: 100.0%`.

---

## 2. Logic Chain

1. **Authentic Implementation & Facade Verification**:
   - Static analysis confirmed zero hardcoded test pass shortcuts, string literal test echoes, or dummy facades.
   - FSM state transitions in `LinuxManagerService` enforce strict state guards (e.g. `startVm()` rejects calls while in `STARTING` or `RUNNING`; `suspendVm()` requires `RUNNING` state).
   - Timer handling uses real Java `ScheduledExecutorService` (`BOOT_TIMEOUT_MS = 15000L`) that triggers `handleBootTimeout()` and transitions state to `STATE_ERROR` with reason `101`. Empirical stress testing confirmed the scheduled timer fires accurately at ~15,000ms and is properly cancelled upon `notifyVmStarted()` or `stopVm()`.
2. **Protocol Framing & Security Boundaries**:
   - Socket server framing in `socket_server.cpp` and vsock framing in `vsock_framing.cpp` validate magic headers (`LNXB_MAGIC = 0x4C4E5842`, `VSOK_MAGIC = 0x56534F4B`), parse network byte order (`ntohl`/`ntohs`), and enforce a 16MB payload ceiling (`MAX_PAYLOAD_SIZE`). Malformed packets or oversized lengths are rejected at the parser boundary before memory allocation.
   - SELinux rules in `linux_manager.te` and `linux_bridge.te` restrict daemon execution to UID 1000 (`system`) and enforce strict `neverallow` rules prohibiting writes to `efs_file` or system partitions.
3. **Security Audit & Clean Record**:
   - Search for security backdoors, hardcoded passwords, hidden bypasses, or debug backdoors yielded 0 findings across the codebase.
   - Permission checks (`PERMISSION_MANAGE_LINUX_ENVIRONMENT` and `PERMISSION_USE_LINUX_TERMINAL`) are enforced on every Binder IPC entry point in `LinuxManagerService`.

---

## 3. Caveats

- **Mock Environment vs Target Kernel Nodes**: Unit and E2E verification suites run Unix domain socket testing at `/dev/socket/linux_bridge` and mock vsock helpers. On target ARM64 hardware running Linux 6.6+, physical `/dev/vsock` kernel drivers handle host-guest vsock port multiplexing.
- **Milestone Scope Boundary**: Milestone M1 defines the host framework, AIDL contracts, socket framing, SystemServer lifecycle, and SELinux domains. Physical Debian kernel booting and LUKS2 CE storage encryption are scheduled for Milestone M2.

---

## 4. Conclusion

Milestone M1 (AOSP Framework & Core Modification Architecture) passes all forensic integrity checks with complete authenticity.
- **Genuine Implementation**: Verified. State machine, boot timeout timer, socket protocol framing, and AIDL dispatching contain real computational logic without facade bypasses.
- **Security & Boundaries**: Verified. Permission enforcement, SELinux domain isolation, and framing bounds checks are fully established.
- **Audit Verdict**: `CLEAN`

---

## 5. Verification Method

To independently verify the audit results, run the following commands from the workspace root (`/Users/iml1s/Documents/mine/aosp-linux`):

1. **Compile and Run Java Unit Test Suite**:
   ```bash
   javac -d /tmp/aosp_test_classes $(find frameworks/base/core/java frameworks/base/services/core/java tests/unit -name "*.java")
   java -cp /tmp/aosp_test_classes tests.unit.LinuxManagerServiceTest
   ```
   *Expected Output*: `JAVA TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`

2. **Compile and Run Java Empirical Stress Test Suite**:
   ```bash
   java -cp /tmp/aosp_test_classes tests.unit.LinuxManagerServiceStressTest
   ```
   *Expected Output*: `STRESS TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`

3. **Compile and Run C++ Native Daemon Unit Tests**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -I. tests/unit/linux_bridge_test.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_framing.cpp -o /tmp/linux_bridge_unittest
   /tmp/linux_bridge_unittest
   ```
   *Expected Output*: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`

4. **Run Milestone M1 E2E Suite**:
   ```bash
   python3 tests/e2e/runner.py --filter F-R1
   ```
   *Expected Output*: `TOTAL TESTS: 61 | PASSED: 61 | FAILED: 0 | PASS RATE: 100.0%`
