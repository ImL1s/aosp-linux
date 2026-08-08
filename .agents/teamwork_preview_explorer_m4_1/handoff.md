# Handoff Report — Explorer 1 (Milestone M4: Real Wayland dma-buf & SurfaceControl Binding - R4)

**Target Scope Focus**: `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_1`  
**Date**: 2026-08-08  

---

## 1. Observation

### 1.1 `LinuxWindowBridgeService.java` Analysis
- **File Path**: `/Users/iml1s/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`
- **Lines 56–80 (`WaylandSurface` Data Structure)**:
  ```java
  public static class WaylandSurface {
      public final int surfaceId;
      public final String appId;
      public String title;
      public String iconPath;
      public int width;
      public int height;
      public int taskId;
      public long lastCommitNs;
      public int committedFrames;
      public SurfaceControl surfaceControl;
      public HardwareBuffer currentBuffer;
      ...
  }
  ```
  *Observation*: `WaylandSurface` has fields `surfaceControl` and `currentBuffer`, but they are never initialized or bound when `createSurface(...)` is called (they default to `null`).

- **Lines 138–155 (`commitFrame` Method)**:
  ```java
  public synchronized boolean commitFrame(int surfaceId) {
      WaylandSurface surface = mSurfaces.get(surfaceId);
      if (surface == null) {
          Slog.w(TAG, "commitFrame: Unknown surfaceId " + surfaceId);
          return false;
      }

      long nowNs = System.nanoTime();
      if (nowNs - surface.lastCommitNs < FRAME_PACING_MIN_INTERVAL_NS) {
          Slog.d(TAG, "commitFrame: Frame dropped due to frame pacing rate limiting on surface " + surfaceId);
          return false;
      }

      surface.lastCommitNs = nowNs;
      surface.committedFrames++;
      Slog.d(TAG, "Frame committed for surface " + surfaceId + " (total frames: " + surface.committedFrames + ")");
      return true;
  }
  ```
  *Verbatim Observation*: `commitFrame(int surfaceId)` ONLY checks rate pacing (`16ms` minimum interval) and increments `committedFrames`. It **does NOT accept a `HardwareBuffer` object**, does **NOT update `surface.currentBuffer`**, and **never executes a `SurfaceControl.Transaction`** (`setBuffer`, `setVisibility`, `apply()`).

- **Lines 157–176 (`destroySurface` Method)**:
  ```java
  public synchronized boolean destroySurface(int surfaceId) {
      WaylandSurface surface = mSurfaces.remove(surfaceId);
      if (surface != null) {
          ...
          if (surface.currentBuffer != null) {
              surface.currentBuffer.close();
              surface.currentBuffer = null;
          }
          if (surface.surfaceControl != null) {
              surface.surfaceControl.release();
              surface.surfaceControl = null;
          }
          return true;
      }
      return false;
  }
  ```
  *Observation*: `destroySurface` correctly closes `currentBuffer` and releases `surfaceControl`, but because `surfaceControl` and `currentBuffer` are never set during surface execution, no actual rendering pipeline cleanup occurs.

