# Scope: Milestone M1 — Real AVF VM Launch (R1)

## Scope Boundary
- `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`
- `system/linux_bridge/socket_server.cpp`
- `guest/scripts/launch_vm.sh`

## Objective
Replace simulated VM state transitions in `LinuxManagerService` & native daemon `socket_server.cpp` with REAL calls to AVF VirtualizationService / `crosvm` binary; integrate `launch_vm.sh` properly without simulated fallbacks.

## Verification Criteria
1. `socket_server.cpp` does NOT return fake `CMD_HANDSHAKE_COMPLETE` immediately upon receiving `CMD_VM_START`.
2. `launch_vm.sh` is actually executed by native daemon with correct arguments.
3. Native daemon unit tests and VM launch integration checks pass.
4. Reviewers APPROVE, Challenger confirms correctness, Forensic Auditor gives CLEAN verdict.
