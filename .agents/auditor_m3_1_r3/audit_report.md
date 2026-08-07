# Forensic Audit Report — Milestone M3 Iteration 3 Gate Review

**Work Product**: `packages/apps/LinuxTerminal/` and `tests/`
**Target**: Milestone M3 (Native Touch Terminal Engine & IME)
**Audit Profile**: General Project / Integrity Forensics
**Verdict**: **CLEAN**

---

## 1. Executive Summary

An independent forensic integrity audit was conducted on the remediated codebase in `packages/apps/LinuxTerminal/` and the associated test suite in `tests/` for Milestone M3 Iteration 3 Gate Review.

All 4 audit requirements specified in the dispatch objective were verified empirically:
1. **TOUCHPAD_MODE Verification**: `TouchpadController.java` genuinely implements relative touch motion tracking, Tap (Button 0), LongPress (Button 2), and Two-Finger Scroll (Buttons 64/65). Zero empty stubs found.
2. **AF_VSOCK Framing Transmission**: `TerminalView.java` calls `mVsockClient.sendFrame(frame)` directly in `sendBytes()`, `sendFrame()`, and `sendResize()`, writing framed binary packets directly to socket output streams. Zero Logcat-only facades found.
3. **E2E Test Execution Authenticity**: All 80 F-R3 E2E test cases execute compiled Java `.class` files or C++ test binaries via `CommandRunner`. Zero self-certifying Python mocks found.
4. **Build & JNI Alignment**: Clean `javac` compilation (Exit Code 0, 0 errors) and 100% symbol alignment between `VTermParser.java` native method signatures and `libvterm_jni.cpp` C++ JNI exports.

---

## 2. Forensic Investigation & Phase Analysis

### Phase 1 — Mode-Agnostic Empirical Observations

#### Observation 1: `TOUCHPAD_MODE` and `TouchpadController.java`
- **File**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/touch/TouchpadController.java` (285 lines)
- **Relative Motion Logic**:
  ```java
  public byte[] handleRelativeMove(float dx, float dy) {
      int oldCol = mVirtualCursorCol;
      int oldRow = mVirtualCursorRow;
      mVirtualCursorX = Math.max(0, Math.min(mTotalCols * mCellWidth, mVirtualCursorX + dx));
      mVirtualCursorY = Math.max(0, Math.min(mTotalRows * mCellHeight, mVirtualCursorY + dy));
      updateGridCoordinates();
      ...
  }
  ```
- **Tap Gesture (Button 0)**:
  ```java
  public byte[] handleSingleTap() {
      String press = SgrMouseProtocolGenerator.formatSgrPacket(0, mVirtualCursorCol, mVirtualCursorRow, true);
      String release = SgrMouseProtocolGenerator.formatSgrPacket(0, mVirtualCursorCol, mVirtualCursorRow, false);
      return (press + release).getBytes(StandardCharsets.US_ASCII);
  }
  ```
- **Long Press (Button 2)**:
  ```java
  public byte[] handleLongPress() {
      String press = SgrMouseProtocolGenerator.formatSgrPacket(2, mVirtualCursorCol, mVirtualCursorRow, true);
      String release = SgrMouseProtocolGenerator.formatSgrPacket(2, mVirtualCursorCol, mVirtualCursorRow, false);
      return (press + release).getBytes(StandardCharsets.US_ASCII);
  }
  ```
- **Two-Finger Scroll (Buttons 64/65)**:
  ```java
  public byte[] handleTwoFingerScroll(float dyScroll) {
      int button = (dyScroll < 0) ? 65 : 64; // 65 = Scroll Down, 64 = Scroll Up
      return SgrMouseProtocolGenerator.formatSgrPacket(button, mVirtualCursorCol, mVirtualCursorRow, true).getBytes(StandardCharsets.US_ASCII);
  }
  ```
- **Integration**: `TerminalView.java` (line 235) and `TerminalSurfaceView.java` (line 130) dispatch touch events directly to `mTouchpadController.handleTouchpadEvent(...)` under `case TOUCHPAD_MODE`.

#### Observation 2: Vsock Byte Stream Transmission in `TerminalView.java`
- **File**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
- **Implementation**:
  ```java
  @Override
  public void sendBytes(byte[] bytes) {
      if (bytes == null || bytes.length == 0) return;
      byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
      try {
          mVsockClient.sendFrame(frame);
          Log.d(TAG, "Transmitted DATA frame (" + frame.length + " bytes) over AF_VSOCK 5001");
      } catch (IOException e) {
          Log.e(TAG, "Failed to send PTY data frame over Vsock Port 5001", e);
      }
  }

  @Override
  public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {
      byte[] frame = VsockPtyFramer.serializeFrame(sessionId, type, payload);
      try {
          mVsockClient.sendFrame(frame);
          Log.d(TAG, "Transmitted frame type " + type + " (" + frame.length + " bytes) over AF_VSOCK 5001");
      } catch (IOException e) {
          Log.e(TAG, "Failed to send PTY frame type " + type + " over Vsock Port 5001", e);
      }
  }

  @Override
  public void sendResize(byte[] sessionId, int cols, int rows) {
      byte[] frame = VsockPtyFramer.serializeResizeFrame(sessionId, cols, rows);
      try {
          mVsockClient.sendFrame(frame);
          Log.d(TAG, "Transmitted RESIZE frame (" + cols + "x" + rows + ") over AF_VSOCK 5001");
      } catch (IOException e) {
          Log.e(TAG, "Failed to send PTY resize frame over Vsock Port 5001", e);
      }
  }
  ```
- **Socket Stream Writer**: `VsockTerminalClient.java`:
  ```java
  public synchronized void sendFrame(byte[] frameBytes) throws IOException {
      if (mOutputStream != null) {
          mOutputStream.write(frameBytes);
          mOutputStream.flush();
      }
  }
  ```

#### Observation 3: Real Binary Execution in E2E Tests
- **Files**: `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` & `tests/e2e/tier2_boundary_corner/test_m3_tier2.py`
- All 80 F-R3 E2E test cases invoke compiled Java binaries (`tests.unit.TerminalAppUnitTest`) or compiled C++ binaries (`m3_native_terminal_test_bin`, `m3_native_challenger2_stress_bin`) via `CommandRunner.run(...)`.
- `CommandRunner` executes subprocesses on OS and parses return codes and standard output.

#### Observation 4: Compilation and JNI Symbol Mapping
- **`javac` Command**:
  `javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_remediation_classes $(find packages/apps/LinuxTerminal/src -name "*.java") tests/unit/TerminalAppUnitTest.java`
  - **Result**: Exit Code 0 (Success, 0 errors, 0 warnings).
- **Java Unit Test Command**:
  `java -cp /tmp/m3_remediation_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest`
  - **Result**: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY` (Exit Code 0).
