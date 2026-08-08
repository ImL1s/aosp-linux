# Handoff Report — Explorer 3 (Milestone M2)

## 1. Observation

Direct observations from codebase inspection, system headers, Rust source files, Java framework services, terminal framers, and E2E test suites:

- **Current Guest Agent Implementation (`guest/bridge-agent/`)**:
  - `guest/bridge-agent/Cargo.toml`: Package name `android-bridge-agent`, dependencies `hex = "0.4"`, `hmac = "0.12"`, `sha2 = "0.10"`, `zeroize = "1.7"`, `libc = "0.2"`.
  - `guest/bridge-agent/src/main.rs`:
    - Defines `VSOK_MAGIC = 0x56534F4B`, frame types (`Control = 0x01`, `PtyData = 0x02`, `Wayland = 0x03`, `Heartbeat = 0x04`, `MsgAuthInit = 0x10`..`MsgAuthSuccess = 0x13`).
    - Currently contains single-thread main loop `loop { std::thread::sleep(Duration::from_secs(5)); }` after handshake.
    - Modules `pty.rs`, `wayland.rs`, and `portal.rs` are not yet created under `src/`.
  - `guest/bridge-agent/src/auth.rs`:
    - Reads `/proc/cmdline` for `linux_auth_token=` or `android_bridge.token=`.
    - Contains fallback default 32-byte zero token (`vec![0u8; 32]`) when token is absent.
  - `guest/bridge-agent/src/vsock.rs`:
    - Defines `CID_HOST = 2`, `PORT_CONTROL = 5000`, `PORT_PTY = 5001`, `PORT_WAYLAND = 5002`, `AF_VSOCK = 40`.
    - Implements `connect_vsock(cid, port)` returning `std::fs::File`.
  - `guest/bridge-agent/src/ota_rollback.rs`:
    - Implements `send_boot_heartbeat()` delivering 13-byte heartbeat signal frame (`0x04`) to Host.

- **Host Side Protocol Specifications & Expectations**:
  - **Port 5001 (PTY Terminal Stream)**:
    - Defined in `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockPtyFramer.java`.
    - Packet header size: 21 bytes (`HEADER_SIZE = 21`).
    - Header structure: `[SessionID (16 bytes)][PacketType (1 byte)][Length (4 bytes Big-Endian)][Payload]`.
    - Max payload length: 64 KB (`65536` bytes).
    - `PacketType`:
      - `0x01` (`DATA`): Terminal stdin/stdout stream bytes.
      - `0x02` (`RESIZE`): Window resize event. Payload is 4 bytes Big-Endian: `cols` (u16 BE) + `rows` (u16 BE).
      - `0x03` (`PING`): Heartbeat ping request.
      - `0x04` (`PONG`): Heartbeat pong response.
      - `0x05` (`EOS`): End of stream / session termination.
  - **Port 5002 (Wayland Display Proxy)**:
    - Defined in `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`.
    - Frame structure: 13-byte `VsockFrameHeader` (`magic = 0x56534F4B`, `frame_type = 0x03` [WAYLAND], `payload_len`, `seq_id`).
    - Host sends JSON control events (`{"event":"configure","surface_id":...,"width":...,"height":...}` and `{"event":"close","surface_id":...}`) alongside raw Wayland binary wire protocol data.
    - Guest agent forwards bytes to Unix domain socket `/run/user/1000/wayland-0` (or `XDG_RUNTIME_DIR/wayland-0`).
  - **Port 5000 (Hardware Portal & Control RPC)**:
    - Defined in `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`.
    - Frame structure: 13-byte `VsockFrameHeader` (`magic = 0x56534F4B`, `frame_type = 0x01` [CONTROL], `payload_len`, `seq_id`).
    - Handles Camera access & resolution negotiation (e.g. 4K -> 1080p@30fps fallback), Microphone audio recording (PCM streaming, privacy toggle zero-filling, 1024-byte minimum buffer padding), Location GPS updates (coarse obfuscation support), and virtiofs SAF file storage mounting.

- **Cargo Test & Check Status**:
  - `cargo check` and `cargo test` in `guest/bridge-agent` currently compile cleanly with 0 errors.

---

## 2. Logic Chain

1. **Observation**: Host `VsockPtyFramer.java` encodes PTY frames with a 21-byte header (`16B session ID + 1B type + 4B BE length`), expecting PacketTypes `0x01` (DATA), `0x02` (RESIZE), `0x03` (PING), `0x04` (PONG), `0x05` (EOS).
   **Reasoning**: `guest/bridge-agent/src/pty.rs` must implement an exact 21-byte frame parser and encoder, allocating master/slave PTY pairs via `libc::openpty` or `libc::forkpty`, handling terminal window size updates (`ioctl(TIOCSWINSZ)`), spawning `/bin/bash`, and bi-directionally piping streams between the Vsock socket and PTY master fd.

