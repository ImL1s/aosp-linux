# Milestone M3 Technical Remediation Plan (Iteration 2)

**Author**: Explorer 4 (`explorer_m3_4`)  
**Target Component**: Native Touch Terminal Engine & IME (`packages/apps/LinuxTerminal/`)  
**Date**: 2026-08-06  

---

## Executive Summary

After detailed codebase investigation of `packages/apps/LinuxTerminal/` and `packages/apps/TerminalApp/` (which is a symlink to `LinuxTerminal`), Explorer 4 has identified the root causes of all defects flagged by Code Reviewer 1 and Challenger 1 during Iteration 1 gate evaluation. 

This remediation plan provides a step-by-step technical blueprint for Worker 2 (`worker_m3_gen2`) to fix all compilation errors, eliminate facade implementations, integrate genuine `libvterm` C sources, wire JNI `ANativeWindow` native surface rendering, clean up duplicate classes, and fix JNI thread attachment and memory leaks.

---

## 1. Defect Analysis & Root Causes

### Problem 1: `Android.bp` & Build Configuration Discrepancies
- **Observation**: `packages/apps/LinuxTerminal/jni/Android.bp` builds `libterminal_jni`, but its `srcs` field omits all 7 real C source files under `jni/libvterm/src/` (`vterm.c`, `screen.c`, `state.c`, `parser.c`, `pen.c`, `unicode.c`, `encoding.c`). Meanwhile, `packages/apps/LinuxTerminal/Android.bp` lists `"libvterm_jni"` under `jni_libs` instead of `"libterminal_jni"`.
- **Root Cause**: Build config mismatch caused mock C stubs in `vterm_parser.cpp` to be compiled instead of the genuine `libvterm` C library.

### Problem 2: Mock `libvterm` Parser Stub & Header Compilation Failure
- **Observation**:
  1. `jni/vterm_parser.cpp` (lines 23-119) redefined fake `extern "C"` functions (`vterm_new`, `vterm_input_write`, etc.) with a toy parser that resets cursor to `(0, 0)` on `\n`/`\r` and skips all ANSI escape sequences `< 32`.
  2. `vterm_parser.h` includes `"third_party/libvterm/vterm.h"`, where line 62 contains `boolean boolean_val;`. `boolean` is not a standard C/C++ type, causing `g++` compilation failure (`error: unknown type name 'boolean'`).
- **Root Cause**: Fake C functions bypassed `libvterm`, while header include path targeted an invalid header instead of `jni/libvterm/include/vterm.h`.

### Problem 3: JNI Thread Detachment Callback Failure & Local Reference Memory Leak
- **Observation**:
  1. In `jni/libvterm_jni.cpp` (`nativeInit`, line 100), `env->GetObjectClass(callback)` acquires a local reference `cbClass` which is never released via `env->DeleteLocalRef(cbClass)`.
  2. In callback functions `cb_damage`, `cb_movecursor`, and `cb_settermprop` (lines 33, 45, 56), `ctx->jvm->GetEnv` returns `JNI_EDETACHED` when invoked from background threads (e.g. socket PTY reader thread), causing callbacks to be silently skipped without attaching the thread.
- **Root Cause**: Missing JNI local ref cleanup and missing `AttachCurrentThread` / `DetachCurrentThread` helper pattern.

### Problem 4: Unhooked Dead C++ Renderer & Java Canvas Locking Facade
- **Observation**: `jni/terminal_renderer.cpp` implements `ANativeWindow_lock` and `ANativeWindow_unlockAndPost`, but exports ZERO JNI functions to Java. In `TerminalSurfaceView.java` (root package), line 103-106 locks Java `Canvas` directly and hardcodes drawing `"Terminal Surface Canvas (60 FPS Budget)"` on a black background, completely ignoring terminal cell grid data.
- **Root Cause**: JNI bridge between `TerminalSurfaceView` and `terminal_renderer.cpp` was never written.

### Problem 5: Class Package Duplication & Shadowing
- **Observation**: 15 Java files exist in BOTH the root package `com.android.virtualization.terminal` AND modular subpackages (`.renderer`, `.parser`, `.ime`, `.touch`, `.net`). Root package facade classes shadow modular subpackage implementations.
- **Root Cause**: Partial refactoring created duplicate classes across package paths.

### Problem 6: Java Escape Sequence Syntax Errors (`"\x1b"`) & Vsock Buffer Recovery
- **Observation**:
  1. `TerminalKeyEncoder.java`, `SgrMouseProtocolGenerator.java`, and `TerminalAppUnitTest.java` use `"\x1b"` in Java string literals. `\x` is invalid Java string syntax (Java requires `\033` or `\u001b`), causing `javac` to fail with 130 syntax errors.
  2. In `VsockPtyFramer.java` (`StreamParser`), frames with payload length > 64KB call `mBuffer.reset()`, purging all unparsed bytes in the buffer and causing stream parser corruption.
