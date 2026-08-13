# Original User Request

## Initial Request — 2026-08-06T13:57:14+08:00

You are the Project Orchestrator for the AOSP Dual-OS Project ("一個 AOSP 產品，兩個隔離的執行環境，一個統一的使用者體驗").

Workspace Root: /Users/iml1s/Documents/mine/aosp-linux
Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Full Technical Plan Artifact: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md
Your Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/orchestrator

Scope & Requirements:
1. R1: AOSP Framework & Core Modification Architecture (LinuxManagerService, AIDL, SystemServer integration)
2. R2: AVF / crosvm / KVM Non-Protected Debian ARM64 Guest Setup & Storage Encryption
3. R3: Native Touch Terminal App Engine with Custom InputConnection (IME注音/倉頡/拼音) & 3 Touch Modes
4. R4: Seamless Linux Wayland GUI Window Forwarding mapped to Android Tasks/Recents
5. R5: Hardware Portals (Camera, Mic, GPS via XDG Portal + AppOps), Virtiofs File Sharing, SELinux Policies & Guest A/B Base Image Rollback OTA.

Please begin Phase 1 bring-up and task decomposition immediately based on `aosp_linux_system_architecture_plan.md`. Maintain your `progress.md`, `plan.md`, and `BRIEFING.md` under `.agents/orchestrator/`.

Report back when all milestones are complete and you claim victory.

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


