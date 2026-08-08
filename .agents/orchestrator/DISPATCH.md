# DISPATCH LOG

## 2026-08-08T13:56:23+08:00

Fix All 6 Deterministic Defects:
1. R1 (Real AVF VM Launch): Replace simulated VM state transitions in LinuxManagerService & native daemon with REAL calls to AVF VirtualizationService AIDL / crosvm binary; integrate launch_vm.sh properly without simulated fallbacks.
2. R2 (Production Guest Agent Loop): In guest/bridge-agent, implement an active multi-threaded server dispatch loop (Ports 5000, 5001, 5002); remove hardcoded secrets, abort on auth failure, and dispatch real PTY, Wayland, and Portal RPCs.
3. R3 (Real Vsock Socket Connect & Session ID): Fix VsockTerminalClient.java to invoke real AF_VSOCK connect(guestCid, 5001) syscall; replace hardcoded session ID "0123456789abcdef" in TerminalView with dynamic IDs issued by LinuxManagerService.
4. R4 (Real Wayland dma-buf & SurfaceControl Binding): In LinuxWindowBridgeService, implement real HardwareBuffer/dma-buf import & SurfaceControl Transaction Commit; bind Linux GUI window frames to Android TaskManager in LinuxAppProxyActivity.
5. R5 (Real System Hardware Portals): Replace in-memory portal models in LinuxPortalService with REAL system calls to CameraManager/Camera2, AudioRecord, LocationManager, and Android AppOpsManager; link SAF provider dynamically to Guest virtiofs & LUKS2 mount lifecycle.
6. R6 (Clean & Honest E2E Test Suite): Eliminate fake passes, hardcoded mock responses, and static JSON readouts in CI; make test runner execute REAL IPC, socket, and system checks.

## 2026-08-08T10:32:08Z
Fix & Verify All 6 Deterministic Defects (R1-R6):
1. R1 (Real AVF VM Launch): M1 verified & passed.
2. R2 (Production Guest Agent Loop): Finalize M2 verification & handoff.
3. R3 (Real Vsock Socket Connect & Session ID): M3 verified & passed.
4. R4 (Real Wayland dma-buf & SurfaceControl Binding): M4 verified & passed.
5. R5 (Real System Hardware Portals): Finalize M5 verification & handoff.
6. R6 (Clean & Honest E2E Test Suite): Finalize M6 verification & handoff.

Please inspect existing progress in .agents/ folder, verify/complete remaining milestones (M2, M5, M6), confirm all 6 milestones pass their gates (Reviewers APPROVE, Challengers APPROVE, Auditor CLEAN), and report victory to parent when complete.