- **C++ Compilation Commands**:
  - `g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_terminal_test.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_terminal_test`
    *Result*: Exit Code 0 (Success).
  - `g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_challenger2_stress.cpp packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp packages/apps/LinuxTerminal/jni/vterm_parser.cpp packages/apps/LinuxTerminal/jni/terminal_renderer.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_challenger2_stress`
    *Result*: Exit Code 0 (Success).
- **JNI Function Symbol Mapping**:
  - `VTermParser.java` declares:
    - `private native long nativeInit(int rows, int cols, TerminalCallback callback);`
    - `private native void nativeWrite(long ptr, byte[] data, int length);`
    - `private native void nativeResize(long ptr, int rows, int cols);`
    - `private native void nativeGetScreenMatrix(long ptr, int[] codepoints, int[] fgColors, int[] bgColors, int[] attrs, int[] widths);`
    - `private native void nativeDestroy(long ptr);`
  - `libvterm_jni.cpp` exports:
    - `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit`
    - `Java_com_android_virtualization_terminal_parser_VTermParser_nativeWrite`
    - `Java_com_android_virtualization_terminal_parser_VTermParser_nativeResize`
    - `Java_com_android_virtualization_terminal_parser_VTermParser_nativeGetScreenMatrix`
    - `Java_com_android_virtualization_terminal_parser_VTermParser_nativeDestroy`
  - Alignment: 100% match.

---

### Phase 2 — Mode-Specific Flagging

Reading `ORIGINAL_REQUEST.md` constraints:
- User requested native touch terminal engine with custom IME and 3 touch modes.
- Mode evaluation under Development, Demo, and Benchmark rules:

| Forensic Category | Development | Demo | Benchmark | Auditor Verdict |
|---|:---:|:---:|:---:|:---:|
| Hardcoded test results | PASS | PASS | PASS | CLEAN |
| Facade implementation | PASS | PASS | PASS | CLEAN |
| Fabricated verification output | PASS | PASS | PASS | CLEAN |
| Binary execution authenticity | PASS | PASS | PASS | CLEAN |
| JNI / javac build clean | PASS | PASS | PASS | CLEAN |

---

## 3. Conclusion & Gate Review Status

Milestone M3 Iteration 3 remediation passes all forensic integrity checks.

**Final Verdict**: **CLEAN**
