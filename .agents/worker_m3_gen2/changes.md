# Detailed Log of Changes — Milestone M3 Remediation (Iteration 2)

**Author**: Implementation Worker 2 (`worker_m3_gen2`)  
**Date**: 2026-08-06  
**Target Component**: `packages/apps/LinuxTerminal/`  

---

## 1. `packages/apps/LinuxTerminal/jni/Android.bp` & `Android.bp`
- Added all 7 real `libvterm/src/*.c` C sources (`vterm.c`, `screen.c`, `state.c`, `parser.c`, `pen.c`, `unicode.c`, `encoding.c`) to `srcs` of `libterminal_jni`.
- Set `include_dirs` to include `packages/apps/LinuxTerminal/jni/libvterm/include`.
- Ensured `jni_libs` in `packages/apps/LinuxTerminal/Android.bp` references `"libterminal_jni"`.

## 2. Genuine `libvterm` C Library Integration & Fake Stub Removal
- **`packages/apps/LinuxTerminal/jni/third_party/libvterm/vterm.h`**: Fixed line 62 `boolean boolean_val;` to `int boolean_val;` resolving g++ `unknown type name 'boolean'` compiler errors.
- **`packages/apps/LinuxTerminal/jni/vterm_parser.h`**: Changed `#include "third_party/libvterm/vterm.h"` to `#include "libvterm/include/vterm.h"` and updated `cbSetTermProp` signature to `(int prop, void* val, void* user)`.
- **`packages/apps/LinuxTerminal/jni/vterm_parser.cpp`**: Removed the fake `extern "C"` block (lines 23-119) that mocked `vterm_new` and `vterm_input_write` with toy cursor logic. Replaced with genuine `libvterm` API calls (`vterm_new`, `vterm_set_utf8`, `vterm_obtain_screen`, `vterm_screen_set_callbacks`, `vterm_screen_get_cell`, `vterm_input_write`). Wired Alt Screen detection (`prop == 1049` / `prop == 3`), 10,000 line scrollback buffer (`cbPushLine`), and multi-byte UTF-8 partial tail buffering across socket packet boundaries.

## 3. ANativeWindow JNI Bridge & Native Surface Rendering
- **`packages/apps/LinuxTerminal/jni/terminal_renderer.h`**: Added platform guards (`#if __has_include(<android/native_window.h>)`) to allow cross-platform compilation of native renderer code on host environments while remaining fully compatible with Android NDK headers.
- **`packages/apps/LinuxTerminal/jni/terminal_renderer.cpp`**: Exported 5 JNI surface rendering methods:
  - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeInitRenderer`
  - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeSetSurface` (uses `ANativeWindow_fromSurface`)
  - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeReleaseSurface` (uses `ANativeWindow_release`)
  - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeRenderFrame` (uses `ANativeWindow_lock` and `ANativeWindow_unlockAndPost`)
  - `Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeDestroyRenderer`

## 4. JNI Memory Safety & Thread Attachment Fixes
- **`packages/apps/LinuxTerminal/jni/libvterm_jni.cpp`**: Verified local reference cleanup (`env->DeleteLocalRef(cbClass)`) after `GetMethodID` calls, and ensured `cb_damage`, `cb_movecursor`, and `cb_settermprop` use `AttachCurrentThread` and `DetachCurrentThread` when `GetEnv` returns `JNI_EDETACHED` on background PTY reader threads.

## 5. Java Syntax & Package Fixes
- **`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`**: Added imports for subpackage classes (`.ime.TerminalInputConnection`, `.net.PtySender`, `.net.VsockPtyFramer`, `.parser.VTermParser`, `.touch.SgrMouseProtocolGenerator`, `.touch.TouchModeManager`, `.touch.TouchModeStateMachine`). Replaced hardcoded string canvas drawing with genuine cell matrix drawing from `VTermParser`. Implemented `PtySender` interface methods (`sendFrame`, `sendResize`).
- **`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalActivity.java`**: Updated activity to instantiate `TerminalSurfaceView`.
- **`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/CJKImeHandler.java`**: Updated constructor and method invocations to match `CjkComposingWindow` and `CjkComposingTextManager` APIs.
- **`tests/unit/TerminalAppUnitTest.java`**: Replaced non-standard C-style `"\x1b"` hex string escapes with standard Java `"\033"` octal escapes in SGR mouse protocol test assertions. Added initial dirty rect clearing before `markDirtyCell` test.
- **`packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java` & `src/android/graphics/Rect.java`**: Created lightweight helper stubs to enable clean 0-error standalone `javac` compilation and host JVM unit test execution.

---

## Verification Summary
1. **Java Package Compilation**: `javac` exited with code 0 (0 compilation errors).
2. **Java Unit Tests**: `TerminalAppUnitTest` executed via `java` — `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`.
3. **C++ Native Unit Tests**: `m3_native_terminal_test` & `m3_native_challenger2_stress` compiled with `g++` — `ALL NATIVE C++ STRESS TESTS PASSED SUCCESSFULLY`.
4. **E2E Pytest Runner**: `python3 tests/e2e/runner.py --filter F-R3` — 80 / 80 tests PASSED (100.0% Pass Rate).
