## 2026-08-06T11:08:14Z
You are Explorer 4 for Milestone M3: Native Touch Terminal Engine & IME (Iteration 2 Remediation).
Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_4

MANDATORY INPUT FILES TO READ FIRST:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/SCOPE.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/GATE_STATUS.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m3_1/handoff.md
6. /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m3_1/handoff.md

YOUR OBJECTIVES:
Analyze the codebase under `packages/apps/LinuxTerminal/` and `packages/apps/TerminalApp/` to formulate a concrete, step-by-step technical remediation plan for Worker 2 addressing all issues flagged in Iteration 1:
1. `Android.bp` build configuration: Include all real `libvterm/src/*.c` C sources in `srcs` of `libterminal_jni` instead of compiling fake C stubs.
2. `vterm_parser.cpp` & `libvterm_jni.cpp`: Remove fake `vterm_input_write` stub. Wire genuine `libvterm` C library calls for ANSI escape sequence parsing (`0x1B`), CSI controls, SGR color palette (256/TrueColor), Alt Screen `\e[?1049h`, 10,000 line scrollback buffer, and UTF-8 multi-byte sequence decoding across socket packet boundaries.
3. Native Surface Rendering: Wire `TerminalSurfaceView.java` to `terminal_renderer.cpp` using `ANativeWindow_fromSurface`, `ANativeWindow_lock`, and `ANativeWindow_unlockAndPost` native rendering instead of locking Java Canvas directly in `TerminalSurfaceView.java`.
4. Class Deduplication & JNI Cleanup: Clean up duplicate class definitions across `com.android.virtualization.terminal` and subpackages (`.renderer`, `.parser`, `.ime`, `.touch`, `.net`). Fix JNI thread detachment callback handling and local reference memory leak (`DeleteLocalRef(cbClass)`).

DELIVERABLES:
- You are READ-ONLY. Do NOT write or edit source code files directly.
- Write your detailed technical remediation plan to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_4/analysis.md`.
- Write your structured handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_4/handoff.md`.
- Send a message when complete.
