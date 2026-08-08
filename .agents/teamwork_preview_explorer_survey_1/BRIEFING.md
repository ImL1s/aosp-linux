# BRIEFING — 2026-08-08T14:00:30Z

## Mission
Survey the AOSP Dual-OS codebase for R1 (Real AVF VM Launch) & R2 (Production Guest Agent Loop) defects.

## 🔒 My Identity
- Archetype: Explorer 1
- Roles: Read-only investigator / Codebase mapper for R1 & R2
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_1
- Original parent: e27b9395-c6bf-4764-91fe-af9e49f3aa80
- Milestone: Survey R1 & R2

## 🔒 Key Constraints
- Read-only investigation — do NOT modify source code (only write to your own directory)

## Current Parent
- Conversation ID: e27b9395-c6bf-4764-91fe-af9e49f3aa80
- Updated: 2026-08-08T14:00:30Z

## Investigation State
- **Explored paths**:
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`
  - `system/linux_bridge/` (`main.cpp`, `socket_server.cpp`, `vsock_server.cpp`, `ILinuxBridgeDaemon.aidl`)
  - `guest/scripts/launch_vm.sh`
  - `guest/bridge-agent/` (`main.rs`, `auth.rs`, `vsock.rs`, `ota_rollback.rs`, `Cargo.toml`)
  - `guest/portal-agent/` (`main.rs`, `desktop_parser.rs`)
  - `tests/unit/` & `tests/e2e/`
- **Key findings**:
  - **R1**: `socket_server.cpp` lines 173-177 contains simulated VM launch logic where `CMD_VM_START` immediately echoes back fake `CMD_HANDSHAKE_COMPLETE` without invoking `launch_vm.sh` or `crosvm`.
  - **R2**: `guest/bridge-agent/src/main.rs` lines 48-52 contains dummy `loop { sleep(5s); }` instead of active multi-threaded server listening on Vsock Ports 5000, 5001, 5002. Hardcoded secrets, token fallback to zeros, and failure to abort on auth errors were also documented.
- **Unexplored areas**: None for R1 & R2 scope.

## Key Decisions Made
- Survey completed. Written detailed 5-component handoff report to `handoff.md`.

## Artifact Index
- handoff.md — Final 5-component survey report and findings
