# Survey Report & Handoff — Explorer 2 (Defects R3 & R4)

**Target Scope**:
- **R3**: Real Vsock Socket Connect & Session ID
- **R4**: Real Wayland dma-buf & SurfaceControl Binding

---

## 1. Observation

### R3 Observations (Real Vsock Socket Connect & Session ID)

1. **`VsockTerminalClient.java`**
   - **Path**: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
   - **Lines 31-37**:
     ```java
     public synchronized void connect(int guestCid, byte[] sessionId, TerminalStreamListener listener) throws IOException {
         try {
             mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
             mInputStream = new FileInputStream(mSocketFd);
             mOutputStream = new FileOutputStream(mSocketFd);
             mRunning = true;
     ```
   - **Verbatim Observation**: `Os.socket(AF_VSOCK, ...)` creates an unconnected file descriptor `mSocketFd`, but `Os.connect(...)` (or native AF_VSOCK syscall `connect(fd, sockaddr_vm)`) is **never called**! `mInputStream` and `mOutputStream` wrap an unconnected socket descriptor.

2. **`TerminalView.java`**
   - **Path**: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
   - **Line 49**:
     ```java
     private byte[] mSessionId = "0123456789abcdef".getBytes();
     ```
   - **Line 82**:
     ```java
     @Override
     protected void onAttachedToWindow() {
         super.onAttachedToWindow();
         connectVsock(GUEST_CID, mSessionId);
     }
     ```
   - **Verbatim Observation**: The session ID is hardcoded to the 16-byte byte string `"0123456789abcdef"`. The view attaches to the window and connects using this hardcoded ID without requesting a dynamic session ID from `LinuxManagerService`.

3. **`LinuxManagerService.java`**
   - **Path**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
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
   - **Verbatim Observation**: `LinuxManagerService` generates session ID strings formatted as `"session_1001"` (12 bytes). However, `VsockPtyFramer` (`VsockPtyFramer.java:HEADER_SIZE` / `SESSION_ID_SIZE`) requires exact 16-byte session ID tokens. Additionally, `TerminalView` does not invoke `ILinuxManager.createTerminalSession(...)` or acquire dynamic session IDs before initiating PTY streaming over Vsock Port 5001.

---

### R4 Observations (Real Wayland dma-buf & SurfaceControl Binding)

