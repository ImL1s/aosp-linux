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

## Follow-up — 2026-08-14T01:19:53+08:00

# AOSP Dual-OS Java Compile Closure, Binder Bridge & Auth Protocol Remediation

Working directory: /Users/iml1s/Documents/mine/aosp-linux
Integrity mode: development

## Requirements

### R1. Complete Java Syntax & Compilation Closure
1. Fix syntax error in `LinuxAppProxyActivity.java` (duplicate unclosed `attachSurfaceControlToBridge` method declarations).
2. Ensure all AIDL interfaces, system server services, and application activities compile cleanly without unresolved symbols or mismatched signatures.

### R2. Pure Binder IPC Window Bridge (App/SystemServer Decoupling)
1. Replace reflection access (`Class.forName("com.android.server.linux.LinuxWindowBridgeService")`) in `LinuxAppProxyActivity.java` with canonical Binder IPC via `ILinuxWindowBridge.aidl`.
2. Connect `SurfaceView`/`Surface` creation, change, and destruction lifecycle to `ILinuxWindowBridge` obtained via `ServiceManager.getService("linux_window_bridge")` or `ILinuxManager`.

### R3. Single-Secret HMAC Key Agreement & Startup Initiator
1. Establish a single shared 32-byte secret agreement between Host Java, C++ daemon, and Guest agent.
2. Host Java generates a 32-byte token and 32-byte secret; C++ propagates this exact secret via kernel cmdline (`android_bridge.token=<hex_secret>`) to Guest.
3. Guest decodes the hex string into the exact 32-byte binary secret.
4. Establish clear Control Channel roles: Host C++ listens on `AF_VSOCK` port 5000; Guest agent acts as initiator upon boot by connecting to Host `CID_HOST=2` port 5000, sending 32-byte token + 32-byte HMAC signature.

### R4. Functional Permission Decision Component
1. Implement functional request handling and AppOps integration in `LinuxPermissionActivity` to process incoming `app_id` and permission op requests.

## Acceptance Criteria

### Java & Build Integrity
- [ ] No duplicate methods, unclosed braces, or compilation syntax errors in any Java files.
- [ ] App layer does not import or reflect upon `com.android.server.*` private implementation classes.
- [ ] All AIDL methods match their Java consumer callers in parameter types and counts.

### Protocol & Cryptography
- [ ] Host and Guest use identical 32-byte binary secrets to compute and verify RFC 2104 HMAC-SHA256 signatures.
- [ ] Guest initiates startup handshake connection to Host (CID 2, Port 5000), transitioning Host VM state to `RUNNING`.
- [ ] ARM64 build (`cargo check --target aarch64-unknown-linux-gnu`) passes cleanly with zero warnings or errors.
- [ ] All unit and empirical tests pass.


