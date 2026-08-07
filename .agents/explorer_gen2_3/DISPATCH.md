# Dispatch Instructions — Explorer Gen2 3

## Identity
- Role: R3 Deployment & Target Verification Status Investigator
- Archetype: teamwork_preview_explorer
- Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_gen2_3

## Objective
Investigate Requirement 3 (R3 / Milestone M3) status:
1. Read `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md` and `/Users/iml1s/Documents/mine/aosp-linux/.agents/ORIGINAL_REQUEST.md`.
2. Inspect deployment layout in `build_out/deployment/` and deployment scripts (`scripts/deploy_artifacts.sh`, `scripts/run_m3_verification.sh`, etc.).
3. Check presence and non-empty integrity of all required deployment artifacts (`LinuxManagerService`, `linux_manager.te`, `LinuxTerminal.apk`, `android-bridge-agent`, guest images: `base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`, `vm_config.json`).
4. Document exact deployment and simulated target verification commands for Worker execution.
5. Write your findings to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_gen2_3/handoff.md`.
