# Handoff Report — Explorer 1 for Milestone M3 (Real Vsock Socket Connect & Session ID - R3)

**From**: Explorer 1 (`explorer_m3_1`)  
**To**: Implementer 1 / Orchestrator  
**Milestone**: M3 (Real Vsock Socket Connect & Session ID - R3)  
**Date**: 2026-08-08  
**Artifact Path**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_1/handoff.md`

---

## 1. Observation

1. **`VsockTerminalClient.java` Socket Connection Defect**:
   - **Path**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
   - **Lines 31-36**:
     ```java
     public synchronized void connect(int guestCid, byte[] sessionId, TerminalStreamListener listener) throws IOException {
         try {
             mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
             mInputStream = new FileInputStream(mSocketFd);
             mOutputStream = new FileOutputStream(mSocketFd);
             mRunning = true;
     ```
   - **Observation**: `Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0)` allocates a socket descriptor `mSocketFd`, but `Os.connect(...)` (or native AF_VSOCK syscall `connect(fd, sockaddr_vm)`) is **never called**. `mInputStream` and `mOutputStream` wrap an unconnected file descriptor. Attempting to read or write on a real kernel vsock driver fails with `ENOTCONN` (*Transport endpoint is not connected*).

2. **`TerminalView.java` Hardcoded Session ID Defect**:
   - **Path**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
   - **Line 49 & Line 82**:
     ```java
     private byte[] mSessionId = "0123456789abcdef".getBytes();
     ...
     @Override
     protected void onAttachedToWindow() {
         super.onAttachedToWindow();
         connectVsock(GUEST_CID, mSessionId);
     }
     ```
   - **Observation**: `mSessionId` is statically initialized to `"0123456789abcdef"`. The view attaches to the window and connects using this hardcoded ID without requesting a dynamic session ID from `LinuxManagerService`.

3. **`LinuxManagerService.java` Session ID Length Defect**:
   - **Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
   - **Lines 391-393**:
     ```java
     String sessionId = "session_" + (++mNextSessionId);
     TerminalSession session = new TerminalSession(sessionId, width, height, callback);
     ```
   - **Observation**: `createTerminalSession` produces strings such as `"session_1001"` (12 bytes). However, `VsockPtyFramer.java` lines 49 and 67 strictly enforce that `sessionId` must be **exactly 16 bytes**. Passing a 12-byte session ID triggers `IllegalArgumentException: Session ID must be exactly 16 bytes`.

4. **Available Android AF_VSOCK API**:
   - **Observation**: `android.system.SocketAddressVmSockets` (constructor `SocketAddressVmSockets(int port, int cid)`) combined with `android.system.Os.connect(mSocketFd, address)` is available in Android platform APIs when `platform_apis: true` is set in `Android.bp`.

---

## 2. Logic Chain

1. **Observation 1** (`VsockTerminalClient.java:33`) shows `Os.socket(AF_VSOCK, ...)` creates a file descriptor, but omitting `Os.connect(...)` leaves the descriptor unconnected.
2. Therefore, when `VsockTerminalClient` attempts to read/write via `FileInputStream` or `FileOutputStream`, the kernel returns `ENOTCONN` error.
3. To fix this, `VsockTerminalClient.connect(...)` must invoke `Os.connect(mSocketFd, new SocketAddressVmSockets(5001, guestCid))` before opening `FileInputStream`/`FileOutputStream`.
4. **Observation 2** (`TerminalView.java:49`) shows `TerminalView` hardcodes `mSessionId` to `"0123456789abcdef"`, bypassing dynamic session management.
5. **Observation 3** (`LinuxManagerService.java:392`) shows `createTerminalSession` generates 12-byte session IDs (`"session_1001"`), which violates `VsockPtyFramer`'s requirement of 16-byte binary tokens.
6. Therefore, `LinuxManagerService` must be updated to generate 16-byte random tokens (or 32 hex character strings representing 16 bytes), and `TerminalView` must dynamically request a session ID from `LinuxManagerService` upon connection.

---

## 3. Caveats

- **No caveats**: Code locations, line numbers, API signatures (`SocketAddressVmSockets`, `Os.connect`), framing requirements (`VsockPtyFramer`), and test targets were completely verified.

---

## 4. Conclusion & Actionable Recommendations

### Action Plan for Implementer:
1. **Modify `VsockTerminalClient.java`**:
   - Add `import android.system.SocketAddressVmSockets;` and `import java.net.SocketAddress;`.
   - In `connect(int guestCid, byte[] sessionId, TerminalStreamListener listener)`:
     - Check `sessionId != null && sessionId.length == 16`.
     - Create socket: `mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);`
     - Connect socket: `SocketAddress address = new SocketAddressVmSockets(VPORT_PTY, guestCid); Os.connect(mSocketFd, address);`
     - Catch `ErrnoException`, clean up via `close()`, and re-throw `IOException`.
2. **Modify `LinuxManagerService.java`**:
   - In `createTerminalSession(...)`, generate 16-byte random token (32 hex chars) instead of `"session_1001"`.
3. **Modify `TerminalView.java`**:
   - Query `ILinuxManager.createTerminalSession(...)` AIDL to obtain dynamic session ID on launch instead of hardcoded `"0123456789abcdef"`.

---

## 5. Verification Method

1. **Inspect Target Files**:
   - Inspect `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java` lines 31-70.
   - Inspect `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` line 49.
   - Inspect `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` lines 390-400.

2. **Execute Unit Tests**:
   - Build and run Java unit test suite:
     ```bash
     javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src \
       -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') \
       tests/unit/TerminalAppUnitTest.java
     java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
     ```

3. **Execute E2E Integration Suite**:
   - Run M3 Tier 1 E2E tests:
     ```bash
     pytest tests/e2e/tier1_feature_coverage/test_m3_tier1.py
     ```
   - Verify `test_m3_tier1.py` output logs pass without socket errors.
