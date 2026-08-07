# Scope: Milestone M5 — Hardware Portals, Virtiofs Bi-directional File Sharing, SELinux Policies & Guest A/B Base Image Rollback OTA

## Architecture
- Hardware Portals Bridge: XDG Desktop Portal daemon in Guest interop with Host AOSP services via IPC (`LinuxPortalService.java`).
- Audio Subsystem: `virtio-snd` PCM streaming mapping to Host `AudioService` + `LinuxAudioPolicyHandler.java` for ducking/focus.
- Storage & Sharing: `virtiofs` zero-copy page cache mount between Host `/data/media/0/LinuxShared` and Guest `/mnt/shared`, plus `LinuxStorageProvider` SAF `DocumentsProvider`.
- Security & SELinux: Domain policies for `linux_manager.te`, `linux_bridge.te`, `linux_portal.te`, strict neverallow rules for `efs_file` & system partition writes, CTS/VTS compliance (`CtsSELinuxHostTestCases`, `CtsSecurityTestCases`).
- OTA & Rollback: EROFS `base_a.img`/`base_b.img` immutable A/B dual slot layout, AVB key signature validation, and 3-boot attempt watchdog rollback engine (`guest_ota_rollback_watchdog.cpp` / `ota_rollback.rs`).

## Feature Inventory
| # | Feature ID | Description | Component / File | Status |
|---|------------|-------------|------------------|--------|
| 1 | F-R5-001 | XDG Portal Camera Bridge | `LinuxPortalService.java` & Camera2 HAL | DONE |
| 2 | F-R5-002 | XDG Portal Microphone Bridge | `LinuxPortalService.java` & Host AudioRecord | DONE |
| 3 | F-R5-003 | XDG Portal Location Bridge | `LinuxPortalService.java` & LocationManager | DONE |
| 4 | F-R5-004 | AppOps Permission Prompt | `LinuxPermissionActivity.java` & AppOpsManager | DONE |
| 5 | F-R5-005 | virtio-snd Audio Mapping | virtio-snd guest driver -> Host AudioService | DONE |
| 6 | F-R5-006 | AudioFocus Policy Handler | `LinuxAudioPolicyHandler.java` for ducking/pausing | DONE |
| 7 | F-R5-007 | virtiofs Bi-directional Sharing | `/data/media/0/LinuxShared` <-> `/mnt/shared` mount | DONE |
| 8 | F-R5-008 | LinuxStorageProvider SAF Provider | `LinuxStorageProvider.java` DocumentsProvider | DONE |
| 9 | F-R5-009 | SELinux Domain Policy Rules | Policy rules for `linux_manager.te`, `linux_bridge.te`, `linux_portal.te` | DONE |
| 10 | F-R5-010 | SELinux neverallow Rules | Strict neverallow protection (`efs_file`, system partition) | DONE |
| 11 | F-R5-011 | CTS / VTS Compatibility | CTS SELinux compliance tests passing | DONE |
| 12 | F-R5-012 | EROFS Base Image A/B Layout | Read-only EROFS `base_a.img` / `base_b.img` dual slot | DONE |
| 13 | F-R5-013 | AVB Key Signature Validation | `AvbVerifier.cpp` OpenSSL RSA-4096 signature verification | DONE |
| 14 | F-R5-014 | Boot Watchdog Rollback Engine | 3-boot attempt watchdog fallback (`guest_ota_rollback_watchdog.cpp` / `ota_rollback.rs`) | DONE |

## Interface Contracts
- Host Portal Service <-> Guest XDG Portal: DBus IPC bridge forwarding org.freedesktop.portal.Camera, Microphone, Location requests.
- AppOps Enforcement: Host checks runtime permissions (`OP_CAMERA`, `OP_RECORD_AUDIO`, `OP_FINE_LOCATION`) before streaming hardware sessions.
- virtiofs Mount: Virtiofs daemon on Host sharing `/data/media/0/LinuxShared` to Guest `/mnt/shared`.
- SAF DocumentsProvider: Exposes `/home/user` via Android `DocumentsProvider` API.
