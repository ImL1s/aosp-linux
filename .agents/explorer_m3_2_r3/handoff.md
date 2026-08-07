# Handoff Report — Explorer 2 (Milestone M3 Iteration 3 Remediation)

**Author**: Explorer 2  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_2_r3/`  
**Target Architecture**: `TOUCHPAD_MODE` in `TerminalView.java` & `TerminalSurfaceView.java`  
**Date**: 2026-08-06  

---

## 1. Observation

1. **Iteration 2 Reviewer 2 Finding 1 (Integrity Violation)**:
   - File: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` (Line 166-167)
   - Code:
     ```java
     case TOUCHPAD_MODE:
         return true;
     ```
   - File: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java` (Line 115-117)
   - Code:
     ```java
     case TOUCHPAD_MODE:
         // Relative touch cursor motion tracking
         return true;
     ```
   - Observation: `TOUCHPAD_MODE` was a dummy stub returning `true` without implementing relative touch tracking, virtual mouse cursor calculation, or DEC SGR 1006 packet generation.

2. **Iteration 2 Reviewer 2 Finding 2 (Facade Implementation)**:
   - File: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` (Lines 95-111)
   - Observation: `sendBytes()`, `sendFrame()`, and `sendResize()` serialized packets and logged them via `Log.d` but never called `mVsockClient.sendFrame(frame)` to send data over AF_VSOCK port 5001.

3. **Dead Ends Log (`DEAD_ENDS.md` line 6)**:
   - Prohibits returning `true` without relative motion tracking or logging messages instead of sending over `mVsockClient.sendFrame`.

---

## 2. Logic Chain

1. **From Observation 1**: The empty `return true;` stubs in `TerminalView.java` and `TerminalSurfaceView.java` cause `TOUCHPAD_MODE` to fail functional tests and gate reviews.
2. **From Observation 2 & 3**: A real implementation must not only process touch motion and gestures into DEC SGR 1006 escape sequences, but also pass those sequences through `sendBytes()`, which in turn calls `mVsockClient.sendFrame()` to transmit over AF_VSOCK port 5001 socket connection.
3. **To solve DRY and modularity**: Instead of duplicating ~150 lines of gesture recognition logic in both `TerminalView.java` and `TerminalSurfaceView.java`, a dedicated controller `TouchpadController.java` should be created in `com.android.virtualization.terminal.touch`.
4. **Gesture Mapping**:
   - Relative touch motion ($\Delta x, \Delta y$) updates virtual cursor coordinates `mVirtualCursorX`, `mVirtualCursorY` and derives grid position `(col, row)`.
   - Tap (single pointer, movement $\le \text{touchSlop}$, duration $<\text{tapTimeout}$) sends Left Click Press `\033[<0;col;rowM` + Release `\033[<0;col;rowm`.
   - Long press ($>500\text{ms}$ held within `touchSlop`) sends Right Click Press `\033[<2;col;rowM` (and Release `\033[<2;col;rowm` on `ACTION_UP`).
   - Two-finger drag (pointer count $\ge 2$, accumulated $\Delta y_{\text{scroll}}$ against `cellHeight`) sends Wheel Down `\033[<65;col;rowM` or Wheel Up `\033[<64;col;rowM`.
5. **Virtual Cursor Overlay**:
   - `TouchpadController` exposes `getVirtualCursorCol()` and `getVirtualCursorRow()`. `TerminalView` and `NativeSurfaceCanvasRenderer` render a virtual pointer indicator at this location when `TOUCHPAD_MODE` is active.

---

## 3. Caveats

- **Scope Boundary**: Explorer 2 is restricted to read-only investigation and technical strategy formulation. No production source code files under `packages/apps/LinuxTerminal/` were directly modified by Explorer 2.
- **Assumptions**: Assumes Android touch event dispatching delivers standard `MotionEvent` streams (`ACTION_DOWN`, `ACTION_MOVE`, `ACTION_UP`, `ACTION_POINTER_DOWN`, `ACTION_CANCEL`) with accurate multi-touch pointer count.
- **Alternative Interpretations**: Tap-to-drag (double tap and hold for dragging) was considered, but single finger tap (left click), long press (right click), and two-finger scroll (wheel) cover all mandatory scope requirements for M3.

---

## 4. Conclusion

A non-facade technical strategy for `TOUCHPAD_MODE` is formulated:
1. Create `TouchpadController.java` in `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/touch/`.
2. Implement relative touch tracking ($\Delta x, \Delta y$), grid clamping $[1, \text{cols}], [1, \text{rows}]$, Tap (Left Click button 0), Long Press (Right Click button 2), and Two-Finger Drag (Wheel scroll buttons 64/65).
3. Connect `TerminalView.java` and `TerminalSurfaceView.java` `onTouchEvent` to `mTouchpadController.handleTouchpadEvent(...)`.
4. Update `TerminalView.sendBytes()` to call `mVsockClient.sendFrame(frame)` over AF_VSOCK port 5001.

Full detailed strategy and complete source proposals are documented in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_2_r3/analysis.md`.

---

## 5. Verification Method

To verify the strategy independently:

1. **File Inspection**:
   - Read `analysis.md` at `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_2_r3/analysis.md`.
   - Verify `TouchpadController.java` design implements all gestures and grid calculations.

2. **Compilation & Unit Test Commands**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src \
     -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') \
     tests/unit/TerminalAppUnitTest.java

   java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```

3. **Integration Test Commands**:
   ```bash
   python3 tests/e2e/tier1_feature_coverage/test_m3_tier1.py
   python3 tests/e2e/test_m3_challenger2_stress.py
   ```

4. **Invalidation Conditions**:
   - `TOUCHPAD_MODE` in `TerminalView.java` or `TerminalSurfaceView.java` contains `return true;` without delegating to relative touch gesture handling.
   - SGR packets generated in `TOUCHPAD_MODE` do not follow DEC SGR 1006 format (`\033[<b;col;rowM` / `m`).
   - `sendBytes()` does not pass frames to `mVsockClient.sendFrame()`.
