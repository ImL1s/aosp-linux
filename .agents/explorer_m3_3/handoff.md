# Handoff Report: Explorer 3 - M3 Touch & Vsock PTY Engine Design

**Author**: Explorer 3  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3`  
**Target Path**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3/handoff.md`  
**Target Recipient**: Sub-Orchestrator M3 (`sub_orch_m3`) / Implementers

---

## 1. Observation

1. **Mandatory Documentation & Reference Files**:
   - `ORIGINAL_REQUEST.md` (lines 1-22): Outlines AOSP Dual-OS system specifications and R3 requirements for native touch terminal engine & vsock channels.
   - `PROJECT.md` (lines 57-59, 104-106): Defines Features `F-R3-005` (Touch Modes State Machine), `F-R3-006` (SGR Mouse Protocol Generator), `F-R3-007` (Vsock Port 5001 PTY Framing). Interface Contract 2 specifies Virtio Vsock Port 5001 binary framing header: `[SessionID (16 bytes)][Type (1 byte: DATA/RESIZE/PING)][Length (4 bytes)][Payload]`.
   - `SCOPE.md` (lines 28-30, 43-45): Outlines class file locations under `packages/apps/TerminalApp/` (or `LinuxTerminal/`) for `TouchModeStateMachine.java`, `SgrMouseProtocolGenerator.java`, and `VsockPtyFramer.java`.
   - `aosp_linux_system_architecture_plan.md` (lines 318-339): Documents touch mode transitions between Shell Mode, TUI Mouse Mode, and Touchpad Mode.

2. **Existing Codebase Inventory**:
   - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` (lines 23-27, 70-90): Contains placeholder enum `TouchMode` (`SHELL_MODE`, `TUI_MOUSE_MODE`, `TOUCHPAD_MODE`) and basic touch event dispatch placeholders.
   - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalActivity.java` (lines 26-32): Demonstrates UI action bar mode switching.

3. **Existing Verification Infrastructure**:
   - `tests/e2e/framework/vsock_helper.py` (lines 12-61): Python verification helper defining `VsockPacketType` (`DATA=0x01`, `RESIZE=0x02`, `PING=0x03`, `PONG=0x04`, `EOS=0x05`), header size ($16 + 1 + 4 = 21$ bytes), big-endian uint32 payload length, and 4-byte big-endian uint16 RESIZE payload (`>HH` for cols, rows).
   - `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` (lines 300-500): Contains E2E tests `T1-72`..`T1-85` validating state transitions, SGR mouse packet generation (`\x1b[<0;10;20;M`, `\x1b[<32;12;20;M`, `\x1b[<0;12;20;m`, buttons 64/65), and framing header/payload serialization.
   - `tests/e2e/tier2_boundary_corner/test_m3_tier2.py` (lines 560-650): Boundary test cases validating invalid frame type byte rejection, partial header reconstruction, payload fragmentation reassembly, session ID mismatch dropping, and 64KB max payload length enforcement.

---

## 2. Logic Chain

1. **From Observations 1 & 2**: The current code in `TerminalView.java` has placeholder touch mode handling and lacks the complete thread-safe state machine, auto-detection integration with DEC escape codes, SGR protocol encoding logic, and Vsock binary framing implementation.
2. **From Observation 3**: The existing test suite in `tests/e2e/framework/vsock_helper.py` and `test_m3_tier1.py` / `test_m3_tier2.py` establishes the exact protocol standard and mathematical contracts expected by the project:
   - Header is exactly 21 bytes: `[16-byte SessionID][1-byte Type][4-byte uint32_BE Length]`.
   - `RESIZE` payload is 4 bytes: `[2-byte uint16_BE Cols][2-byte uint16_BE Rows]`.
   - SGR mouse sequences must follow DEC SGR 1006 standard (`\x1b[?<Cb>;<Cx>;<Cy>M` for press/motion, `m` for release), where cell coordinates are 1-based and clamped to terminal grid boundaries $[1, Cols]$ and $[1, Rows]$.
   - Touch Mode State Machine must support automatic transitions triggered by terminal DEC escape codes (`\x1b[?1000h` / `\x1b[?1006h`), manual overrides (action bar toggle), and session persistence via `SharedPreferences`.
3. **Conclusion Synthesis**: A production-grade implementation of `TouchModeStateMachine.java`, `SgrMouseProtocolGenerator.java`, and `VsockPtyFramer.java` can be constructed directly from the Java reference implementations provided in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3/analysis.md`.

---

## 3. Caveats

- **No Caveats**: All 3 features have complete specification details, exact binary header contracts, explicit touch gesture translation formulas, and corresponding unit/E2E test suites in the repository.

---

## 4. Conclusion

The technical strategy and architectural designs for F-R3-005, F-R3-006, and F-R3-007 are complete, fully specified, and documented in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3/analysis.md`. Implementers can proceed directly with writing `TouchModeStateMachine.java`, `SgrMouseProtocolGenerator.java`, and `VsockPtyFramer.java` under `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/`.

---

## 5. Verification Method

1. **Inspection Verification**:
   - Inspect `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3/analysis.md` for class designs, packet binary layouts, and gesture translation math.
   - Verify alignment with `tests/e2e/framework/vsock_helper.py`.

2. **Automated Test Execution**:
   - Run the M3 Tier 1 E2E tests:
     ```bash
     python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/tier1_feature_coverage/test_m3_tier1.py
     ```
   - Run the M3 Tier 2 Boundary tests:
     ```bash
     python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/tier2_boundary_corner/test_m3_tier2.py
     ```

3. **Invalidation Conditions**:
   - Change in Vsock 5001 header byte count (must remain 21 bytes).
   - Change in SGR 1006 mouse sequence format or button bitmask definitions.