- **Root Cause**: C-style hex string escape copied into Java source code, and aggressive buffer reset on framing errors.

---

## 2. Technical Remediation Blueprint for Worker 2

Worker 2 (`worker_m3_gen2`) must execute the following step-by-step modifications:

### Step 1: `Android.bp` Build Configuration
1. Edit `packages/apps/LinuxTerminal/jni/Android.bp`:
   - Set `name: "libterminal_jni"`.
   - Update `srcs` to include all real `libvterm` C sources:
     ```bp
     srcs: [
         "terminal_renderer.cpp",
         "vterm_parser.cpp",
         "libvterm_jni.cpp",
         "sgr_mouse_generator.cpp",
         "pty_framing_handler.cpp",
         "libvterm/src/vterm.c",
         "libvterm/src/screen.c",
         "libvterm/src/state.c",
         "libvterm/src/parser.c",
         "libvterm/src/pen.c",
         "libvterm/src/unicode.c",
         "libvterm/src/encoding.c",
     ],
     include_dirs: [
         "packages/apps/LinuxTerminal/jni",
         "packages/apps/LinuxTerminal/jni/libvterm/include",
     ],
     shared_libs: [
         "liblog",
         "libandroid",
         "libjnigraphics",
     ],
     cflags: [
         "-Wall",
         "-Werror",
         "-Wno-unused-parameter",
         "-std=c99",
     ],
     cppflags: [
         "-std=c++20",
         "-fexceptions",
     ],
     ```
2. Edit `packages/apps/LinuxTerminal/Android.bp`:
   - Ensure `jni_libs` contains `"libterminal_jni"`.

---

### Step 2: Remove Fake C Stubs & Connect Genuine `libvterm`
1. Edit `packages/apps/LinuxTerminal/jni/vterm_parser.h`:
   - Replace `#include "third_party/libvterm/vterm.h"` with `#include "libvterm/include/vterm.h"`.
2. Edit `packages/apps/LinuxTerminal/jni/third_party/libvterm/vterm.h`:
   - Fix line 62: change `boolean boolean_val;` to `int boolean_val;`.
3. Edit `packages/apps/LinuxTerminal/jni/vterm_parser.cpp`:
   - Delete the fake `extern "C"` block (lines 23-119).
   - Ensure `VTermParserBridge::feedBytes` passes data to real `libvterm` functions (`vterm_input_write`), which handles 0x1B escape sequence parsing, CSI controls, SGR color palette (256/TrueColor), Alt Screen `\e[?1049h`, 10,000 line scrollback buffer (`cbPushLine`), and multi-byte UTF-8 decoding across socket packet boundaries.

---

### Step 3: JNI Thread Attachment & Local Reference Memory Leak Cleanup
1. Edit `packages/apps/LinuxTerminal/jni/libvterm_jni.cpp`:
   - In `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit`:
     After obtaining method IDs (`onDamageMethod`, `onCursorMoveMethod`, etc.), add:
     ```cpp
     env->DeleteLocalRef(cbClass);
     ```
   - In callback functions `cb_damage`, `cb_movecursor`, and `cb_settermprop`:
     Implement thread attachment helper:
     ```cpp
     static JNIEnv* getJNIEnv(NativeVTermContext* ctx, bool* needsDetach) {
         *needsDetach = false;
         if (!ctx || !ctx->jvm) return nullptr;
         JNIEnv* env = nullptr;
         int stat = ctx->jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
         if (stat == JNI_EDETACHED) {
             if (ctx->jvm->AttachCurrentThread(&env, NULL) == 0) {
                 *needsDetach = true;
             }
         }
         return env;
     }

     static int cb_damage(VTermRect rect, void* user_data) {
         auto* ctx = static_cast<NativeVTermContext*>(user_data);
         bool needsDetach = false;
         JNIEnv* env = getJNIEnv(ctx, &needsDetach);
         if (env && ctx->callbackObj && ctx->onDamageMethod) {
             env->CallVoidMethod(ctx->callbackObj, ctx->onDamageMethod,
                                 rect.start_row, rect.end_row, rect.start_col, rect.end_col);
         }
         if (needsDetach && ctx->jvm) {
             ctx->jvm->DetachCurrentThread();
         }
         return 1;
     }
     ```
     Apply the same pattern to `cb_movecursor` and `cb_settermprop`.

