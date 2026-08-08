## 2026-08-08T06:15:00Z
<USER_REQUEST>
You are Worker 1 for Milestone M4 (Real Wayland dma-buf & SurfaceControl Binding - R4).
Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_m4_1

Write ownership (You have EXCLUSIVE write permission for these files):
- frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java
- packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java
- system/linux_bridge/wayland_buffer_sharing.cpp

MUST READ mandatory reference files:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_1/handoff.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_2/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_3/handoff.md

Scope & Requirements:
1. LinuxWindowBridgeService.java: Implement real HardwareBuffer / dma-buf import in commitFrame() and apply SurfaceControl.Transaction (setBuffer, setVisibility, apply()). Add attachSurfaceControl(int surfaceId, SurfaceControl surfaceControl), overload commitFrame(int surfaceId, HardwareBuffer buffer), and manage HardwareBuffer / SurfaceControl lifecycle (close/release on destroy/flush).
2. LinuxAppProxyActivity.java: Register SurfaceView / SurfaceControl with LinuxWindowBridgeService to bind Wayland GUI window frames to Android TaskManager. Connect SurfaceHolder.Callback (surfaceCreated/surfaceDestroyed) to LinuxWindowBridgeService.
3. wayland_buffer_sharing.cpp: Implement native ASurfaceTransaction_setBuffer binding, AHardwareBuffer_import / createFromHandle for dma-buf import, ASurfaceTransaction NDK calls, and AHardwareBuffer_release for resource cleanup.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Verification Requirements:
1. Run build commands (e.g. mmm / mm / m or target build scripts for the modules) and run unit/integration tests for affected components.
2. Document all build and test commands and execution outputs in your handoff report.

Write your report to: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_m4_1/handoff.md
When finished, notify the orchestrator via send_message with your handoff path and build/test results.
</USER_REQUEST>
