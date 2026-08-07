# Architectural Analysis & Technical Design Report: M3 Native Touch Terminal & PTY Protocol Engine

**Author**: Explorer 3 (Milestone M3: Native Touch Terminal & IME)  
**Target Path**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3/analysis.md`  
**Scope**: F-R3-005 (Touch Modes State Machine), F-R3-006 (SGR Mouse Protocol Generator), F-R3-007 (Vsock Port 5001 PTY Framing)

---

## 1. Executive Summary & Scope Mapping

This document establishes the technical design and architectural blueprint for the three core touch input and PTY communication features of Milestone M3 (Native Touch Terminal & IME) in AOSP Dual-OS:

| Feature ID | Feature Name | Description | Target Component |
|------------|--------------|-------------|------------------|
| **F-R3-005** | Touch Modes State Machine | State machine managing `SHELL_MODE`, `TUI_MOUSE_MODE`, and `TOUCHPAD_MODE` | `com.android.terminal.touch.TouchModeStateMachine` |
| **F-R3-006** | SGR Mouse Protocol Generator | Touch gesture to DEC SGR 1006 mouse protocol translation (`\x1b[?<button>;<x>;<y>M` / `m`) | `com.android.terminal.touch.SgrMouseProtocolGenerator` |
| **F-R3-007** | Vsock Port 5001 PTY Framing | Binary framing header parser and byte stream serializer over Vsock 5001 (`[SessionID (16B)][Type (1B)][Length (4B)][Payload]`) | `com.android.terminal.net.VsockPtyFramer` |

---

## 2. Feature 1: F-R3-005 - Touch Modes State Machine Design

### 2.1 State Definitions & Behaviors

```
                           +------------------------+
                           |       SHELL_MODE       |  (Default Terminal Mode)
                           |  - Drag: Scrollback    |
                           |  - Long Press: Select  |
                           |  - Tap: Show IME       |
                           +-----------+------------+
                                       |
                   \x1b[?1000h / \x1b[?1006h | \x1b[?1000l / \x1b[?1006l
              (Vim/tmux SGR Enabled)   | (Vim/tmux Exited)
                                       v
                           +------------------------+
                           |     TUI_MOUSE_MODE     |  (ANSI Mouse Mode)
                           |  - Touch: SGR Packets  |
                           |  - Swipe: Wheel 64/65  |
                           |  - Passthrough to PTY  |
                           +-----------+------------+
                                       |
                         User Manual Toggle / Action Bar
                                       |
                                       v
                           +------------------------+
                           |     TOUCHPAD_MODE      |  (Desktop Trackpad)
                           |  - Relative Cursor     |
                           |  - Tap: Left Click     |
                           |  - 2-Finger: Right Click|
                           +------------------------+
```

1. **`SHELL_MODE` (Standard Shell Navigation)**:
   - **Single Finger Vertical Drag / Fling**: Controls terminal scrollback buffer. Moving finger down scrolls up into scrollback history; moving finger up scrolls down toward active command line.
   - **Long Press + Drag**: Triggers terminal text selection engine. Highlights character cells between touch start and end positions, copying selected text to system `ClipboardManager`.
   - **Single Tap**: Focuses terminal view and requests soft keyboard via `InputMethodManager.showSoftInput(view, SHOW_IMPLICIT)`.
   - **Pinch Gesture**: Adjusts terminal font size dynamically ($12\text{pt} \le \text{fontSize} \le 36\text{pt}$), triggers cell metric recalculation, and dispatches a Vsock `RESIZE` frame.

2. **`TUI_MOUSE_MODE` (SGR Mouse Event Pass-through)**:
   - Activated automatically when full-screen terminal programs (e.g. Vim, tmux, htop, less) send DEC Private Mode set escape sequences `\x1b[?1000h` (SET_MOUSE_BTN) or `\x1b[?1006h` (SET_SGR_EXT_MODE).
   - Touch events are intercepted by `SgrMouseProtocolGenerator` and translated into ANSI SGR mouse sequences (`\x1b[?<Cb>;<Cx>;<Cy>M` / `m`).
   - Single finger drag in Vim selects visual blocks or moves cursor position.
   - Two-finger vertical swipe triggers mouse wheel scroll (Button 64 for Wheel Up, Button 65 for Wheel Down).

3. **`TOUCHPAD_MODE` (Virtual Trackpad Cursor Emulation)**:
   - Screen acts as a relative laptop trackpad controlling virtual mouse cursor on Linux desktop windows.
   - **Relative Delta Tracking**: Touch displacement $(\Delta x, \Delta y)$ is scaled using pointer acceleration curve $v' = v \cdot (1 + \alpha \cdot |v|)$ and sent to virtio-input pointer device.
   - **Tap**: Emulates Left Mouse Button Click (Button 1).
   - **Two-finger Tap**: Emulates Right Mouse Button Click (Button 2 / Context Menu).
   - **Three-finger Tap**: Emulates Middle Mouse Button Click (Button 3).
   - **Two-finger Scroll**: Emulates mouse wheel panning.

### 2.2 Auto-Detection & State Transition Logic

- **Auto-Detection Engine**: Integrated into the terminal escape parser (`libvterm` / JNI parser). When parser processes DEC Private Mode codes:
  - `\x1b[?1000h`, `\x1b[?1002h`, `\x1b[?1003h`, `\x1b[?1006h` $\rightarrow$ Triggers `mStateMachine.onMouseTrackingModeChanged(true)`.
  - `\x1b[?1000l`, `\x1b[?1006l` $\rightarrow$ Triggers `mStateMachine.onMouseTrackingModeChanged(false)`.
- **Manual Lock Overrides**:
  - The state machine maintains a `isManualLocked` flag.
  - When user clicks action bar "Switch Mode" button or performs a 3-finger long-press gesture, `isManualLocked` is set to `true` and state is locked to the selected mode (`SHELL_MODE`, `TUI_MOUSE_MODE`, or `TOUCHPAD_MODE`).
  - While `isManualLocked == true`, automatic escape code transitions are suppressed.

### 2.3 Java Class Structure (`TouchModeStateMachine.java`)

```java
package com.android.terminal.touch;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.CopyOnWriteArrayList;

public class TouchModeStateMachine {
    public enum TouchMode {
        SHELL_MODE,
        TUI_MOUSE_MODE,
        TOUCHPAD_MODE
    }

    public interface OnTouchModeChangeListener {
        void onTouchModeChanged(TouchMode oldMode, TouchMode newMode, boolean isManual);
    }

    private static final String PREF_NAME = "terminal_touch_prefs";
    private static final String KEY_PREF_MODE = "saved_touch_mode";

    private TouchMode mCurrentMode = TouchMode.SHELL_MODE;
    private boolean mIsManualLocked = false;
    private boolean mMouseTrackingRequested = false;
    private final CopyOnWriteArrayList<OnTouchModeChangeListener> mListeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences mPrefs;

    public TouchModeStateMachine(Context context) {
        mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = mPrefs.getString(KEY_PREF_MODE, TouchMode.SHELL_MODE.name());
        try {
            mCurrentMode = TouchMode.valueOf(saved);
        } catch (Exception e) {
            mCurrentMode = TouchMode.SHELL_MODE;
        }
    }

    public synchronized TouchMode getCurrentMode() {
        return mCurrentMode;
    }

    public synchronized boolean isManualLocked() {
        return mIsManualLocked;
    }

    public synchronized void setManualTouchMode(TouchMode mode) {
        mIsManualLocked = true;
        transitionTo(mode, true);
    }

    public synchronized void unlockAutoMode() {
        mIsManualLocked = false;
        if (mMouseTrackingRequested) {
            transitionTo(TouchMode.TUI_MOUSE_MODE, false);
        } else {
            transitionTo(TouchMode.SHELL_MODE, false);
        }
    }

    public synchronized void onTerminalEscapeMouseTrackingChanged(boolean enabled) {
        mMouseTrackingRequested = enabled;
        if (!mIsManualLocked) {
            TouchMode target = enabled ? TouchMode.TUI_MOUSE_MODE : TouchMode.SHELL_MODE;
            transitionTo(target, false);
        }
    }

    private void transitionTo(TouchMode newMode, boolean isManual) {
        if (mCurrentMode != newMode) {
            TouchMode oldMode = mCurrentMode;
            mCurrentMode = newMode;
            mPrefs.edit().putString(KEY_PREF_MODE, newMode.name()).apply();
            for (OnTouchModeChangeListener listener : mListeners) {
                listener.onTouchModeChanged(oldMode, newMode, isManual);
            }
        }
    }

    public void addListener(OnTouchModeChangeListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(OnTouchModeChangeListener listener) {
        mListeners.remove(listener);
    }
}
```

---

## 3. Feature 2: F-R3-006 - SGR Mouse Protocol Generator Design

### 3.1 DEC SGR Extended Mouse Protocol Format

The DEC SGR 1006 mouse protocol serializes touch/mouse events into readable ASCII escape packets:

```
Press / Motion:   \x1b[?<button_code>;<col>;<row>M
Release:          \x1b[?<button_code>;<col>;<row>m
```

#### Button Code Encoding Table ($\text{Cb}$)

| Event / Action | Base Value | Drag Offset (+32) | Final Code ($\text{Cb}$) | Protocol Suffix |
|----------------|------------|-------------------|--------------------------|-----------------|
| Left Button Down | `0` | - | `0` | `M` |
| Middle Button Down | `1` | - | `1` | `M` |
| Right Button Down | `2` | - | `2` | `M` |
| Left Button Drag | `0` | `+32` | `32` | `M` |
| Middle Button Drag | `1` | `+32` | `33` | `M` |
| Right Button Drag | `2` | `+32` | `34` | `M` |
| Motion (No buttons) | `3` | `+32` | `35` | `M` |
| Scroll Wheel Up | `64` | - | `64` | `M` |
| Scroll Wheel Down | `65` | - | `65` | `M` |
| Left Button Release | `0` | - | `0` | `m` |

**Modifier Bitmasks** (added to $\text{Cb}$):
- `Shift`: $+4$
- `Alt / Meta`: $+8$
- `Ctrl`: $+16$

### 3.2 Touch-to-Grid Coordinate Translation Math

Given:
- Touch pixel coordinate: $(X_{px}, Y_{px})$
- Character cell dimensions: $W_{cell}$ (pixels), $H_{cell}$ (pixels)
- View padding offsets: $Pad_X, Pad_Y$
- Terminal grid dimensions: $Cols, Rows$

1-based grid column ($Cx$) and row ($Cy$) calculations:

$$Cx = \min\left(\max\left(1, \, \left\lfloor \frac{X_{px} - Pad_X}{W_{cell}} \right\rfloor + 1\right), \, Cols\right)$$

$$Cy = \min\left(\max\left(1, \, \left\lfloor \frac{Y_{px} - Pad_Y}{H_{cell}} \right\rfloor + 1\right), \, Rows\right)$$

### 3.3 Touch Event Processing & Scroll Wheel Math

```java
package com.android.terminal.touch;

import android.view.MotionEvent;
import java.nio.charset.StandardCharsets;

public class SgrMouseProtocolGenerator {
    private boolean mMouseTrackingEnabled = false;
    private boolean mSgrModeEnabled = true;

    private int mLastCol = -1;
    private int mLastRow = -1;
    private float mStartY = 0f;
    private float mAccumulatedScrollY = 0f;

    public void setMouseTrackingEnabled(boolean enabled) {
        this.mMouseTrackingEnabled = enabled;
    }

    public boolean isMouseTrackingEnabled() {
        return mMouseTrackingEnabled;
    }

    public void setSgrModeEnabled(boolean sgrMode) {
        this.mSgrModeEnabled = sgrMode;
    }

    public byte[] processMotionEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows) {
        if (!mMouseTrackingEnabled) {
            return new byte[0];
        }

        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        int col = Math.max(1, Math.min(totalCols, (int) (x / cellWidth) + 1));
        int row = Math.max(1, Math.min(totalRows, (int) (y / cellHeight) + 1));

        StringBuilder sb = new StringBuilder();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mLastCol = col;
                mLastRow = row;
                mStartY = y;
                mAccumulatedScrollY = 0f;
                // Button 0 Press
                sb.append(String.format("\x1b[<0;%d;%d;M", col, row));
                break;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1) {
                    if (col != mLastCol || row != mLastRow) {
                        mLastCol = col;
                        mLastRow = row;
                        // Button 0 Drag / Motion (Cb = 0 + 32 = 32)
                        sb.append(String.format("\x1b[<32;%d;%d;M", col, row));
                    }
                } else if (event.getPointerCount() == 2) {
                    // Two-finger scroll wheel translation
                    float dy = y - mStartY;
                    mStartY = y;
                    mAccumulatedScrollY += dy;
                    if (Math.abs(mAccumulatedScrollY) >= cellHeight) {
                        int button = (mAccumulatedScrollY < 0) ? 65 : 64; // 65=Wheel Down, 64=Wheel Up
                        sb.append(String.format("\x1b[<%d;%d;%d;M", button, col, row));
                        mAccumulatedScrollY = 0f;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                col = (mLastCol > 0) ? mLastCol : col;
                row = (mLastRow > 0) ? mLastRow : row;
                // Button 0 Release
                sb.append(String.format("\x1b[<0;%d;%d;m", col, row));
                mLastCol = -1;
                mLastRow = -1;
                break;
        }

        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
```

---

## 4. Feature 3: F-R3-007 - Vsock Port 5001 PTY Framing Design

### 4.1 Binary Packet Framing Specification

All terminal stream communication over Virtio Vsock Port 5001 follows a strict 21-byte header binary frame layout:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                                                               +
|                    SessionID (16 Bytes)                       |
+                                                               +
|                                                               |
+                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                               | Type (1 Byte) | Length (4B)...|
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|...Length (uint32_t Big-Endian)|        Payload (N Bytes)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
|                                                               |
+---------------------------------------------------------------+
```

#### Field Specifications

1. **`SessionID` (16 Bytes)**: 128-bit session UUID or binary token identifying the target terminal session.
2. **`Type` (1 Byte `uint8_t`)**:
   - `0x01` (`DATA`): Terminal stdin/stdout UTF-8 raw byte stream or ANSI escape sequences.
   - `0x02` (`RESIZE`): Terminal window dimensions change event.
   - `0x03` (`PING`): Vsock layer heartbeat keepalive ping.
   - `0x04` (`PONG`): Vsock layer heartbeat keepalive pong.
   - `0x05` (`EOS`): End-of-Stream / Shell session logout notification.
3. **`Length` (4 Bytes `uint32_t` Big-Endian / Network Byte Order)**: Payload byte count ($0 \le N \le 65536$).
4. **`Payload` ($N$ Bytes)**: Binary payload content.

#### `RESIZE` Payload Structure (4 Bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     Cols (uint16_t BE)        |     Rows (uint16_t BE)        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 4.2 Stream Serializer & Reassembly Parser Architecture

Vsock socket reads can yield partial headers, split payloads, or multiple concatenated frames. The parsing engine uses an accumulation buffer (`ByteArrayOutputStream` or ring buffer) with validation checks:

- **Header Check**: Minimum 21 bytes required before parsing header.
- **Frame Type Validation**: Type must be $\in \{0x01, 0x02, 0x03, 0x04, 0x05\}$. Any other byte raises `IllegalArgumentException("Invalid Vsock frame type: " + type)`.
- **Payload Length Sanity Check**: Max frame size is 64KB ($65,536$ bytes). If $Length > 65536$, parser raises `IllegalArgumentException("PayloadLengthExceeded: " + length)`.
- **Session Validation**: Incoming `SessionID` must match active session ID; mismatches are dropped.

### 4.3 Java Implementation (`VsockPtyFramer.java`)

```java
package com.android.terminal.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class VsockPtyFramer {
    public static final int HEADER_SIZE = 21; // 16 + 1 + 4
    public static final int MAX_PAYLOAD_SIZE = 65536; // 64 KB limit

    public enum PacketType {
        DATA(0x01),
        RESIZE(0x02),
        PING(0x03),
        PONG(0x04),
        EOS(0x05);

        private final byte mValue;

        PacketType(int value) {
            mValue = (byte) value;
        }

        public byte getValue() {
            return mValue;
        }

        public static PacketType fromByte(byte b) {
            for (PacketType type : values()) {
                if (type.mValue == b) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid Vsock frame type byte: 0x" + Integer.toHexString(b & 0xFF));
        }
    }

    public static class Frame {
        public final byte[] sessionId;
        public final PacketType type;
        public final byte[] payload;

        public Frame(byte[] sessionId, PacketType type, byte[] payload) {
            if (sessionId == null || sessionId.length != 16) {
                throw new IllegalArgumentException("Session ID must be exactly 16 bytes");
            }
            this.sessionId = sessionId;
            this.type = type;
            this.payload = (payload != null) ? payload : new byte[0];
        }
    }

    public interface OnFrameParsedListener {
        void onFrameParsed(Frame frame);
        void onError(Exception e);
    }

    // Serializes a Frame object to binary byte array
    public static byte[] serializeFrame(byte[] sessionId, PacketType type, byte[] payload) {
        if (sessionId == null || sessionId.length != 16) {
            throw new IllegalArgumentException("Session ID must be exactly 16 bytes");
        }
        byte[] data = (payload != null) ? payload : new byte[0];
        if (data.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload length " + data.length + " exceeds maximum " + MAX_PAYLOAD_SIZE);
        }

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(sessionId);
        buffer.put(type.getValue());
        buffer.putInt(data.length);
        buffer.put(data);

        return buffer.array();
    }

    // Creates a RESIZE payload frame
    public static byte[] serializeResizeFrame(byte[] sessionId, int cols, int rows) {
        ByteBuffer payload = ByteBuffer.allocate(4);
        payload.order(ByteOrder.BIG_ENDIAN);
        payload.putShort((short) cols);
        payload.putShort((short) rows);
        return serializeFrame(sessionId, PacketType.RESIZE, payload.array());
    }

    // Parses RESIZE payload bytes into [cols, rows]
    public static int[] parseResizePayload(byte[] payload) {
        if (payload == null || payload.length != 4) {
            throw new IllegalArgumentException("Resize payload must be exactly 4 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        int cols = buffer.getShort() & 0xFFFF;
        int rows = buffer.getShort() & 0xFFFF;
        return new int[]{cols, rows};
    }

    // Stateful parser for stream fragment reassembly
    public static class StreamParser {
        private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();

        public synchronized void appendAndParse(byte[] chunk, int offset, int length, byte[] expectedSessionId, OnFrameParsedListener listener) {
            mBuffer.write(chunk, offset, length);
            byte[] bytes = mBuffer.toByteArray();
            int readOffset = 0;

            while (bytes.length - readOffset >= HEADER_SIZE) {
                ByteBuffer headerBuf = ByteBuffer.wrap(bytes, readOffset, HEADER_SIZE);
                headerBuf.order(ByteOrder.BIG_ENDIAN);

                byte[] sessionId = new byte[16];
                headerBuf.get(sessionId);
                byte typeByte = headerBuf.get();
                int payloadLength = headerBuf.getInt();

                if (payloadLength > MAX_PAYLOAD_SIZE) {
                    mBuffer.reset();
                    listener.onError(new IllegalArgumentException("PayloadLengthExceeded: " + payloadLength + " > " + MAX_PAYLOAD_SIZE));
                    return;
                }

                int totalFrameLength = HEADER_SIZE + payloadLength;
                if (bytes.length - readOffset < totalFrameLength) {
                    // Incomplete frame, wait for next socket read
                    break;
                }

                try {
                    PacketType type = PacketType.fromByte(typeByte);
                    byte[] payload = Arrays.copyOfRange(bytes, readOffset + HEADER_SIZE, readOffset + totalFrameLength);

                    if (expectedSessionId != null && !Arrays.equals(sessionId, expectedSessionId)) {
                        // Drop frame due to session mismatch
                    } else {
                        listener.onFrameParsed(new Frame(sessionId, type, payload));
                    }
                } catch (Exception e) {
                    listener.onError(e);
                }

                readOffset += totalFrameLength;
            }

            // Keep unparsed trailing bytes in buffer
            byte[] remaining = Arrays.copyOfRange(bytes, readOffset, bytes.length);
            mBuffer.reset();
            mBuffer.write(remaining, 0, remaining.length);
        }
    }
}
```

---

## 5. Summary of Test Scenarios & Verification Matrix

| Test Suite | Feature | Scenario | Verification Method |
|------------|---------|----------|---------------------|
| `test_m3_tier1.py` | F-R3-005 | Default Touch Mode is `SHELL_MODE` | Assert `mStateMachine.getCurrentMode() == SHELL_MODE` |
| `test_m3_tier1.py` | F-R3-005 | Auto transition to `TUI_MOUSE_MODE` on DEC set code | Send `\x1b[?1006h`, verify transition to `TUI_MOUSE_MODE` |
| `test_m3_tier1.py` | F-R3-005 | Manual lock override suppresses auto escape code | Set manual lock to `TOUCHPAD_MODE`, send `\x1b[?1006h`, mode remains `TOUCHPAD_MODE` |
| `test_m3_tier1.py` | F-R3-006 | Touch down to SGR Button 0 press | Touch at (75px, 150px) with cell size 8x16 generates `\x1b[<0;9;9;M` |
| `test_m3_tier1.py` | F-R3-006 | Touch drag to SGR Motion (Cb=32) | Drag event generates `\x1b[<32;col;row;M` |
| `test_m3_tier1.py` | F-R3-006 | Scroll wheel gesture to Buttons 64/65 | Two-finger scroll generates `\x1b[<64;col;row;M` or `\x1b[<65;col;row;M` |
| `test_m3_tier1.py` | F-R3-007 | 21-byte frame header serialization | Verify header size = 21, payload length matches, SessionID byte array intact |
| `test_m3_tier1.py` | F-R3-007 | `RESIZE` frame payload packing/unpacking | Pack 120 cols / 40 rows into 4-byte payload, unpack and verify values |
| `test_m3_tier2.py` | F-R3-007 | Invalid frame type byte rejection | Byte `0xFF` raises `IllegalArgumentException` |
| `test_m3_tier2.py` | F-R3-007 | Partial header & fragmented payload reassembly | Split 21-byte header across socket reads, verify parser buffers and reassembles correctly |
| `test_m3_tier2.py` | F-R3-007 | Payload length $> 64\text{KB}$ rejection | Pack `length = 100000`, verify `PayloadLengthExceeded` error raised |

---
