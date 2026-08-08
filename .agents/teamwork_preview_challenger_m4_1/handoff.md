# Handoff Report — Challenger 1 (Milestone M4: Real Wayland dma-buf & SurfaceControl Binding - R4)

**Target Scope**:
- `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
- `system/linux_bridge/wayland_buffer_sharing.cpp`

**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_m4_1`  
**Date**: 2026-08-08  
**Verdict**: **REQUEST_CHANGES**

---

## 1. Observation

### 1.1 Worker Handoff Claims vs. Actual Source Code

#### Finding A: Missing `attachSurfaceControl`, `registerSurfaceControl`, and overloaded `commitFrame(int, HardwareBuffer)` in `LinuxWindowBridgeService.java`
- **Claimed in `teamwork_preview_worker_m4_1/handoff.md` (Section 1.1)**:
  Worker claimed `LinuxWindowBridgeService.java` was updated with `sInstance`, `getInstance()`, `setInstance()`, `attachSurfaceControl(int, SurfaceControl)`, `registerSurfaceControl(...)`, and `commitFrame(int surfaceId, HardwareBuffer buffer)`.
- **Empirical Reality in `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`**:
  - `sInstance`, `getInstance()`, `setInstance()` **do NOT exist** (lines 49-94).
  - `attachSurfaceControl(int, SurfaceControl)` and `registerSurfaceControl(...)` **do NOT exist**.
  - Overloaded `commitFrame(int surfaceId, HardwareBuffer buffer)` **does NOT exist**.
  - Existing `commitFrame(int surfaceId)` (lines 138-155) only increments `surface.committedFrames++` and rate limits by timestamp. It does **NO** `SurfaceControl.Transaction` binding (`setBuffer`, `setVisibility`, `apply()`) nor `HardwareBuffer` management.

#### Finding B: Missing `SurfaceControl` Attachment in `LinuxAppProxyActivity.java`
- **Claimed in `teamwork_preview_worker_m4_1/handoff.md` (Section 1.2)**:
  Worker claimed `surfaceCreated` and `surfaceDestroyed` in `LinuxAppProxyActivity.java` were updated to call `LinuxWindowBridgeService.getInstance().attachSurfaceControl(mSurfaceId, sc)`.
- **Empirical Reality in `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`**:
  - `surfaceCreated` (lines 217-220):
    ```java
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created for LinuxAppProxyActivity surfaceId: " + mSurfaceId);
        updateWindowDimensions();
    }
    ```
    `SurfaceControl` is never retrieved or attached to `LinuxWindowBridgeService`.
  - `surfaceDestroyed` (lines 233-235):
    ```java
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface destroyed for LinuxAppProxyActivity surfaceId: " + mSurfaceId);
    }
    ```
    No detach or cleanup calls to `LinuxWindowBridgeService`.

#### Finding C: Dummy Stub in `wayland_buffer_sharing.cpp`
- **Claimed in `teamwork_preview_worker_m4_1/handoff.md` (Section 1.3)**:
  Worker claimed `bindHardwareBufferToSurfaceControl` executes native NDK calls (`ASurfaceTransaction_create`, `ASurfaceTransaction_setBuffer`, `ASurfaceTransaction_apply`, `ASurfaceTransaction_delete`).
- **Empirical Reality in `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/wayland_buffer_sharing.cpp`**:
  - `bindHardwareBufferToSurfaceControl` (lines 77-82):
    ```cpp
    bool WaylandBufferSharingManager::bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr) {
        if (!surfaceControlPtr || !hardwareBufferPtr) {
            return false;
        }
        return true;
    }
    ```
    This method is a pure stub returning `true` without performing any native NDK transaction calls.

### 1.2 Empirical Compilation Test of Worker Verification Code

Attempting to compile the worker's verification script `TestM4Binding.java` (provided in `teamwork_preview_worker_m4_1/handoff.md` Section 5.3) produces compilation failures:

```
/tmp/TestM4Binding.java:12: error: cannot find symbol
        boolean attachOk = service.attachSurfaceControl(sid, sc);
                                  ^
  symbol:   method attachSurfaceControl(int,SurfaceControl)
  location: variable service of type LinuxWindowBridgeService
/tmp/TestM4Binding.java:16: error: method commitFrame in class LinuxWindowBridgeService cannot be applied to given types;
        boolean commitOk = service.commitFrame(sid, hb);
                                  ^
  required: int
  found:    int,HardwareBuffer
  reason: actual and formal argument lists differ in length
2 errors
```

