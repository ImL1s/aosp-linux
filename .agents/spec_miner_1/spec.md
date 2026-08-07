# Specification Mining Report: R3 & R4
**AOSP Dual-OS Technical Architecture Specification**

- **Source Request File**: `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
- **Authoritative Technical Blueprint**: `/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md`
- **Investigator**: `spec_miner_1`
- **Target Milestones**:
  - **R3**: Native Touch Terminal App Engine & Custom `TerminalInputConnection` IME
  - **R4**: Seamless Wayland GUI Window Forwarding, Android Recents & Launcher3 Integration

---

## 1. Overview & Specification Scope

This document details the exact functional specifications, IPC contracts, state machines, input handling pipelines, UI behaviors, and edge cases for **R3** and **R4** in the AOSP Dual-OS architecture.

The system host is **AOSP (Android 15 / Mainline)** running a **Non-Protected Debian 12 (ARM64)** guest inside **AVF (crosvm + KVM)**. Communication occurs strictly over authenticated `virtio-vsock` (AF_VSOCK) and zero-copy `virtio-gpu` buffer sharing.

---

## 2. R3: Native Touch Terminal App Engine & Custom IME Specification

### 2.1 Terminal Engine & Surface Rendering
- **Engine Selection**: Native Surface Canvas Renderer coupled with a C/C++ `libvterm` or Rust `vte` escape sequence parser. WebView + xterm.js is explicitly rejected due to memory overhead (>150MB WebView process) and touch/IME latency.
- **Buffer Management**:
  - **Primary Screen Buffer**: Stores standard terminal output (grid of cells containing character UTF-32, foreground color, background color, text attributes).
  - **Alternate Screen Buffer**: Activated by `DECSET 1049` (e.g., when launching `vim`, `htop`, `tmux`, `nano`).
  - **Scrollback History Buffer**: Retains up to 10,000 lines of scrollable history for Primary Screen Buffer.
- **Performance Targets (SLO)**:
  - Touch input-to-render latency: **< 25 ms** (on 120Hz display).
  - Cold boot launch (VM off -> Interactive Shell Prompt): **< 3.5 s**.
  - Warm start launch (VM running -> Interactive Shell Prompt): **< 800 ms**.
- **Font & Metrics Scaling**:
  - Dynamic font rendering using FreeType / Skia text layout.
  - Pinch-to-zoom gesture adjusts font size dynamically (min 8pt, max 36pt), recomputing terminal grid matrix `(columns x rows)` and sending window resize command `TIOCSWINSZ` to Guest PTY.

### 2.2 Custom `TerminalInputConnection` & IME Composing Window
- **Class Structure**: Extends `android.view.inputmethod.BaseInputConnection` attached to `TerminalSurfaceView`.
- **Multi-Stage CJK IME Support**:
  - Handles complex IME composing states for Zhuyin (注音), Cangjie (倉頡), Pinyin (拼音), Japanese Kana/Kanji, and Korean Hangul.
- **Composing Text Window Mechanism**:
  1. While IME user is entering raw phonetic keys (e.g. `ㄅ`, `ㄆ`, `ㄇ`), IME engine calls `setComposingText(text, newCursorPosition)`.
  2. The terminal displays the composing string in an **inline candidate window / highlight overlay** directly above the terminal cursor without transmitting partial characters over Vsock.
  3. On user candidate selection or Enter, IME calls `commitText(text, newCursorPosition)` or `finishComposingText()`.
  4. The custom `TerminalInputConnection` intercepts `commitText()`, converts the finalized string to UTF-8 byte stream, and sends it to Vsock Port `5001`.
  5. Backspacing during composition triggers `deleteSurroundingText()`, which updates the local composing buffer without generating extra backspace control bytes (`\x7f`) in the PTY stream.
- **Hardware & Special Key Routing**:
  - Direct hardware keys or virtual softkey toolbar buttons generate raw VT escape sequences:
    - **Arrow Up/Down/Left/Right**: `\x1b[A`, `\x1b[B`, `\x1b[D`, `\x1b[C`
    - **Ctrl + Key**: `Ctrl+C` -> `\x03`, `Ctrl+Z` -> `\x1a`, `Ctrl+D` -> `\x04`
    - **Function Keys F1-F12**: `\x1bOP` .. `\x1b[24~`
    - **Escape**: `\x1b`
    - **Tab / Shift+Tab**: `\x09` / `\x1b[Z`

### 2.3 Touch Modes State Machine
The Terminal UI operates in three distinct touch modes, governed by the state machine below:

```
                  +-----------------------+
                  |     Shell Mode        |
                  |  (Scroll & Select)    |
                  +-----------+-----------+
                              |
                [DECSET 1000/1002/1006 Detected]
                              |
                              v
                  +-----------------------+
                  |    TUI Mouse Mode     |
                  |  (Vim / tmux / SGR)   |
                  +-----------+-----------+
                              |
                  [User Toggles Touchpad]
                              |
                              v
                  +-----------------------+
                  |    Touchpad Mode      |
                  |  (GUI Cursor Ctrl)    |
                  +-----------------------+
```

1. **Shell Mode (Scroll & Select)**:
   - Default mode for standard CLI shells (`bash`, `zsh`).
   - Single-finger vertical drag: Scrolls through the primary scrollback buffer.
   - Double-tap: Selects word under touch position.
   - Long-press: Activates text selection handles and shows Contextual Copy/Paste/Share Action Bar.
   - Pinch-to-zoom: Scales text font size and updates grid size.
2. **TUI Mouse Mode (Vim / tmux / SGR Mouse Protocol)**:
   - Activated automatically when Guest PTY receives SGR mouse enablement VT sequences (`DECSET 1000`, `1002`, or `1006`).
   - Touch tap: Translates touch coordinates `(x, y)` to cell `(col, row)` and transmits SGR mouse press/release packet: `\x1b[<0;col;rowM` / `\x1b[<0;col;rowm`.
   - Touch drag: Transmits mouse motion tracking packet: `\x1b[<32;col;rowM`.
   - Two-finger drag up/down: Transmits mouse wheel scroll codes: `\x1b[<64;col;rowM` (scroll up) / `\x1b[<65;col;rowM` (scroll down).
   - FAB Override: A floating button allows the user to temporarily bypass TUI Mouse Mode to copy text directly from Vim/tmux without holding Shift.
3. **Touchpad Mode (Virtual Trackpad)**:
   - Used for controlling GUI cursors remotely or interacting with X11/Wayland headless windows.
   - Relative touch movement moves cursor across screen.
   - Single tap = Left Click; Two-finger tap = Right Click; Two-finger drag = Vertical/Horizontal pan; Tap-and-drag = Mouse Drag.

### 2.4 PTY Stream IPC Contract (Vsock Port 5001)
- **Host Endpoint**: `LinuxBridgeService` / `TerminalApp`.
- **Guest Endpoint**: `pty-agent` (running under Debian systemd).
- **Control & Stream Messages**:
  - `MSG_INIT_SESSION`: Terminal request with requested TERM environment (`xterm-256color`), width (cols), height (rows).
  - `MSG_RESIZE`: Payload containing `struct winsize { ws_row, ws_col, ws_xpixel, ws_ypixel }`. Guest triggers `ioctl(fd, TIOCSWINSZ, &ws)`.
  - `MSG_DATA`: Binary UTF-8 payload buffer.
  - `MSG_SIGNAL`: Transmits out-of-band signals like `SIGINT` (2), `SIGQUIT` (3), `SIGHUP` (1), `SIGKILL` (9).

---

## 3. R4: Seamless Wayland GUI Window Forwarding & Recents/Launcher3 Specification

### 3.1 Wayland GUI Window Forwarding Architecture
- **Guest Side Wayland Proxy (`Sommelier`)**:
  - Intercepts Wayland client connections in the Linux Guest (e.g., VS Code, GIMP, LibreOffice, Firefox).
  - Handles Wayland core protocols: `wl_compositor`, `wl_shm`, `wl_surface`, `xdg_wm_base`, `xdg_toplevel`, `zwp_text_input_v3`.
  - Allocates shared surface memory buffers using `virtio-gpu` dma-buf / zero-copy memory buffers (`gfxstream` or `virgl`).
- **Host Window Bridge (`LinuxWindowBridgeService`)**:
  - Connects to Guest via Vsock Port `5002` (Wayland Display Control Channel).
  - Listens for surface creation, destruction, title changes, focus updates, and geometry requests.
- **Host App Proxy Activity (`LinuxAppProxyActivity`)**:
  - Host dynamically instantiates a dedicated `LinuxAppProxyActivity` instance for each forwarded Linux window.
  - The `LinuxAppProxyActivity` attaches the `virtio-gpu` dma-buf Surface directly to its `SurfaceControl`.

### 3.2 Task ID Mapping & Android Recents Integration
- **Discrete Android Tasks**:
  - Every running Linux GUI application window is assigned a unique Android Task ID.
  - `LinuxAppProxyActivity` launched with flags: `Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK`.
- **Android Recents (Overview Screen)**:
  - Linux GUI apps appear as separate, native app cards in the Android Recents UI.
  - Application title and icon are derived from the `.desktop` file metadata or Wayland `xdg_toplevel.set_title` property.
  - Swiping away a Linux GUI app card in Recents sends an `xdg_toplevel.close` signal via Vsock to gracefully terminate the Linux process.
- **Multi-Window & Freeform Support**:
  - Fully integrated into Android 15 WindowManager:
    - **Split-Screen Mode**: Supports snapping Linux apps alongside native Android apps.
    - **Freeform Windowing Mode**: On large screen / tablet devices, Linux apps render in resizable windows with title bars and window control buttons.
    - **Dynamic Resize**: Resizing an Android window sends an `xdg_toplevel.configure` event to the Guest application, triggering native GUI reflow.

### 3.3 `.desktop` File Synchronization & Launcher3 Integration
- **Guest `portal-agent` Inotify Daemon**:
  - Monitors directories: `/usr/share/applications/` and `~/.local/share/applications/`.
  - Listens for inotify events: `IN_CREATE`, `IN_MODIFY`, `IN_DELETE`, `IN_MOVED_TO`, `IN_MOVED_FROM`.
- **Desktop Entry Parsing**:
  - Extracts fields: `Name`, `GenericName`, `Comment`, `Exec`, `Icon`, `Categories`, `MimeType`, `NoDisplay`, `Terminal`.
  - Filters out hidden utilities where `NoDisplay=true` or `Terminal=true` (unless terminal app launcher is specified).
  - Resolves icon paths from standard Freedesktop icon themes (`/usr/share/icons/`, `~/.local/share/icons/`, `/usr/share/pixmaps/`).
- **Sync Protocol over Vsock Port 5000**:
  - `portal-agent` serializes app entry into Protobuf message `LinuxAppMetadata`:
    ```protobuf
    message LinuxAppMetadata {
        string app_id = 1;         // e.g. "code.desktop"
        string name = 2;           // e.g. "Visual Studio Code"
        string exec = 3;           // e.g. "code %F"
        string categories = 4;     // e.g. "Development;IDE;"
        bytes icon_png_bytes = 5;  // Rendered PNG bitmap
        bool is_installed = 6;     // true if created/modified, false if deleted
    }
    ```
- **Host `LinuxAppRegistryService` & Launcher3 Integration**:
  - Host receives metadata and registers shortcuts in `LinuxAppTracker` within `packages/apps/Launcher3/`.
  - Generates synthetic app icons in the Android App Drawer.
  - Tapping a synthetic icon executes: `LinuxManager.launchLinuxApp(appId, displayId)`.
  - **Cold-Boot Auto-Start**: If the Debian VM is currently stopped when the user taps a Linux launcher shortcut, `LinuxManagerService` automatically boots the VM, waits for `android-bridge-agent` readiness, and launches the target application seamlessly.

### 3.4 Touch, Keyboard & IME Forwarding for Wayland
- **Input Events**: Touch gestures, hardware keyboard keystrokes, and mouse pointer events on `LinuxAppProxyActivity` are translated into Wayland `wl_touch`, `wl_keyboard`, and `wl_pointer` events.
- **IME Bridge**: Host IME composition strings are bridged directly to Wayland `zwp_text_input_v3` protocol, ensuring that CJK character entry inside VS Code or LibreOffice works with full native Android IME keyboards.

---

## 4. Features Discovered Table

| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | R3 - Terminal | Native Surface Canvas Renderer | Low-latency terminal renderer using Native Canvas + libvterm/vte | Touch/Keyboard events | Pixel buffer on SurfaceView | Fallback to software canvas rendering | Architecture Blueprint §10.1 |
| 2 | R3 - Terminal | Custom `TerminalInputConnection` | Intercepts Android IME events for CJK composing text | Zhuyin/Cangjie/Pinyin IME keycodes | UTF-8 bytes to Vsock Port 5001 | Drops invalid UTF-8 sequences gracefully | Architecture Blueprint §10.1 |
| 3 | R3 - Terminal | Inline Candidate Window | Displays composing text inline at terminal cursor position | `setComposingText()` AIDL calls | Visual highlight on terminal canvas | Clears inline state on IME reset | Architecture Blueprint §10.1 |
| 4 | R3 - Terminal | Touch Mode State Machine | Manages Shell Mode, TUI Mouse Mode, and Touchpad Mode | VT escape sequences & touch gestures | SGR mouse codes, scroll ioctls, or cursor moves | Resets to Shell Mode on session disconnect | Architecture Blueprint §10.2 |
| 5 | R3 - Terminal | SGR Mouse Protocol Encoding | Translates touch taps and drags to VT SGR mouse packets | Touch coordinates `(x,y)` | VT bytes `\x1b[<0;col;rowM` | Clamps coordinates to terminal grid bounds | Architecture Blueprint §10.2 |
| 6 | R3 - Terminal | Pinch-to-Zoom Font Scaling | Dynamically resizes terminal font and updates grid size | Two-finger pinch gesture | Font size update + `TIOCSWINSZ` ioctl | Clamps font size between 8pt and 36pt | Architecture Blueprint §10.1 |
| 7 | R3 - Terminal | FAB Touch Mode Override | Floating button to manually toggle between Touch Modes | User tap on FAB | State machine transition | Maintains state across app resume | Architecture Blueprint §10.2 |
| 8 | R3 - Terminal | Vsock PTY Channel | Dedicated binary framing channel over AF_VSOCK Port 5001 | Raw VT bytes, resize packets | Guest PTY execution stream | Re-establishes connection on vsock drop | Architecture Blueprint §9.1 & §10.1 |
| 9 | R4 - Wayland | Sommelier Wayland Proxy | Guest-side proxy intercepting Wayland GUI client calls | Wayland protocol messages | virtio-gpu dma-buf allocations | Returns `wl_display` error on client disconnect | Architecture Blueprint §11.1 |
| 10 | R4 - Wayland | virtio-gpu dma-buf Forwarding | Zero-copy buffer sharing from Guest GPU to Host SurfaceControl | GPU memory handles | Host `SurfaceControl` rendering | Fallback to software buffer copy (llvmpipe) | Architecture Blueprint §11.1 |
| 11 | R4 - Wayland | `LinuxAppProxyActivity` Creation | Dynamic Host Activity creation per Linux GUI window | Vsock window creation event | Discrete Android Task ID & Window | Destroys Activity on Linux window close | Architecture Blueprint §11.1 |
| 12 | R4 - Wayland | Recents/Overview Integration | Displays running Linux apps as distinct task cards in Recents | `xdg_toplevel` title & icon | Android Recents Task Card | Force closes Linux process on swipe-away | Architecture Blueprint §11.1 |
| 13 | R4 - Wayland | Freeform & Split-Screen Windowing | WindowManager integration for resizing and side-by-side view | Window resize touch events | `xdg_toplevel.configure` Wayland event | Clamps window dimensions to min/max hints | Architecture Blueprint §11.1 |
| 14 | R4 - Sync | Inotify Desktop File Daemon | Monitors Guest `/usr/share/applications/` for changes | Inotify FS events | Protobuf `LinuxAppMetadata` | Retries on failed vsock transmission | Architecture Blueprint §12 |
| 15 | R4 - Sync | Synthetic Launcher Shortcut Creation | Adds Linux app icons dynamically to Android Launcher3 App Drawer | `LinuxAppMetadata` PNG payload | Launcher3 Shortcut Item | Removes shortcut if `.desktop` deleted | Architecture Blueprint §12 |
| 16 | R4 - Sync | Auto Cold-Boot VM Launch | Launches VM and opens target Linux app on shortcut tap | User tap on Launcher shortcut | VM boot sequence + App exec | Shows loading UI dialog if VM fails to boot | Architecture Blueprint §12 |
| 17 | R4 - Input | Wayland IME Text Input Bridge | Bridges Host IME candidate strings to Wayland clients | Android IME commit text | `zwp_text_input_v3` Wayland events | Cancels composition on focus loss | Architecture Blueprint §11.1 |

---

## 5. Edge Cases Specification

| # | Feature | Input / Condition | Observed & Required Behavior |
|---|---------|-------------------|-----------------------------|
| 1 | R3 - Custom IME | User types rapid Zhuyin candidate choices before previous string finishes rendering | `TerminalInputConnection` queues commit operations sequentially, maintaining strict FIFO order before sending to Vsock Port 5001. |
| 2 | R3 - Touch State | Vim exits suddenly while in TUI Mouse Mode without sending `DECSET 1005/1006` disable sequence | `pty-agent` monitors process group termination and sends an out-of-band state reset packet to Host to revert UI to Shell Mode. |
| 3 | R3 - Terminal Resize | Device rotated between portrait and landscape during active `htop` run | `TerminalSurfaceView` recalculates cell dimensions, sends `MSG_RESIZE` over Vsock Port 5001, triggering `SIGWINCH` in Guest. |
| 4 | R4 - GUI Forwarding | Linux GUI app crashes unexpectedly (e.g. VS Code out-of-memory) | Sommelier notifies `LinuxWindowBridgeService` over Vsock Port 5002; Host tears down `LinuxAppProxyActivity` gracefully without crashing SystemServer. |
| 5 | R4 - Desktop Sync | Malformed `.desktop` file created in `/usr/share/applications/` | Guest `portal-agent` parser catches parsing error, logs warning, and ignores malformed file without sending corrupted Protobuf data. |
| 6 | R4 - Launcher Tap | User taps Linux app shortcut while Host device is under low memory pressure | `LinuxManagerService` checks RAM SLO (<450MB alloc target), invokes Memory Ballooning if necessary, and starts VM with cold-boot progress notification. |
| 7 | R4 - Windowing | User resizes freeform Linux app window faster than Guest application can redraw frames | Host `SurfaceControl` retains last valid dma-buf frame while Wayland `xdg_toplevel.configure` updates asynchronously, avoiding screen flickering. |
| 8 | R4 - IME Bridge | Focus switches between Linux GUI app and Android native app during active IME composition | Android IME dismisses candidate window, sends `zwp_text_input_v3.deactivate` to Wayland client, and clears host composing state. |

---

## 6. Verification Protocol for Implementation Teams

1. **R3 Verification**:
   - Verify `TerminalInputConnection` with Zhuyin (注音) IME: enter `ㄋㄏ`, select candidate `你好`, confirm exact UTF-8 byte stream `\xe4\xbd\xa0\xe5\xa5\xbd` sent over Vsock Port 5001.
   - Verify DECSET 1006 SGR mouse tracking: run `vim` in guest, verify single tap moves cursor to tapped line.
   - Verify font scaling: pinch zoom, observe `TIOCSWINSZ` ioctl invocation in guest.
2. **R4 Verification**:
   - Verify `.desktop` file sync: `touch /usr/share/applications/test.desktop` in guest, verify synthetic icon appears in Launcher3 app drawer within 1.5s.
   - Verify Wayland forwarding: launch `gedit` or `code`, verify discrete Android Task ID in `adb shell dumpsys window windows`, verify presence in Recents menu.
