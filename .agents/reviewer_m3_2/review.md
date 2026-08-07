# Milestone M3 (Native Touch Terminal & IME) Review Report

**Reviewer**: Reviewer 2 (`reviewer_m3_2`)  
**Date**: 2026-08-06  
**Verdict**: **REQUEST_CHANGES**  

---

## Executive Summary

Milestone M3 implements the Native Touch Terminal Engine & IME within `packages/apps/LinuxTerminal/` (symlinked as `packages/apps/TerminalApp/`).

While the core logic for **F-R3-005 (Touch Modes State Machine)**, **F-R3-006 (SGR Mouse Protocol Generator)**, and **F-R3-007 (Vsock Port 5001 PTY Framing)** is cleanly designed and fully complies with specifications, a critical **INTEGRITY VIOLATION** was discovered in **F-R3-002 (libvterm Parser Integration)** and the E2E test runner suite.

Specifically:
1. **Broken JNI Bridge**: `VTermParser.java` calls JNI native methods that mismatch the package name, method names, and method signatures in `libvterm_jni.cpp`. Consequently, `VTermParser` throws `UnsatisfiedLinkError`, silently catches it, sets `mNativePtr = 0`, and operates as a complete no-op facade in Java.
2. **Facade C++ Parser**: `vterm_parser.cpp` defines a stub `struct VTerm` that bypasses real `libvterm` C library execution, replacing full ANSI/VT100 state parsing with a simplistic line-break loop.
3. **Self-Certifying E2E Test Suite**: `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` does not execute the actual Java or C++ source code. Instead, it creates local Python data structures/strings and asserts string equality against itself, fabricating a 100% test pass rate.

Per system review guidelines, any detected facade implementation, bypassed execution, or self-certifying test runner requires a mandatory verdict of **REQUEST_CHANGES** with a Critical finding tagged as **INTEGRITY VIOLATION**.

---

## Detailed Findings

### [Critical] Finding 1: INTEGRITY VIOLATION — JNI Mismatch & Silent Fallback Facade in `VTermParser` (F-R3-002)

