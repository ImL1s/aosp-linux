# Explorer 1 Investigation Report — Milestone M3 (Real Vsock Socket Connect & Session ID - R3)

**Author**: Explorer 1  
**Milestone**: M3 (Real Vsock Socket Connect & Session ID - R3)  
**Date**: 2026-08-08  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_1`

---

## 1. Executive Summary

This investigation analyzes the defective socket initialization and session ID mechanism in the Android `LinuxTerminal` app (`VsockTerminalClient.java`, `TerminalView.java`) and `LinuxManagerService.java`. Currently:
1. `VsockTerminalClient.java` creates an AF_VSOCK socket descriptor via `Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0)`, but **never invokes `Os.connect(...)`** to establish a socket connection to Guest CID 3, Port 5001. Reading/writing to stream wrappers operates on an unconnected file descriptor.
2. `TerminalView.java` uses a hardcoded 16-byte session ID `"0123456789abcdef"` instead of requesting dynamic session IDs from `LinuxManagerService`.
3. `LinuxManagerService.java` generates session ID strings formatted as `"session_1001"` (12 bytes), which violates `VsockPtyFramer`'s mandatory 16-byte session ID header requirement (`SESSION_ID_SIZE = 16`).

This report provides the exact Java/Android system APIs (`android.system.Os.connect`, `android.system.SocketAddressVmSockets`), CID/port configuration, session ID refactoring, exception handling, and build/test verification steps.

---

## 2. Detailed Observations & Code Inspection

### 2.1 `VsockTerminalClient.java`
- **File Location**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
- **Lines 31-37**:
  ```java
  public synchronized void connect(int guestCid, byte[] sessionId, TerminalStreamListener listener) throws IOException {
      try {
          mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
          mInputStream = new FileInputStream(mSocketFd);
          mOutputStream = new FileOutputStream(mSocketFd);
          mRunning = true;
  ```
- **Defect Observation**:
  - `Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0)` allocates a socket file descriptor `mSocketFd` (where `AF_VSOCK = 40`).
  - Immediately following socket creation, `mInputStream` and `mOutputStream` wrap `mSocketFd`.
  - **No `Os.connect(...)` call is executed**. The socket file descriptor remains unconnected.
  - When executed against a real Linux kernel virtio-vsock driver, subsequent read or write calls on `mInputStream` or `mOutputStream` fail immediately with `ENOTCONN` (*Transport endpoint is not connected*).

### 2.2 `TerminalView.java`
- **File Location**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
- **Line 49 & 82**:
  ```java
  private byte[] mSessionId = "0123456789abcdef".getBytes();

  @Override
  protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      connectVsock(GUEST_CID, mSessionId);
  }
  ```
- **Defect Observation**:
  - `mSessionId` is statically initialized to `"0123456789abcdef"`.
  - On window attachment, `TerminalView` directly initiates `connectVsock(GUEST_CID, mSessionId)` using the hardcoded session ID without querying `LinuxManagerService` (`ILinuxManager.createTerminalSession(...)`).

### 2.3 `LinuxManagerService.java`
- **File Location**: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- **Lines 387-401**:
  ```java
  @Override
  public String createTerminalSession(int width, int height, ILinuxTerminalCallback callback) {
      ...
      synchronized (mStateLock) {
          String sessionId = "session_" + (++mNextSessionId);
          TerminalSession session = new TerminalSession(sessionId, width, height, callback);
          mTerminalSessions.put(sessionId, session);
          ...
          return sessionId;
      }
  }
  ```
- **Defect Observation**:
  - `createTerminalSession` produces session IDs such as `"session_1001"` (length 12 bytes).
  - In `VsockPtyFramer.java` (lines 49, 67), `Frame` constructor and `serializeFrame` strictly require `sessionId` to be **exactly 16 bytes** (`if (sessionId == null || sessionId.length != 16)`).
  - Passing a 12-byte string causes `IllegalArgumentException: Session ID must be exactly 16 bytes`.

---

## 3. Technical Analysis & Solution Architecture

### 3.1 Real AF_VSOCK Socket Creation & `Os.connect` API
In Android (API 30+ / Android 14+ AOSP Platform), AF_VSOCK socket connection is established using:
1. **Socket Allocation**:
   ```java
   FileDescriptor fd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
   ```
   - `AF_VSOCK` = `40` (defined as `private static final int AF_VSOCK = 40;` or `OsConstants.AF_VSOCK`).
2. **Address & Syscall Invocation**:
   ```java
   SocketAddress address = new SocketAddressVmSockets(VPORT_PTY, guestCid);
   Os.connect(mSocketFd, address);
   ```
   - `android.system.SocketAddressVmSockets` is available in standard AOSP platform APIs (`platform_apis: true` in `Android.bp`).
   - Constructor signature: `public SocketAddressVmSockets(int port, int cid)`
     - `port`: `5001` (`VPORT_PTY`)
     - `cid`: `guestCid` (typically `3` for AVF Guest VM).

3. **Fallback / Reflection for Host JVM Unit Test Environments**:
   For JUnit tests running on desktop JVM where `android.system.SocketAddressVmSockets` might not be in standard desktop `android.jar`:
   ```java
   try {
       SocketAddress address = new SocketAddressVmSockets(VPORT_PTY, guestCid);
       Os.connect(mSocketFd, address);
   } catch (NoClassDefFoundError | NoSuchMethodError e) {
       // Reflection fallback if SocketAddressVmSockets is resolved dynamically
       Class<?> clazz = Class.forName("android.system.SocketAddressVmSockets");
       java.lang.reflect.Constructor<?> ctor = clazz.getConstructor(int.class, int.class);
       SocketAddress addr = (SocketAddress) ctor.newInstance(VPORT_PTY, guestCid);
       Os.connect(mSocketFd, addr);
   }
   ```

### 3.2 Guest CID Resolution & Port 5001 Setup
- **Guest CID**: In Android Virtualization Framework (AVF / crosvm), Host CID is `2` (`VMADDR_CID_HOST`), and the Debian Guest VM is assigned CID `3` (`GUEST_CID = 3`).
- **Port**: Vsock Port `5001` is reserved for PTY terminal framing (`VSOCK_PORT_5001 = 5001`).
- **Data Flow**:
  1. `LinuxTerminal` (`TerminalView`) queries `ILinuxManager.createTerminalSession(cols, rows, callback)` AIDL.
  2. `LinuxManagerService` returns a 16-byte raw byte array / 32-character hex string representing the dynamic session token.
  3. `TerminalView` passes `guestCid=3` and the dynamic 16-byte `sessionId` to `VsockTerminalClient.connect(3, sessionId, listener)`.
  4. `VsockTerminalClient` executes `Os.socket(AF_VSOCK, SOCK_STREAM, 0)` followed by `Os.connect(fd, new SocketAddressVmSockets(5001, 3))`.

### 3.3 Dynamic 16-Byte Session ID Token Architecture
To fix the session ID mismatch:
1. In `LinuxManagerService.java`:
   - Replace `"session_" + (++mNextSessionId)` with a 16-byte cryptographically secure token generator:
     ```java
     byte[] token = new byte[16];
     new java.security.SecureRandom().nextBytes(token);
     String sessionIdHex = bytesToHex(token); // 32 hex chars
     ```
   - Provide helper method `getSessionIdBytes(String hexString)` to convert 32-char hex string into 16 raw bytes.
2. In `TerminalView.java`:
   - Replace static `"0123456789abcdef"` initialization with dynamic AIDL acquisition when VM service connects.

### 3.4 Exception Handling & Socket Closing Lifecycle
- **Exception Handling**:
  - `Os.socket(...)` and `Os.connect(...)` throw `android.system.ErrnoException`.
  - `VsockTerminalClient.connect(...)` catches `ErrnoException` and re-throws `IOException` with detailed diagnostic context:
    ```java
    } catch (ErrnoException e) {
        close();
        throw new IOException("Failed to connect AF_VSOCK socket (CID: " + guestCid + ", Port: " + VPORT_PTY + "): errno " + e.errno, e);
    }
    ```
- **Stream & Descriptor Teardown**:
  - `close()` must gracefully terminate background reader thread, close input/output streams, and close socket file descriptor:
    ```java
    public synchronized void close() {
        mRunning = false;
        if (mReadThread != null) {
            mReadThread.interrupt();
        }
        try {
            if (mInputStream != null) mInputStream.close();
            if (mOutputStream != null) mOutputStream.close();
            if (mSocketFd != null && mSocketFd.valid()) {
                Os.close(mSocketFd);
            }
        } catch (Exception ignored) {}
    }
    ```

---

## 4. Proposed Code Snippets (For Implementer Agent)

### 4.1 Proposed `VsockTerminalClient.java` Changes
```java
package com.android.virtualization.terminal.net;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.SocketAddressVmSockets;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketAddress;

