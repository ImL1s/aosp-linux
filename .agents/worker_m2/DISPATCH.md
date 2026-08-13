## 2026-08-13T17:31:46Z
<USER_REQUEST>
You are worker_m2 (M2 Pure Binder IPC Window Bridge Worker).
Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2
Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Survey Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_1/survey_report.md
Project Plan: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md

Scope & Tasks for Milestone 2 (R2):
1. Read ORIGINAL_REQUEST.md (R2 requirement) and survey_report.md.
2. Modify `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`:
   - Extend `ILinuxWindowBridge.Stub`.
   - Publish to ServiceManager as `"linux_window_bridge"` (e.g. in constructor or service publish method: `ServiceManager.addService("linux_window_bridge", this)`).
   - Implement `onSurfaceCreated(int surfaceId, Surface surface)`, `onSurfaceChanged(int surfaceId, int width, int height)`, and `onSurfaceDestroyed(int surfaceId)`.
3. Modify `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`:
   - Completely remove reflection access `Class.forName("com.android.server.linux.LinuxWindowBridgeService")` and reflection method invocation.
   - Obtain `ILinuxWindowBridge` Binder interface cleanly using `ILinuxWindowBridge.Stub.asInterface(ServiceManager.getService("linux_window_bridge"))`.
   - Connect SurfaceHolder lifecycle (`surfaceCreated`, `surfaceChanged`, `surfaceDestroyed`) to invoke `ILinuxWindowBridge` Binder IPC methods.
4. Run Java compilation command:
   `javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java -sourcepath packages/apps/LinuxTerminal/src:frameworks/base/core/java:frameworks/base/services/core/java -d /tmp/classes_m2 packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java frameworks/base/services/core/java/com/android/server/linux/*.java`
   Verify javac exits with code 0 and zero compilation errors.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Document your changes and build verification in:
- /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2/handoff.md

Send a completion message when done.
</USER_REQUEST>