### 1.2 `LinuxAppProxyActivity.java` Analysis
- **File Path**: `/Users/iml1s/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
- **Lines 100–103 & 217–236**:
  ```java
  mSurfaceView = new SurfaceView(this);
  mSurfaceView.getHolder().addCallback(this);
  setContentView(mSurfaceView);
  ...
  @Override
  public void surfaceCreated(SurfaceHolder holder) {
      Log.i(TAG, "Surface created for LinuxAppProxyActivity surfaceId: " + mSurfaceId);
      updateWindowDimensions();
  }
  ```
  *Verbatim Observation*: `LinuxAppProxyActivity` instantiates a `SurfaceView` and listens to `SurfaceHolder.Callback`, but it **never invokes any bridge API or IPC** to pass `mSurfaceView.getSurfaceControl()` (or `holder.getSurface()`) to `LinuxWindowBridgeService`. This leaves `surface.surfaceControl` in `LinuxWindowBridgeService` perpetually `null`.

### 1.3 Native Buffer Sharing Layer Analysis (`wayland_buffer_sharing.cpp`)
- **File Path**: `/Users/iml1s/aosp-linux/system/linux_bridge/wayland_buffer_sharing.cpp`
- **Lines 77–82**:
  ```cpp
  bool WaylandBufferSharingManager::bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr) {
      if (!surfaceControlPtr || !hardwareBufferPtr) {
          return false;
      }
      return true;
  }
  ```
  *Observation*: The native method `bindHardwareBufferToSurfaceControl` is currently a placeholder stub returning `true` without executing `ASurfaceTransaction_setBuffer` or SurfaceControl JNI calls.

---

## 2. Logic Chain

1. **Observation 1.1** (`LinuxWindowBridgeService.java:138`) demonstrates that `commitFrame(int surfaceId)` lacks a buffer parameter and omits `SurfaceControl.Transaction` execution.
2. **Observation 1.2** (`LinuxAppProxyActivity.java:217`) demonstrates that `LinuxAppProxyActivity` does not transmit its `SurfaceControl` handle to `LinuxWindowBridgeService`.
3. **Reasoning Step**:
   - Wayland GUI app frames generated in the Linux Guest (e.g. Firefox, GIMP, VLC) pass via virtio-gpu dma-buf to the host.
   - Even if native dma-buf file descriptors are exported via `wayland_buffer_sharing.cpp`, `LinuxWindowBridgeService` cannot present these frames to Android Task windows because (a) `surface.surfaceControl` is never populated by `LinuxAppProxyActivity`, and (b) `commitFrame()` never attaches incoming `HardwareBuffer`s to `SurfaceControl` or applies transactions.
4. **Conclusion**:
   - Defect R4 occurs because the Wayland GUI zero-copy presentation pipeline is disconnected at `LinuxWindowBridgeService`.
   - Re-connecting the pipeline requires:
     1. Adding a `SurfaceControl` attachment mechanism (`attachSurfaceControl(int surfaceId, SurfaceControl sc)`).
     2. Enhancing `commitFrame` to accept a `HardwareBuffer`, replace `surface.currentBuffer`, and execute `SurfaceControl.Transaction` (`setBuffer`, `setVisibility`, `apply()`).
     3. Updating `LinuxAppProxyActivity` to call `attachSurfaceControl(...)` during `surfaceCreated()`.

---

## 3. Caveats

- **Native NDK vs Java SurfaceControl.Transaction**: While NDK provides `ASurfaceTransaction_setBuffer` in native code (`wayland_buffer_sharing.cpp`), using Java `SurfaceControl.Transaction` directly inside `LinuxWindowBridgeService.java` is cleaner, safer, and avoids custom JNI bindings for `SurfaceControl` Java objects.
- **HardwareBuffer Import Source**: In production, `HardwareBuffer` objects can be passed via AIDL/Binder or constructed via NDK `AHardwareBuffer_import` from the virtio-gpu dma-buf file descriptor. In both cases, Java `HardwareBuffer` management rules in `LinuxWindowBridgeService.java` remain identical.
- **No caveats regarding file locations or existing code structure**: All related framework services, activities, native bridge headers, and unit/stress test suites were completely inspected.

---

## 4. Conclusion & Actionable Implementation Strategy

### 4.1 Required Method Additions & Modifications in `LinuxWindowBridgeService.java`

#### 1. Add SurfaceControl Attachment Method
Add `attachSurfaceControl(int surfaceId, SurfaceControl surfaceControl)` to bind `LinuxAppProxyActivity`'s window surface control:
```java
public synchronized boolean attachSurfaceControl(int surfaceId, SurfaceControl surfaceControl) {
    WaylandSurface surface = mSurfaces.get(surfaceId);
    if (surface == null) {
        Slog.w(TAG, "attachSurfaceControl: Unknown surfaceId " + surfaceId);
        return false;
    }
    if (surface.surfaceControl != null && surface.surfaceControl != surfaceControl) {
        surface.surfaceControl.release();
    }
    surface.surfaceControl = surfaceControl;
    Slog.i(TAG, "Attached SurfaceControl to Wayland surface " + surfaceId);

    // If a buffer is already available, present it immediately
    if (surface.currentBuffer != null && surfaceControl != null && surfaceControl.isValid()) {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setBuffer(surfaceControl, surface.currentBuffer);
            transaction.setVisibility(surfaceControl, true);
            transaction.apply();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to apply initial SurfaceControl transaction: " + e.getMessage());
        }
    }
    return true;
}
```

#### 2. Upgrade `commitFrame` for Real Buffer Binding & Transaction Commit
Overload `commitFrame` to accept `HardwareBuffer buffer`:
```java
public synchronized boolean commitFrame(int surfaceId, HardwareBuffer buffer) {
    WaylandSurface surface = mSurfaces.get(surfaceId);
    if (surface == null) {
        Slog.w(TAG, "commitFrame: Unknown surfaceId " + surfaceId);
        return false;
    }
    if (buffer == null || buffer.isClosed()) {
        Slog.w(TAG, "commitFrame: Invalid or closed HardwareBuffer for surfaceId " + surfaceId);
        return false;
    }

    long nowNs = System.nanoTime();
    if (nowNs - surface.lastCommitNs < FRAME_PACING_MIN_INTERVAL_NS) {
        Slog.d(TAG, "commitFrame: Frame dropped due to frame pacing rate limiting on surface " + surfaceId);
        return false;
    }

    surface.lastCommitNs = nowNs;
    surface.committedFrames++;

    // Close previous buffer to prevent graphic memory leaks
    if (surface.currentBuffer != null && surface.currentBuffer != buffer) {
        surface.currentBuffer.close();
    }
    surface.currentBuffer = buffer;

    // Apply SurfaceControl Transaction
    if (surface.surfaceControl != null && surface.surfaceControl.isValid()) {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setBuffer(surface.surfaceControl, surface.currentBuffer);
            transaction.setVisibility(surface.surfaceControl, true);
            transaction.apply();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to apply SurfaceControl transaction for surface " + surfaceId + ": " + e.getMessage());
        }
    }

    Slog.d(TAG, "Frame committed for surface " + surfaceId + " (total frames: " + surface.committedFrames + ")");
    return true;
}

