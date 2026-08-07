# Changes Log — worker_m3_gen3 (Milestone M3 Iteration 3 Remediation)

## Modified Files Summary

### 1. `packages/apps/TerminalApp/src/com/android/virtualization/terminal/touch/SgrMouseProtocolGenerator.java`
- **Purpose**: Implement `TOUCHPAD_MODE` relative touch motion tracking and SGR 1006 protocol encoding.
- **Changes**:
  - Added Touchpad state tracking fields (`mTouchpadCol`, `mTouchpadRow`, `mTouchpadLastX`, `mTouchpadLastY`, `mTouchpadAccumX`, `mTouchpadAccumY`, `mTouchpadDownTime`, `mTouchpadTotalMoveDist`, `mTouchpadIsDragging`, `mTouchpadScrollAccumY`, `mTouchpadVelocityScale`).
  - Implemented `processTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows)`:
    - Center initialization of simulated touchpad cursor coordinates `(mTouchpadCol, mTouchpadRow)` when uninitialized.
    - Two-finger scroll detection: averages finger Y coordinates, accumulates delta Y, converts scroll beyond cell height threshold to SGR scroll wheel delta (`\033[<64;col;rowM` for Wheel Up, `\033[<65;col;rowM` for Wheel Down).
    - Single-finger relative delta X/Y accumulators scaled by `mTouchpadVelocityScale`, updating virtual grid coordinates `mTouchpadCol` and `mTouchpadRow`.
    - Grid position movement with button held (dragging) emits SGR button 32 motion (`\033[<32;col;rowM`), whereas hover motion emits SGR button 35 motion (`\033[<35;col;rowM`).
    - Single tap detection (duration < 250ms and move distance < 20px) emits left-click press and release (`\033[<0;col;rowM\033[<0;col;rowm`).
    - Drag release on `ACTION_UP`/`ACTION_CANCEL` emits left-click release (`\033[<0;col;rowm`).
  - Added getters/setters for velocity scale, drag state, and touchpad column/row positions.

### 2. `packages/apps/TerminalApp/src/com/android/virtualization/terminal/TerminalView.java`
- **Purpose**: Wire `TOUCHPAD_MODE` gesture handling and connect `VsockTerminalClient` data output over AF_VSOCK Port 5001.
- **Changes**:
  - Updated `TouchModeStateMachine` listener in `initView()` to enable SGR mouse tracking for both `TUI_MOUSE_MODE` and `TOUCHPAD_MODE`.
  - Updated `sendBytes()`, `sendFrame()`, and `sendResize()` to directly execute `mVsockClient.sendFrame(frame)` wrapped in try-catch `IOException` error handling instead of only logging debug messages.
  - Added `getVsockTerminalClient()` and `connectVsock(int guestCid, byte[] sessionId)` methods to connect the Vsock stream and dispatch incoming bytes to `mVTermParser.processOutput(data)` and trigger UI redraws.
  - Updated `onTouchEvent()` `case TOUCHPAD_MODE`: calls `mSgrMouseGenerator.processTouchpadEvent(...)` and forwards non-empty SGR escape sequences to `sendBytes()`.

### 3. `packages/apps/TerminalApp/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java`
- **Purpose**: Ensure `TOUCHPAD_MODE` parity for SurfaceView renderer.
- **Changes**:
  - Added state machine listener in `init()` to set mouse tracking on `mSgrGenerator` when entering `TUI_MOUSE_MODE` or `TOUCHPAD_MODE`.
  - Updated `onTouchEvent()` `case TOUCHPAD_MODE`: calls `mSgrGenerator.processTouchpadEvent(...)` and sends bytes via `mPtySender.sendBytes(...)`.

### 4. `packages/apps/TerminalApp/src/com/android/virtualization/terminal/parser/VTermParser.java`
- **Purpose**: Expose stream output processing helper methods.
- **Changes**:
  - Added `processOutput(byte[] data)` and `processOutput(byte[] data, int length)` methods that invoke `write(data, length)`.

### 5. `packages/apps/TerminalApp/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
- **Purpose**: Support polymorphic stream handling.
- **Changes**:
  - Changed `mInputStream` and `mOutputStream` fields to `java.io.InputStream` and `java.io.OutputStream` to accommodate both socket input streams and `FileDescriptor` file streams cleanly.

### 6. `packages/apps/TerminalApp/jni/vterm_parser.cpp` & `packages/apps/TerminalApp/jni/terminal_renderer.cpp`
- **Purpose**: Fix UTF-8 partial multi-byte sequence reassembly scan and native macro logging headers for standalone C++ compilation.
- **Changes**:
  - Rewrote `VTermParserBridge::feedBytes()` backward scanning loop to correctly identify partial multi-byte UTF-8 sequences at stream boundaries without prematurely dropping valid continuation bytes.
  - Added `#if __has_include(<android/log.h>)` guards in `terminal_renderer.cpp`.

### 7. `tests/unit/TerminalAppUnitTest.java`, `tests/e2e/tier1_feature_coverage/test_m3_tier1.py`, `tests/e2e/tier2_boundary_corner/test_m3_tier2.py`
- **Purpose**: Enhance unit test coverage and native test binary linkage.
- **Changes**:
  - Added assertions in `testSgrMouseProtocolGenerator()` testing touchpad velocity scale getters/setters and disabled state checks.
  - Updated `cmd_stress` and `cmd_cpp` build rules and test invocation in `test_m3_tier1.py` and `test_m3_tier2.py` to link `vterm_parser.cpp` and `terminal_renderer.cpp` into `./tests/unit/m3_native_challenger2_stress_bin`.

---

## Verification Results Summary
1. `java -cp /tmp/m3_classes:... tests.unit.TerminalAppUnitTest`: **PASS** (All M3 unit tests passed successfully).
2. `./tests/unit/m3_native_terminal_test_bin`: **PASS** (C++ native libvterm parser unit test passed cleanly).
3. `./tests/unit/m3_native_challenger2_stress_bin`: **PASS** (All C++ empirical stress tests passed successfully).
4. `python3 tests/e2e/runner.py --tier 1`: **PASS** (185 / 185 tests passed, 100% pass rate).
5. `python3 tests/e2e/runner.py --tier 2`: **PASS** (185 / 185 tests passed, 100% pass rate).
