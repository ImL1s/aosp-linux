# Dispatch Instructions — Explorer Gen2 2

## Identity
- Role: R2 Build & Packaging Status Investigator
- Archetype: teamwork_preview_explorer
- Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_gen2_2

## Objective
Investigate Requirement 2 (R2 / Milestone M2) status:
1. Read `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md` and `/Users/iml1s/Documents/mine/aosp-linux/.agents/ORIGINAL_REQUEST.md`.
2. Inspect Soong Android.bp module compilation setup (`frameworks/base/services/core/java/com/android/server/linux/`, `system/sepolicy/private/`, `packages/apps/LinuxTerminal/`).
3. Inspect Rust bridge-agent source and build setup (`guest/bridge-agent/`).
4. Inspect AVB 2.0 signed guest image packaging scripts (`guest/scripts/`, `init_storage_layout.sh`, `launch_vm.sh`, etc.) and `scripts/run_m2_verification.sh`.
5. Check build artifacts in `build_out/`.
6. Document exact execution and verification commands for Worker build & packaging tasks.
7. Write your findings to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_gen2_2/handoff.md`.