// Preserve existing commitFrame(int surfaceId) for backward compatibility
public synchronized boolean commitFrame(int surfaceId) {
    WaylandSurface surface = mSurfaces.get(surfaceId);
    if (surface == null) return false;

    long nowNs = System.nanoTime();
    if (nowNs - surface.lastCommitNs < FRAME_PACING_MIN_INTERVAL_NS) {
        return false;
    }

    surface.lastCommitNs = nowNs;
    surface.committedFrames++;

    if (surface.currentBuffer != null && surface.surfaceControl != null && surface.surfaceControl.isValid()) {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setBuffer(surface.surfaceControl, surface.currentBuffer);
            transaction.setVisibility(surface.surfaceControl, true);
            transaction.apply();
        }
    }
    return true;
}
```

### 4.2 Memory & File Descriptor Lifecycle Management Rules
1. **Buffer Handover**: `commitFrame(surfaceId, buffer)` assumes ownership of `buffer`. The prior `surface.currentBuffer` must be closed (`surface.currentBuffer.close()`) before overwriting to prevent native graphic buffer handle leaks.
2. **Surface Destruction Cleanup**: When `destroySurface(surfaceId)` or `flushTasks()` is invoked:
   - Call `surface.currentBuffer.close()` and set `surface.currentBuffer = null`.
   - Call `surface.surfaceControl.release()` and set `surface.surfaceControl = null`.
3. **Transaction Scoping**: Always instantiate `SurfaceControl.Transaction` within try-with-resources blocks so native transaction allocations are closed cleanly.

### 4.3 Error Handling & Edge Cases
- **Invalid Buffer**: Reject `null` or closed `HardwareBuffer` without updating surface state.
- **Rate Limiting (60 FPS / 16ms)**: Sub-16ms frame requests return `false` without discarding `surface.currentBuffer`.
- **Invalid SurfaceControl**: If `surfaceControl == null || !surfaceControl.isValid()`, cache `currentBuffer` so that when `attachSurfaceControl` is called later, the latest frame will display immediately.
- **VM Shutdown**: `onVmStateChanged(false)` flushes all surface entries and closes all active buffers and SurfaceControl objects.

---

## 5. Verification Method

### 5.1 Verification Commands
Run unit, stress, and E2E test suites to verify implementation:

1. **Unit Test Suite**:
   ```bash
   javac tests/unit/LinuxWindowBridgeServiceTest.java
   java tests.unit.LinuxWindowBridgeServiceTest
   ```
2. **Stress & Adversarial Test Suite**:
   ```bash
   javac tests/stress/AdversarialLinuxWindowBridgeServiceTest.java
   java tests.stress.AdversarialLinuxWindowBridgeServiceTest
   ```
3. **E2E Tier 1 Functional Test Suite**:
   ```bash
   pytest tests/e2e/tier1_feature_coverage/test_m4_tier1.py
   ```
4. **E2E Tier 2 Boundary Test Suite**:
   ```bash
   pytest tests/e2e/tier2_boundary_corner/test_m4_tier2.py
   ```

### 5.2 Code Inspection Checklist for Verification
- Inspect `/Users/iml1s/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`:
  - Verify `attachSurfaceControl` method exists and checks `surfaceControl.isValid()`.
  - Verify `commitFrame(int surfaceId, HardwareBuffer buffer)` closes old buffer and applies `SurfaceControl.Transaction`.
  - Verify `destroySurface` and `flushTasks` release both `HardwareBuffer` and `SurfaceControl`.
- Inspect `/Users/iml1s/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`:
  - Verify `surfaceCreated(SurfaceHolder holder)` passes `mSurfaceView.getSurfaceControl()` to `LinuxWindowBridgeService.attachSurfaceControl`.
