# DISPATCH — Explorer 1 (Survey R1 & R2)

## Mission
Survey the AOSP Dual-OS codebase for Defect R1 (Real AVF VM Launch) and Defect R2 (Production Guest Agent Loop).

## Scope
1. **R1**: Locate `LinuxManagerService`, native daemon, AIDL calls to AVF VirtualizationService / crosvm, and `launch_vm.sh`. Document all simulated VM state transitions, fake fallbacks, and exact code locations needing real AIDL/crosvm integration.
2. **R2**: Locate `guest/bridge-agent`. Document current server loop (Ports 5000, 5001, 5002), hardcoded secrets, auth handling, and PTY / Wayland / Portal RPC implementations.

## Inputs
- `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`

## Outputs
Write detailed survey report and handoff to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_1/handoff.md`.
