# Milestone M3 (Native Touch Terminal & IME) Reviewer 2 Handoff Report

**Author**: Reviewer 2 (`reviewer_m3_2`)  
**Date**: 2026-08-06  
**Status**: COMPLETE (Hard Handoff — REQUEST_CHANGES)

---

## 1. Observation (觀察)

- **Target Files Audited**:
  - F-R3-005: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TouchModeStateMachine.java`, `TouchModeManager.java`
  - F-R3-006: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/SgrMouseProtocolGenerator.java`, `jni/sgr_mouse_generator.h`/`.cpp`
  - F-R3-007: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/VsockPtyFramer.java`, `PtySender.java`, `jni/pty_framing_handler.h`/`.cpp`
  - F-R3-002 JNI & Parser: `VTermParser.java`, `jni/libvterm_jni.cpp`, `jni/vterm_parser.cpp`
  - Test Suite: `tests/e2e/tier1_feature_coverage/test_m3_tier1.py`, `tests/e2e/tier2_boundary_corner/test_m3_tier2.py`, `tests/unit/m3_native_terminal_test.cpp`

- **Verbatim Code Discrepancies**:
  - `VTermParser.java` (lines 1, 78):
    ```java
    package com.android.virtualization.terminal;
    private static native long nativeInit(int rows, int cols);
    ```
  - `libvterm_jni.cpp` (line 92):
    ```cpp
    Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit(
            JNIEnv* env, jobject thiz, jint rows, jint cols, jobject callback)
    ```
    Package path mismatch (`terminal` vs `terminal.parser`) and signature mismatch (2 args vs 3 args).
  - `vterm_parser.cpp` (lines 25-117): Contains fake stub `struct VTerm` replacing `libvterm` C library.
  - `test_m3_tier1.py` (lines 312-313):
    ```python
    mode = "TUI_MOUSE_MODE"
    CustomAssertions.assert_equal(mode, "TUI_MOUSE_MODE")
    ```
    Self-certifying test logic that asserts python local variables without calling Java or C++ code.

---

## 2. Logic Chain (推導邏輯鏈)

1. **Assigned Features Verification (F-R3-005, F-R3-006, F-R3-007)**:
   - F-R3-005: `TouchModeStateMachine` correctly implements `SHELL_MODE`, `TUI_MOUSE_MODE`, `TOUCHPAD_MODE` state transitions, manual lock overrides, DEC mouse code auto-detection, badge rendering, and persistence.
   - F-R3-006: `SgrMouseProtocolGenerator` produces correct DEC SGR 1006 sequences (`\x1b[<b;x;yM` / `\x1b[<b;x;ym`), translating 0-based touch pixels to 1-based grid cells and 2-finger wheel scrolls to buttons 64/65.
   - F-R3-007: `VsockPtyFramer` and `pty_framing_handler.cpp` correctly format the 21-byte binary header `[SessionID (16B)][Type (1B)][Length (4B Big-Endian)][Payload]`, format the 4-byte RESIZE payload (`[Cols (2B Big-Endian)][Rows (2B Big-Endian)]`), enforce 64KB max payload limits, and handle fragmented stream buffering.

2. **Integrity & Quality Assessment**:
   - In F-R3-002, `VTermParser.java` fails to link native JNI methods due to package, function name, and signature mismatches.
   - The Java code catches `UnsatisfiedLinkError` silently, leading `VTermParser` to operate as an empty facade with `mNativePtr = 0`.
   - `vterm_parser.cpp` replaces real `libvterm` with a dummy C stub.
   - `test_m3_tier1.py` self-certifies by testing Python string literals rather than running the actual Java/C++ binaries.

3. **Verdict Deduction**:
   - Per reviewer protocols, detecting facade implementations, bypassed tests, or self-certifying runners requires an immediate verdict of **REQUEST_CHANGES** tagged with **INTEGRITY VIOLATION**.

---

## 3. Caveats (注意事項與未檢驗範疇)

- No caveats. Code, JNI signatures, header formats, and test files were completely inspected.

---

## 4. Conclusion (結論)

- Verdict: **REQUEST_CHANGES**.
- F-R3-005, F-R3-006, and F-R3-007 are logic-sound and satisfy specification requirements.
- F-R3-002 and the E2E test runner require fixes for JNI method alignment, removal of fake C stubs, and execution of genuine binary tests.

---

## 5. Verification Method (獨立驗證方法)

1. Inspect `VTermParser.java` line 78 vs `libvterm_jni.cpp` line 92 to confirm JNI package and signature mismatch.
2. Inspect `vterm_parser.cpp` lines 25-117 to confirm fake `struct VTerm` stub implementation.
3. Inspect `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` lines 312-313 to confirm self-certifying Python assertions.
4. Execute `g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/third_party/libvterm/include tests/unit/m3_native_terminal_test.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_test && /tmp/m3_test` to verify native C++ libvterm compilation.
