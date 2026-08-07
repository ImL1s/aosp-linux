# AOSP Dual-OS End-to-End Test Infrastructure Specification (`TEST_INFRA.md`)

## Executive Summary
This document defines the E2E Test Infrastructure for the AOSP Dual-OS Project ("一個 AOSP 產品，兩個隔離的執行環境，一個統一的使用者體驗"). The infrastructure provides full automated test coverage for all 37 features across all 5 project milestones (M1–M5).

Testing follows a strict **4-Tier Hierarchy**:
- **Tier 1: Per-Feature Functional Coverage** (185 tests: 37 features × 5 tests each)
- **Tier 2: Boundary, Corner Case & Negative Validation** (185 tests: 37 features × 5 tests each)
- **Tier 3: Cross-Feature Integration Pairwise Matrix** (37 pairwise interaction tests: T3-PAIR-01..T3-PAIR-37)
- **Tier 4: Real-World End-to-End Application Scenarios** (18 multi-step application scenarios)

---

## 1. Test Environment Architecture & Mocking Strategy

The E2E test framework operates in a dual mode:
1. **Mock Environment**: High-speed, isolated Python-based simulated environment for hardware, vsock IPC, systemd guest agents, Sommelier Wayland proxy, XDG portals, and SELinux enforcement.
2. **Target Device Environment**: Execution on physical or emulated ARM64 Android 15/16 devices over ADB.

```
+-----------------------------------------------------------------------------------+
| E2E Test Runner (`tests/e2e/runner.py`)                                           |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | Test Engine: Discovery, Execution, Assertion Verification, Result Formatting|  |
|  +-----------------------------------------------------------------------------+  |
|                                       |                                           |
|       +-------------------------------+-------------------------------+           |
|       |                                                               |           |
|       v                                                               v           |
|  [Mock Architecture Environment]                             [Target Device Environment] |
|  - MockVsockBridge (Ports 5000, 5001, 5002)                   - ADB Host Execution        |
|  - MockSystemServer & LinuxManagerService                     - Native Terminal Test App  |
|  - MockSommelier & virtio-gpu Allocator                       - crosvm / KVM Guest Shell  |
|  - MockXdgPortal & AppOps Policy Engine                       - SELinux Audit Log Inspector|
+-----------------------------------------------------------------------------------+
```

---

## 2. Feature Inventory & Coverage Mapping (Tiers 1 & 2)

| # | Feature ID | Feature Name | Milestone | Tier 1 Tests (5) | Tier 2 Tests (5) |
|---|------------|--------------|-----------|------------------|------------------|
| 1 | F-R1-001 | Framework API Namespace | M1 | T1-01..T1-05 | T2-01..T2-05 |
| 2 | F-R1-002 | Framework AIDL Interfaces | M1 | T1-06..T1-10 | T2-06..T2-10 |
| 3 | F-R1-003 | SystemServer Integration | M1 | T1-11..T1-15 | T2-11..T2-15 |
| 4 | F-R1-004 | Daemon Process Isolation | M1 | T1-16..T1-20 | T2-16..T2-20 |
| 5 | F-R1-005 | State Machine Lifecycle | M1 | T1-21..T1-25 | T2-21..T2-25 |
| 6 | F-R2-001 | Non-Protected Debian VM | M2 | T1-26..T1-30 | T2-26..T2-30 |
| 7 | F-R2-002 | 4-Layer Storage Image Layout | M2 | T1-31..T1-35 | T2-31..T2-35 |
| 8 | F-R2-003 | LUKS2 CE Storage Encryption | M2 | T1-36..T1-40 | T2-36..T2-40 |
| 9 | F-R2-004 | Vsock 3-Port Allocation | M2 | T1-41..T1-45 | T2-41..T2-45 |
| 10 | F-R2-005 | HMAC-SHA256 Auth Handshake | M2 | T1-46..T1-50 | T2-46..T2-50 |
| 11 | F-R3-001 | Native Surface Canvas Renderer | M3 | T1-51..T1-55 | T2-51..T2-55 |
| 12 | F-R3-002 | libvterm Parser Integration | M3 | T1-56..T1-60 | T2-56..T2-60 |
| 13 | F-R3-003 | TerminalInputConnection | M3 | T1-61..T1-65 | T2-61..T2-65 |
| 14 | F-R3-004 | Multi-stage CJK IME Commit | M3 | T1-66..T1-70 | T2-66..T2-70 |
| 15 | F-R3-005 | Touch Modes State Machine | M3 | T1-71..T1-75 | T2-71..T2-75 |
| 16 | F-R3-006 | SGR Mouse Protocol Generator | M3 | T1-76..T1-80 | T2-76..T2-80 |
| 17 | F-R3-007 | Vsock Port 5001 PTY Framing | M3 | T1-81..T1-85 | T2-81..T2-85 |
| 18 | F-R4-001 | Wayland Window Forwarding | M4 | T1-86..T1-90 | T2-86..T2-90 |
| 19 | F-R4-002 | virtio-gpu dma-buf Sharing | M4 | T1-91..T1-95 | T2-91..T2-95 |
| 20 | F-R4-003 | LinuxAppProxyActivity Task ID | M4 | T1-96..T1-100 | T2-96..T2-100 |
| 21 | F-R4-004 | Freeform Multi-Window Resize | M4 | T1-101..T1-105 | T2-101..T2-105 |
| 22 | F-R4-005 | .desktop Inotify Monitor Daemon | M4 | T1-106..T1-110 | T2-106..T2-110 |
| 23 | F-R4-006 | Launcher3 Synthetic Shortcuts | M4 | T1-111..T1-115 | T2-111..T2-115 |
| 24 | F-R5-001 | XDG Portal Camera Bridge | M5 | T1-116..T1-120 | T2-116..T2-120 |
| 25 | F-R5-002 | XDG Portal Microphone Bridge | M5 | T1-121..T1-125 | T2-121..T2-125 |
| 26 | F-R5-003 | XDG Portal Location Bridge | M5 | T1-126..T1-130 | T2-126..T2-130 |
| 27 | F-R5-004 | AppOps Permission Prompt | M5 | T1-131..T1-135 | T2-131..T2-135 |
| 28 | F-R5-005 | virtio-snd Audio Mapping | M5 | T1-136..T1-140 | T2-136..T2-140 |
| 29 | F-R5-006 | AudioFocus Policy Handler | M5 | T1-141..T1-145 | T2-141..T2-145 |
| 30 | F-R5-007 | virtiofs Bi-directional Sharing | M5 | T1-146..T1-150 | T2-146..T2-150 |
| 31 | F-R5-008 | LinuxStorageProvider SAF Provider | M5 | T1-151..T1-155 | T2-151..T2-155 |
| 32 | F-R5-009 | SELinux Domain Policy Rules | M5 | T1-156..T1-160 | T2-156..T2-160 |
| 33 | F-R5-010 | SELinux neverallow Rules | M5 | T1-161..T1-165 | T2-161..T2-165 |
| 34 | F-R5-011 | CTS / VTS Compatibility | M5 | T1-166..T1-170 | T2-166..T2-170 |
| 35 | F-R5-012 | EROFS Base Image A/B Layout | M5 | T1-171..T1-175 | T2-171..T2-175 |
| 36 | F-R5-013 | AVB Key Signature Validation | M5 | T1-176..T1-180 | T2-176..T2-180 |
| 37 | F-R5-014 | Boot Watchdog Rollback Engine | M5 | T1-181..T1-185 | T2-181..T2-185 |

---

## 3. Detailed Per-Feature Test Catalog (Tiers 1 & 2)

