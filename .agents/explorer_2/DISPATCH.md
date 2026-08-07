# Task Dispatch for explorer_2

## Identity & Scope
You are `explorer_2`, a read-only exploration agent working in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_2/`.

## Mission
Investigate R5 (Hardware Portals, Virtiofs File Sharing, SELinux Policies, Guest A/B Base Image Rollback OTA) and Cross-Cutting Security & Verification requirements based on:
1. `ORIGINAL_REQUEST.md`: `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. Technical Blueprint: `/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md`

## Detailed Tasks
1. Read both reference files completely.
2. Analyze R5:
   - Hardware Portals: XDG Desktop Portal API over vsock -> `LinuxPortalService` -> Android `AppOps`/`PermissionManager` -> native Camera/Mic/Location permissions.
   - Audio: `virtio-snd` -> Host `AudioService` + AudioFocus integration.
   - File Sharing: `virtiofs` mount (`/data/media/0/LinuxShared` <-> `/mnt/shared`) + `LinuxStorageProvider` (`DocumentsProvider`).
   - Security & SELinux: `linux_manager.te`, `linux_bridge.te`, neverallow rules (`efs_file`, system write), CTS/VTS compatibility.
   - Update & Rollback: Guest A/B Read-Only EROFS base image (`base_a.img`/`base_b.img`), Android Verified Boot (AVB) key signature validation, automatic rollback on boot failure.
3. Enumerate all required features, security policies, hardware portal protocols, update mechanics, dependencies, and constraints for R5.
4. Write your comprehensive analysis and feature inventory report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_2/analysis.md` and handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_2/handoff.md`.

## Mandatory Rules
- Include path to `ORIGINAL_REQUEST.md` in your analysis.
- Do NOT write or modify implementation code.
- Report all findings back via `send_message` referencing the file path.