1. **`LinuxWindowBridgeService.java`**
   - **Path**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`
   - **Lines 56-80**: `WaylandSurface` has fields `public SurfaceControl surfaceControl;` and `public HardwareBuffer currentBuffer;`.
   - **Lines 138-155**:
     ```java
     public synchronized boolean commitFrame(int surfaceId) {
         WaylandSurface surface = mSurfaces.get(surfaceId);
         if (surface == null) return false;

         long nowNs = System.nanoTime();
         if (nowNs - surface.lastCommitNs < FRAME_PACING_MIN_INTERVAL_NS) {
             return false;
         }

         surface.lastCommitNs = nowNs;
         surface.committedFrames++;
         return true;
     }
     ```
   - **Verbatim Observation**: `commitFrame` only checks frame rate pacing. It does **NOT import `HardwareBuffer` / dma-buf handles** from incoming Wayland stream packets or native buffer descriptors. Furthermore, it **never creates or applies a `SurfaceControl.Transaction`** (`transaction.setBuffer(surface.surfaceControl, surface.currentBuffer); transaction.apply();`).

2. **`LinuxAppProxyActivity.java`**
   - **Path**: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
   - **Lines 100-102**:
     ```java
     mSurfaceView = new SurfaceView(this);
     mSurfaceView.getHolder().addCallback(this);
     setContentView(mSurfaceView);
     ```
   - **Lines 217-236**: `surfaceCreated`, `surfaceChanged`, `surfaceDestroyed` only write log statements.
   - **Verbatim Observation**: `LinuxAppProxyActivity` creates a `SurfaceView`, but it **never registers or passes** `mSurfaceView.getHolder().getSurface()` or its underlying `SurfaceControl` back to `LinuxWindowBridgeService`. As a result, task management and window surface binding are completely disconnected.

3. **Native Wayland Buffer Sharing Reference**
   - **Path**: `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/wayland_buffer_sharing.cpp`
   - **Lines 77-82**: `bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr)` returns `true` as a stub without performing native `ASurfaceTransaction_setBuffer` or SurfaceControl JNI binding.

---

## 2. Logic Chain

1. **R3 Logic Chain**:
   - **Observation 1** (`VsockTerminalClient.java:33`) proves that `Os.socket(AF_VSOCK, ...)` creates an socket descriptor, but no `connect()` call is executed to bind to `guestCid:5001`.
   - Any attempt to read/write on `mInputStream`/`mOutputStream` operates on an unconnected socket, resulting in `ENOTCONN` failure when executing on real kernel socket descriptors.
   - **Observation 2** (`TerminalView.java:49`) proves that `TerminalView` hardcodes `mSessionId` to `"0123456789abcdef"`.
   - **Observation 3** (`LinuxManagerService.java:392`) shows `createTerminalSession` generates `"session_1001"` (12 bytes ASCII), which disagrees with `VsockPtyFramer`'s requirement of 16-byte binary/hex session IDs.
   - **Conclusion**: R3 is defective because (1) `VsockTerminalClient.connect(...)` omits `Os.connect(...)` / AF_VSOCK sockaddr connect, (2) `TerminalView` uses hardcoded session ID instead of calling `LinuxManagerService`, and (3) `LinuxManagerService` session ID format does not align with the 16-byte framing specification.

2. **R4 Logic Chain**:
   - **Observation 1** (`LinuxWindowBridgeService.java:138`) shows `commitFrame()` increments frame count without importing dma-buf into `HardwareBuffer` or performing `SurfaceControl.Transaction` commits.
   - **Observation 2** (`LinuxAppProxyActivity.java:217`) shows `LinuxAppProxyActivity` creates a `SurfaceView` but does not pass its `SurfaceControl` or `Surface` to `LinuxWindowBridgeService`.
   - **Observation 3** (`wayland_buffer_sharing.cpp:77`) shows the native bridge buffer binding function is a stub returning `true`.
   - **Conclusion**: R4 is defective because Wayland GUI frames cannot render to Android Tasks — dma-buf importing into `HardwareBuffer`, `SurfaceControl.Transaction` applying, and `LinuxAppProxyActivity` window binding are missing.

---

## 3. Caveats

- **No caveats**: All relevant codebase components for R3 and R4 were thoroughly located, read, and verified against the production remediation requirements.

---

## 4. Conclusion & Actionable Recommendations

### Remediation Requirements for R3 (Implementer):
1. **Fix `VsockTerminalClient.java`**:
   - Implement real `AF_VSOCK` socket connect: Use `Os.connect(mSocketFd, sockaddr)` (or JNI helper `/ VsockAddress` reflection) targeting `(AF_VSOCK=40, port=5001, cid=guestCid)`.
2. **Fix `LinuxManagerService.java`**:
   - Update `createTerminalSession(...)` to generate 16-byte hex session IDs (e.g. 32-char hex string converted to 16 bytes or 16-byte random byte array).
3. **Fix `TerminalView.java`**:
   - Query `LinuxManagerService` / `ILinuxManager` AIDL interface to receive dynamic session ID on launch instead of static `"0123456789abcdef"`.

### Remediation Requirements for R4 (Implementer):
1. **Fix `LinuxWindowBridgeService.java`**:
   - Implement real `HardwareBuffer` / dma-buf import in `commitFrame(int surfaceId, HardwareBuffer buffer)` or via native handle import.
   - Implement `SurfaceControl.Transaction` creation and commit:
     ```java
     SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
     transaction.setBuffer(surface.surfaceControl, surface.currentBuffer);
     transaction.setVisibility(surface.surfaceControl, true);
     transaction.apply();
     ```
2. **Fix `LinuxAppProxyActivity.java`**:
   - In `surfaceCreated(SurfaceHolder holder)` and `surfaceChanged(...)`, pass `holder.getSurface()` or `mSurfaceView.getSurfaceControl()` to `LinuxWindowBridgeService` to bind `surface.surfaceControl` with the activity's task window.

---

## 5. Verification Method

To independently verify the identified code locations and defect details:

1. **Verify R3 Files**:
   - Inspect `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java` lines 30–70.
   - Inspect `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` line 49.
   - Inspect `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` lines 390–400.

2. **Verify R4 Files**:
   - Inspect `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java` lines 135–156.
   - Inspect `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java` lines 215–236.

3. **Run Existing Test Suite**:
   - Run unit test scripts under `tests/unit/`:
     - `TerminalAppUnitTest.java`
     - `LinuxWindowBridgeServiceTest.java`
     - `LinuxAppProxyActivityTest.java`