### F-R1-001: Framework API Namespace
- **Tier 1 Functional Tests**:
  - `T1-01`: Verify class loading of `android.system.linux.LinuxManager`.
  - `T1-02`: Retrieve `LinuxManager` system service via `Context.getSystemService("linux")`.
  - `T1-03`: Verify `LinuxAppInfo` instantiation and getter field defaults.
  - `T1-04`: Register status callback listener via `LinuxManager.registerStatusCallback()`.
  - `T1-05`: Query initial guest status returning expected state enumeration.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-01`: Reject null callback registration with `IllegalArgumentException`.
  - `T2-02`: Enforce `MANAGE_LINUX_ENVIRONMENT` permission on public API calls.
  - `T2-03`: Handle unparceling corrupted parcel data gracefully.
  - `T2-04`: Duplicate registration of same callback instance returns success without duplicate triggers.
  - `T2-05`: Recover state when underlying binder service dies (`DeadObjectException`).

### F-R1-002: Framework AIDL Interfaces
- **Tier 1 Functional Tests**:
  - `T1-06`: Inter-process invocation of `ILinuxManager.startVm()`.
  - `T1-07`: Callback dispatch on `ILinuxStatusCallback.onStatusChanged()`.
  - `T1-08`: Terminal byte stream delivery via `ILinuxTerminalCallback.onDataReceived()`.
  - `T1-09`: IPC status querying via `ILinuxManager.getVmStatus()`.
  - `T1-10`: Registration and unregistration of listeners across AIDL boundaries.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-06`: Transaction timeout handling on hanging AIDL calls.
  - `T2-07`: Max payload limit enforcement (1MB parcel cap) on AIDL byte transfer.
  - `T2-08`: Concurrent multi-threaded AIDL calls lock safety.
  - `T2-09`: Handling dead binder remote process during callback dispatch.
  - `T2-10`: Invalid UID caller rejection over AIDL transaction interface.

### F-R1-003: SystemServer Integration
- **Tier 1 Functional Tests**:
  - `T1-11`: Service published in `ServiceManager` under name `"linux"`.
  - `T1-12`: `LinuxManagerService` lifecycle init during `PHASE_THIRD_PARTY_APPS_CAN_START`.
  - `T1-13`: `LinuxManagerInternal` local service registration.
  - `T1-14`: System boot completed broadcast handler initializes VM daemon connection.
  - `T1-15`: User switching handler triggers storage key rotation.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-11`: SystemServer crash recovery and service re-registration.
  - `T2-12`: Handshake delay during early boot phase non-blocking boot process.
  - `T2-13`: System shut-down hook clean VM termination.
  - `T2-14`: Low memory killer pressure signal handling by SystemServer service.
  - `T2-15`: Deny unauthorized access from background untrusted UIDs.

### F-R1-004: Daemon Process Isolation
- **Tier 1 Functional Tests**:
  - `T1-16`: `linux_bridge` process starts under UID `system` / GID `system`.
  - `T1-17`: Control vsock socket bound to port 5000.
  - `T1-18`: Unix domain socket IPC connection established with SystemServer.
  - `T1-19`: Ping/pong heartbeats active between daemon and host framework.
  - `T1-20`: Process priority and oom_score_adj correctly set.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-16`: Automatic process respawn watchdog on daemon `SIGKILL`.
  - `T2-17`: Reject malformed vsock binary frames at daemon parser boundary.
  - `T2-18`: Prevent socket buffer overflow under high byte-rate flood.
  - `T2-19`: Restrict daemon privileges to block non-vsock network access.
  - `T2-20`: Memory leak validation under continuous 24h ping stress.

### F-R1-005: State Machine Lifecycle
- **Tier 1 Functional Tests**:
  - `T1-21`: State transition `OFF -> STARTING -> RUNNING`.
  - `T1-22`: State transition `RUNNING -> SUSPENDED -> RUNNING`.
  - `T1-23`: State transition `RUNNING -> STOPPING -> OFF`.
  - `T1-24`: State transition `RUNNING -> ERROR` on guest crash signal.
  - `T1-25`: Status broadcast listener notification on every state transition.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-21`: Invalid transition rejection (e.g. `OFF -> SUSPENDED`).
  - `T2-22`: Rapid start/stop call race condition prevention.
  - `T2-23`: VM start timeout fallback to `ERROR` state.
  - `T2-24`: Force stop invocation during `STARTING` state.
  - `T2-25`: Re-initialization from `ERROR` state after corrective cleanup.

### F-R2-001: Non-Protected Debian VM
- **Tier 1 Functional Tests**:
  - `T1-26`: Launch crosvm instance with non-protected guest config.
  - `T1-27`: Guest kernel boot verification (`Debian 12 ARM64 6.6+`).
  - `T1-28`: Guest `systemd` PID 1 init completion.
  - `T1-29`: `android-bridge-agent` service active in guest.
  - `T1-30`: Virtual CPU & RAM allocation matching requested configuration.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-26`: Host KVM kernel module missing error handling.
  - `T2-27`: Insufficient device RAM error handling prior to launch.
  - `T2-28`: Guest kernel panic detection and host event escalation.
  - `T2-29`: Unexpected guest poweroff clean socket drop.
  - `T2-30`: Virtual CPU stall/hang detection mechanism.

### F-R2-002: 4-Layer Storage Image Layout
- **Tier 1 Functional Tests**:
  - `T1-31`: Mounting read-only `base_rootfs.img` on `/`.
  - `T1-32`: Overlayfs writable layer mounted over `/etc`, `/var`, `/usr`.
  - `T1-33`: LUKS2 decrypted `user_home.img` mounted on `/home/user`.
  - `T1-34`: VM state snapshot created at `/data/misc/linux/vm_state.snapshot`.
  - `T1-35`: Overlayfs diff persistence after reboot.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-31`: Prevent write operations to immutable `base_rootfs.img`.
  - `T2-32`: Storage full error handling on overlayfs partition.
  - `T2-33`: Corrupted overlayfs image automatic recovery/wipe.
  - `T2-34`: Snapshot restoration failure fallback to clean boot.
  - `T2-35`: Multi-process concurrent image mount lock contention prevention.

### F-R2-003: LUKS2 CE Storage Encryption
- **Tier 1 Functional Tests**:
  - `T1-36`: Derive 256-bit encryption key from Android CE Keymaster / KeyMint.
  - `T1-37`: `cryptsetup` open `user_home.img` using CE key on user unlock.
  - `T1-38`: Mount `/dev/mapper/user_home_decrypted` to `/home/user`.
  - `T1-39`: Unmount & `cryptsetup close` on Android user lock.
  - `T1-40`: AES-256-XTS cipher integrity verification.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-36`: Fail decryption with incorrect CE key material.
  - `T2-37`: Lock screen event forces immediate key wipe from RAM.
  - `T2-38`: Corrupted LUKS2 header recovery/format fallback prompt.
  - `T2-39`: Direct read attempt on raw `user_home.img` yields cipher text.
  - `T2-40`: Re-keying procedure on Android lock screen credential change.

### F-R2-004: Vsock 3-Port Allocation
- **Tier 1 Functional Tests**:
  - `T1-41`: Port 5000 bound for Control RPC protocol.
  - `T1-42`: Port 5001 bound for PTY terminal stream.
  - `T1-43`: Port 5002 bound for Wayland GUI protocol.
  - `T1-44`: Bi-directional byte transmission across all 3 ports.
  - `T1-45`: Independent socket close on individual port teardown.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-41`: Reject unauthorized port connection attempts.
  - `T2-42`: Port collision handling when port is already bound.
  - `T2-43`: Vsock CID (Context ID) spoofing rejection.
  - `T2-44`: Socket buffer exhaustion under high throughput per port.
  - `T2-45`: Unexpected guest reboot vsock socket clean reconnect logic.

### F-R2-005: HMAC-SHA256 Auth Handshake
- **Tier 1 Functional Tests**:
  - `T1-46`: Host generates single-use 256-bit random auth token.
  - `T1-47`: Token passed to guest via virtio seed / kernel cmdline.
  - `T1-48`: Guest computes HMAC-SHA256 signature and returns challenge response.
  - `T1-49`: Host verifies challenge response before opening ports 5001/5002.
  - `T1-50`: Session establishment state marked authenticated.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-46`: Reject connection with invalid HMAC signature.
  - `T2-47`: Reject replayed handshake tokens (single-use enforcement).
  - `T2-48`: Handshake timeout handling (5-second window expiration).
  - `T2-49`: Key mismatch error logging & alert generation.
  - `T2-50`: Re-authentication protocol after guest resume from suspend.

