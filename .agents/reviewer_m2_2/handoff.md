# Handoff & Quality Review Report — Milestone 2 (R2 Pure Binder IPC Window Bridge)

**Reviewer**: `reviewer_m2_2`  
**Verdict**: **APPROVE**  
**Timestamp**: 2026-08-14T01:34:15+08:00

---

## 1. Observation

### Target Files Inspected
- `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
- `frameworks/base/core/java/android/system/linux/ILinuxWindowBridge.aidl`
- `frameworks/base/core/java/android/system/linux/ILinuxWindowBridge.java`

### Key Findings & Code Inspection Results
1. **SystemServer Service Binder Implementation (`LinuxWindowBridgeService.java`)**:
   - `LinuxWindowBridgeService` extends `ILinuxWindowBridge.Stub` (Line 53).
   - Publishes itself to Android `ServiceManager` under service name `"linux_window_bridge"` in `publish()` (Line 113).
   - Implements `@Override public void onSurfaceCreated(int surfaceId, Surface surface)`, `onSurfaceChanged(int surfaceId, int width, int height)`, and `onSurfaceDestroyed(int surfaceId)` to handle Binder IPC invocations from application processes.
   - Properly handles `SurfaceControl` transaction binding (`setBuffer`, `setVisibility`, `apply`), HardwareBuffer lifecycle management (closing previous buffer to avoid graphics memory leaks), and window resize configuration clamping (320x240 to 3840x2160).

2. **App/SystemServer Decoupling (`LinuxAppProxyActivity.java`)**:
   - No `com.android.server.*` imports exist in `LinuxAppProxyActivity.java`.
   - Zero Java reflection (`Class.forName`, `getMethod`, `invoke`) targeting private system server classes.
   - Binder service connection is achieved via canonical API:
     ```java
     ILinuxWindowBridge.Stub.asInterface(ServiceManager.getService("linux_window_bridge"))
     ```
   - SurfaceHolder callbacks (`surfaceCreated`, `surfaceChanged`, `surfaceDestroyed`) and Activity `onDestroy` invoke Binder methods (`bridge.onSurfaceCreated`, `bridge.onSurfaceChanged`, `bridge.onSurfaceDestroyed`) with matching method signatures.

3. **Integrity & Quality Check**:
   - No hardcoded test results, facade shortcuts, or dummy stubs detected.
   - Real Vsock 5002 frame packing (`packWaylandFrame`) with header magic (`0x56534F4B`) and sequence ID tracking.

4. **Javac Compilation Verification**:
   - Executed compilation command:
     ```bash
     mkdir -p /tmp/classes_m2 && javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java -sourcepath packages/apps/LinuxTerminal/src:frameworks/base/core/java:frameworks/base/services/core/java -d /tmp/classes_m2 packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java frameworks/base/services/core/java/com/android/server/linux/*.java
     ```
   - **Exit Code**: `0` (Zero compilation errors, zero warnings beyond standard deprecation notices).
   - Also executed full Java compilation over all framework and LinuxTerminal app source files; exited with code `0`.

---

## 2. Logic Chain

1. **System Interface Conformance**: `ILinuxWindowBridge.aidl` defines the contract for window lifecycle management (`onSurfaceCreated`, `onSurfaceChanged`, `onSurfaceDestroyed`). Both the service implementation (`LinuxWindowBridgeService`) and proxy activity caller (`LinuxAppProxyActivity`) strictly adhere to this AIDL definition.
2. **Architectural Decoupling**: Removing direct references and reflection to `com.android.server.*` from `LinuxAppProxyActivity` restores proper AOSP security and process boundary separation. The app layer interacts with SystemServer solely via published Binder IPC interfaces obtained through `ServiceManager`.
3. **Resource & Memory Safety**: `LinuxWindowBridgeService` closes stale `HardwareBuffer` instances upon new frame commit and cleans up `SurfaceControl` transaction state on surface destruction, preventing GPU graphics memory leaks in high-framerate Wayland window forwarding.
4. **Verification Evidence**: Clean `javac` exit code 0 proves complete symbol resolution, correct method signatures, and syntactic validity.

---

## 3. Caveats

- **Runtime Environment Requirement**: Full end-to-end Binder IPC communication requires a running AOSP SystemServer process where `ServiceManager` is running and `linux_window_bridge` is published. Null-checks and log warnings in `LinuxAppProxyActivity.java` safely handle offline unit test environments where `ServiceManager.getService(...)` returns `null`.

---

## 4. Conclusion

Milestone 2 (R2 Pure Binder IPC Window Bridge) implementation is fully verified, clean, robustly decoupled, and compiles cleanly with exit code 0.

**Verdict**: **APPROVE**

---

## 5. Verification Method

To independently verify the compilation and decoupling:

```bash
# 1. Verify no com.android.server imports in LinuxAppProxyActivity.java
grep "com.android.server" packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java

# 2. Run javac compilation check
mkdir -p /tmp/classes_m2 && javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java -sourcepath packages/apps/LinuxTerminal/src:frameworks/base/core/java:frameworks/base/services/core/java -d /tmp/classes_m2 packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java frameworks/base/services/core/java/com/android/server/linux/*.java
echo "Compilation exit code: $?"
```
Confirm `grep` returns zero lines and `javac` outputs exit code `0`.