2. **Observation**: `LinuxWindowBridgeService.java` communicates over Vsock Port 5002 using 13-byte `VsockFrameHeader` (`magic = 0x56534F4B`, `frame_type = 0x03`), transmitting JSON window control events (`configure`, `close`) and Wayland protocol streams.
   **Reasoning**: `guest/bridge-agent/src/wayland.rs` must act as a bi-directional socket proxy, unpacking Vsock 5002 frames, passing raw Wayland binary wire protocol data and window events to/from the Guest Unix domain socket `/run/user/1000/wayland-0`, and handling socket lifecycle/disconnects cleanly.

3. **Observation**: `LinuxPortalService.java` manages hardware access (Camera2 HAL, AudioRecord PCM, LocationManager GPS, virtiofs SAF mounts) over Vsock Port 5000 using JSON RPC requests wrapped in 13-byte `VsockFrameHeader` (`frame_type = 0x01`).
   **Reasoning**: `guest/bridge-agent/src/portal.rs` must implement the Portal RPC dispatcher, handling XDG Desktop Portal RPC calls from guest apps, dispatching requests across Vsock 5000 to `LinuxPortalService`, and returning responses/streams to Guest callers.

4. **Observation**: `src/auth.rs` currently includes a fallback default zero token when `/proc/cmdline` lacks a token, and `src/main.rs` continues into a 5-second sleep loop instead of multi-threaded active port listening.
   **Reasoning**: To satisfy Requirement R2, `src/main.rs` and `src/auth.rs` must abort immediately (`std::process::exit(1)`) on token extraction or handshake failure, zero-wipe token memory, and launch a multi-threaded server dispatch loop listening on Vsock Ports 5000, 5001, and 5002.

---

## 3. Caveats

- **Debian Guest Runtime Dependencies**: Slave PTY shell execution requires `/bin/bash` or `/bin/sh` to exist in the guest image environment.
- **Wayland Socket Availability**: Wayland proxying relies on a running Wayland compositor or proxy daemon creating `/run/user/1000/wayland-0` (or `XDG_RUNTIME_DIR/wayland-0`). If the socket does not exist, `wayland.rs` must log the connection error and gracefully handle retries or host notifications without crashing the bridge agent daemon.
- **Multi-threaded Execution Safety**: All thread workers servicing Vsock ports must use thread-safe data structures (`Arc<Mutex<T>>` or atomic flags) and properly handle `SIGCHLD` to prevent zombie child shell processes.

---

## 4. Technical Specifications & Architecture

### 4.1 PTY Dispatcher Module (`guest/bridge-agent/src/pty.rs` - Port 5001)

#### Responsibilities
- Listen for and accept incoming Vsock connections on Port 5001 (`PORT_PTY = 5001`).
- Parse and serialize 21-byte binary packet frames matching `VsockPtyFramer.java`:
  - **Header Layout (21 bytes)**:
    - `session_id` (16 bytes): `[u8; 16]`
    - `type` (1 byte): `u8` (`0x01` DATA, `0x02` RESIZE, `0x03` PING, `0x04` PONG, `0x05` EOS)
    - `length` (4 bytes Big-Endian): `u32` (Payload byte count, max 65,536 bytes)
    - `payload`: `[u8; length]`
- PTY Allocation & Management:
  - Call `libc::openpty(&mut master_fd, &mut slave_fd, ptr::null_mut(), &ws, ptr::null_mut())`.
  - Set slave terminal `termios` attributes.
  - Fork child shell process (`/bin/bash` or `/bin/sh`) attached to slave PTY with environment: `TERM=xterm-256color`, `SHELL=/bin/bash`, `HOME=/home/user`, `LANG=en_US.UTF-8`.
- Multi-Threaded Bi-Directional Stream Forwarding:
  - **Host -> PTY Master Thread**: Reads 21-byte header from Vsock stream; if `DATA (0x01)`, writes to `master_fd`; if `RESIZE (0x02)`, parses 4-byte BE `cols` (u16) + `rows` (u16) and calls `libc::ioctl(master_fd, libc::TIOCSWINSZ, &winsize)`; if `PING (0x03)`, responds with `PONG (0x04)` header; if `EOS (0x05)`, terminates session and closes PTY.
  - **PTY Master -> Host Thread**: Reads bytes from `master_fd`, wraps in 21-byte `DATA (0x01)` frame, and transmits over Vsock 5001 to Host. On EOF/shell exit, transmits `EOS (0x05)` frame and closes session.

