# DISPATCH — Sub-Orchestrator M1 (Real AVF VM Launch - R1)

## Mission
Orchestrate the iteration loop (Explorer -> Worker -> Reviewer -> Challenger -> Auditor) to fix Defect R1: Real AVF VM Launch.

## Scope & Requirements
1. `LinuxManagerService.java` & `LinuxBridgeService.java`: Trigger real VM launch flow.
2. `system/linux_bridge/socket_server.cpp`: Replace fake `CMD_HANDSHAKE_COMPLETE` immediate response on `CMD_VM_START` (0x0001) with actual execution/management of `launch_vm.sh` / `crosvm` and real handshake verification.
3. `guest/scripts/launch_vm.sh`: Integrate invocation from `socket_server.cpp`, pass security token, track child PID, and support clean teardown.

## Input Documents
- `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
- `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_1/handoff.md`

## Outputs
Write progress to `.agents/sub_orch_m1/progress.md` and handoff report to `.agents/sub_orch_m1/handoff.md`.
