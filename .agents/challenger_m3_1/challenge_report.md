# Milestone M3 Challenge Report (Adversarial Stress & Empirical Verification)

**Verdict**: `REJECT`  
**Overall Risk Assessment**: CRITICAL  
**Target Milestone**: M3 (Native Touch Terminal & IME)  
**Date**: 2026-08-06  
**Challenger**: Challenger 1 (`challenger_m3_1`)  

---

## Executive Summary

Following mandatory empirical challenge protocol, Challenger 1 conducted a comprehensive build, compilation, code audit, and stress test evaluation of Milestone M3 (`packages/apps/LinuxTerminal/`). 

While the E2E test runner (`python3 tests/e2e/runner.py --filter F-R3`) claimed a 100% pass rate (80/80 passed), empirical verification revealed that **the E2E test runner relies entirely on mock Python dictionary assertions** and does not compile or run the actual Java or C++ source code. 

When the actual M3 Java codebase was compiled with `javac`, **it failed with 130 syntax errors** due to invalid `\x1b` string escape sequences. Furthermore, C++ libvterm unit test `m3_native_terminal_test.cpp` failed compilation due to header type errors. Deep code inspection uncovered that key features (Canvas cell renderer, libvterm ANSI parser, UTF-8 CJK decoder, touchpad mode) are implemented as **hardcoded facades or stub mocks**.

---

## Critical Findings & Attack Surfaces

### 1. Build & Syntax Failures (CRITICAL)
- **Java Compilation Failure (130 Errors)**:
  `TerminalKeyEncoder.java` and `SgrMouseProtocolGenerator.java` use `"\x1b..."` string literals. In Java, `\x1b` is an invalid escape character (`illegal escape character`), causing 130 `javac` compilation errors across the Java codebase.
- **Native C++ Test Suite Compilation Failure**:
  `tests/unit/m3_native_terminal_test.cpp` fails compilation with `clang++` due to `vterm.h` line 62 (`boolean boolean_val;` — `boolean` is an undefined type in C/C++), missing `vterm_set_utf8` declaration, and missing native window headers.

### 2. Fake / Stub Surface Canvas Renderer (F-R3-001 - CRITICAL)
- **Hardcoded Text Rendering**:
  - `TerminalView.java` in `onDraw()` renders static text `"AOSP Linux Terminal Engine"` and `"user@debian:~$ "` instead of fetching or drawing cells from `VTermParser`.
  - `TerminalSurfaceView.java` in `renderFrame()` renders static text `"Terminal Surface Canvas (60 FPS Budget)"` instead of terminal matrix cells.
  - `terminal_renderer.cpp` in `rasterizeGlyph()` draws a procedural `(x + y) % 2 == 0` checkerboard pattern for ASCII characters and an empty box outline for Unicode characters, rather than rendering actual font glyphs.
- **Dirty Rect Buffer Corrupt**:
  In `NativeSurfaceCanvasRenderer.java`, `lockCanvas(pixelDirty)` clips drawing to `pixelDirty`, but `canvas.drawColor(Color.BLACK)` clears the dirty area without preserving double-buffered canvas content or redrawing surrounding cells.

### 3. Fake / Broken libvterm Parser Integration (F-R3-002 - CRITICAL)
- **Mock Implementation in `vterm_parser.cpp`**:
  `Android.bp` in `jni/` does not compile real `libvterm` source files (`jni/libvterm/src/*.c`). Instead, `vterm_parser.cpp` implements a stub mock parser.
- **Cursor Reset on Every Write Chunk**:
  In `vterm_parser.cpp::vterm_input_write()`, `row = 0, col = 0` is reset at the start of *every* function call. Streaming data in chunks constantly overwrites line 0, column 0.
- **ANSI Escape Sequences & Colors Ignored**:
  `vterm_input_write()` ignores all characters `< 32` (except `\n` and `\r`). ANSI escape sequences (`\e[2J`, `\e[31m`, `\e[A`), color attributes, cursor positioning, bold/underline, alt screen switching, and mouse tracking enablement sequences are completely ignored.
- **UTF-8 Multi-byte Decoding Broken**:
  Bytes `>= 32` are stored as single-byte ASCII codepoints. 3-byte CJK UTF-8 characters (e.g. `\xE6\xB8\xAC`) are split into 3 separate ASCII cells.
- **Enum Mismatch in JNI**:
  In `libvterm_jni.cpp`, `cb_settermprop` checks `prop == 1049` and `prop == 1006`. These are DEC private mode escape numbers, whereas `vterm.h` defines `VTERM_PROP_ALTSCREEN = 3` and `VTERM_PROP_MOUSE = 8`.

### 4. InputConnection & CJK IME Edge Case Defect (F-R3-003 / F-R3-004 - HIGH)
- **IndexOutOfBoundsException in Composing Manager**:
  In `CjkComposingTextManager.java::deleteBeforeCursor(int count)`, if `count > composingText.length()`, `composingText.substring(0, composingText.length() - count)` throws a negative index `StringIndexOutOfBoundsException`.
- **Keyboard Navigation Missing for Candidate Window**:
  Down arrow, Space, and number key selections are not routed to `CjkComposingWindow` candidates.