---

### 4.2 Wayland Socket Proxy Module (`guest/bridge-agent/src/wayland.rs` - Port 5002)

#### Responsibilities
- Listen for and accept incoming Vsock connections on Port 5002 (`PORT_WAYLAND = 5002`).
- Establish local Unix domain socket connection to Guest Wayland socket path: `/run/user/1000/wayland-0` (fallback `/tmp/wayland-0`).
- Bi-directional stream forwarding between Vsock Port 5002 and Unix domain socket:
  - Unpacks 13-byte `VsockFrameHeader` (`magic = 0x56534F4B`, `frame_type = 0x03` [WAYLAND], `payload_len`, `seq_id`).
  - Handles JSON window control events (`configure`, `close`) from Host `LinuxWindowBridgeService`.
  - Forwards raw Wayland binary wire protocol data between Host and Guest Wayland compositor.
- Worker Threads & Teardown:
  - Spawns dedicated read/write thread pair per Vsock connection.
  - Gracefully closes both Vsock and Unix domain sockets upon EOF or socket error.

---

### 4.3 Portal RPC Dispatcher Module (`guest/bridge-agent/src/portal.rs` - Port 5000)

#### Responsibilities
- Listen for and accept incoming Control / Portal RPC requests on Port 5000 (`PORT_CONTROL = 5000`).
- Process 13-byte `VsockFrameHeader` (`magic = 0x56534F4B`, `frame_type = 0x01` [CONTROL]) with JSON RPC payload.
- Dispatch Portal Requests:
  - **Camera Portal**: Access checks, resolution negotiation (`startCameraStream`, 4K -> 1080p@30fps fallback handling), stopping stream.
  - **Audio / Microphone Portal**: Audio recording session setup (`startMicStream`), PCM audio frame transport, silent frame zero-filling when privacy toggle is enabled, 1024-byte minimum buffer padding.
  - **Location / GPS Portal**: Location permission checks, GPS lat/lon coordinate subscriptions, coarse location obfuscation support.
  - **File Access / virtiofs Portal**: SAF directory mount requests (`/mnt/shared`), directory listing, and file access synchronization.
- Interface with Guest XDG Desktop Portal DBus daemon (`org.freedesktop.portal.Desktop`).

---

### 4.4 Main Dispatcher Loop & Auth Hardening (`guest/bridge-agent/src/main.rs`)

#### Responsibilities
- **Authentication Failure Exit Policy**:
  - If token extraction from `/proc/cmdline` fails, token is missing, or token length != 32 bytes: log error and **immediately execute `std::process::exit(1)`**. Remove default zero-token fallback.
  - Perform 4-step HMAC-SHA256 handshake on Port 5000. If handshake fails: **immediately execute `std::process::exit(1)`**.
  - Upon success: zeroize token memory (`auth::zeroize_token()`).
- **Multi-Threaded Server Loop**:
  - Launch active multi-threaded server dispatch loop listening on Vsock Ports 5000, 5001, and 5002:
    - Spawn Port 5000 listener thread -> dispatches to `portal::handle_portal_connection(stream)`.
    - Spawn Port 5001 listener thread -> dispatches to `pty::handle_pty_connection(stream)`.
    - Spawn Port 5002 listener thread -> dispatches to `wayland::handle_wayland_connection(stream)`.

---

## 5. Verification Method

To independently verify the investigation findings and specifications:

1. **Verify Rust Codebase Compilation**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"
   cd guest/bridge-agent
   cargo check
   cargo test
   ```

2. **Verify Native Host Bridge Tests & Framing**:
   ```bash
   ./build_out/bin/linux_bridge_test
   ./build_out/bin/challenger_m2_framing_test
   ./build_out/bin/challenger_m2_hmac_test
   ```

3. **Verify E2E Test Suite for Milestone M2**:
   ```bash
   python3 tests/e2e/runner.py --filter "F-R2"
   python3 tests/unit/challenger_m2_empirical_stress_test.py
   ```

4. **Invalidation Conditions**:
   - `cargo check` fails or produces compilation errors.
   - PTY frame header size != 21 bytes or session ID != 16 bytes.
   - Wayland Vsock frame magic != `0x56534F4B` or frame type != `0x03`.
   - Token extraction failure does not invoke `std::process::exit(1)`.
