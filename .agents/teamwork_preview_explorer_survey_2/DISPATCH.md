# DISPATCH — Explorer 2 (Survey R3 & R4)

## Mission
Survey the AOSP Dual-OS codebase for Defect R3 (Real Vsock Socket Connect & Session ID) and Defect R4 (Real Wayland dma-buf & SurfaceControl Binding).

## Scope
1. **R3**: Locate `VsockTerminalClient.java`, `TerminalView`, and session management in `LinuxManagerService`. Document AF_VSOCK syscall usage, socket connect to guestCid:5001, dynamic session ID generation and flow.
2. **R4**: Locate `LinuxWindowBridgeService` and `LinuxAppProxyActivity`. Document HardwareBuffer / dma-buf import logic, SurfaceControl Transaction Commit, and TaskManager window binding.

## Inputs
- `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`

## Outputs
Write detailed survey report and handoff to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_2/handoff.md`.