### F-R3-001: Native Surface Canvas Renderer
- **Tier 1 Functional Tests**:
  - `T1-51`: `SurfaceView` native window creation in `TerminalActivity`.
  - `T1-52`: Canvas rendering pipeline update at 60 FPS.
  - `T1-53`: Terminal font bitmap glyph rendering accuracy.
  - `T1-54`: ANSI color palette rendering (16/256/truecolor).
  - `T1-55`: Dynamic terminal window dimension recalculation on surface changed.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-51`: Handle rapid surface rotation without frame drop/tearing.
  - `T2-52`: Memory reclamation on view detachment.
  - `T2-53`: High resolution (4K/8K display) rendering performance budget check.
  - `T2-54`: Invalid surface state handling when backgrounded.
  - `T2-55`: Glyph rasterization fallback on unsupported Unicode symbols.

### F-R3-002: libvterm Parser Integration
- **Tier 1 Functional Tests**:
  - `T1-56`: Parse standard ASCII stream into screen matrix.
  - `T1-57`: Interpret ANSI escape sequences (cursor movement, colors, clear).
  - `T1-58`: Process VT100 / xterm control codes.
  - `T1-59`: Maintain scrollback buffer (up to 10,000 lines).
  - `T1-60`: Screen resize recalculation via `vterm_set_size()`.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-56`: Malformed escape sequence parser resilience (no crash).
  - `T2-57`: Overflow handling on massive binary log dump.
  - `T2-58`: Alternate screen buffer switching (e.g. Vim exit restoration).
  - `T2-59`: Zero-width & wide UTF-8 character alignment handling.
  - `T2-60`: UTF-8 partial multi-byte sequence split across packet boundaries.

### F-R3-003: TerminalInputConnection
- **Tier 1 Functional Tests**:
  - `T1-61`: Instantiate `TerminalInputConnection extends BaseInputConnection`.
  - `T1-62`: Key event translation (ASCII, Enter, Backspace, Arrow keys).
  - `T1-63`: Modifier state handling (Ctrl, Alt, Shift).
  - `T1-64`: Soft keyboard commit text dispatch (`commitText()`).
  - `T1-65`: Selection & cursor position reporting to Android IME.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-61`: Rapid key press storm handling without character drop.
  - `T2-62`: Special key combination mapping (Ctrl+C, Ctrl+Z, Ctrl+D).
  - `T2-63`: Hardware keyboard physical key event passthrough.
  - `T2-64`: Dead key composition support.
  - `T2-65`: Input connection focus loss clean buffer reset.

### F-R3-004: Multi-stage CJK IME Commit
- **Tier 1 Functional Tests**:
  - `T1-66`: Inline composing text display for Zhuyin (注音).
  - `T1-67`: Inline composing text display for Cangjie (倉頡) & Pinyin (拼音).
  - `T1-68`: Candidates selection panel display & navigation.
  - `T1-69`: UTF-8 multi-byte string commit to pty stream.
  - `T1-70`: Backspace deletion within composing text window.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-66`: Cancel inline composition on focus change or ESC key.
  - `T2-67`: Handle rapid IME candidate selection without buffer corrupt.
  - `T2-68`: Surround text query handling near line boundaries.
  - `T2-69`: Extremely long composing text buffer (>256 chars) truncation/handling.
  - `T2-70`: Third-party keyboard (Gboard, SwiftKey) compatibility check.

