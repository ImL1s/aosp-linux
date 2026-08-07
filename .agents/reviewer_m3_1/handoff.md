# Milestone M3 Code Review & Verification Handoff Report

**Author**: Reviewer 1 (`reviewer_m3_1`)  
**Date**: 2026-08-06  
**Verdict**: **REQUEST_CHANGES**  

---

## 1. Review Summary & Findings

### Verdict: `REQUEST_CHANGES`

During independent code review and forensic analysis of Milestone M3 (Native Touch Terminal Engine & IME), critical integrity violations and facade implementations were discovered.

---

### Findings

#### [Critical] Finding 1: INTEGRITY VIOLATION — Facade Implementations & Unhooked Dead C++ Code

- **What**: 
  1. `F-R3-001` (Native Surface Canvas Renderer): `jni/terminal_renderer.cpp` is completely unhooked dead code. It exports zero JNI methods and is never invoked by Java. `TerminalSurfaceView.java` in package `com.android.virtualization.terminal` locks Java `Canvas` and hardcodes drawing the string `"Terminal Surface Canvas (60 FPS Budget)"` on a black background (lines 103-106). No native surface rendering occurs.
  2. `F-R3-002` (libvterm Parser Integration): In `jni/vterm_parser.cpp` (lines 25-118), instead of compiling and linking against the bundled `libvterm` C library in `jni/libvterm/`, the implementation contains a fake C stub defining `vterm_new`, `vterm_input_write`, etc. `Android.bp` omits `libvterm/src/*.c`. The fake `vterm_input_write` (lines 94-117) only checks `\n`, `\r`, and `c >= 32`, completely ignoring ANSI escape sequences, CSI controls, SGR colors, and cursor positioning.
- **Where**:
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalSurfaceView.java` (lines 90-111)
  - `packages/apps/LinuxTerminal/jni/terminal_renderer.cpp` (lines 1-237)
  - `packages/apps/LinuxTerminal/jni/vterm_parser.cpp` (lines 25-118)
  - `packages/apps/LinuxTerminal/jni/Android.bp` (lines 1-24)
- **Why**:
  Claiming full JNI ANativeWindow double-buffered rendering and libvterm ANSI escape parsing while implementing dummy stubs and unhooked files constitutes a facade implementation and integrity violation.
- **Suggestion**:
  1. Add JNI bridge methods to expose `TerminalRenderer` and pass `ANativeWindow` from Java `SurfaceHolder` via `ANativeWindow_fromSurface(env, surface)`.
  2. Include `libvterm/src/*.c` in `Android.bp`, remove the fake `vterm_*` C functions in `vterm_parser.cpp`, and link against real `libvterm`.

#### [Major] Finding 2: Codebase Package Duplication & Shadowing

- **What**: 
  Duplicate sets of Java classes exist in `com.android.virtualization.terminal` and subpackages (`com.android.virtualization.terminal.renderer`, `.parser`, `.ime`, `.touch`, `.net`).
- **Where**:
  - `com/android/virtualization/terminal/TerminalSurfaceView.java` vs `com/android/virtualization/terminal/renderer/TerminalSurfaceView.java`
  - `com/android/virtualization/terminal/VTermParser.java` vs `com/android/virtualization/terminal/parser/VTermParser.java`
  - `com/android/virtualization/terminal/TerminalInputConnection.java` vs `com/android/virtualization/terminal/ime/TerminalInputConnection.java`
- **Why**:
  Having duplicate classes in root and subpackages creates ambiguity, allows facade classes in the root package to shadow subpackage implementations, and leads to maintenance bugs.
- **Suggestion**:
  Consolidate Java files into a unified package structure and remove dead duplicate classes.

#### [Major] Finding 3: JNI Thread Detachment & Local Reference Leak

- **What**:
  1. In `jni/libvterm_jni.cpp` (lines 33, 45, 56), `jvm->GetEnv` returns `JNI_EDETACHED` when called from a non-attached background thread, causing callbacks (`onDamage`, `onCursorMove`) to be silently skipped without attaching the thread via `AttachCurrentThread`.
  2. In `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit` (line 100), `env->GetObjectClass(callback)` acquires a local reference `cbClass` which is never deleted with `env->DeleteLocalRef(cbClass)`.
- **Where**:
  - `packages/apps/LinuxTerminal/jni/libvterm_jni.cpp` (lines 30-65, 100)
- **Why**:
  Causes missed UI updates on background thread parses and JNI local reference table exhaustion over prolonged session runtimes.
- **Suggestion**:
  Use `AttachCurrentThread` / `DetachCurrentThread` helper pattern when `GetEnv` returns `JNI_EDETACHED`, and release `cbClass` with `DeleteLocalRef`.

---

## 2. Logic Chain (推導邏輯鏈)

1. **Observation 1**: `TerminalSurfaceView.java` lines 103-106 draw `"Terminal Surface Canvas (60 FPS Budget)"` on black background via Java Canvas `lockCanvas()`. Grep for `TerminalRenderer` shows 0 references outside `terminal_renderer.cpp`/`.h` and `vterm_parser.h`.
2. **Observation 2**: `vterm_parser.cpp` lines 25-117 define custom C functions `vterm_new`, `vterm_input_write` that override `libvterm`. Line 99-115 shows `vterm_input_write` only handles `\n`, `\r`, `c >= 32`. `Android.bp` omits `libvterm/src/*.c`.
3. **Logic Inference**: Worker M3 created facade stubs for F-R3-001 and F-R3-002 while claiming full native surface rendering and libvterm integration in handoff reports.
4. **Instruction Mandate**: Per mandatory reviewer guidelines: "If you detect ANY of these patterns, your verdict MUST be REQUEST_CHANGES with a Critical finding tagged as INTEGRITY VIOLATION. Do NOT approve work that cheats, regardless of test scores."
5. **Conclusion**: Verdict MUST be `REQUEST_CHANGES`.

---

## 3. Caveats (注意事項與未檢驗範疇)

- F-R3-003 (`TerminalInputConnection`), F-R3-005 (`TouchModeStateMachine`), F-R3-006 (`SgrMouseProtocolGenerator`), and F-R3-007 (`VsockPtyFramer`) logic structures were inspected and are functionally solid in Java/C++. However, because F-R3-001 and F-R3-002 are core dependencies for rendering and parsing, the overall terminal stack cannot operate properly until F-R3-001 and F-R3-002 are fixed.

---

## 4. Conclusion (結論)

- **Verdict**: **REQUEST_CHANGES**
- **Action Required**: Worker M3 must replace facade/stub implementations with genuine `ANativeWindow_fromSurface` native surface rendering, link real `libvterm`, remove code duplication, and fix JNI thread attachment/memory leaks.

---

## 5. Verification Method (獨立驗證方法)

To independently verify after remediation:
1. Verify JNI linking: Confirm `Android.bp` compiles `libvterm/src/*.c` and `vterm_parser.cpp` no longer defines fake `vterm_*` C functions.
2. Verify native rendering: Inspect `TerminalSurfaceView.java` to confirm Java passes `Surface` object to JNI and native code locks surface via `ANativeWindow_fromSurface` and `ANativeWindow_lock`.
3. Run E2E test runner:
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