public class VsockTerminalClient {
    private static final String TAG = "VsockTerminalClient";
    private static final int AF_VSOCK = 40;
    private static final int VPORT_PTY = 5001;

    private FileDescriptor mSocketFd;
    private java.io.InputStream mInputStream;
    private java.io.OutputStream mOutputStream;
    private Thread mReadThread;
    private volatile boolean mRunning = false;

    public interface TerminalStreamListener {
        void onDataReceived(byte[] data);
        void onError(Exception e);
    }

    public synchronized void connect(int guestCid, byte[] sessionId, TerminalStreamListener listener) throws IOException {
        if (sessionId == null || sessionId.length != 16) {
            throw new IllegalArgumentException("Session ID must be exactly 16 bytes for VsockPtyFramer");
        }

        try {
            // 1. Allocate raw AF_VSOCK stream socket descriptor
            mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);

            // 2. Invoke real AF_VSOCK connect syscall targeting guestCid and VPORT_PTY (5001)
            SocketAddress address = new SocketAddressVmSockets(VPORT_PTY, guestCid);
            Os.connect(mSocketFd, address);

            // 3. Wrap connected socket file descriptor in FileInputStream/FileOutputStream
            mInputStream = new FileInputStream(mSocketFd);
            mOutputStream = new FileOutputStream(mSocketFd);
            mRunning = true;

            VsockPtyFramer.StreamParser parser = new VsockPtyFramer.StreamParser();

            mReadThread = new Thread(() -> {
                byte[] buffer = new byte[8192];
                while (mRunning) {
                    try {
                        int n = mInputStream.read(buffer);
                        if (n < 0) break;
                        parser.appendAndParse(buffer, 0, n, sessionId, new VsockPtyFramer.OnFrameParsedListener() {
                            @Override
                            public void onFrameParsed(VsockPtyFramer.Frame frame) {
                                if (frame.type == VsockPtyFramer.PacketType.DATA && listener != null) {
                                    listener.onDataReceived(frame.payload);
                                }
                            }

                            @Override
                            public void onError(Exception e) {
                                if (listener != null) listener.onError(e);
                            }
                        });
                    } catch (Exception e) {
                        if (mRunning && listener != null) listener.onError(e);
                        break;
                    }
                }
            }, "VsockReadThread");
            mReadThread.start();
            Log.i(TAG, "Successfully connected AF_VSOCK socket to CID " + guestCid + ":" + VPORT_PTY);
        } catch (ErrnoException e) {
            close();
            throw new IOException("Failed to connect AF_VSOCK socket to CID " + guestCid + ":" + VPORT_PTY + " (errno: " + e.errno + ")", e);
        }
    }
