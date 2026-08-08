# Original User Request

## Initial Request — 2026-08-06T13:24:58Z

# AOSP Dual-OS Verification & Deployment Run Task

You are dispatched as the Teamwork Multi-Agent Execution Team for the AOSP Dual-OS Verification & Deployment Run.

Working directory: /Users/iml1s/Documents/mine/aosp-linux
Integrity mode: development

Task Requirements:
1. R1: Run all 430+ automated E2E & empirical stress test suites (runner.py) and generate full verification reports.
2. R2: Execute Soong Android.bp module compilation checks, Rust bridge-agent static build, and AVB 2.0 signed guest image packaging.
3. R3: Deploy generated AOSP artifacts (LinuxManagerService, linux_manager.te, LinuxTerminal.apk, android-bridge-agent, guest images) to build_out/deployment/ directory and perform simulated target verification.
4. Report final verification and deployment results once completed.

## Follow-up — 2026-08-08T13:55:14+08:00

# AOSP Dual-OS Production Remediation Task (Addressing All 6 Core Defects)

You are dispatched as the Teamwork Multi-Agent Execution Team for the AOSP Dual-OS Production Remediation Project.

Working directory: /Users/iml1s/Documents/mine/aosp-linux
Integrity mode: development

Core Objectives — Fix All 6 Deterministic Defects:
1. R1 (Real AVF VM Launch): Replace simulated VM state transitions in LinuxManagerService & native daemon with REAL calls to AVF VirtualizationService AIDL / crosvm binary; integrate launch_vm.sh properly without simulated fallbacks.
2. R2 (Production Guest Agent Loop): In guest/bridge-agent, implement an active multi-threaded server dispatch loop (Ports 5000, 5001, 5002); remove hardcoded secrets, abort on auth failure, and dispatch real PTY, Wayland, and Portal RPCs.
3. R3 (Real Vsock Socket Connect & Session ID): Fix VsockTerminalClient.java to invoke real AF_VSOCK connect(guestCid, 5001) syscall; replace hardcoded session ID "0123456789abcdef" in TerminalView with dynamic IDs issued by LinuxManagerService.
4. R4 (Real Wayland dma-buf & SurfaceControl Binding): In LinuxWindowBridgeService, implement real HardwareBuffer/dma-buf import & SurfaceControl Transaction Commit; bind Linux GUI window frames to Android TaskManager in LinuxAppProxyActivity.
5. R5 (Real System Hardware Portals): Replace in-memory portal models in LinuxPortalService with REAL system calls to CameraManager/Camera2, AudioRecord, LocationManager, and Android AppOpsManager; link SAF provider dynamically to Guest virtiofs & LUKS2 mount lifecycle.
6. R6 (Clean & Honest E2E Test Suite): Eliminate fake passes, hardcoded mock responses, and static JSON readouts in CI; make test runner execute REAL IPC, socket, and system checks.

Execute all remediation tasks systematically with full verification.

