# Project: AOSP Dual-OS Production Remediation

## Architecture
- **Framework Services**: `LinuxManagerService`, `LinuxBridgeService`, `LinuxWindowBridgeService`, `LinuxPortalService`, `LinuxStorageProvider`.
- **Native Host Daemons & Scripts**: `system/linux_bridge` (`linux_bridge` daemon, `socket_server.cpp`), `guest/scripts/launch_vm.sh`, `crosvm` / AVF AIDL.
- **Guest Agent**: `guest/bridge-agent` (Ports 5000, 5001, 5002, authentication, PTY dispatch, Wayland proxy, Portal RPCs).
- **Android Terminal App & Windowing**: `LinuxTerminal` (`TerminalView`, `VsockTerminalClient`, `LinuxAppProxyActivity`).
- **E2E Test Suite**: `tests/e2e/` (`runner.py`, real IPC & socket checks, `.github/workflows/ci.yml`).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | R1: Real AVF VM Launch | Replace simulated VM state transitions in LinuxManagerService & native daemon with REAL calls to AVF VirtualizationService AIDL / crosvm binary; integrate launch_vm.sh properly without simulated fallbacks | M1 | survey |
| 2 | R2: Production Guest Agent Loop | In guest/bridge-agent, implement active multi-threaded server dispatch loop (Ports 5000, 5001, 5002); remove hardcoded secrets, abort on auth failure, dispatch real PTY, Wayland, Portal RPCs | M2 | survey |
| 3 | R3: Real Vsock Socket Connect & Session ID | Fix VsockTerminalClient.java to invoke real AF_VSOCK connect(guestCid, 5001) syscall; replace hardcoded session ID "0123456789abcdef" in TerminalView with dynamic IDs issued by LinuxManagerService | M3 | survey |
| 4 | R4: Real Wayland dma-buf & SurfaceControl Binding | In LinuxWindowBridgeService, implement real HardwareBuffer/dma-buf import & SurfaceControl Transaction Commit; bind Linux GUI window frames to Android TaskManager in LinuxAppProxyActivity | M4 | survey |
| 5 | R5: Real System Hardware Portals | Replace in-memory portal models in LinuxPortalService with REAL system calls to CameraManager/Camera2, AudioRecord, LocationManager, AppOpsManager; link SAF provider dynamically to Guest virtiofs & LUKS2 mount lifecycle | M5 | survey |
| 6 | R6: Clean & Honest E2E Test Suite | Eliminate fake passes, hardcoded mock responses, static JSON readouts in CI; make test runner execute REAL IPC, socket, and system checks | M6 | survey |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Real AVF VM Launch (R1) | LinuxManagerService, linux_bridge socket_server.cpp, launch_vm.sh, crosvm integration | none | DONE |
| 2 | Production Guest Agent Loop (R2) | guest/bridge-agent (main.rs, auth.rs, vsock.rs), multi-threaded ports 5000/5001/5002, auth abort | M1 | DONE |
| 3 | Real Vsock Socket Connect & Session ID (R3) | VsockTerminalClient.java, TerminalView.java, LinuxManagerService.java session ID generation & AF_VSOCK connect | M1, M2 | DONE |
| 4 | Real Wayland dma-buf & SurfaceControl Binding (R4) | LinuxWindowBridgeService.java, LinuxAppProxyActivity.java, wayland_buffer_sharing.cpp | M1, M2 | DONE |
| 5 | Real System Hardware Portals (R5) | LinuxPortalService.java, LinuxStorageProvider.java, AppOpsManager, Camera, Audio, Location, LUKS2/virtiofs | M1, M2 | DONE |
| 6 | Clean & Honest E2E Test Suite (R6) | .github/workflows/ci.yml, tests/e2e/ (runner.py, mock_env.py, test_m*.py real socket/IPC checks) | M1, M2, M3, M4, M5 | IN_PROGRESS |

## Interface Contracts

### LinuxManagerService ↔ linux_bridge (socket_server.cpp)
- Socket: `/dev/socket/linux_bridge` (LocalSocket)
- Packets: `CMD_VM_START` (0x0001), `CMD_VM_STOP` (0x0002), `CMD_HANDSHAKE_COMPLETE` (0x0003)

### LinuxManagerService ↔ TerminalView
- AIDL: `ILinuxManager.createTerminalSession(width, height, callback)` -> returns 16-byte hex session ID string

### VsockTerminalClient ↔ bridge-agent
- Protocol: Vsock AF_VSOCK (`cid=guestCid`, `port=5001`)
- Framing: `VsockPtyFramer` (16-byte session ID + command/payload)

### LinuxWindowBridgeService ↔ Wayland Proxy / SurfaceControl
- Protocol: HardwareBuffer / dma-buf handle passing over local socket or binder
- SurfaceControl Transaction: `setBuffer`, `setVisibility`, `apply()`

### LinuxPortalService ↔ Guest Agent
- Protocol: Vsock AF_VSOCK (`cid=guestCid`, `port=5000` Control/Portal)
- System services: `AppOpsManager` (`noteOpNoThrow`), `CameraManager`, `AudioRecord`, `LocationManager`

## Code Layout
- `frameworks/base/services/core/java/com/android/server/linux/`
  - `LinuxManagerService.java`
  - `LinuxBridgeService.java`
  - `LinuxWindowBridgeService.java`
  - `LinuxPortalService.java`
  - `storage/LinuxStorageProvider.java`
- `system/linux_bridge/`
  - `socket_server.cpp`
  - `wayland_buffer_sharing.cpp`
- `guest/`
  - `scripts/launch_vm.sh`
  - `bridge-agent/` (`main.rs`, `auth.rs`, `vsock.rs`, `pty.rs`, `wayland.rs`, `portal.rs`)
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/`
  - `TerminalView.java`
  - `LinuxAppProxyActivity.java`
  - `net/VsockTerminalClient.java`
- `tests/`
  - `e2e/`
  - `e2e_report.json`
- `.github/workflows/ci.yml`