```

### 4.2 Proposed `LinuxManagerService.java` Changes
```java
        @Override
        public String createTerminalSession(int width, int height, ILinuxTerminalCallback callback) {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to create terminal session");
            }
            synchronized (mStateLock) {
                // Generate 16-byte token (32 hex characters)
                byte[] tokenBytes = new byte[16];
                new java.security.SecureRandom().nextBytes(tokenBytes);
                StringBuilder sb = new StringBuilder();
                for (byte b : tokenBytes) {
                    sb.append(String.format("%02x", b));
                }
                String sessionId = sb.toString();

                TerminalSession session = new TerminalSession(sessionId, width, height, callback);
                mTerminalSessions.put(sessionId, session);
                Slog.i(TAG, "Created terminal session (16-byte token): " + sessionId + " (" + width + "x" + height + ")");
                if (mBridgeService != null) {
                    mBridgeService.openPtyChannel(sessionId, width, height);
                }
                return sessionId;
            }
        }
```

---

## 5. Verification & Testing Strategy

### 5.1 Compilation Check
To verify that `VsockTerminalClient.java` builds cleanly against Android platform SDK:
```bash
javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src \
  -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') \
  tests/unit/TerminalAppUnitTest.java
```

### 5.2 Unit Test Execution
Execute the M3 unit test suite:
```bash
java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
```
Expected output:
```
=== Starting M3 TerminalApp Unit Test Suite ===
[TEST] F-R3-007: VsockPtyFramer (Serialization, RESIZE, StreamParser)... PASS
[TEST] F-R3-007: VsockTerminalClient Real Socket Transmission... PASS
JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY
```

### 5.3 E2E Test Execution
Execute the Tier 1 E2E test runner for Milestone M3:
```bash
python3 tests/e2e/runner.py
```
Or directly via pytest:
```bash
pytest tests/e2e/tier1_feature_coverage/test_m3_tier1.py
```

---

## 6. Conclusion

All technical requirements for defect R3 (Real Vsock Socket Connect & Session ID) have been completely mapped. The implementation strategy replaces unconnected socket creation in `VsockTerminalClient.java` with genuine `Os.connect(mSocketFd, new SocketAddressVmSockets(5001, guestCid))` calls, aligns `LinuxManagerService.java` session IDs with 16-byte tokens, and updates `TerminalView.java` to dynamically fetch session IDs.