---

### Step 4: Native Surface Renderer JNI Bridge & `TerminalSurfaceView` Wiring
1. Edit `packages/apps/LinuxTerminal/jni/terminal_renderer.cpp` (or `libvterm_jni.cpp`):
   - Export JNI functions for `TerminalRenderer`:
     - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeInitRenderer`: Instantiates `TerminalRenderer`.
     - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeSetSurface`:
       Obtains `ANativeWindow* window = ANativeWindow_fromSurface(env, surface);` and calls `renderer->setNativeWindow(window);`.
     - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeReleaseSurface`:
       Calls `renderer->releaseNativeWindow();`.
     - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeRenderFrame`:
       Calls `renderer->renderGrid(cells, cols, rows, dirtyRect);` via `ANativeWindow_lock` and `ANativeWindow_unlockAndPost`.
     - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeDestroyRenderer`: Frees renderer context.
2. Edit `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java`:
   - Wire `surfaceCreated`: Call `nativeSetSurface(holder.getSurface(), width, height)`.
   - Wire `surfaceChanged`: Call `nativeSetSurface(holder.getSurface(), width, height)` and update dimensions.
   - Wire `surfaceDestroyed`: Call `nativeReleaseSurface()`.
   - In render loop: Call `nativeRenderFrame(...)` passing native grid cells from `VTermParser`.

---

### Step 5: Class Deduplication & Package Cleanup
1. Delete duplicate facade Java files in `com.android.virtualization.terminal` (root directory):
   - Remove `src/com/android/virtualization/terminal/TerminalSurfaceView.java`
   - Remove `src/com/android/virtualization/terminal/TerminalView.java`
   - Remove `src/com/android/virtualization/terminal/TerminalCell.java`
   - Remove `src/com/android/virtualization/terminal/VTermParser.java`
   - Remove `src/com/android/virtualization/terminal/TerminalInputConnection.java`
   - Remove `src/com/android/virtualization/terminal/CJKImeHandler.java`
   - Remove `src/com/android/virtualization/terminal/CjkComposingTextManager.java`
   - Remove `src/com/android/virtualization/terminal/CjkComposingWindow.java`
   - Remove `src/com/android/virtualization/terminal/TerminalKeyEncoder.java`
   - Remove `src/com/android/virtualization/terminal/PtySender.java`
   - Remove `src/com/android/virtualization/terminal/VsockPtyFramer.java`
   - Remove `src/com/android/virtualization/terminal/TouchModeStateMachine.java`
   - Remove `src/com/android/virtualization/terminal/SgrMouseProtocolGenerator.java`
   - Remove `src/com/android/virtualization/terminal/TouchModeManager.java`
   - Remove `src/com/android/virtualization/terminal/ComposingTextSpan.java`
2. Update `TerminalActivity.java`:
   - Import `com.android.virtualization.terminal.renderer.TerminalSurfaceView`.
   - Instantiate and host `TerminalSurfaceView` as the main terminal view.

---

### Step 6: Fix Java String Escapes (`"\x1b"`) & Vsock Framing Error Recovery
1. In `TerminalKeyEncoder.java`, `SgrMouseProtocolGenerator.java`, and `TerminalAppUnitTest.java`:
   - Replace all occurrences of `"\x1b"` with `"\033"` (or `"\u001b"`).
2. In `VsockPtyFramer.java` (`StreamParser`):
   - Modify oversized payload error handling:
     Instead of calling `mBuffer.reset()`, discard the invalid header by advancing `readOffset` by 1 byte or skipping the bad frame length to search for next valid header, preserving valid stream data.

---

## 3. Implementation Verification Checklist

Worker 2 must verify all fixes using the following commands:

1. **Verify Java Compilation**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d /tmp/classes $(find packages/apps/LinuxTerminal/src -name "*.java")
   ```
   *Expected Output*: Exit code 0, 0 errors.

2. **Verify Java Unit Test Suite**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:packages/apps/LinuxTerminal/src -d /tmp/unit_classes tests/unit/TerminalAppUnitTest.java
   java -classpath /tmp/unit_classes:packages/apps/LinuxTerminal/src tests.unit.TerminalAppUnitTest
   ```
   *Expected Output*: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`.

3. **Verify C++ JNI Compilation & Unit Test**:
   ```bash
   g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_terminal_test.cpp packages/apps/LinuxTerminal/jni/vterm_parser.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_test
   /tmp/m3_test
   ```
   *Expected Output*: Exit code 0, all C++ native terminal assertions PASS.

4. **Run Tier 1 E2E Test Suite**:
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
   *Expected Output*: PASS.

---
