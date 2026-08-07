## 2026-08-06T19:14:00Z
You are Worker (R2 Gen2) for Milestone M3 Iteration 2 Remediation.

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_r2_gen2

Mandatory Reference Files:
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md (READ THIS FIRST!)
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- SCOPE.md: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/SCOPE.md
- Technical Architecture Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md
- FULL FORENSIC AUDIT REPORT: /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m3_1/audit_report.md
- R2 Explorer 1 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_1_r2/analysis.md
- R2 Explorer 2 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_2_r2/analysis.md
- R2 Explorer 3 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3_r2/analysis.md
- Dead Ends Log: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/DEAD_ENDS.md
- Gate Status: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/GATE_STATUS.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Remediation Tasks (Execute all code changes and build verifications in packages/apps/LinuxTerminal/ and tests/):

1. **Syntax & Package Cleanup**:
   - Replace all `"\x1b"` with `"\033"` / `"\u001b"` in `TerminalKeyEncoder.java`, `SgrMouseProtocolGenerator.java`, and `TerminalAppUnitTest.java` (fixes 130 javac syntax errors).
   - Remove the 14 duplicate shadow `.java` files from `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/` root directory.
   - Keep `TerminalActivity.java` and `TerminalView.java` in root, and organize subpackages under `.renderer`, `.parser`, `.ime`, `.touch`, `.net`.

2. **Real libvterm JNI Integration (F-R3-002)**:
   - Match JNI exports in `libvterm_jni.cpp` to `com.android.virtualization.terminal.parser.VTermParser`. Remove silent `UnsatisfiedLinkError` catch in `VTermParser.java`.
   - Remove fake C stub in `vterm_parser.cpp` and link authentic `jni/libvterm/src/*.c` sources in `Android.bp`.
   - Add `AttachCurrentThread`/`DetachCurrentThread` and `DeleteLocalRef` in `libvterm_jni.cpp`.

3. **Genuine Surface Renderer & Vsock Communication (F-R3-001 & F-R3-007)**:
   - `TerminalSurfaceView.java` MUST render real `TerminalScreenMatrix` cells with ANativeWindow lock/unlockAndPost or Canvas drawing (remove static text facade).
   - Implement `VsockTerminalClient.java` with real AF_VSOCK sockets for Port 5001 (remove Logcat logging stubs).
   - Fix `VsockPtyFramer.java`: add `payloadLength < 0` MSB overflow check and 1-byte stream resynchronization on invalid packet type.

4. **IME & Touch Mode Fixes (F-R3-004, F-R3-005, F-R3-006)**:
   - Fix `CjkComposingTextManager.java::deleteBeforeCursor` bounds check to eliminate `StringIndexOutOfBoundsException`.
   - Implement functional `TOUCHPAD_MODE` in `TerminalView.java` and persist `KEY_PREF_MANUAL_LOCKED` in `SharedPreferences`.
   - Fix DEC SGR 1006 mouse format: remove extra trailing semicolon (`"\033[<%d;%d;%d%s"`).

5. **Test Suite Authenticity**:
   - Fix `TerminalAppUnitTest.java` compilation and imports.
   - Update `test_m3_tier1.py` and `test_m3_tier2.py` so that E2E test cases execute actual compiled Java `.class` files or C++ test binaries (`m3_native_terminal_test`) via subprocess/CommandRunner rather than self-certifying Python dicts.

Run local compilation and unit tests to verify before reporting back.
Write changes to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_r2_gen2/changes.md` and `handoff.md`, then send a concise message back.
