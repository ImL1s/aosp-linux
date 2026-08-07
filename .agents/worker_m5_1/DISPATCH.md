## 2026-08-06T12:06:03Z
You are Worker 1 for Milestone M5 (Hardware Portals, Virtiofs Bi-directional File Sharing, SELinux Policies & Guest A/B Base Image Rollback OTA).

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_1

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

MANDATORY Context & Reference Files (You MUST read these files first):
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Scope Document: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m5/SCOPE.md
- Full Technical Architecture Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md
- Explorer 1 Analysis & Handoff (Portals & Audio): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1/analysis.md and /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1/handoff.md
- Explorer 2 Analysis & Handoff (Virtiofs & Storage SAF): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/analysis.md and /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/handoff.md
- Explorer 3 Analysis & Handoff (SELinux & OTA Rollback): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/analysis.md and /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/handoff.md

Your Task:
Implement, build, and verify all 14 features of Milestone M5:
1. F-R5-001: XDG Portal Camera Bridge (`LinuxPortalService.java`, `org.freedesktop.portal.Camera` -> Camera2 HAL).
2. F-R5-002: XDG Portal Microphone Bridge (`org.freedesktop.portal.Microphone` -> Host AudioRecord).
3. F-R5-003: XDG Portal Location Bridge (`org.freedesktop.portal.Location` -> Host LocationManager).
4. F-R5-004: AppOps Permission Prompt (`LinuxPermissionActivity.java`, `AppOpsManager` enforcement).
5. F-R5-005: virtio-snd Audio Mapping (`virtio-snd` guest driver -> Host AudioService / AudioTrack).
6. F-R5-006: AudioFocus Policy Handler (`LinuxAudioPolicyHandler.java` for phone call / alarm ducking and pausing).
7. F-R5-007: virtiofs Bi-directional Sharing (`/data/media/0/LinuxShared` <-> `/mnt/shared` zero-copy page cache mount).
8. F-R5-008: LinuxStorageProvider SAF Provider (`DocumentsProvider` integration for `/home/user`).
9. F-R5-009: SELinux Domain Policy Rules (`linux_manager.te`, `linux_bridge.te`, `linux_portal.te`, file_contexts).
10. F-R5-010: SELinux neverallow Rules (strict neverallow assertions for `efs_file` and system partition writes).
11. F-R5-011: CTS / VTS Compatibility (`CtsSELinuxHostTestCases`, `CtsSecurityTestCases` compliance).
12. F-R5-012: EROFS Base Image A/B Layout (immutable read-only EROFS `base_a.img` / `base_b.img` dual slot layout).
13. F-R5-013: AVB Key Signature Validation (AVB key chain verification engine).
14. F-R5-014: Boot Watchdog Rollback Engine (3-boot attempt watchdog fallback in `guest_ota_rollback_watchdog.cpp` / `ota_rollback.rs`).
