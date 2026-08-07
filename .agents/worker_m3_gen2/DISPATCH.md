## 2026-08-06T11:10:15Z
You are Implementation Worker 2 for Milestone M3: Native Touch Terminal Engine & IME (Iteration 2 Remediation).
Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_gen2

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

MANDATORY INPUT FILES TO READ FIRST:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/SCOPE.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/GATE_STATUS.md
5. Explorer 4 Technical Remediation Plan: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_4/analysis.md
6. Explorer 4 Handoff: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_4/handoff.md
7. Reviewer 1 Handoff: /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m3_1/handoff.md
8. Challenger 1 Handoff: /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m3_1/handoff.md

YOUR REMEDIATION TASKS:
Execute the 6-step technical remediation plan from `explorer_m3_4/analysis.md`:
1. Fix `packages/apps/LinuxTerminal/jni/Android.bp`: Add all 7 real `libvterm/src/*.c` C sources (`vterm.c`, `state.c`, `screen.c`, `parser.c`, `pen.c`, `unicode.c`, `encoding.c`) to `srcs` of `libterminal_jni` and ensure `jni_libs` in Android.bp uses `libterminal_jni`.
2. Remove Fake C Stubs in `vterm_parser.cpp` & `libvterm_jni.cpp`: Remove fake `extern "C"` functions. Fix `vterm.h` `boolean` compile error. Wire genuine `libvterm` library calls (`vterm_input_write`, `vterm_obtain_screen`, ANSI escape code parsing (`0x1B`), CSI controls, SGR 256/TrueColor color palette, Alt Screen `\e[?1049h`, 10,000 line scrollback buffer, and UTF-8 multi-byte decoding across socket packet boundaries).
3. Connect Native Surface Rendering: Export JNI functions (`nativeSetSurface`, `nativeRenderFrame`) in `terminal_renderer.cpp` using `ANativeWindow_fromSurface`, `ANativeWindow_lock`, and `ANativeWindow_unlockAndPost`. Wire Java `TerminalSurfaceView.java` to native rendering instead of locking Java Canvas directly in Java.
4. Class Deduplication & JNI Cleanup: Remove duplicate dummy class files in root package `com.android.virtualization.terminal` that shadow subpackage implementations. Fix JNI local ref leak (`DeleteLocalRef(cbClass)`) and use `AttachCurrentThread` / `DetachCurrentThread` for background thread callbacks in `libvterm_jni.cpp`.
5. Java Escape Characters & Vsock Buffer: Replace non-standard `"\x1b"` with standard `"\033"` in Java sources (`TerminalKeyEncoder.java`, `SgrMouseProtocolGenerator.java`, `TerminalAppUnitTest.java`). Fix `VsockPtyFramer.java` buffer handling on invalid length packets.
6. Verification & Test Execution: Run `pytest tests/e2e/tier1_feature_coverage/test_m3_tier1.py` and `pytest tests/e2e/tier2_boundary_corner/test_m3_tier2.py` and native unit tests.

DELIVERABLES:
- Write detailed log of changes to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_gen2/changes.md`.
- Write handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_gen2/handoff.md` including exact verification outputs.
- Send a message when complete.
