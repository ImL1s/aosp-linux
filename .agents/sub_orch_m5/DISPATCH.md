# DISPATCH Log

## 2026-08-06T12:01:37Z

You are sub_orch_m5, the Sub-Orchestrator for Milestone M5 (Hardware Portals, Virtiofs Bi-directional File Sharing, SELinux Policies & Guest A/B Base Image Rollback OTA) in the AOSP Dual-OS Project.

Your parent orchestrator conversation ID: f49c13dc-3d4b-4e80-a68f-c1ded5ab6c3a

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m5
Scope Document: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m5/SCOPE.md

Context & Reference Files:
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md (MANDATORY: read this first!)
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Full Technical Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md

Scope of Milestone M5 (14 features):
1. F-R5-001: XDG Portal Camera Bridge - org.freedesktop.portal.Camera interception & Camera2 HAL streaming (`LinuxPortalService.java`).
2. F-R5-002: XDG Portal Microphone Bridge - org.freedesktop.portal.Microphone interception & AudioRecord streaming.
3. F-R5-003: XDG Portal Location Bridge - org.freedesktop.portal.Location interception & LocationManager streaming.
4. F-R5-004: AppOps Permission Prompt - Mandatory Host runtime permission dialog & AppOpsManager enforcement (`OP_CAMERA`, `OP_RECORD_AUDIO`, `OP_FINE_LOCATION`).
5. F-R5-005: virtio-snd Audio Mapping - virtio-snd guest driver mapping to Host AudioService.
6. F-R5-006: AudioFocus Policy Handler - Automatic audio ducking/pausing on phone calls and alarms (`LinuxAudioPolicyHandler.java`).
7. F-R5-007: virtiofs Bi-directional Sharing - `/data/media/0/LinuxShared` <-> `/mnt/shared` zero-copy page cache mount.
8. F-R5-008: LinuxStorageProvider SAF Provider - DocumentsProvider integration for Android access to Guest `/home/user`.
9. F-R5-009: SELinux Domain Policy Rules - Policy rules for `linux_manager.te`, `linux_bridge.te`, `linux_portal.te`.
10. F-R5-010: SELinux neverallow Rules - Strict neverallow protection for `efs_file` and system partition writes.
11. F-R5-011: CTS / VTS Compatibility - CTS compliance (`CtsSELinuxHostTestCases`, `CtsSecurityTestCases`).
12. F-R5-012: EROFS Base Image A/B Layout - Immutable read-only EROFS `base_a.img` / `base_b.img` dual slot layout.
13. F-R5-013: AVB Key Signature Validation - Android Verified Boot (AVB) key chain verification for guest image OTA.
14. F-R5-014: Boot Watchdog Rollback Engine - 3-boot attempt watchdog fallback to previous base slot on boot failure (`guest_ota_rollback_watchdog.cpp` / `ota_rollback.rs`).
