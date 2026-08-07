# Handoff Report — Milestone M3 Iteration 2 Gate Review (Challenger 2)

## 1. Observation
- **Native C++ Empirical Stress Test Execution**:
  Command executed:
  `g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_challenger2_stress.cpp packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp packages/apps/LinuxTerminal/jni/vterm_parser.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_challenger2_stress && /tmp/m3_native_challenger2_stress`
  Output/Failure:
  ```text
  [CPP STRESS 05] CJK IME UTF-8 Socket Fragmentation & Wide-Char Parsing...
  Assertion failed: (cells[0].codepoint == 0x6E2C), function test_utf8_cjk_fragmentation_stress, file m3_native_challenger2_stress.cpp, line 175.
  ```
- **Code Inspection Findings**:
  1. `packages/apps/LinuxTerminal/jni/vterm_parser.cpp` (lines 51-76): `VTermParserBridge::feedBytes` decrements `validLen` during backward scanning for lead bytes (`validLen--`). When a 3-byte CJK character (`0xE6 0xB8 0xA1` "測") arrives fragmented over socket reads, `validLen` is decremented from 3 to 1, causing `0xE6` to be sent alone to `vterm` and `[0xB8, 0xA1]` stored as orphaned bytes in `mUtf8PartialBuffer`.
  2. `packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp` (lines 53-67): `PtyFramingHandlerNative::processIncomingChunk` calls `mBuffer.clear()` when an invalid frame type or oversized payload is encountered, discarding all queued stream data and breaking stream resynchronization (mismatched with Java `VsockPtyFramer.java` which advances 1 byte).
  3. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/TerminalInputConnection.java` (lines 186-197): `dispatchBytesToPty` splits outgoing byte streams into 1024-byte blocks without verifying UTF-8 character boundaries.

## 2. Logic Chain
1. **Observation**: Executing `m3_native_challenger2_stress` resulted in `Assertion failed: (cells[0].codepoint == 0x6E2C)` when feeding CJK multi-byte UTF-8 bytes 1 byte at a time into `VTermParserBridge`.
   **Deduction**: The C++ partial UTF-8 buffering algorithm in `vterm_parser.cpp` corrupts multi-byte CJK sequences during socket fragmentation.
2. **Observation**: Tracing `vterm_parser.cpp` revealed that `validLen` starts at `buffer.size()` (3) and is decremented inside the `while` loop whenever a continuation byte (`0x80..0xBF`) is checked.
   **Deduction**: Because `validLen` is decremented twice for continuation bytes `0xA1` and `0xB8`, `validLen` ends up as 1 instead of 3. `vterm_input_write` receives `0xE6` alone, and `mUtf8PartialBuffer` receives `[0xB8, 0xA1]`, causing stream corruption.
3. **Observation**: Code review of `pty_framing_handler.cpp` showed `mBuffer.clear()` on header parsing error, whereas `VsockPtyFramer.java` steps forward by 1 byte.
   **Deduction**: C++ framing handler lacks resilient stream resynchronization under malformed packet injection or socket stream noise.
4. **Conclusion**: Milestone M3 cannot be approved due to an empirically reproduced critical bug in native CJK multi-byte UTF-8 socket fragmentation parsing.

## 3. Caveats
- Java unit tests (`TerminalAppUnitTest.java`) passed because they tested `VsockPtyFramer.java` and `TouchModeStateMachine.java` directly in Java, but did not invoke `vterm_parser.cpp` under 1-byte fragmented socket reads.

## 4. Conclusion
Milestone M3 Iteration 2 Gate Review verdict is **`REJECT`**. Remediation is required for `vterm_parser.cpp` UTF-8 partial buffer parsing and `pty_framing_handler.cpp` stream resynchronization.

## 5. Verification Method
To independently reproduce and verify this finding, execute:

```bash
g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_challenger2_stress.cpp packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp packages/apps/LinuxTerminal/jni/vterm_parser.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_challenger2_stress && /tmp/m3_native_challenger2_stress
```

Expected result before fix: Assertion failure at line 175 (`cells[0].codepoint == 0x6E2C`).
Expected result after fix: `ALL NATIVE C++ STRESS TESTS PASSED SUCCESSFULLY`.
