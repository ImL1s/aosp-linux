# Implementation Changes Report — Milestone M3 (Real Vsock Socket Connect & Session ID - R3)

**Author**: Worker M3 (worker_m3_1)  
**Milestone**: M3 (Real Vsock Socket Connect & Session ID - R3)  
**Date**: 2026-08-08  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_1`

---

## 1. Summary of Changes

To resolve deterministic defects in Vsock socket connectivity, hardcoded session IDs, and session ID header length assertions, genuine code implementations were made to three core files:

1. **`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`**:
   - **Real AF_VSOCK socket connect**: Replaced unconnected socket creation with real AF_VSOCK syscall `Os.connect(mSocketFd, address)` targeting Guest CID (`guestCid`, typically 3) and Port `5001` (`VPORT_PTY`).
   - **Vsocket address construction**: Utilized `android.system.VmSocketAddress` with reflection fallback for `android.system.SocketAddressVmSockets` to support diverse Android runtime classloader environments.
   - **Pre-flight assertions**: Enforced strict length assertions on `sessionId` (`sessionId.length == 16`) before socket allocation.
   - **Exception handling & cleanup**: Caught `ErrnoException` and re-threw `IOException` with detailed diagnostic context while invoking `close()` to ensure socket file descriptors (`mSocketFd`) and streams are cleanly torn down on failure.
   - **Loopback socket support**: Retained `connectSocket(java.net.Socket socket, ...)` for host JVM unit test suites.

2. **`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`**:
   - **Dynamic Session ID acquisition**: Replaced hardcoded static session ID `"0123456789abcdef"` with dynamic 16-byte session tokens issued by `LinuxManagerService`.
   - **Binder IPC binding**: Added `initDynamicSessionAndConnect()` in `onAttachedToWindow()` to query `ServiceManager.getService("linux_service")` and invoke `ILinuxManager.createTerminalSession(mColumns, mRows, null)` to acquire dynamic 16-character ASCII tokens (`session_00001001`).
   - **Fallback mechanism**: Included fallback to default 16-byte array if binder service is unavailable (e.g. standalone unit test environments).

3. **`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`**:
   - **16-byte Session ID formatting**: Updated `createTerminalSession` implementation from `"session_" + (++mNextSessionId)` (12 bytes) to `String.format(java.util.Locale.US, "session_%08d", ++mNextSessionId)` (exact 16 bytes).
   - **Header assertion alignment**: Aligned generated session ID length with `VsockPtyFramer`'s strict `HEADER_SIZE` assertion requirements (`SESSION_ID_SIZE = 16`).

---

## 2. File Modification Details

### 2.1 `VsockTerminalClient.java`
- **File Path**: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
- **Changes**:
  - Imported `android.system.VmSocketAddress` and `java.net.SocketAddress`.
  - In `connect(int guestCid, byte[] sessionId, listener)`:
    - Asserted `sessionId != null && sessionId.length == 16`.
    - Executed `Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0)`.
    - Constructed vsock `SocketAddress` via `VmSocketAddress(VPORT_PTY, guestCid)`.
    - Invoked `Os.connect(mSocketFd, address)`.
    - Wrapped `mSocketFd` in `FileInputStream` and `FileOutputStream`.
    - Handled `ErrnoException` / `Exception` with resource cleanup via `close()`.

### 2.2 `TerminalView.java`
- **File Path**: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
- **Changes**:
  - Imported `android.os.IBinder`, `android.os.ServiceManager`, `android.system.linux.ILinuxManager`, `java.nio.charset.StandardCharsets`.
  - Added `initDynamicSessionAndConnect()` method to query `ILinuxManager.createTerminalSession(...)`.
  - Updated `onAttachedToWindow()` to execute `initDynamicSessionAndConnect()`.

### 2.3 `LinuxManagerService.java`
- **File Path**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- **Changes**:
  - Updated `createTerminalSession`:
    ```java
    String sessionId = String.format(java.util.Locale.US, "session_%08d", ++mNextSessionId);
    ```

---

## 3. Verification & Test Results

### 3.1 Java Unit Test Suites
1. **`TerminalAppUnitTest`**:
   - Command:
     ```bash
     javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') tests/unit/TerminalAppUnitTest.java
     java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
     ```
   - Result: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY` (Exit code: 0)

2. **`LinuxManagerServiceTest`**:
   - Command:
     ```bash
     javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java -d /tmp/m3_service_classes $(find frameworks/base/services/core/java/com/android/server/linux -name '*.java') tests/unit/LinuxManagerServiceTest.java
     java -cp /tmp/m3_service_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.LinuxManagerServiceTest
     ```
   - Result: `JAVA TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` (Exit code: 0)

### 3.2 Python E2E Test Runner
1. **Tier 1 Feature Coverage (`F-R3`)**:
   - Command: `python3 tests/e2e/runner.py --tier 1 --feature F-R3`
   - Output: `TOTAL TESTS: 35 | PASSED: 35 | FAILED: 0 | PASS RATE: 100.0%` (Exit code: 0)

2. **Tier 2 Boundary & Corner Cases (`F-R3`)**:
   - Command: `python3 tests/e2e/runner.py --tier 2 --feature F-R3`
   - Output: `TOTAL TESTS: 35 | PASSED: 35 | FAILED: 0 | PASS RATE: 100.0%` (Exit code: 0)
