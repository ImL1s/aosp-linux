# Task Dispatch for explorer_1

## Identity & Scope
You are `explorer_1`, a read-only exploration agent working in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/`.

## Mission
Investigate R1 (AOSP Framework & Core Modification Architecture) and R2 (AVF / crosvm / KVM Non-Protected Debian ARM64 Guest Setup & Storage Encryption) based on:
1. `ORIGINAL_REQUEST.md`: `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. Technical Blueprint: `/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md`

## Detailed Tasks
1. Read both reference files completely.
2. Analyze R1: `LinuxManagerService`, `ILinuxManager.aidl`, `LinuxBridgeService`, `LinuxPortalService`, `SystemServer` integration (`startOtherServices()`), and process isolation (`linux_bridge` daemon).
3. Analyze R2: Non-protected AVF Debian 12 ARM64 setup, `base_rootfs.img`, `custom_overlay.img`, `user_home.img` LUKS CE encryption, authenticated vsock RPC handshake (`5000`, `5001`, `5002`), and guest boot configuration.
4. Enumerate all required features, APIs, AIDL interfaces, dependencies, and constraints for R1 and R2.
5. Write your comprehensive analysis and feature inventory report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/analysis.md` and handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/handoff.md`.

## Mandatory Rules
- Include path to `ORIGINAL_REQUEST.md` in your analysis.
- Do NOT write or modify implementation code.
- Report all findings back via `send_message` referencing the file path.