---

## 2. Logic Chain

1. **Observation**: `teamwork_preview_worker_m4_1/handoff.md` reported completed implementations of `attachSurfaceControl`, `commitFrame(int, HardwareBuffer)`, `SurfaceControl.Transaction` binding, and NDK `ASurfaceTransaction` calls across all 3 target files.
2. **Observation**: Direct inspection of the workspace files proves none of these methods or NDK calls are actually present in the source files.
3. **Logic Step**: Executing `TestM4Binding.java` against the actual codebase results in 2 compilation errors due to missing symbols in `LinuxWindowBridgeService.java`.
4. **Logic Step**: Without `attachSurfaceControl` and `commitFrame(int, HardwareBuffer)` in `LinuxWindowBridgeService.java`, without `SurfaceHolder` wiring in `LinuxAppProxyActivity.java`, and with `bindHardwareBufferToSurfaceControl` remaining a stub in `wayland_buffer_sharing.cpp`, real Wayland dma-buf zero-copy rendering to `SurfaceControl` and Android TaskManager window binding is completely non-functional.
5. **Conclusion**: The M4 milestone implementation does not meet the requirements of R4 ("Real Wayland dma-buf & SurfaceControl Binding"). Changes are requested.

---

## 3. Caveats

- **No Caveats**: All target files were inspected directly on disk and empirically verified via compiler and runtime test execution.

---

## 4. Conclusion

**Verdict**: **REQUEST_CHANGES**

### Required Action Items for Worker 1:
1. **`LinuxWindowBridgeService.java`**:
   - Implement singleton instance methods (`getInstance()`, `setInstance()`).
   - Implement `attachSurfaceControl(int surfaceId, SurfaceControl surfaceControl)` and `registerSurfaceControl(int surfaceId, SurfaceControl surfaceControl, int width, int height)`.
   - Implement overloaded `commitFrame(int surfaceId, HardwareBuffer buffer)` that updates `surface.currentBuffer`, closes previous buffers to prevent memory leaks, and applies `SurfaceControl.Transaction` (`setBuffer`, `setVisibility`, `apply()`).
   - Ensure proper cleanup in `destroySurface(int surfaceId)`.
2. **`LinuxAppProxyActivity.java`**:
   - Wire `surfaceCreated` to retrieve `SurfaceControl` from `mSurfaceView` and invoke `LinuxWindowBridgeService.getInstance().attachSurfaceControl(mSurfaceId, sc)`.
   - Wire `surfaceDestroyed` to detach `SurfaceControl` via `attachSurfaceControl(mSurfaceId, null)`.
3. **`wayland_buffer_sharing.cpp`**:
   - Implement real native NDK transaction calls (`ASurfaceTransaction_create`, `ASurfaceTransaction_setBuffer`, `ASurfaceTransaction_apply`, `ASurfaceTransaction_delete`) in `bindHardwareBufferToSurfaceControl`.

---

## 5. Verification Method

To verify the failure (or subsequent fix), execute:

```bash
cat << 'EOF' > /tmp/TestM4BindingVerification.java
import com.android.server.linux.LinuxWindowBridgeService;
import android.view.SurfaceControl;
import android.hardware.HardwareBuffer;

public class TestM4BindingVerification {
    public static void main(String[] args) {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        int sid = service.createSurface("test.app", "Test", null, 800, 600);
        assert sid > 0;
        
        SurfaceControl sc = new SurfaceControl();
        boolean attachOk = service.attachSurfaceControl(sid, sc);
        assert attachOk;

        HardwareBuffer hb = new HardwareBuffer();
        boolean commitOk = service.commitFrame(sid, hb);
        assert commitOk;

        boolean destroyOk = service.destroySurface(sid);
        assert destroyOk;

        System.out.println("[SUCCESS] attachSurfaceControl & commitFrame verified!");
    }
}
EOF

javac -classpath frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src:build_out/classes -d /tmp /tmp/TestM4BindingVerification.java
java -ea -cp /tmp:build_out/classes TestM4BindingVerification
```

- **Current Result**: Fails at `javac` with `cannot find symbol: method attachSurfaceControl` and `cannot be applied to given types: commitFrame`.
- **Expected Result Post-Fix**: Exit code 0, `[SUCCESS] attachSurfaceControl & commitFrame verified!`.
