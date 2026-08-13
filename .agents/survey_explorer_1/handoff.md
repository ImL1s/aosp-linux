# Handoff Report: Java Architecture & Binder IPC Survey

**Agent**: `survey_explorer_1`  
**Milestone**: Java Architecture & Binder Survey  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_1`  
**Date**: 2026-08-14  

---

## 1. Observation

Direct observations from the codebase investigation:

1. **File Locations**:
   - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java` (App package activity)
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxAppProxyActivity.java` (Framework service copy)
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java`
   - `frameworks/base/core/java/android/system/linux/ILinuxWindowBridge.aidl`
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`
   - Total of 9 AIDL files and 39 Java source files located in the repository.

2. **Syntax Error in `LinuxAppProxyActivity.java`**:
   - File: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
   - Lines 264–274:
     ```java
     264: private void attachSurfaceControlToBridge(int surfaceId, SurfaceControl surfaceControl) {
     265:     if (surfaceId <= 0) {
     266:         Log.w(TAG, "Invalid surfaceId: " + surfaceId + ", skipping attachSurfaceControl");
     267:         return;
     268:     }
     269: 
     270: private void attachSurfaceControlToBridge(int surfaceId, SurfaceControl surfaceControl) {
     271:     if (surfaceId <= 0) {
     272:         Log.w(TAG, "Invalid surfaceId: " + surfaceId + ", skipping attachSurfaceControl");
     273:         return;
     274:     }
     ```
   - Missing closing brace `}` after line 268 before line 270 re-declares `attachSurfaceControlToBridge`.

3. **Reflection Usage in `LinuxAppProxyActivity.java`**:
   - File: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
   - Lines 276–290 & 295–306:
     ```java
     Class<?> bridgeClass = Class.forName("com.android.server.linux.LinuxWindowBridgeService");
     java.lang.reflect.Method getInstanceMethod = bridgeClass.getMethod("getInstance");
     Object instance = getInstanceMethod.invoke(null);
     ```
   - Standard app layer invokes SystemServer implementation via reflection. `LinuxWindowBridgeService.java` does NOT implement `ILinuxWindowBridge.Stub`.

4. **Permission Activity Stubbing**:
   - File: `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java`
   - Lines 18–21:
     ```java
     @Override
     protected void onCreate(Bundle savedInstanceState) {
         super.onCreate(savedInstanceState);
         finish();
     }
     ```
   - Activity immediately calls `finish()` without extracting Intent extras `app_id` or `op`, displaying a permission prompt, or updating `LinuxPortalService` / `AppOpsManager`.

5. **AIDL vs Java Implementation Audit**:
   - `ILinuxWindowBridge.aidl` defines `onSurfaceCreated(int surfaceId, in Surface surface)`, `onSurfaceChanged(int surfaceId, int width, int height)`, `onSurfaceDestroyed(int surfaceId)`. Un-implemented by `LinuxWindowBridgeService.java`.
   - `ILinuxPortalService.aidl` defines `getCameraStatus()`, `getAudioStatus()`, `getLocation()`. Un-implemented by `LinuxPortalService.java`.
   - `ILinuxManager.aidl` (14 methods) is fully implemented by `LinuxManagerService.java` (`BinderService extends ILinuxManager.Stub`) and consumed by `LinuxManager.java`.

---

## 2. Logic Chain

1. **Observation 2** shows that `LinuxAppProxyActivity.java` contains an unclosed brace and duplicate method header at lines 264–274. **Therefore**, attempting to compile `LinuxAppProxyActivity.java` with `javac` results in a fatal compiler error.
2. **Observation 3** shows that `LinuxAppProxyActivity.java` bypasses Binder IPC using reflection `Class.forName(...)` to access `LinuxWindowBridgeService`. **Furthermore**, `LinuxWindowBridgeService` is not exposed as a `BinderService` implementing `ILinuxWindowBridge.Stub`. **Therefore**, replacing reflection requires (a) modifying `LinuxWindowBridgeService` to extend `ILinuxWindowBridge.Stub` and publish to `ServiceManager`, and (b) calling `ILinuxWindowBridge` methods in `LinuxAppProxyActivity` during `surfaceCreated`, `surfaceChanged`, and `surfaceDestroyed`.
3. **Observation 4** shows `LinuxPermissionActivity.java` immediately finishes without rendering UI or updating state. **Therefore**, `MODE_PROMPT` permission requests launched from `LinuxPortalService` fail to record user consent and return stale permission status.
4. **Observation 5** shows `ILinuxWindowBridge.aidl` and `ILinuxPortalService.aidl` are disconnected from their respective SystemServer service classes. **Therefore**, full compilation and Binder IPC closure requires updating both services to implement their corresponding AIDL Stubs.

---

## 3. Caveats

- **Read-Only Scope**: In accordance with agent identity rules, no source files outside the `.agents/survey_explorer_1` directory were modified during this investigation.
- **Runtime Native Rendering**: Verification focused on Java/AIDL source code structure. Native C++ Wayland buffer imports (`virtio-gpu` / `dma-buf`) depend on host GL and kernel vsock drivers at runtime.

---

## 4. Conclusion

The Java architecture requires 4 deterministic fixes:
1. Fix the duplicate/unclosed `attachSurfaceControlToBridge` syntax error in `LinuxAppProxyActivity.java`.
2. Replace reflection in `LinuxAppProxyActivity.java` with canonical Binder IPC calls to `ILinuxWindowBridge` (obtained via `ServiceManager.getService("linux_window_bridge")`), and make `LinuxWindowBridgeService` implement `ILinuxWindowBridge.Stub`.
3. Implement functional dialog prompting and `LinuxPortalService.setAppOp(...)` integration in `LinuxPermissionActivity.java`.
4. Align `LinuxPortalService.java` with `ILinuxPortalService.aidl`.

---

## 5. Verification Method

To independently verify these findings:

1. **Syntax & Compilation Verification**:
   Inspect `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java` around line 264–274 to confirm the duplicate unclosed method header.
   Run compilation test:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java -sourcepath packages/apps/LinuxTerminal/src:frameworks/base/core/java:frameworks/base/services/core/java -d build_out/classes packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java
   ```
2. **Reflection & Binder IPC Verification**:
   Grep for `Class.forName` in `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java` to confirm reflection calls at lines 276 and 295.
   Inspect `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java` to confirm it does not implement `ILinuxWindowBridge.Stub`.
3. **Permission Activity Verification**:
   Inspect `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java` to confirm `onCreate` only contains `super.onCreate(savedInstanceState); finish();`.
4. **AIDL Signature Audit**:
   Compare `frameworks/base/core/java/android/system/linux/ILinuxWindowBridge.aidl` with methods in `LinuxWindowBridgeService.java`.