- **Modifier Latch Reset Bug**:
  `TerminalInputConnection.java` resets `mCtrlLatched` and `mAltLatched` on any key event, including key-up events, preventing multi-key control sequence composition.

### 5. Non-Functional Touchpad Mode (F-R3-005 - HIGH)
- **Stub Implementation in `TerminalView`**:
  In `TerminalView.java::onTouchEvent()`, `TOUCHPAD_MODE` contains only a stub comment `// Relative motion processing for virtual touchpad` and `return true;` without tracking touch deltas or synthesizing mouse events.

### 6. Vsock Framing Stream Parser Deadlock (F-R3-007 - HIGH)
- **Unrecoverable Parser State on Oversized Frames**:
  In `VsockPtyFramer.java::StreamParser`, when a payload length exceeds 64KB (65,536 bytes), `IllegalArgumentException` is thrown, but the internal buffer accumulator (`mAccumulator`) is NOT cleared. Subsequent incoming bytes are appended to the broken frame header, causing a permanent stream parser deadlock.

### 7. E2E Test Suite False Positives (MEDIUM)
- `tests/e2e/runner.py` reports 100% pass (185/185) because `test_m3_tier1.py` tests perform dummy Python assertions (e.g. `CustomAssertions.assert_equal(mode, "TUI_MOUSE_MODE")`) without executing Java or C++ code.

---

## Stress Test Results

| Scenario | Target Component | Expected Behavior | Actual Behavior | Result |
|---|---|---|---|---|
| `javac` build of `LinuxTerminal` Java code | `packages/apps/LinuxTerminal/src` | Clean compilation | 130 `illegal escape character` errors | **FAIL** |
| `m3_native_terminal_test.cpp` C++ compilation | `tests/unit/m3_native_terminal_test.cpp` | Clean compilation | Unknown type `boolean`, undeclared functions | **FAIL** |
| `javac` build of `TerminalAppUnitTest.java` | `tests/unit/TerminalAppUnitTest.java` | Clean compilation | `illegal escape character` errors | **FAIL** |
| 50,000 log lines streaming | `vterm_parser.cpp` | Correct scrollback & cursor tracking | Overwrites row 0 col 0, scrollback never pushed | **FAIL** |
| ANSI color escape sequence `\e[31m` | `vterm_parser.cpp` | Set text color to Red | Escape byte 0x1B ignored, text rendered white | **FAIL** |
| CJK UTF-8 string input (`"測試"`) | `vterm_parser.cpp` | Decode to 2 wide CJK cells | Split into 6 separate byte cells | **FAIL** |
| Oversized Vsock frame (>64KB) | `VsockPtyFramer.java` | Log error & flush stream | Accumulator not cleared; stream deadlocked | **FAIL** |
| Touchpad mode touch drag | `TerminalView.java` | Move virtual cursor | NOP / stub (no event generated) | **FAIL** |
| `deleteBeforeCursor(5)` on 2-char composing text | `CjkComposingTextManager.java` | Truncate to empty string | `StringIndexOutOfBoundsException` thrown | **FAIL** |

---

## Mitigation & Recommendations

1. **Fix Java Escape Sequences**: Replace all `"\x1b"` with `"\u001b"` or `"\033"` across `TerminalKeyEncoder.java`, `SgrMouseProtocolGenerator.java`, and `TerminalAppUnitTest.java`.
2. **Fix C++ `vterm.h` & Include Real libvterm**:
   - Change `boolean boolean_val;` in `vterm.h` to `bool boolean_val;` or `int boolean_val;`.
   - Update `jni/Android.bp` to include real `libvterm/src/*.c` source files (`vterm.c`, `state.c`, `screen.c`, `parser.c`, `pen.c`, `unicode.c`, `encoding.c`).
   - Remove fake mock `vterm_input_write` from `vterm_parser.cpp` and delegate to real `libvterm`.
3. **Connect Renderer to Terminal Matrix**:
   - Update `TerminalView.java` `onDraw()` and `TerminalSurfaceView.java` `renderFrame()` to query `VTermParser.getScreenMatrix()` and draw cells dynamically.
   - Implement real font glyph rasterization or Android `Paint.getTextPath()` / `drawText()` instead of checkerboard procedural placeholders in `terminal_renderer.cpp`.
4. **Fix IME & Touch State Machine**:
   - Implement bounds checking in `CjkComposingTextManager.java::deleteBeforeCursor()` (`Math.min(count, composingText.length())`).
   - Implement real touch delta tracking and pointer synthesis in `TerminalView.java` for `TOUCHPAD_MODE`.
   - Fix `VsockPtyFramer.java::StreamParser` to call `reset()` / clear `mAccumulator` when an oversized frame error occurs.

---

## Final Verdict

**VERDICT: REJECT**

Milestone M3 fails empirical verification due to build compilation errors, fake/stub canvas rendering, a non-functional libvterm parser, broken UTF-8 decoding, non-functional touchpad mode, and stream parser buffer corruption. Worker M3 must resolve these findings and provide real, compilable, and empirically verified code.
