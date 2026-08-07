## 2026-08-06T12:01:57Z

<USER_REQUEST>
You are Explorer 1 for Milestone M5 (Hardware Portals & Audio Subsystem).

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1

MANDATORY Context Files (You MUST read these files first):
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Scope Document: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m5/SCOPE.md
- Technical Architecture Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md

Your Focus Area:
1. F-R5-001: XDG Portal Camera Bridge - org.freedesktop.portal.Camera interception & Camera2 HAL streaming (`LinuxPortalService.java`).
2. F-R5-002: XDG Portal Microphone Bridge - org.freedesktop.portal.Microphone interception & AudioRecord streaming.
3. F-R5-003: XDG Portal Location Bridge - org.freedesktop.portal.Location interception & LocationManager streaming.
4. F-R5-004: AppOps Permission Prompt - Mandatory Host runtime permission dialog & AppOpsManager enforcement (`OP_CAMERA`, `OP_RECORD_AUDIO`, `OP_FINE_LOCATION`).
5. F-R5-005: virtio-snd Audio Mapping - virtio-snd guest driver mapping to Host AudioService.
6. F-R5-006: AudioFocus Policy Handler - Automatic audio ducking/pausing on phone calls and alarms (`LinuxAudioPolicyHandler.java`).

Instructions:
1. Read the mandatory reference files listed above.
2. Investigate the codebase for existing implementations, related packages, interfaces, and missing components for features F-R5-001 through F-R5-006.
3. Formulate a detailed technical implementation strategy for these features, specifying exact file paths, class/interface definitions, IPC channels, and permission models.
4. Write your comprehensive analysis and implementation strategy to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1/analysis.md`.
5. Write your handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1/handoff.md`.
6. Send a message to parent orchestrator with the summary of findings and file paths.
</USER_REQUEST>