### F-R3-005: Touch Modes State Machine
- **Tier 1 Functional Tests**:
  - `T1-71`: Active mode set to `SHELL_MODE` (keyboard priority + touch scroll).
  - `T1-72`: Switch to `TUI_MOUSE_MODE` (direct tap/drag mapped to mouse events).
  - `T1-73`: Switch to `TOUCHPAD_MODE` (virtual trackpad cursor overlay).
  - `T1-74`: Mode transition UI indicator display.
  - `T1-75`: Persistence of touch mode preference per session.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-71`: Multi-touch gesture rejection in `SHELL_MODE`.
  - `T2-72`: Pinch-to-zoom font scaling gesture in `SHELL_MODE`.
  - `T2-73`: Palm rejection filtering in `TOUCHPAD_MODE`.
  - `T2-74`: Fast touch mode toggling race condition safety.
  - `T2-75`: State machine recovery on app pause/resume.

### F-R3-006: SGR Mouse Protocol Generator
- **Tier 1 Functional Tests**:
  - `T1-76`: Touch down translated to `\e[<0;X;Y;M` (SGR button 0 press).
  - `T1-77`: Touch drag translated to `\e[<32;X;Y;M` (SGR motion).
  - `T1-78`: Touch up translated to `\e[<0;X;Y;m` (SGR button 0 release).
  - `T1-79`: Scroll wheel gesture translated to SGR buttons 64/65.
  - `T1-80`: 1-based coordinate translation matching terminal grid columns/rows.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-76`: Out-of-bounds coordinate clamping (X > cols, Y > rows).
  - `T2-77`: Sub-pixel touch motion delta thresholding (debounce).
  - `T2-78`: Right-click long-press translation to SGR button 2.
  - `T2-79`: Terminal resize dynamic grid coordinate recalculation.
  - `T2-80`: Disable SGR mouse sequence generation when guest app disables mouse tracking.

### F-R3-007: Vsock Port 5001 PTY Framing
- **Tier 1 Functional Tests**:
  - `T1-81`: Frame header serialization: `[SessionID (16B)][Type (1B)][Len (4B)]`.
  - `T1-82`: Frame payload parser extraction of `DATA` packets.
  - `T1-83`: Terminal window resize frame (`RESIZE` type with cols/rows).
  - `T1-84`: Keepalive `PING`/`PONG` frame roundtrip over port 5001.
  - `T1-85`: End-of-Stream (`EOS`) packet handling on shell logout.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-81`: Reject invalid magic / unknown frame type header byte.
  - `T2-82`: Handle fragmented TCP/vsock packet payload reassembly.
  - `T2-83`: Reconstruct partial frame headers split across socket reads.
  - `T2-84`: Session ID mismatch packet drop.
  - `T2-85`: Payload length sanity check (reject length > 64KB per frame).

### F-R4-001: Wayland Window Forwarding
- **Tier 1 Functional Tests**:
  - `T1-86`: Connect guest `Sommelier` Wayland proxy to host `LinuxWindowBridgeService`.
  - `T1-87`: Forward Wayland `wl_surface.commit` events over vsock port 5002.
  - `T1-88`: Render guest GUI app window inside `LinuxAppProxyActivity`.
  - `T1-89`: Dispatch touch/mouse input events back to Sommelier proxy.
  - `T1-90`: Wayland surface destroy event cleans up host Activity.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-86`: Window forwarding recovery on guest Sommelier crash.
  - `T2-87`: Multi-window Wayland surface mapping isolation.
  - `T2-88`: Protocol version mismatch negotiation fallback.
  - `T2-89`: High frame-rate Wayland buffer delivery buffer drop check.
  - `T2-90`: Out-of-order Wayland protocol packet sequence handling.

### F-R4-002: virtio-gpu dma-buf Sharing
- **Tier 1 Functional Tests**:
  - `T1-91`: Guest allocates graphic buffer via `virtio-gpu`.
  - `T1-92`: Export dma-buf file descriptor across hypervisor boundary.
  - `T1-93`: Import dma-buf to host Android `HardwareBuffer`.
  - `T1-94`: Bind `HardwareBuffer` directly to `SurfaceControl`.
  - `T1-95`: Zero-copy frame presentation latency < 16ms (60 FPS).
- **Tier 2 Boundary/Negative Tests**:
  - `T2-91`: Invalid dma-buf handle import failure handling.
  - `T2-92`: Hardware graphics memory leak validation under dynamic allocation.
  - `T2-93`: GPU device reset / host surface recreation handling.
  - `T2-94`: Format incompatibility fallback (e.g. RGB vs YUV buffers).
  - `T2-95`: Synchronize GPU fence completion before host display read.

### F-R4-003: LinuxAppProxyActivity Task ID
- **Tier 1 Functional Tests**:
  - `T1-96`: Launch `LinuxAppProxyActivity` with unique Android Task ID per Linux app.
  - `T1-97`: Display Linux app title and icon in Android Recents overview.
  - `T1-98`: Switch between Linux apps and native Android apps via Recents.
  - `T1-99`: Task termination from Recents sends `SIGTERM` to Linux app PID.
  - `T1-100`: Launch multiple instances of Linux apps under distinct Task IDs.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-96`: Reuse existing Task ID when tapping app icon while app is running.
  - `T2-97`: Handle abrupt task kill without leaking guest processes.
  - `T2-98`: Maximum concurrent Linux task limit enforcement.
  - `T2-99`: Retain Task ID state across device display orientation changes.
  - `T2-100`: Clear Task ID state on VM unexpected shutdown.

### F-R4-004: Freeform Multi-Window Resize
- **Tier 1 Functional Tests**:
  - `T1-101`: Support Android freeform window resizing drag handles.
  - `T1-102`: Send Wayland `xdg_toplevel.configure` event on window resize.
  - `T1-103`: Guest app re-renders buffer to match new width/height.
  - `T1-104`: Frame pacing synchronization during live window drag.
  - `T1-105`: Maximize / Minimize window state transitions.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-101`: Enforce minimum window size constraints (e.g. 320x240).
  - `T2-102`: Enforce maximum window size constraints (screen resolution).
  - `T2-103`: Rapid window resizing flood buffer queue stability.
  - `T2-104`: Aspect ratio preservation for fixed-ratio Linux apps.
  - `T2-105`: Display density (DPI) change window buffer scaling.

### F-R4-005: .desktop Inotify Monitor Daemon
- **Tier 1 Functional Tests**:
  - `T1-106`: `portal-agent` inotify watch registered on `/usr/share/applications/`.
  - `T1-107`: Detect creation of new `.desktop` files (e.g., `apt install gimp`).
  - `T1-108`: Parse `.desktop` metadata (Name, Icon, Exec, Categories).
  - `T1-109`: Transmit app metadata payload to Host over vsock port 5000.
  - `T1-110`: Detect modification or deletion of `.desktop` files.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-106`: Ignore invalid/malformed `.desktop` syntax files.
  - `T2-107`: Ignore hidden (`NoDisplay=true`) desktop entries.
  - `T2-108`: Inotify event burst throttling (debounce rapid file writes).
  - `T2-109`: Handle missing icon assets (fallback to default Linux app icon).
  - `T2-110`: Subfolder recursive inotify watching (`~/.local/share/applications`).

### F-R4-006: Launcher3 Synthetic Shortcuts
- **Tier 1 Functional Tests**:
  - `T1-111`: Host receives `.desktop` app metadata from daemon.
  - `T1-112`: Dynamically generate synthetic shortcut in `Launcher3` app drawer.
  - `T1-113`: Extract and format app icon PNG/SVG for Android launcher icon.
  - `T1-114`: Tapping launcher icon starts `LinuxAppProxyActivity` with app command.
  - `T1-115`: Uninstalling Linux package removes synthetic shortcut from launcher.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-111`: Duplicate shortcut deduplication logic.
  - `T2-112`: Launcher restart persistence of custom Linux shortcuts.
  - `T2-113`: Special character escaping in app titles and exec paths.
  - `T2-114`: Icon conversion fallback for unknown/custom binary icon formats.
  - `T2-115`: Work profile / multi-user shortcut isolation.

### F-R5-001: XDG Portal Camera Bridge
- **Tier 1 Functional Tests**:
  - `T1-116`: Intercept `org.freedesktop.portal.Camera.AccessCamera` in guest.
  - `T1-117`: Forward request to Host `LinuxPortalService`.
  - `T1-118`: Check Android `android.permission.CAMERA` via `AppOpsManager`.
  - `T1-119`: Pipe Host `Camera2` video stream over v4l2loopback / virtio-video.
  - `T1-120`: Frame delivery to Linux app (e.g. Cheese, OBS).
- **Tier 2 Boundary/Negative Tests**:
  - `T2-116`: Return permission denied error to guest when user denies camera prompt.
  - `T2-117`: Release Camera hardware resource when guest app exits.
  - `T2-118`: Concurrent Android app camera usage contention resolution.
  - `T2-119`: Camera resolution / frame rate negotiation mismatch fallback.
  - `T2-120`: Handle device camera hardware disconnection during active stream.

### F-R5-002: XDG Portal Microphone Bridge
- **Tier 1 Functional Tests**:
  - `T1-121`: Intercept `org.freedesktop.portal.Microphone` D-Bus request in guest.
  - `T1-122`: Forward request to Host `LinuxPortalService`.
  - `T1-123`: Check `android.permission.RECORD_AUDIO` via `AppOpsManager`.
  - `T1-124`: Stream Host `AudioRecord` PCM audio into guest PipeWire / ALSA.
  - `T1-125`: Audio sample rate (44.1kHz / 48kHz) conversion.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-121`: Return audio capture failure when mic permission is revoked.
  - `T2-122`: Mute audio stream when Android microphone privacy toggle is enabled.
  - `T2-123`: Audio latency buffer underflow mitigation.
  - `T2-124`: Stop mic recording when Linux app goes into background.
  - `T2-125`: Multi-channel audio stream channel downmixing (stereo to mono).

### F-R5-003: XDG Portal Location Bridge
- **Tier 1 Functional Tests**:
  - `T1-126`: Intercept `org.freedesktop.portal.Location` D-Bus request in guest.
  - `T1-127`: Check `ACCESS_FINE_LOCATION` permission via Host `AppOpsManager`.
  - `T1-128`: Fetch position fix from Host `LocationManager` (GPS / Network).
  - `T1-129`: Format location update into GeoClue D-Bus structure in guest.
  - `T1-130`: Continuous position updates delivered to Linux app (e.g. Marble).
- **Tier 2 Boundary/Negative Tests**:
  - `T2-126`: Return approximate location when coarse location granted.
  - `T2-127`: Location access failure when device GPS is turned off.
  - `T2-128`: Location update frequency throttling to conserve battery.
  - `T2-129`: Location spoofing / mock location filtering.
  - `T2-130`: Unsubscribe location updates when Linux app terminates location session.

### F-R5-004: AppOps Permission Prompt
- **Tier 1 Functional Tests**:
  - `T1-131`: Trigger system permission dialog on host when guest requests portal.
  - `T1-132`: Display requesting Linux app name and requested permission.
  - `T1-133`: Record "Allow" choice in Host `AppOpsManager` database.
  - `T1-134`: Record "Deny" choice and return authorization error to guest.
  - `T1-135`: Support "Allow Only While Using App" dynamic state tracking.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-131`: Permission prompt timeout default rejection (30 sec timeout).
  - `T2-132`: Reject duplicate permission prompts while dialog is visible.
  - `T2-133`: System policy override (e.g., enterprise MDM permission force-deny).
  - `T2-134`: Permission revocation via Android System Settings app ops list.
  - `T2-135`: Prompt display when screen is locked (suppressed until unlocked).

### F-R5-005: virtio-snd Audio Mapping
- **Tier 1 Functional Tests**:
  - `T1-136`: Guest ALSA / PulseAudio outputs audio to `virtio-snd` pci device.
  - `T1-137`: Host `LinuxPortalService` receives audio PCM buffer.
  - `T1-138`: Play audio through Host `AudioTrack` / `AudioService`.
  - `T1-139`: Hardware volume control synchronization (guest volume -> host volume).
  - `T1-140`: Low-latency audio playback (< 20ms buffer delay).
- **Tier 2 Boundary/Negative Tests**:
  - `T2-136`: Audio buffer overflow under heavy CPU load.
  - `T2-137`: Bluetooth headset disconnect / output device switching.
  - `T2-138`: Zero-fill silent frames on audio buffer underrun.
  - `T2-139`: Sample format conversion (INT16 to FLOAT32).
  - `T2-140`: Simultaneous multi-stream audio mixing.

### F-R5-006: AudioFocus Policy Handler
- **Tier 1 Functional Tests**:
  - `T1-141`: Request `AUDIOFOCUS_GAIN` on Host when Linux audio playback starts.
  - `T1-142`: Handle `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` (duck Linux volume).
  - `T1-143`: Handle `AUDIOFOCUS_LOSS_TRANSIENT` (pause Linux audio playback).
  - `T1-144`: Handle `AUDIOFOCUS_LOSS` (stop Linux audio stream).
  - `T1-145`: Restore audio playback on `AUDIOFOCUS_GAIN` notification.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-141`: Incoming phone call triggers immediate audio ducking/mute.
  - `T2-142`: Alarm clock trigger pauses Linux media playback.
  - `T2-143`: Reject audio focus request when backgrounded without foreground service.
  - `T2-144`: Audio focus state recovery after app suspension.
  - `T2-145`: Rapid audio focus toggle stability test.

### F-R5-007: virtiofs Bi-directional Sharing
- **Tier 1 Functional Tests**:
  - `T1-146`: Mount Host directory `/data/media/0/LinuxShared` to Guest `/mnt/shared`.
  - `T1-147`: File created in Host appears immediately in Guest `/mnt/shared`.
  - `T1-148`: File created in Guest appears immediately in Host `/data/media/0/LinuxShared`.
  - `T1-149`: Subdirectory creation and file deletion bi-directional sync.
  - `T1-150`: Zero-copy page cache file read performance.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-146`: Symlink traversal restriction (prevent escaping shared folder root).
  - `T2-147`: File permission bit mapping (Host Android UID vs Guest Linux UID).
  - `T2-148`: Concurrent edit lock conflict resolution on shared files.
  - `T2-149`: Large file transfer (> 4GB) integrity check via SHA256 checksum.
  - `T2-150`: Out of disk space error propagation across virtiofs boundary.

### F-R5-008: LinuxStorageProvider SAF Provider
- **Tier 1 Functional Tests**:
  - `T1-151`: Register `LinuxStorageProvider extends DocumentsProvider`.
  - `T1-152`: Expose Guest `/home/user` in Android Files app system picker.
  - `T1-153`: Browse guest directories via Android SAF framework.
  - `T1-154`: Open, edit, and save guest file using native Android editor.
  - `T1-155`: Copy file from Android local storage into Guest home directory.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-151`: Hide system root `/` directories from SAF provider picker.
  - `T2-152`: Handle guest VM offline state when SAF file picker accessed.
  - `T2-153`: Deny access to locked LUKS2 volume prior to credential unlock.
  - `T2-154`: SAF document notification change trigger on guest file modification.
  - `T2-155`: Enforce read-only SAF flags when guest volume mounted read-only.

### F-R5-009: SELinux Domain Policy Rules
- **Tier 1 Functional Tests**:
  - `T1-156`: `linux_manager.te` domain policy enforcement for SystemServer component.
  - `T1-157`: `linux_bridge.te` domain policy enforcement for native bridge daemon.
  - `T1-158`: `linux_portal.te` domain policy enforcement for XDG portal handler.
  - `T1-159`: Vsock IPC permission rules (`allow linux_bridge self:vsock_socket ...`).
  - `T1-160`: `getattr` / `read` / `write` file permissions for designated storage types.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-156`: Audit log verification: no unhandled `avc: denied` messages during normal operation.
  - `T2-157`: Block `linux_bridge` from accessing unlabelled storage files.
  - `T2-158`: Block `linux_portal` from reading system credential keystore files.
  - `T2-159`: Transition domain correctly when `linux_bridge` executable is spawned.
  - `T2-160`: Enforcing vs Permissive SELinux mode execution check.

### F-R5-010: SELinux neverallow Rules
- **Tier 1 Functional Tests**:
  - `T1-161`: Enforce `neverallow linux_bridge efs_file:file *`.
  - `T1-162`: Enforce `neverallow linux_manager system_file:file write`.
  - `T1-163`: Enforce `neverallow linux_portal device:chr_file raw_io`.
  - `T1-164`: Enforce `neverallow` rules prohibiting direct modem / baseband access.
  - `T1-165`: Policy compilation verification via `secilc` / `checkpolicy`.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-161`: Build failure assertion when violating `neverallow` policy rule.
  - `T2-162`: Prevent unauthorized domain transition to `su` or `init`.
  - `T2-163`: Block raw block device read/write execution from all guest domains.
  - `T2-164`: Verify denial enforcement under active exploit simulation.
  - `T2-165`: Validate neverallow assertions across all target board sepolicy configs.

### F-R5-011: CTS / VTS Compatibility
- **Tier 1 Functional Tests**:
  - `T1-166`: Execute `CtsSELinuxHostTestCases` suite with 0 failures.
  - `T1-167`: Execute `CtsSecurityTestCases` suite with 0 failures.
  - `T1-168`: VTS kernel compliance test validation for AVF guest environment.
  - `T1-169`: Android Framework API compatibility check for public `android.system.linux`.
  - `T1-170`: CTS Verifier manual test suite compatibility.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-166`: Detect CTS regressions on custom AIDL interface modifications.
  - `T2-167`: Ensure system partition modification does not violate Treble boundaries.
  - `T2-168`: Verify GSI (Generic System Image) boot compatibility with Dual-OS.
  - `T2-169`: Verify SELinux policy compliance on userbuild vs userdebug targets.
  - `T2-170`: Performance overhead compliance (< 2% battery drop under idle CTS run).

### F-R5-012: EROFS Base Image A/B Layout
- **Tier 1 Functional Tests**:
  - `T1-171`: Immutable read-only EROFS filesystem layout for `base_a.img` and `base_b.img`.
  - `T1-172`: Active boot slot determination (`slot_a` vs `slot_b`) via boot metadata.
  - `T1-173`: Guest rootfs mount from `/dev/block/by-name/linux_base_a`.
  - `T1-174`: Background OTA image streaming write into inactive slot (`slot_b`).
  - `T1-175`: Active slot flag update after successful OTA installation.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-171`: Block write operations to active EROFS partition.
  - `T2-172`: Handle interrupted OTA download without corrupting active slot.
  - `T2-173`: Compression ratio verification (EROFS vs EXT4 size savings).
  - `T2-174`: Verify read performance throughput on EROFS base image (> 200MB/s).
  - `T2-175`: Fallback slot mount when primary slot image block checksum fails.

### F-R5-013: AVB Key Signature Validation
- **Tier 1 Functional Tests**:
  - `T1-176`: Android Verified Boot (AVB) key chain verification on guest `base.img`.
  - `T1-177`: Validate RSA-4096 / ECDSA signature header against Host trusted root key.
  - `T1-178`: Calculate SHA256 digest of guest image and match vbmeta descriptor.
  - `T1-179`: Successful OTA update authorization on valid key signature.
  - `T1-180`: Report AVB verification state to Host `LinuxManagerService`.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-176`: Reject guest image signed with unauthorized / untrusted private key.
  - `T2-177`: Reject tampered / bit-flipped base image file during boot check.
  - `T2-178`: Handle missing vbmeta header in guest OTA package.
  - `T2-179`: Prevent rollback to older image version when rollback index enforced.
  - `T2-180`: Debug key vs Production key policy enforcement check.

### F-R5-014: Boot Watchdog Rollback Engine
- **Tier 1 Functional Tests**:
  - `T1-181`: Increment boot attempt counter (1 to 3) in bootloader / persistent storage.
  - `T1-182`: Reset boot attempt counter to 0 upon successful guest heartbeat signal.
  - `T1-183`: Trigger boot watchdog timer (60 second boot completion deadline).
  - `T1-184`: Automatic slot rollback (`slot_b -> slot_a`) when boot count exceeds 3.
  - `T1-185`: Emit critical system log & notification on boot watchdog rollback event.
- **Tier 2 Boundary/Negative Tests**:
  - `T2-181`: Guest kernel freeze during boot triggers hardware watchdog reset.
  - `T2-182`: Guest systemd boot loop triggers watchdog rollback after 3 attempts.
  - `T2-183`: Retain user data partition (`user_home.img`) intact during base image rollback.
  - `T2-184`: Mark failed slot as unbootable (`successful_boot=0`).
  - `T2-185`: Manual force-rollback API invocation test.

---

## 4. Tier 3: Cross-Feature Integration Pairwise Matrix

Tier 3 tests evaluate inter-module communication across subsystem boundaries.

| Feature A | Feature B | Integration Test Scenario ID | Description |
|-----------|-----------|------------------------------|-------------|
| F-R1-005 (Lifecycle) | F-R2-003 (LUKS Encryption) | `T3-PAIR-01` | VM state shutdown triggers automatic LUKS2 volume unmount & key purge. |
| F-R2-004 (Vsock 3-Port) | F-R3-007 (PTY Framing) | `T3-PAIR-02` | Port 5001 socket framing byte stream integrity under concurrent control RPC on Port 5000. |
| F-R3-004 (CJK IME) | F-R3-002 (libvterm) | `T3-PAIR-03` | Inline Zhuyin composition rendering updates libvterm cursor positions cleanly. |
| F-R4-001 (Wayland Forwarding) | F-R4-002 (virtio-gpu) | `T3-PAIR-04` | Wayland surface commit triggers zero-copy dma-buf buffer binding to Host `SurfaceControl`. |
| F-R4-005 (.desktop Inotify) | F-R4-006 (Synthetic Shortcuts)| `T3-PAIR-05` | Guest `apt install` triggers inotify notification which immediately updates `Launcher3`. |
| F-R5-001 (Camera Bridge) | F-R5-004 (AppOps Prompt) | `T3-PAIR-06` | Guest XDG camera portal call invokes Host AppOps permission prompt before video pipe starts. |
| F-R5-005 (virtio-snd) | F-R5-006 (AudioFocus) | `T3-PAIR-07` | Guest ALSA audio stream request triggers Host AudioFocus request and ducking on phone call. |
| F-R5-007 (virtiofs) | F-R5-008 (SAF Provider) | `T3-PAIR-08` | File written via virtiofs in Guest `/home/user` is instantly accessible via SAF DocumentsProvider. |
| F-R5-009 (SELinux Domain) | F-R5-010 (SELinux neverallow)| `T3-PAIR-09` | SELinux policy allows `linux_bridge` vsock IPC while rigorously enforcing `neverallow` rules. |
| F-R5-012 (EROFS A/B Layout) | F-R5-014 (Boot Watchdog) | `T3-PAIR-10` | Corrupted EROFS slot A triggers boot watchdog timeout and automatic fallback to slot B. |
| F-R2-005 (HMAC Handshake) | F-R1-004 (Daemon Isolation)| `T3-PAIR-11` | Isolated daemon verifies HMAC challenge response before granting host IPC access. |
| F-R3-005 (Touch Modes) | F-R3-006 (SGR Mouse) | `T3-PAIR-12` | Touchpad mode gestures accurately map to SGR protocol packets for terminal mouse control. |
| F-R1-001 (API Namespace) | F-R1-003 (SystemServer) | `T3-PAIR-13` | Framework LinuxManager API delegates control calls to SystemServer LinuxManagerService. |
| F-R1-002 (AIDL Interfaces) | F-R2-004 (Vsock 3-Port) | `T3-PAIR-14` | AIDL ILinuxTerminalCallback byte stream routed through Vsock Port 5001 PTY socket. |
| F-R1-003 (SystemServer) | F-R5-009 (SELinux Domain) | `T3-PAIR-15` | SystemServer service registration guarded by SELinux linux_manager domain policy rules. |
| F-R2-001 (Debian VM) | F-R2-002 (Storage Layout) | `T3-PAIR-16` | Non-protected Debian VM launch verifies 4-layer storage mount hierarchy setup. |
| F-R2-002 (Storage Layout) | F-R2-003 (LUKS Encryption) | `T3-PAIR-17` | Overlayfs writable layer persists changes on decrypted LUKS2 user home volume. |
| F-R2-003 (LUKS Encryption) | F-R5-008 (SAF Provider) | `T3-PAIR-18` | Locked LUKS2 storage denies SAF provider access until Android user profile unlock. |
| F-R3-001 (Surface Canvas) | F-R3-002 (libvterm) | `T3-PAIR-19` | libvterm screen buffer updates trigger Native Surface Canvas 60 FPS redraw. |
| F-R3-003 (InputConnection) | F-R3-007 (PTY Framing) | `T3-PAIR-20` | TerminalInputConnection key commit text formatted into PTY framing DATA packets. |
| F-R4-001 (Wayland Forwarding) | F-R4-003 (Task ID Recents) | `T3-PAIR-21` | Wayland surface creation maps to LinuxAppProxyActivity with discrete Android Task ID. |
| F-R4-002 (virtio-gpu) | F-R4-004 (Freeform Resize) | `T3-PAIR-22` | Freeform window drag resize updates virtio-gpu dma-buf buffer geometry and display bounds. |
| F-R4-003 (Task ID Recents) | F-R4-006 (Synthetic Shortcuts)| `T3-PAIR-23` | Tapping Launcher3 synthetic shortcut launches LinuxAppProxyActivity with allocated Task ID. |
| F-R5-002 (Mic Bridge) | F-R5-004 (AppOps Prompt) | `T3-PAIR-24` | XDG Microphone portal D-Bus request enforces AppOps RECORD_AUDIO permission verification. |
| F-R5-003 (Location Bridge) | F-R5-004 (AppOps Prompt) | `T3-PAIR-25` | XDG Location portal stream checks AppOps FINE_LOCATION permission before positioning updates. |
| F-R5-005 (virtio-snd) | F-R5-007 (virtiofs) | `T3-PAIR-26` | virtio-snd audio streaming operates concurrently with virtiofs file sync without buffer stalls. |
| F-R5-010 (SELinux neverallow) | F-R5-011 (CTS Compatibility)| `T3-PAIR-27` | CTS SELinux host compatibility test suite verifies zero neverallow policy rule violations. |
| F-R5-012 (EROFS A/B Layout) | F-R5-013 (AVB Validation) | `T3-PAIR-28` | AVB RSA-4096 signature validation verifies EROFS base image slot integrity prior to boot. |
| F-R5-013 (AVB Validation) | F-R5-014 (Boot Watchdog) | `T3-PAIR-29` | Invalid AVB signature on guest image update triggers boot watchdog rollback to previous slot. |
| F-R1-005 (Lifecycle) | F-R2-001 (Debian VM) | `T3-PAIR-30` | State machine transition to STARTING spawns crosvm non-protected Debian guest VM process. |
| F-R2-004 (Vsock 3-Port) | F-R2-005 (HMAC Handshake) | `T3-PAIR-31` | Vsock Port 5000 control channel executes HMAC-SHA256 handshake before opening Ports 5001/5002. |
| F-R3-004 (CJK IME) | F-R3-007 (PTY Framing) | `T3-PAIR-32` | Multi-stage CJK IME commit streams multi-byte UTF-8 payload through Vsock Port 5001 PTY framing. |
| F-R4-005 (.desktop Inotify) | F-R5-007 (virtiofs) | `T3-PAIR-33` | Inotify monitor daemon detects .desktop modifications on virtiofs shared application directory. |
| F-R5-001 (Camera Bridge) | F-R5-005 (virtio-snd) | `T3-PAIR-34` | Simultaneous Camera portal video capture stream and virtio-snd audio playback hardware mapping. |
| F-R5-006 (AudioFocus) | F-R5-008 (SAF Provider) | `T3-PAIR-35` | SAF file provider operations maintain thread stability during AudioFocus ducking events. |
| F-R5-009 (SELinux Domain) | F-R5-011 (CTS Compatibility)| `T3-PAIR-36` | CTS security suite verifies SELinux domain process context transition for linux_bridge daemon. |
| F-R1-004 (Daemon Isolation) | F-R5-010 (SELinux neverallow)| `T3-PAIR-37` | Isolated linux_bridge daemon process access to efs_file blocked by SELinux neverallow enforcement. |

---

## 5. Tier 4: Real-World End-to-End Application Scenarios

The Tier 4 test suite covers 18 end-to-end user application workflows:

### Scenario 01: Cold Boot Debian 12 Guest & Full Shell Session
- **Pre-conditions**: Device booted, user unlocked, LinuxManager service running.
- **Workflow**:
  1. User opens Terminal App.
  2. `LinuxManager.startVm()` invoked.
  3. crosvm boots Debian 12 guest; systemd starts `android-bridge-agent` & `pty-agent`.
  4. HMAC-SHA256 handshake succeeds over vsock Port 5000.
  5. Port 5001 PTY framing stream connects to `TerminalView`.
  6. Interactive bash prompt appears (`user@debian:~$ `).
- **Assertions**: Boot time < 3.5s; PTY terminal responds to key press; zero SELinux audit denials.

### Scenario 02: CJK IME Text Input in Terminal App
- **Pre-conditions**: Terminal active in interactive shell session.
- **Workflow**:
  1. User toggles Android Gboard to Zhuyin (注音) input mode.
  2. User types keys `5j0` (ㄘㄨㄛ).
  3. `TerminalInputConnection` displays inline composing text window.
  4. User selects candidate word `測試`.
  5. UTF-8 encoded bytes for `測試` committed through vsock Port 5001 PTY framing.
- **Assertions**: Composing window renders without screen artifacts; terminal receives exact UTF-8 sequence `\xe6\xb8\xac\xe8\xa9\xa6`.

### Scenario 03: TUI Editor Navigation (Vim Mouse Dragging & SGR Scroll)
- **Pre-conditions**: Vim editor running inside terminal in `TUI_MOUSE_MODE`.
- **Workflow**:
  1. User opens a 1,000-line source code file in Vim.
  2. User performs vertical swipe gesture on touch screen.
  3. `SGR Mouse Protocol Generator` translates swipe to SGR wheel scroll button events (`\e[<64;40;25M`).
  4. Vim scrolls view smoothly.
  5. User drags across text to select lines; SGR drag events (`\e[<32;X;Y;M`) highlight text.
- **Assertions**: Vim responds to mouse events; terminal grid coordinates accurately map touch positions.

### Scenario 04: Wayland GUI App Launch & Task Manager / Recents Integration
- **Pre-conditions**: Guest VM active; `Launcher3` visible on host home screen.
- **Workflow**:
  1. User launches `GIMP` from synthetic shortcut in `Launcher3`.
  2. Host sends `START_APP` command over vsock Port 5000.
  3. Guest spawns `gimp` process; window created via Sommelier Wayland proxy.
  4. `LinuxWindowBridgeService` maps Wayland surface to `LinuxAppProxyActivity` with dedicated Task ID.
  5. App appears in Android Recents overview with GIMP icon and window preview.
  6. User switches to native Android Settings app and back to GIMP via Recents.
- **Assertions**: Task ID correctly mapped; window frame buffer updates; smooth task switching.

### Scenario 05: Freeform Multi-Window Pacing & Dynamic Resizing
- **Pre-conditions**: Linux GUI app running in Android Freeform window mode.
- **Workflow**:
  1. User drags bottom-right corner resize handle of `LinuxAppProxyActivity`.
  2. Host sends updated dimensions via Wayland `xdg_toplevel.configure`.
  3. Guest app re-renders interface to match new aspect ratio and resolution.
  4. Frame pacing engine maintains 60 FPS without surface tearing or black borders.
- **Assertions**: No frame drops > 2 frames; virtio-gpu dma-buf correctly reallocated; surface bounds match layout.

### Scenario 06: Synthetic Desktop Shortcut Sync via Inotify Monitor
- **Pre-conditions**: Guest VM running; `portal-agent` watching `/usr/share/applications/`.
- **Workflow**:
  1. User executes `sudo apt-get install -y vlc` inside terminal session.
  2. Package manager writes `vlc.desktop` to `/usr/share/applications/`.
  3. `portal-agent` inotify watcher detects `IN_CLOSE_WRITE` event.
  4. App metadata payload (Name: VLC, Exec: vlc, Icon: vlc.png) sent over vsock Port 5000.
  5. Host `LinuxManagerService` processes metadata and notifies `Launcher3`.
  6. VLC app icon dynamically appears in Android application drawer.
- **Assertions**: Shortcut creation latency < 1.0s after apt install; icon rendered correctly.

### Scenario 07: Hardware Camera Streaming via XDG Portal + AppOps Grant
- **Pre-conditions**: Linux video app (e.g. Cheese) running in Guest VM.
- **Workflow**:
  1. Cheese requests camera access via `org.freedesktop.portal.Camera`.
  2. Guest `portal-agent` forwards request to Host `LinuxPortalService`.
  3. Host displays Android permission dialog: *"Allow Linux App Cheese to access Camera?"*.
  4. User taps *"Allow"*.
  5. Host `Camera2` HAL opens device camera and streams frames over shared memory to guest `/dev/video0`.
  6. Live camera video feed renders inside Cheese window.
- **Assertions**: Permission grant stored in AppOps; camera preview runs at 30 FPS; stream closes on app exit.

### Scenario 08: Microphone Access & Audio Capture Prompt Denial
- **Pre-conditions**: Linux audio recorder app running in Guest VM.
- **Workflow**:
  1. Audio recorder app requests microphone stream via `org.freedesktop.portal.Microphone`.
  2. Host displays permission prompt.
  3. User taps *"Deny"*.
  4. `LinuxPortalService` returns `AccessDenied` D-Bus error to guest app.
- **Assertions**: AppOps records denial; guest app receives formal error response; host microphone hardware remains closed.

### Scenario 09: GPS Location Streaming with Fine Location Permission Check
- **Pre-conditions**: Linux navigation app (e.g. Marble) running in Guest VM.
- **Workflow**:
  1. App requests location via `org.freedesktop.portal.Location`.
  2. Host verifies `ACCESS_FINE_LOCATION` permission granted.
  3. Host `LocationManager` streams latitude/longitude updates over vsock Port 5000.
  4. Guest GeoClue daemon receives position updates and updates Marble map view.
- **Assertions**: Coordinate accuracy within host GPS provider precision; location updates stop on app close.

### Scenario 10: Virtio-snd Audio Playback with Incoming Phone Call AudioFocus Ducking
- **Pre-conditions**: Linux media player playing audio via `virtio-snd` -> Host `AudioService`.
- **Workflow**:
  1. Linux player outputs 44.1kHz stereo audio stream.
  2. Host receives incoming cellular phone call simulation.
  3. Host `TelephonyManager` requests high-priority audio focus.
  4. `AudioFocus Policy Handler` intercepts event and ducks/pauses Linux audio stream.
  5. Call ends; host restores audio focus to Linux media player; music playback resumes.
- **Assertions**: Audio ducking latency < 100ms; seamless playback resume without audio distortion.

### Scenario 11: Virtiofs Shared Storage File Creation & Storage Access Framework (SAF) Access
- **Pre-conditions**: Shared directory mounted (`/data/media/0/LinuxShared` <-> `/mnt/shared`).
- **Workflow**:
  1. User creates text document `notes.txt` in Linux guest at `/mnt/shared/notes.txt`.
  2. User opens Android Files app and navigates to `LinuxStorageProvider` SAF location.
  3. User selects `notes.txt`, modifies text content in Android text editor, and saves file.
  4. Linux guest reads updated `/mnt/shared/notes.txt`.
- **Assertions**: Immediate bi-directional visibility; file SHA256 matches; zero file lock deadlocks.

### Scenario 12: Guest LUKS2 CE Storage Decryption on Android User Unlock
- **Pre-conditions**: Device powered on at lock screen (Credential Encrypted storage locked).
- **Workflow**:
  1. Device boots up; guest starts base system but `/home/user` remains unmounted.
  2. User enters lock screen PIN/pattern to unlock Android user profile.
  3. Android Keymaster releases CE master key to `LinuxManagerService`.
  4. Host invokes `cryptsetup open` on `user_home.img` using CE key.
  5. Decrypted block device mounted at `/home/user` inside guest VM.
- **Assertions**: User home files accessible only after unlock; key safely wiped from RAM on re-lock.

### Scenario 13: Vsock 3-Port Handshake Authentication with Replay Attack Prevention
- **Pre-conditions**: Host daemon listening on vsock Ports 5000, 5001, 5002.
- **Workflow**:
  1. Malicious guest process attempts unauthorized connection to Port 5001 without valid HMAC-SHA256 token.
  2. Host daemon drops connection attempt and logs security warning.
  3. Legitimate guest agent attempts connection using an already-expired authentication token.
  4. Host daemon detects token replay and rejects connection.
  5. Legitimate guest agent requests new token via secure seed channel, computes fresh HMAC, and connects successfully.
- **Assertions**: Unauthorized and replayed connections strictly rejected; genuine handshake succeeds.

### Scenario 14: SELinux Domain Denial Interception for Unauthorized File System Access
- **Pre-conditions**: Device running in SELinux Enforcing mode.
- **Workflow**:
  1. Compromised or rogue process inside `linux_bridge` domain attempts to read `/efs/factory.prop`.
  2. Kernel SELinux subsystem blocks access based on `neverallow` rule: `neverallow linux_bridge efs_file:file *`.
  3. `dmesg` records `avc: denied { read } for pid=... scontext=u:r:linux_bridge:s0 tcontext=u:object_r:efs_file:s0`.
- **Assertions**: Read operation returns `EACCES (Permission denied)`; zero system integrity compromise.

### Scenario 15: Guest OTA Image Verification & AVB Key Signature Validation Failure Rollback
- **Pre-conditions**: Host receives guest OTA update package `base_update_v2.img`.
- **Workflow**:
  1. Host `LinuxManagerService` initiates OTA installation into inactive slot B.
  2. Prior to boot switch, Host verifies Android Verified Boot (AVB) signature of `base_update_v2.img`.
  3. Test injects corrupted key signature into package header.
  4. Signature verification fails (`AVB_SLOT_VERIFY_RESULT_ERROR_VERIFICATION_FAILURE`).
  5. Host aborts OTA installation, marks slot B as unbootable, and retains active slot A.
- **Assertions**: Invalid update rejected before VM reboot; active VM continues running without disruption.

### Scenario 16: VM Crash & Watchdog Automatic Recovery to Backup Slot B
- **Pre-conditions**: Dual-OS running on slot A; slot B contains verified working fallback image.
- **Workflow**:
  1. Test injects kernel panic into guest kernel on slot A.
  2. Boot watchdog counter increments to 1.
  3. VM attempts reboot; kernel panics again (attempt 2 & attempt 3).
  4. Upon 3rd consecutive failed boot attempt, `Boot Watchdog Rollback Engine` triggers automatic slot switch to slot B.
  5. System boots guest VM successfully from slot B.
- **Assertions**: Watchdog detects 3 consecutive failures; slot switch executed; system returns to `RUNNING` state.

### Scenario 17: Multi-App Wayland Forwarding & Concurrent Audio/Video Portals
- **Pre-conditions**: Dual-OS active with multiple Wayland apps running concurrently.
- **Workflow**:
  1. User runs `VLC` playing video with audio, `GIMP` editing an image, and `TerminalApp` compiling code simultaneously.
  2. VLC outputs video via virtio-gpu dma-buf and audio via virtio-snd.
  3. GIMP handles touch inputs over Wayland window forwarding.
  4. TerminalApp streams build logs over vsock Port 5001 PTY framing.
- **Assertions**: All apps run concurrently without frame tearing, audio stuttering, or socket cross-talk.

### Scenario 18: Storage Encryption Key Revocation on Device Screen Lock / Relock
- **Pre-conditions**: Dual-OS active with `/home/user` LUKS2 volume mounted and accessible.
- **Workflow**:
  1. User presses power button to lock Android device screen.
  2. Android `KeyguardMediator` signals screen lock to `LinuxManagerService`.
  3. Service sends flush command to guest `android-bridge-agent` and unmounts `/home/user`.
  4. `cryptsetup close` executed on Host; CE key material zeroized from system RAM.
  5. Test attempts memory dump scan for raw key bytes.
- **Assertions**: LUKS2 volume safely closed; key material absent from host/guest RAM dump.

---

## 6. Directory Structure & Test Suite Layout

```
tests/e2e/
├── run_tests.sh                      # Shell executable launcher
├── runner.py                         # Complete Python test runner CLI
├── framework/                        # Common test framework & utilities
│   ├── __init__.py
│   ├── base_test.py                  # BaseTestCase class & lifecycle hooks
│   ├── assertions.py                 # Custom test assertion helpers
│   ├── mock_env.py                   # Mock SystemServer, Vsock & Portal env
│   ├── vsock_helper.py               # Vsock packet framing & HMAC helpers
│   ├── command_runner.py             # Process launcher & command runner
│   └── report_formatter.py           # Console & JSON report generators
├── tier1_feature_coverage/           # Tier 1 Functional Coverage Tests (37 features x 5 = 185 tests)
│   ├── test_m1_framework_core.py     # F-R1-001..F-R1-005 tests
│   ├── test_m2_avf_guest_luks.py     # F-R2-001..F-R2-005 tests
│   ├── test_m3_terminal_ime.py       # F-R3-001..F-R3-007 tests
│   ├── test_m4_wayland_recents.py    # F-R4-001..F-R4-006 tests
│   └── test_m5_portals_ota_sepolicy.py# F-R5-001..F-R5-014 tests
├── tier2_boundary_corner/            # Tier 2 Boundary/Negative Tests (37 features x 5 = 185 tests)
│   ├── test_m1_boundary.py
│   ├── test_m2_boundary.py
│   ├── test_m3_boundary.py
│   ├── test_m4_boundary.py
│   └── test_m5_boundary.py
├── tier3_cross_feature/              # Tier 3 Integration Pairwise Tests
│   └── test_pairwise_matrix.py       # T3-PAIR-01..T3-PAIR-37 tests
└── tier4_real_world/                 # Tier 4 End-to-End Application Scenarios
    └── test_scenarios.py             # SCENARIO-01..SCENARIO-18 tests
```

---

## 7. Execution Guide

### CLI Commands
```bash
# Display help and usage instructions
python3 tests/e2e/runner.py --help

# List all discovered tests across all tiers
python3 tests/e2e/runner.py --list

# Run all test suites
./tests/e2e/run_tests.sh

# Run specific tier
python3 tests/e2e/runner.py --tier 1
python3 tests/e2e/runner.py --tier 4

# Run specific feature test filter
python3 tests/e2e/runner.py --filter "F-R1-001"

# Generate detailed JSON report
python3 tests/e2e/runner.py --output-json report.json
```
