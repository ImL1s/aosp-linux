# Project: AOSP Dual-OS System Architecture & Remediation

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | R1: Java Syntax & Compilation Closure | Fix syntax errors in `LinuxAppProxyActivity.java` & clean compilation | M1 | ORIGINAL_REQUEST R1 |
| 2 | R2: Pure Binder IPC Window Bridge | Replace reflection with `ILinuxWindowBridge.aidl` Binder IPC & Surface lifecycle | M2 | ORIGINAL_REQUEST R2 |
| 3 | R3: Single-Secret HMAC Key Agreement | 32-byte shared secret agreement between Host Java, C++ daemon & Guest agent | M3 | ORIGINAL_REQUEST R3 |
| 4 | R3: Guest Startup Handshake Initiator | Guest connects to Host (CID 2, Port 5000) with token + HMAC signature to transition VM to `RUNNING` | M3 | ORIGINAL_REQUEST R3 |
| 5 | R3: ARM64 Cargo Build Cleanliness | `cargo check --target aarch64-unknown-linux-gnu` passes with zero errors | M3 | ORIGINAL_REQUEST R3 |
| 6 | R4: Functional Permission Decision Component | `LinuxPermissionActivity` handles `app_id` and permission ops with AppOps integration | M4 | ORIGINAL_REQUEST R4 |
| 7 | Full E2E & Empirical Verification | Run all Java, Rust, C++ and E2E unit and empirical test suites | M5 | Acceptance Criteria |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: Java Syntax & Compilation Closure | Fix duplicate unclosed method in `LinuxAppProxyActivity.java` and ensure full Java & AIDL compilation closure | none | DONE |
| 2 | M2: Pure Binder IPC Window Bridge | Replace reflection in `LinuxAppProxyActivity.java` with canonical `ILinuxWindowBridge.aidl` Binder IPC, implementing `ILinuxWindowBridge.Stub` in `LinuxWindowBridgeService` | M1 | DONE |
| 3 | M3: Single-Secret HMAC Agreement & Handshake | Single 32-byte secret across Java/C++/Guest, Host C++ listening on AF_VSOCK 5000, Guest initiating handshake (CID 2, Port 5000) -> RUNNING VM state | none | DONE |
| 4 | M4: Permission Decision & AppOps Integration | Implement `LinuxPermissionActivity` handling `app_id` & permission op requests linked to `LinuxPortalService` and AppOps | M1 | DONE |
| 5 | M5: E2E Verification & Forensic Integrity Audit | Execute full build checks, ARM64 Rust check, unit/empirical test suites, and forensic integrity audit | M1, M2, M3, M4 | DONE |

## Interface Contracts
### App (`LinuxAppProxyActivity`) ↔ System Server (`LinuxWindowBridgeService`)
- Interface: `android.system.linux.ILinuxWindowBridge`
- Service Name: `"linux_window_bridge"` (published via `ServiceManager`)
- Methods:
  - `onSurfaceCreated(int surfaceId, in Surface surface)`
  - `onSurfaceChanged(int surfaceId, int width, int height)`
  - `onSurfaceDestroyed(int surfaceId)`

### Host Java (`LinuxManagerService`) ↔ Host C++ Daemon (`system/linux_bridge`)
- Interface: Unix Domain Socket (`/dev/socket/linux_bridge`)
- Protocol:
  - `CMD_VM_START` (0x0001): Payload contains 64 bytes (32-byte `token` + 32-byte `secret`).
  - `CMD_HANDSHAKE_COMPLETE` (0x0003): Sent by Host C++ to Java when Guest completes AF_VSOCK 5000 handshake, transitioning Java state to `STATE_RUNNING`.

### Host C++ Daemon ↔ Guest Agent (`guest/bridge-agent`)
- Channel: AF_VSOCK socket connection
- Host Server: Binds to AF_VSOCK port 5000 listening.
- Guest Initiator: Connects to `CID_HOST=2` port 5000 upon boot.
- Payload: 64 bytes (`32-byte token` + `32-byte RFC 2104 HMAC-SHA256 signature`).
- Shared Secret: Passed via kernel command line `android_bridge.token=<64-char hex string of secret>`.

### Permission Activity ↔ System Server (`LinuxPortalService`)
- Interface: `android.system.linux.ILinuxPortalService`
- Integration: `LinuxPermissionActivity` parses `app_id` and `op` extras, prompts user, and updates permission state via `LinuxPortalService.setAppOp(String appId, int op, int mode)`.

## Code Layout
- Java Application: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/`
- Java System Services: `frameworks/base/services/core/java/com/android/server/linux/`
- AIDL Interfaces: `frameworks/base/core/java/android/system/linux/`
- Host C++ Bridge Daemon: `system/linux_bridge/`
- Guest VM Scripts: `guest/scripts/`
- Guest Agent Rust Crate: `guest/bridge-agent/`
- Verification & Test Scripts: `scripts/`, `tests/`
