# Project Remediation Plan — AOSP Dual-OS

## High-Level Plan

### Phase 0: Survey & Codebase Assessment
- Dispatch 3 parallel Explorers to investigate:
  1. Java app/framework code (`LinuxAppProxyActivity.java`, `LinuxPermissionActivity.java`, `ILinuxWindowBridge.aidl`, `LinuxWindowBridgeService.java`, AppOps).
  2. HMAC key agreement & VSOCK handshake (`LinuxManagerService`, C++ host daemon, Rust guest agent, kernel cmdline parsing, HMAC-SHA256 CID 2 port 5000 initiator).
  3. Build configuration & test verification suite (`cargo check`, Java build setup, unit tests, empirical tests).

### Phase 1: Milestone Execution & Verification
- **Milestone 1 (R1)**: Java Syntax & Compilation Closure
  - Fix syntax error in `LinuxAppProxyActivity.java`.
  - Fix any duplicate/unclosed method declarations in Java files.
  - Verify clean compilation of AIDL, system server, and app activities.
- **Milestone 2 (R2)**: Pure Binder IPC Window Bridge
  - Remove reflection access `Class.forName("com.android.server.linux.LinuxWindowBridgeService")`.
  - Wire `ILinuxWindowBridge.aidl` Binder IPC to `SurfaceView`/`Surface` creation, change, and destruction lifecycle.
- **Milestone 3 (R3)**: Single-Secret HMAC Key Agreement & Startup Initiator
  - Single 32-byte secret agreement between Host Java, C++ daemon, and Guest agent.
  - Propagate `android_bridge.token=<hex_secret>` via cmdline.
  - Host C++ listening on AF_VSOCK port 5000.
  - Guest agent initiates boot handshake to Host (CID 2, Port 5000) with token + HMAC-SHA256 signature, transitioning Host VM state to `RUNNING`.
- **Milestone 4 (R4)**: Functional Permission Decision Component
  - Implement request handling & AppOps integration in `LinuxPermissionActivity` for `app_id` and permission op requests.
- **Milestone 5**: Comprehensive E2E Verification & Audit
  - ARM64 Rust check (`cargo check --target aarch64-unknown-linux-gnu`).
  - Java compilation validation.
  - Run all unit and empirical tests.
  - Forensic integrity audit.
