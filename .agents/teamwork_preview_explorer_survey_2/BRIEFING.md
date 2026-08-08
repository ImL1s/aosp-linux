# BRIEFING — 2026-08-08T13:57:40+08:00

## Mission
Survey the AOSP Dual-OS codebase for Defect R3 (Real Vsock Socket Connect & Session ID) and Defect R4 (Real Wayland dma-buf & SurfaceControl Binding).

## 🔒 My Identity
- Archetype: explorer
- Roles: Read-only investigator & survey reporter
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_2
- Original parent: e27b9395-c6bf-4764-91fe-af9e49f3aa80
- Milestone: Survey R3 & R4

## 🔒 Key Constraints
- Read-only investigation — do NOT implement code changes in project source files
- Write findings to /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_2/handoff.md
- Use Traditional Chinese (繁體中文) per user rules where appropriate, keeping technical terms accurate

## Current Parent
- Conversation ID: e27b9395-c6bf-4764-91fe-af9e49f3aa80
- Updated: 2026-08-08T13:57:40+08:00

## Investigation State
- **Explored paths**:
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
  - `system/linux_bridge/wayland_buffer_sharing.h` & `wayland_buffer_sharing.cpp`
  - `tests/unit/TerminalAppUnitTest.java`, `tests/unit/LinuxWindowBridgeServiceTest.java`
- **Key findings**:
  - R3 Defect: `VsockTerminalClient.java` creates `Os.socket(AF_VSOCK, ...)` but lacks `Os.connect(guestCid, 5001)` call. `TerminalView.java` hardcodes session ID `"0123456789abcdef"`. `LinuxManagerService.java` produces 12-byte session strings instead of 16-byte tokens expected by framer, and is not queried dynamically by `TerminalView`.
  - R4 Defect: `LinuxWindowBridgeService.java` `commitFrame()` only performs frame pacing without importing dma-buf/HardwareBuffer or applying `SurfaceControl.Transaction`. `LinuxAppProxyActivity.java` creates `SurfaceView` but never registers its `SurfaceControl`/`Surface` with `LinuxWindowBridgeService`.
- **Unexplored areas**: None within R3/R4 scope.

## Key Decisions Made
- Completed full code mapping and line-by-line inspection for R3 and R4 defects. Preparing structured handoff report.

## Artifact Index
- handoff.md — Final survey report and handoff for Implementer 2 / Orchestrator
