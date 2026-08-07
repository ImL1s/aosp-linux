## 2026-08-06T11:22:46Z
<USER_REQUEST>
You are Worker (R3) for Milestone M3 Iteration 3 Remediation.

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_r3

Mandatory Reference Files:
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md (READ THIS FIRST!)
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- SCOPE.md: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/SCOPE.md
- Technical Architecture Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md
- R3 Explorer 1 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_1_r3/analysis.md
- R3 Explorer 2 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_2_r3/analysis.md
- R3 Explorer 3 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3_r3/analysis.md
- Challenger 2 (R2) Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m3_2_r2/challenge_report.md
- Dead Ends Log: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/DEAD_ENDS.md
- Gate Status: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/GATE_STATUS.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Remediation Tasks (Execute all code changes and build verifications in packages/apps/LinuxTerminal/ and tests/):

1. **Implement TouchpadController & Wire TOUCHPAD_MODE**:
   - Create `TouchpadController.java` in `com.android.virtualization.terminal.touch` implementing relative touch tracking ($\Delta x, \Delta y$), virtual cursor grid calculation/clamping $[1, \text{cols}], [1, \text{rows}]$, Tap (Button 0 press/release), Long Press (Button 2 press/release), and Two-Finger Drag (Wheel scroll buttons 64/65).
   - Wire `handleTouchpadEvent(...)` in `TerminalView.java:onTouchEvent` and `TerminalSurfaceView.java:onTouchEvent` for `TOUCHPAD_MODE`. Eliminate empty `return true;` stubs.

2. **Wire VsockTerminalClient Socket Transmission in TerminalView**:
   - Connect `VsockTerminalClient` during View attachment (`onAttachedToWindow()`) to CID 2 (Port 5001) with listener routing incoming bytes into `mVTermParser.writeInput(data)` and `postInvalidate()`. Clean up socket via `mVsockClient.close()` in `onDetachedFromWindow()`.
   - Update `sendBytes()`, `sendFrame()`, and `sendResize()` in `TerminalView.java` to call `mVsockClient.sendFrame(frame)` directly, replacing facade logging with authentic AF_VSOCK socket writes.

3. **C++ Native CJK UTF-8 & Framing Stream Resynchronization Fixes**:
   - In `vterm_parser.cpp` (`VTermParserBridge::feedBytes`), fix the Lead Byte fallback loop so checking continuation bytes (`0x80~0xBF`) does NOT decrement `validLen`.
   - In `pty_framing_handler.cpp`, fix invalid header type handling to perform 1-byte stream resynchronization instead of clearing `mBuffer`.

4. **Test Suite Verification**:
   - Add unit tests `testTouchpadModeEventGeneration()` and `testVsockTerminalClientSocketTransmission()` in `TerminalAppUnitTest.java`.
   - Run local compilation and test commands:
     - `javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name "*.java") tests/unit/TerminalAppUnitTest.java`
     - `java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest`
     - `g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_terminal_test.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_terminal_test && /tmp/m3_native_terminal_test`
     - `g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni tests/unit/m3_native_challenger2_stress.cpp packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp -o /tmp/m3_native_challenger2_stress && /tmp/m3_native_challenger2_stress`
     - `python3 tests/e2e/runner.py --filter F-R3`

Write report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_r3/changes.md` and `handoff.md`, then send a concise message back.
</USER_REQUEST>
