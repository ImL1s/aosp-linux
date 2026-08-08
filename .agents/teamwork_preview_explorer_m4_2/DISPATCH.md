## 2026-08-08T06:11:20Z
<USER_REQUEST>
You are Explorer 2 for Milestone M4 (Real Wayland dma-buf & SurfaceControl Binding - R4).
Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_2

MUST READ mandatory reference files:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_2/handoff.md

Your scope focus:
packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java

Objectives & Requirements:
1. Investigate registering SurfaceView / SurfaceControl with LinuxWindowBridgeService to bind Wayland GUI window frames to Android TaskManager.
2. Analyze SurfaceHolder.Callback lifecycle, SurfaceControl extraction from SurfaceView, RPC/binder connection to LinuxWindowBridgeService, and window state synchronization.
3. Recommend exact implementation strategy, methods to add/modify, layout configuration, and binder call patterns without modifying source code.

Write your report to: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_2/handoff.md
When done, deliver your report path and key findings to the orchestrator via send_message.
</USER_REQUEST>
