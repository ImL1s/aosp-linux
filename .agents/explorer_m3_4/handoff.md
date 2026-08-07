# Milestone M3 Handoff Report: Technical Remediation Analysis

**Author**: Explorer 4 (`explorer_m3_4`)  
**Date**: 2026-08-06  
**Status**: Hard Handoff — Complete  

---

## 1. Observation

- **Directory Inspected**:
  - `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/`
  - `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/TerminalApp/` (symlink to `LinuxTerminal`)

- **Exact File Paths & Line Numbers Observed**:
  1. `packages/apps/LinuxTerminal/jni/Android.bp` (lines 3-9):
     ```bp
     srcs: [
         "terminal_renderer.cpp",
         "vterm_parser.cpp",
         "libvterm_jni.cpp",
         "sgr_mouse_generator.cpp",
         "pty_framing_handler.cpp",
     ],
     ```
     `libvterm/src/*.c` source files are missing from `srcs`.
  2. `packages/apps/LinuxTerminal/jni/vterm_parser.cpp` (lines 23-119):
     Contains a fake `extern "C"` block defining mock functions `vterm_new`, `vterm_free`, `vterm_input_write` with dummy parsing logic (`row = (row + 1) % vt->rows; col = 0;`), skipping escape codes `< 32`.
  3. `packages/apps/LinuxTerminal/jni/vterm_parser.h` (line 4) & `third_party/libvterm/vterm.h` (line 62):
     ```cpp
     typedef union {
         boolean boolean_val; // Line 62: error: unknown type name 'boolean'
         int number;
         ...
     } VTermValue;
     ```
  4. `packages/apps/LinuxTerminal/jni/libvterm_jni.cpp`:
     - Line 100: `jclass cbClass = env->GetObjectClass(callback);` — `cbClass` is never released with `env->DeleteLocalRef(cbClass)`.
     - Lines 33, 45, 56: `if (ctx && ctx->jvm && ctx->jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK)` — returns `JNI_EDETACHED` on background PTY reader threads, causing `onDamage`, `onCursorMove`, `onAltScreenChanged` callbacks to be skipped.
  5. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalSurfaceView.java` (lines 95-106):
     Locks Java Canvas directly (`mHolder.lockCanvas()`) and hardcodes drawing `"Terminal Surface Canvas (60 FPS Budget)"`. `terminal_renderer.cpp` exports zero JNI functions to Java.
  6. **Class Package Duplication**:
     15 duplicate Java files exist in root package `com.android.virtualization.terminal` and subpackages (`.renderer`, `.parser`, `.ime`, `.touch`, `.net`).
  7. **Java Escape Sequence Syntax Errors (`"\x1b"`)**:
     `TerminalKeyEncoder.java`, `SgrMouseProtocolGenerator.java`, and `TerminalAppUnitTest.java` contain `"\x1b"`. Running `javac` results in 130 `illegal escape character` errors.

---

## 2. Logic Chain

1. **Observation 1 & 2**: `Android.bp` in `jni/` omitted real `libvterm/src/*.c` C files, leading to the creation of a dummy `extern "C"` stub in `vterm_parser.cpp` that ignores ANSI escape sequences, CSI controls, and SGR color palettes.
2. **Observation 3 & 4**: `vterm_parser.h` included an invalid header with `boolean`, while `libvterm_jni.cpp` leaked local JNI references and skipped callbacks when invoked from detached background threads.
3. **Observation 5**: `terminal_renderer.cpp` implemented `ANativeWindow_lock` and `ANativeWindow_unlockAndPost` in C++, but because no JNI bindings were exported, `TerminalSurfaceView.java` fell back to locking Java `Canvas` and drawing hardcoded strings.
4. **Observation 6 & 7**: Partial refactoring created duplicated classes across root and subpackages, and C-style `"\x1b"` escapes broke Java compilation.
5. **Conclusion**: Formulated a comprehensive, 6-step actionable remediation plan for Worker 2 (`worker_m3_gen2`) to fix all build configurations, replace stubs with genuine `libvterm`, wire JNI native surface rendering, clean up duplicate classes, fix JNI thread attachment/memory leaks, and fix Java syntax errors.

---

## 3. Caveats

- **No caveats**. The codebase files, header include issues, duplicate packages, missing JNI exports, thread detachment behavior, and syntax errors were empirically verified across `packages/apps/LinuxTerminal/` and `packages/apps/TerminalApp/`.

---

## 4. Conclusion

- Explorer 4 has completed full codebase analysis and produced a step-by-step remediation plan in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_4/analysis.md`.
- Worker 2 (`worker_m3_gen2`) can directly execute the 6 steps detailed in `analysis.md` to resolve all Iteration 1 defects.

---

## 5. Verification Method

Worker 2 and Reviewers can independently verify remediation using:

1. **Java Package Compilation**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d /tmp/classes $(find packages/apps/LinuxTerminal/src -name "*.java")
   ```
   *Pass Criteria*: Exits 0, 0 compilation errors.

2. **Java Unit Test Execution**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:packages/apps/LinuxTerminal/src -d /tmp/unit_classes tests/unit/TerminalAppUnitTest.java
   java -classpath /tmp/unit_classes:packages/apps/LinuxTerminal/src tests.unit.TerminalAppUnitTest
   ```
   *Pass Criteria*: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`.

3. **C++ Native Build & Unit Test**:
   ```bash
   g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_terminal_test.cpp packages/apps/LinuxTerminal/jni/vterm_parser.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_test
   /tmp/m3_test
   ```
   *Pass Criteria*: Exits 0, native terminal assertions PASS.

4. **Tier 1 E2E Integration Test**:
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
   *Pass Criteria*: All M3 integration tests PASS.
