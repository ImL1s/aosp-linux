## 2026-08-08T06:21:15Z

You are Worker 2 for Milestone M4 (Iteration 2).
Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_m4_2

Write ownership (You have EXCLUSIVE write permission for these files):
- frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java
- packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java
- system/linux_bridge/wayland_buffer_sharing.cpp

MUST READ mandatory reference files:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_auditor_m4_1/handoff.md (AUDIT VIOLATION EVIDENCE REPORT - READ CAREFULLY TO AVOID REPEATING CHEATING/FACADE ISSUES)
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_iter2_1/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_iter2_2/handoff.md
6. /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_iter2_3/handoff.md

Scope & Concrete Implementation Tasks:
1. frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java:
   - Implement attachSurfaceControl(int surfaceId, SurfaceControl surfaceControl) and registerSurfaceControl(int surfaceId, SurfaceControl surfaceControl, int width, int height).
   - Implement overloaded commitFrame(int surfaceId, HardwareBuffer buffer) using SurfaceControl.Transaction (setBuffer, setVisibility, apply()). Close previous HardwareBuffer on frame replacement.
   - Implement complete resource cleanup in destroySurface and flushTasks (close currentBuffer and release SurfaceControl).

2. packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java:
   - Extract SurfaceControl from SurfaceView in surfaceCreated/surfaceChanged (mSurfaceView.getSurfaceControl()) and register with LinuxWindowBridgeService via bridge helper/binder.
   - Handle surfaceDestroyed and onDestroy lifecycle cleanup.

3. system/linux_bridge/wayland_buffer_sharing.cpp:
   - Implement REAL NDK bindings in bindHardwareBufferToSurfaceControl (ASurfaceTransaction_create, ASurfaceTransaction_setBuffer, ASurfaceTransaction_apply, ASurfaceTransaction_delete).
   - Implement REAL AHardwareBuffer import/allocation in importDmaBufToHardwareBuffer.
   - Fix data race on mActiveBuffers by changing uint32_t mActiveBuffers to std::atomic<size_t> mActiveBuffers.
   - Implement AHardwareBuffer_release in releaseBuffer.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Verification Requirements:
1. ACTUALLY edit and write the target source files. Verify git diff or file contents on disk.
2. Run build commands (e.g. javac, g++, or project build tool) and run tests for affected targets.
3. Include actual build and test execution logs in your handoff report.

Write your handoff report to: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_m4_2/handoff.md
When completed, notify the orchestrator via send_message with your handoff path and build/test results.
