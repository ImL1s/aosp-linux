# Changes Report — Milestone M3 Iteration 3 Remediation

## Executive Summary
This remediation addresses all issues identified during Milestone M3 Iteration 2 Gate Review by `reviewer_m3_2_r2`. All facade implementations have been eliminated and replaced with authentic implementations:
1. `TouchpadController.java` is implemented and wired into `TerminalView.java` and `TerminalSurfaceView.java`.
2. `VsockTerminalClient` socket transmission is connected in `TerminalView.java` during view lifecycle (`onAttachedToWindow` / `onDetachedFromWindow`) and directly invoked in `sendBytes()`, `sendFrame()`, and `sendResize()`.
3. C++ UTF-8 Lead Byte fallback loop in `vterm_parser.cpp` is fixed to preserve `validLen` when encountering continuation bytes.
4. C++ invalid header type handling in `pty_framing_handler.cpp` is fixed to perform 1-byte stream resynchronization instead of clearing `mBuffer`.
5. Unit tests `testTouchpadModeEventGeneration()` and `testVsockTerminalClientSocketTransmission()` are added to `TerminalAppUnitTest.java`.
6. All Java unit tests, C++ native tests (including Challenger 2 stress suite), and Python E2E runner tests (`F-R3` filter) pass with 100% pass rate (80/80).

---

## Detailed File Modifications

### 1. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/touch/TouchpadController.java`
- **Purpose**: Class implementing relative touch tracking ($\Delta x, \Delta y$), virtual cursor positioning & clamping $[1, \text{cols}], [1, \text{rows}]$, single tap (Left Click / Button 0 press & release), long press (Right Click / Button 2 press & release), and two-finger drag (Wheel scroll buttons 64/65).
- **Key Methods**:
  - `handleTouchpadEvent(...)`: Main event entry point handling single-finger motion, taps, long-press timer, and two-finger scrolling.
  - `handleRelativeMove(dx, dy)`: Relative delta cursor tracking with grid coordinate clamping.
  - `handleSingleTap()`: Formats Button 0 press & release SGR packet.
  - `handleLongPress()`: Formats Button 2 press & release SGR packet.
  - `handleTwoFingerScroll(dyScroll)`: Formats Button 64 / 65 SGR scroll packet.

### 2. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
- **Purpose**: Primary terminal view component.
- **Changes**:
  - Connected `VsockTerminalClient` in `onAttachedToWindow()` to `HOST_CID` (CID 2, Port 5001) with stream listener calling `mVTermParser.writeInput(data)` and `postInvalidate()`.
  - Added socket resource cleanup in `onDetachedFromWindow()` via `mVsockClient.close()`.
  - Replaced logging-only stubs in `sendBytes()`, `sendFrame()`, and `sendResize()` with direct `mVsockClient.sendFrame(frame)` calls.
  - Wired `TOUCHPAD_MODE` in `onTouchEvent()` to call `mTouchpadController.handleTouchpadEvent(...)`.

### 3. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java`
- **Purpose**: Native SurfaceView terminal rendering component.
- **Changes**:
  - Instantiated `TouchpadController` in `init()`.
  - Wired `TOUCHPAD_MODE` in `onTouchEvent()` to delegate to `mTouchpadController.handleTouchpadEvent(...)`.

### 4. `packages/apps/LinuxTerminal/jni/vterm_parser.cpp`
- **Purpose**: JNI native bridge to C libvterm parser.
- **Changes**:
  - Fixed `feedBytes()` Lead Byte fallback loop when parsing partial UTF-8 multi-byte sequences.
  - Continuation bytes (`0x80~0xBF`, `(b & 0xC0) == 0x80`) no longer decrement `validLen` during backwards scanning, preventing complete CJK/Emoji characters from being incorrectly truncated into the partial buffer.

### 5. `packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp`
- **Purpose**: C++ native handler for Vsock Port 5001 PTY framing.
- **Changes**:
  - In `processIncomingChunk()`, updated invalid packet type byte (< 0x01 or > 0x05) and oversized payload length (> 64KB) handling from `mBuffer.clear()` to 1-byte stream resynchronization (`readOffset += 1; continue;`).

### 6. `tests/unit/TerminalAppUnitTest.java`
- **Purpose**: Java unit test suite for LinuxTerminal app.
- **Changes**:
  - Added `testTouchpadModeEventGeneration()` verifying relative delta motion tracking, virtual cursor clamping, Tap (Button 0), Long Press (Button 2), and Two-Finger Scroll (Buttons 64/65).
  - Added `testVsockTerminalClientSocketTransmission()` setting up local ServerSocket loopback and asserting real socket transmission of serialized frames.

### 7. `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` & `tests/e2e/tier2_boundary_corner/test_m3_tier2.py`
- **Purpose**: E2E python test suites for M3 features.
- **Changes**:
  - Cleaned up build command for `m3_native_challenger2_stress_bin` by removing unneeded `terminal_renderer.cpp` dependency to allow clean compilation on host systems.

---

## Verification Results

1. **Java Compilation**:
   - `javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name "*.java") tests/unit/TerminalAppUnitTest.java`
   - **Result**: PASSED (exit code 0).

2. **Java Unit Tests**:
   - `java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest`
   - **Result**: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY` (exit code 0).

3. **C++ libvterm Unit Test**:
   - `g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_terminal_test.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_terminal_test && /tmp/m3_native_terminal_test`
   - **Result**: `ALL PASSED` (exit code 0).

4. **C++ Challenger 2 Stress Suite**:
   - `g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_challenger2_stress.cpp packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp packages/apps/LinuxTerminal/jni/vterm_parser.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_challenger2_stress && /tmp/m3_native_challenger2_stress`
   - **Result**: `ALL NATIVE C++ STRESS TESTS PASSED SUCCESSFULLY` (exit code 0).

5. **Python E2E Test Suite**:
   - `python3 tests/e2e/runner.py --filter F-R3`
   - **Result**: `TOTAL TESTS: 80, PASSED: 80, FAILED: 0, PASS RATE: 100.0%` (exit code 0).