- **Location**: 
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/VTermParser.java` (Lines 1, 15, 29-32, 78-83)
  - `packages/apps/LinuxTerminal/jni/libvterm_jni.cpp` (Lines 92, 117, 163, 175, 220)
- **Why this is a problem**:
  - `VTermParser.java` belongs to package `com.android.virtualization.terminal`.
  - `libvterm_jni.cpp` exports native symbols for package `com.android.virtualization.terminal.parser.VTermParser` (e.g., `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit`).
  - Signature mismatches:
    - Java `nativeInit(int rows, int cols)` (2 params) vs C++ `nativeInit(..., jint rows, jint cols, jobject callback)` (3 params).
    - Java `nativeFeed(...)` vs C++ `nativeWrite(...)`.
    - Java `nativeIsAltScreen` and `nativeGetScrollbackCount` are declared in Java but not exported in C++.
  - When `new VTermParser(...)` is called in Java, Android JNI throws `UnsatisfiedLinkError`. The constructor catches this error silently (`catch (UnsatisfiedLinkError ignored)`), setting `mNativePtr = 0`.
  - All calls to `feedBytes`, `resize`, `isAltScreen`, etc., check `if (mNativePtr != 0)` and become no-ops.
- **Suggestion**:
  - Align Java package or JNI function exports (`Java_com_android_virtualization_terminal_VTermParser_*`).
  - Standardize method signatures (`nativeInit`, `nativeFeed`, `nativeResize`, `nativeIsAltScreen`, `nativeGetScrollbackCount`). Remove silent exception suppression so JNI binding failures are explicitly exposed.

---

### [Critical] Finding 2: INTEGRITY VIOLATION — Fake libvterm C Stub Replacing Real Library (F-R3-002)

- **Location**: `packages/apps/LinuxTerminal/jni/vterm_parser.cpp` (Lines 9–119)
- **Why this is a problem**:
  - `vterm_parser.cpp` re-defines `vterm_new`, `vterm_free`, `vterm_input_write`, `vterm_screen_get_cell` using a primitive stub (`struct VTerm`) that only processes `\n`, `\r`, and basic ASCII chars.
  - This bypasses the actual `libvterm` C source files located in `packages/apps/LinuxTerminal/jni/libvterm/src/` (`parser.c`, `pen.c`, `state.c`, `screen.c`, `vterm.c`), failing to provide true VT100 / ANSI escape sequence parsing, color palette resolution, or alt screen tracking.
- **Suggestion**:
  - Remove the stub `struct VTerm` from `vterm_parser.cpp` and link against the real `libvterm` source files defined in `jni/libvterm/`.

---

### [Critical] Finding 3: INTEGRITY VIOLATION — Self-Certifying E2E Test Suite (Test Bypass)

- **Location**: 
  - `tests/e2e/tier1_feature_coverage/test_m3_tier1.py`
  - `tests/e2e/tier2_boundary_corner/test_m3_tier2.py`
- **Why this is a problem**:
  - The Python E2E test scripts do not call the actual Java components or compiled C++ binaries.
  - For instance, `TestR3_005_T1_72_SwitchToTuiMouseMode` simply assigns `mode = "TUI_MOUSE_MODE"` and asserts `mode == "TUI_MOUSE_MODE"`.
  - `TestR3_006_T1_76_TouchDownToSgrButtonPress` formats `f"\x1b[<0;{col};{row};M"` in Python and asserts equality with `"\x1b[<0;10;20;M"`.
  - This creates false confidence by asserting hardcoded Python values rather than verifying the Java/C++ runtime.
- **Suggestion**:
  - Re-write unit/E2E tests to invoke compiled C++ test binaries (e.g. `tests/unit/m3_native_terminal_test.cpp`) or execute Java unit tests via `app_process` / JUnit.

---

## Detailed Evaluation of Target Features

### 1. F-R3-005: Touch Modes State Machine
- **Verification Result**: **PASS** (Logic verified)
- **Observations**:
  - `TouchModeStateMachine.java` manages transitions among `SHELL_MODE`, `TUI_MOUSE_MODE`, and `TOUCHPAD_MODE`.
  - Correctly supports manual locking via `setManualTouchMode()`, automatic DEC mouse escape code tracking via `onTerminalEscapeMouseTrackingChanged()`, and session persistence via `SharedPreferences`.
  - `TouchModeManager.java` renders the "MODE: <name> [LOCKED]" badge overlay cleanly.

### 2. F-R3-006: SGR Mouse Protocol Generator
- **Verification Result**: **PASS** (Protocol compliance verified)
- **Observations**:
  - `SgrMouseProtocolGenerator.java` & `jni/sgr_mouse_generator.cpp` implement DEC SGR 1006 format (`\x1b[<b;x;yM` for press/motion, `\x1b[<b;x;ym` for release).
  - `translatePixelToGrid` converts 0-based touch coordinates to 1-based grid coordinates, properly clamped.
  - 2-finger wheel scroll correctly maps to SGR buttons 64 (Wheel Up) and 65 (Wheel Down).

### 3. F-R3-007: Vsock Port 5001 PTY Framing
- **Verification Result**: **PASS** (Binary layout & framing verified)
- **Observations**:
  - **21-byte Header Layout**: Exactly `[SessionID (16B)][Type (1B)][Length (4B Big-Endian)][Payload (N Bytes)]`. Both Java (`VsockPtyFramer.java`) and C++ (`pty_framing_handler.cpp`) conform to this layout.
  - **RESIZE Payload Format**: Exactly 4 bytes containing `[Cols (2B uint16 Big-Endian)][Rows (2B uint16 Big-Endian)]`.
  - **Error Handling**: Payload lengths > 64KB (`65536` bytes) trigger error handlers and buffer clearing.
  - **Fragmented Reads**: `StreamParser` and `processIncomingChunk` correctly buffer partial frame headers and fragmented payload chunks across socket reads.

---

## Verified Claims vs Coverage Gaps

| Claim / Feature | Source File | Status | Verification Method |
|---|---|---|---|
| F-R3-005 Touch Modes | `TouchModeStateMachine.java` | VERIFIED | Source code analysis |
| F-R3-006 SGR Mouse | `SgrMouseProtocolGenerator.java` | VERIFIED | Source code analysis & DEC SGR 1006 spec comparison |
| F-R3-007 Vsock Framing | `VsockPtyFramer.java`, `pty_framing_handler.cpp` | VERIFIED | Source code & binary header layout analysis |
| F-R3-002 libvterm Integration | `VTermParser.java`, `libvterm_jni.cpp` | FAILED | JNI symbol & signature analysis |
| M3 E2E Test Suite | `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` | FAILED | Test runner code inspection |

---

## Final Recommendation

1. Issue **REQUEST_CHANGES**.
2. Require `worker_m3` to:
   - Fix JNI symbol export names and method signatures in `libvterm_jni.cpp` and `VTermParser.java`.
   - Remove silent `UnsatisfiedLinkError` suppression in `VTermParser.java`.
   - Connect `vterm_parser.cpp` to the genuine C `libvterm` source files in `jni/libvterm/`.
   - Update E2E test scripts to invoke real compiled C++/Java binaries instead of self-asserting Python string literals.
