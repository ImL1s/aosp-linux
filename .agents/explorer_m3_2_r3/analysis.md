# Technical Strategy & Architectural Analysis: `TOUCHPAD_MODE` Implementation

**Author**: Explorer 2 (Milestone M3 Iteration 3 Remediation)  
**Date**: 2026-08-06  
**Target Components**: `TerminalView.java`, `TerminalSurfaceView.java`, `SgrMouseProtocolGenerator.java`, `TouchpadController.java`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_2_r3/`

---

## 1. Executive Summary & Problem Context

In Iteration 2 Gate Review, `reviewer_m3_2_r2` flagged a critical **Integrity Violation (Prohibited Pattern #2 / Dummy Implementation)**:
- `TerminalView.java` (line 166) and `TerminalSurfaceView.java` (line 115) had `TOUCHPAD_MODE` returning `true` as an empty stub without implementing relative touch tracking, virtual cursor positioning, tap/long-press/scroll gesture mapping, or DEC SGR 1006 packet generation.
- Furthermore, `TerminalView.java` logged packets in `sendBytes()` without calling `mVsockClient.sendFrame()`.

To resolve this issue permanently in Iteration 3 Remediation, this report establishes a complete, robust, non-facade technical design for `TOUCHPAD_MODE` relative touch motion tracking, gesture classification, DEC SGR 1006 mouse packet generation, and AF_VSOCK port 5001 packet transmission.

---

## 2. Architectural Design of `TOUCHPAD_MODE`

### 2.1 Virtual Cursor Grid State & Motion Tracking

Unlike `TUI_MOUSE_MODE` (which maps absolute touch coordinates directly to terminal cells), `TOUCHPAD_MODE` acts as a relative trackpad controller.

#### State Variables
- `float mVirtualCursorX`, `float mVirtualCursorY`: Virtual cursor position in view pixel space. Initialized to center of view (`width / 2f`, `height / 2f`).
- `int mVirtualCursorCol`, `int mVirtualCursorRow`: Virtual cursor cell grid coordinates (1-based), derived via:
  $$\text{col} = \text{clamp}\left(\lfloor \text{mVirtualCursorX} / \text{cellWidth} \rfloor + 1, 1, \text{totalCols}\right)$$
  $$\text{row} = \text{clamp}\left(\lfloor \text{mVirtualCursorY} / \text{cellHeight} \rfloor + 1, 1, \text{totalRows}\right)$$
- `float mLastTouchX`, `float mLastTouchY`: Tracking previous single-touch position for relative delta calculation.
- `float mSensitivity`: Touchpad movement multiplier (default `1.0f`).

#### Delta Calculation & Coordinate Update
On `ACTION_MOVE` (with pointer count = 1):
$$\Delta x = (x_{\text{curr}} - \text{mLastTouchX}) \times mSensitivity$$
$$\Delta y = (y_{\text{curr}} - \text{mLastTouchY}) \times mSensitivity$$
$$\text{mVirtualCursorX} = \text{clamp}(\text{mVirtualCursorX} + \Delta x, 0, \text{viewWidth})$$
$$\text{mVirtualCursorY} = \text{clamp}(\text{mVirtualCursorY} + \Delta y, 0, \text{viewHeight})$$
$$\text{mLastTouchX} = x_{\text{curr}}, \quad \text{mLastTouchY} = y_{\text{curr}}$$

When `(col, row)` changes during relative movement:
- If a drag state is active (e.g. after long press or tap-drag), emit SGR Drag Motion (`\033[<32;col;rowM`).
- If motion tracking is requested, emit SGR Motion (`\033[<32;col;rowM`).

---

### 2.2 Gesture Classification Mechanics

Four primary gestures are recognized on the virtual touchpad surface:

```
[MotionEvent]
     │
     ├── Pointer Count == 1 ──┬── ACTION_DOWN ──> Record Down Position & Start Long-Press Timer
     │                        ├── ACTION_MOVE ──> Update Δx, Δy -> Virtual Cursor (col, row)
     │                        │                    If movement > TouchSlop, Cancel Long-Press
     │                        └── ACTION_UP ────> If !moved & duration < TapTimeout -> Left Click (Button 0)
     │                                             If long-pressed -> Release Right Click (Button 2)
     │
     └── Pointer Count >= 2 ──┬── ACTION_POINTER_DOWN ──> Cancel Single-Finger Tap/Long-Press
                              └── ACTION_MOVE ──────────> Calculate Δy_scroll
                                                           If |Δy_scroll| >= cellHeight:
                                                             Δy < 0 -> Wheel Down (Button 65: \033[<65;c;rM)
                                                             Δy > 0 -> Wheel Up   (Button 64: \033[<64;c;rM)
```

#### 1. Tap (Left Click — Button 0)
- **Condition**: Single finger touches down (`ACTION_DOWN`), stays within `touchSlop` (`ViewConfiguration.get(context).getScaledTouchSlop()`), and releases (`ACTION_UP`) within `tapTimeout` (e.g., $< 200\text{ms}$).
- **Action**: Emit Button 0 Press (`\033[<0;col;rowM`), followed immediately by Button 0 Release (`\033[<0;col;rowm`) at the current virtual cursor position `(col, row)`.

#### 2. Long Press (Right Click — Button 2)
- **Condition**: Single finger touches down (`ACTION_DOWN`) and holds without moving past `touchSlop` for $> 500\text{ms}$ (`ViewConfiguration.getLongPressTimeout()`).
- **Action**: When timer triggers, set `mIsLongPressed = true`, emit Button 2 Press (`\033[<2;col;rowM`) at `(col, row)`. On `ACTION_UP`, emit Button 2 Release (`\033[<2;col;rowm`).

#### 3. Two-Finger Drag (Vertical Scroll — Buttons 64/65)
- **Condition**: `event.getPointerCount() >= 2`.
- **Action**: Track average vertical delta $\Delta y_{\text{scroll}} = y_{\text{avg\_curr}} - y_{\text{avg\_prev}}$. Accumulate into `mAccumulatedScrollY`.
- **Threshold**: When `|mAccumulatedScrollY| >= cellHeight`:
  - If `mAccumulatedScrollY < 0`: Emit Wheel Down (Button 65) press (`\033[<65;col;rowM`).
  - If `mAccumulatedScrollY > 0`: Emit Wheel Up (Button 64) press (`\033[<64;col;rowM`).
  - Reset `mAccumulatedScrollY = 0f`.

#### 4. Virtual Cursor Visual Overlay
To enhance user experience and visual feedback:
- In `TOUCHPAD_MODE`, render a distinct virtual mouse cursor (e.g. crosshair / translucent pointer icon or highlighted grid cell) at `(mVirtualCursorCol, mVirtualCursorRow)` or `(mVirtualCursorX, mVirtualCursorY)` inside `TerminalView.onDraw(Canvas)` / `NativeSurfaceCanvasRenderer`.

---

## 3. Decoupled Architecture: `TouchpadController.java`

To prevent code duplication between `TerminalView.java` and `TerminalSurfaceView.java`, we introduce a reusable controller: `TouchpadController.java` in package `com.android.virtualization.terminal.touch`.

### Proposed Source Code: `TouchpadController.java`

```java
package com.android.virtualization.terminal.touch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.virtualization.terminal.net.PtySender;

/**
 * Controller for TOUCHPAD_MODE relative touch motion tracking, gesture classification,
 * virtual cursor positioning, and DEC SGR 1006 mouse protocol packet dispatch.
 */
public class TouchpadController {
    private static final long LONG_PRESS_TIMEOUT_MS = 500L;

    private float mVirtualCursorX = 400f;
    private float mVirtualCursorY = 300f;
    private int mVirtualCursorCol = 1;
    private int mVirtualCursorRow = 1;

    private float mLastTouchX = 0f;
    private float mLastTouchY = 0f;
    private float mDownX = 0f;
    private float mDownY = 0f;
    private long mDownTime = 0L;

    private boolean mIsMoved = false;
    private boolean mIsLongPressed = false;
    private boolean mIsTwoFingerDrag = false;

    private float mTwoFingerStartY = 0f;
    private float mAccumulatedScrollY = 0f;

    private final int mTouchSlop;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mLongPressRunnable;

    public TouchpadController(Context context) {
        if (context != null) {
            mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        } else {
            mTouchSlop = 8;
        }
    }

    public int getVirtualCursorCol() {
        return mVirtualCursorCol;
    }

    public int getVirtualCursorRow() {
        return mVirtualCursorRow;
    }

    public float getVirtualCursorX() {
        return mVirtualCursorX;
    }

    public float getVirtualCursorY() {
        return mVirtualCursorY;
    }

    public void setVirtualCursorPosition(float x, float y, int cellWidth, int cellHeight, int totalCols, int totalRows) {
        this.mVirtualCursorX = Math.max(0, Math.min(x, totalCols * cellWidth));
        this.mVirtualCursorY = Math.max(0, Math.min(y, totalRows * cellHeight));
        updateGridCoordinates(cellWidth, cellHeight, totalCols, totalRows);
    }

    private void updateGridCoordinates(int cellWidth, int cellHeight, int totalCols, int totalRows) {
        int safeCellW = Math.max(1, cellWidth);
        int safeCellH = Math.max(1, cellHeight);
        mVirtualCursorCol = Math.max(1, Math.min(totalCols, (int) (mVirtualCursorX / safeCellW) + 1));
        mVirtualCursorRow = Math.max(1, Math.min(totalRows, (int) (mVirtualCursorY / safeCellH) + 1));
    }

    public boolean handleTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows,
                                       PtySender ptySender, SgrMouseProtocolGenerator sgrGenerator) {
        if (event == null || ptySender == null || sgrGenerator == null) {
            return false;
        }

        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        // 1. Multi-finger gesture handling (Two-finger scroll)
        if (pointerCount >= 2) {
            cancelLongPressTimer();
            float currentAvgY = (event.getY(0) + event.getY(1)) / 2f;

            if (!mIsTwoFingerDrag) {
                mIsTwoFingerDrag = true;
                mTwoFingerStartY = currentAvgY;
                mAccumulatedScrollY = 0f;
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dy = currentAvgY - mTwoFingerStartY;
                mTwoFingerStartY = currentAvgY;
                mAccumulatedScrollY += dy;

                int threshold = Math.max(1, cellHeight);
                if (Math.abs(mAccumulatedScrollY) >= threshold) {
                    int button = (mAccumulatedScrollY < 0) ? 65 : 64; // 65=Scroll Down, 64=Scroll Up
                    byte[] packet = sgrGenerator.formatSgrPacketBytes(button, mVirtualCursorCol, mVirtualCursorRow, true);
                    ptySender.sendBytes(packet);
                    mAccumulatedScrollY = 0f;
                }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                mIsTwoFingerDrag = false;
            }
            return true;
        }

        // 2. Single-finger gesture handling
        float x = event.getX();
        float y = event.getY();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mLastTouchX = x;
                mLastTouchY = y;
                mDownX = x;
                mDownY = y;
                mDownTime = System.currentTimeMillis();
                mIsMoved = false;
                mIsLongPressed = false;
                mIsTwoFingerDrag = false;

                // Schedule long press timer for Right Click (Button 2)
                scheduleLongPressTimer(ptySender, sgrGenerator);
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = x - mLastTouchX;
                float dy = y - mLastTouchY;
                mLastTouchX = x;
                mLastTouchY = y;

                float distFromDown = (float) Math.hypot(x - mDownX, y - mDownY);
                if (distFromDown > mTouchSlop) {
                    mIsMoved = true;
                    cancelLongPressTimer();
                }

                // Update virtual cursor position with relative delta
                int oldCol = mVirtualCursorCol;
                int oldRow = mVirtualCursorRow;
                mVirtualCursorX = Math.max(0, Math.min(totalCols * cellWidth, mVirtualCursorX + dx));
                mVirtualCursorY = Math.max(0, Math.min(totalRows * cellHeight, mVirtualCursorY + dy));
                updateGridCoordinates(cellWidth, cellHeight, totalCols, totalRows);

                // Send drag / motion packet if cursor grid position changed while long-pressed
                if (mIsLongPressed && (mVirtualCursorCol != oldCol || mVirtualCursorRow != oldRow)) {
                    byte[] dragPacket = sgrGenerator.formatSgrPacketBytes(32, mVirtualCursorCol, mVirtualCursorRow, true);
                    ptySender.sendBytes(dragPacket);
                }
                break;

            case MotionEvent.ACTION_UP:
                cancelLongPressTimer();
                long duration = System.currentTimeMillis() - mDownTime;

                if (mIsLongPressed) {
                    // Release Right Click (Button 2)
                    byte[] releaseRight = sgrGenerator.formatSgrPacketBytes(2, mVirtualCursorCol, mVirtualCursorRow, false);
                    ptySender.sendBytes(releaseRight);
                    mIsLongPressed = false;
                } else if (!mIsMoved && duration < ViewConfiguration.getTapTimeout()) {
                    // Tap detected: Left Click Press & Release (Button 0)
                    byte[] pressLeft = sgrGenerator.formatSgrPacketBytes(0, mVirtualCursorCol, mVirtualCursorRow, true);
                    byte[] releaseLeft = sgrGenerator.formatSgrPacketBytes(0, mVirtualCursorCol, mVirtualCursorRow, false);
                    ptySender.sendBytes(pressLeft);
                    ptySender.sendBytes(releaseLeft);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                cancelLongPressTimer();
                if (mIsLongPressed) {
                    byte[] releaseRight = sgrGenerator.formatSgrPacketBytes(2, mVirtualCursorCol, mVirtualCursorRow, false);
                    ptySender.sendBytes(releaseRight);
                }
                mIsLongPressed = false;
                break;
        }

        return true;
    }

    private void scheduleLongPressTimer(PtySender ptySender, SgrMouseProtocolGenerator sgrGenerator) {
        cancelLongPressTimer();
        mLongPressRunnable = () -> {
            if (!mIsMoved && !mIsTwoFingerDrag) {
                mIsLongPressed = true;
                // Emit Right Click Press (Button 2)
                byte[] pressRight = sgrGenerator.formatSgrPacketBytes(2, mVirtualCursorCol, mVirtualCursorRow, true);
                ptySender.sendBytes(pressRight);
            }
        };
        mHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
    }

    private void cancelLongPressTimer() {
        if (mLongPressRunnable != null) {
            mHandler.removeCallbacks(mLongPressRunnable);
            mLongPressRunnable = null;
        }
    }
}
```

---

## 4. Integration into `TerminalView.java` and `TerminalSurfaceView.java`

### 4.1 Integration Proposal in `TerminalView.java`

In `TerminalView.java`:
1. Add `private TouchpadController mTouchpadController;`
2. Initialize `mTouchpadController = new TouchpadController(context);` in `initView()`.
3. In `onTouchEvent(MotionEvent event)`:
   Replace the dummy stub (`case TOUCHPAD_MODE: return true;`) with:

```java
case TOUCHPAD_MODE:
    return mTouchpadController.handleTouchpadEvent(
        event,
        mCellWidth,
        mCellHeight,
        mColumns,
        mRows,
        this, // TerminalView implements PtySender
        mSgrMouseGenerator
    );
```

4. Wire Socket Transmission in `TerminalView.java`:
   To resolve Finding 2 (`sendBytes()` log facade), update `sendBytes()`, `sendFrame()`, and `sendResize()`:

```java
@Override
public void sendBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0) return;
    byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
    try {
        mVsockClient.sendFrame(frame);
    } catch (IOException e) {
        Log.e(TAG, "Failed to send PTY frame over AF_VSOCK socket", e);
    }
}
```

### 4.2 Integration Proposal in `TerminalSurfaceView.java`

In `TerminalSurfaceView.java`:
1. Add `private TouchpadController mTouchpadController;`
2. Initialize `mTouchpadController = new TouchpadController(context);` in `init()`.
3. In `onTouchEvent(MotionEvent event)`:

```java
case TOUCHPAD_MODE:
    if (mPtySender != null) {
        return mTouchpadController.handleTouchpadEvent(
            event,
            (int) mRenderer.getCellWidth(),
            (int) mRenderer.getCellHeight(),
            mScreenMatrix.getCols(),
            mScreenMatrix.getRows(),
            mPtySender,
            mSgrGenerator
        );
    }
    return true;
```

---

## 5. Unit Test Plan (`TerminalAppUnitTest.java`)

Add `testTouchpadController()` to `TerminalAppUnitTest.java`:
1. **Initial Grid Position**: Verify default virtual cursor grid calculation.
2. **Relative Delta Motion**: Simulate single-finger `ACTION_DOWN` $\rightarrow$ `ACTION_MOVE` ($\Delta x = 100, \Delta y = 50$). Verify `mVirtualCursorCol` and `mVirtualCursorRow` update according to grid cell size.
3. **Tap (Left Click - Button 0)**: Simulate `ACTION_DOWN` $\rightarrow$ `ACTION_UP` within $100\text{ms}$ and zero displacement. Verify `PtySender` receives `\033[<0;col;rowM` followed by `\033[<0;col;rowm`.
4. **Long Press (Right Click - Button 2)**: Simulate `ACTION_DOWN` held past $500\text{ms}$. Verify `PtySender` receives `\033[<2;col;rowM` on timer and `\033[<2;col;rowm` on `ACTION_UP`.
5. **Two-Finger Scroll (Buttons 64/65)**: Simulate 2 pointers `ACTION_POINTER_DOWN` $\rightarrow$ `ACTION_MOVE` ($\Delta y < 0$). Verify `PtySender` receives `\033[<65;col;rowM`.

---

## 6. Verification Strategy

1. **Compilation Check**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src \
     -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') \
     tests/unit/TerminalAppUnitTest.java
   ```
2. **Java Unit Test Execution**:
   ```bash
   java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```
3. **E2E & Stress Verification**:
   ```bash
   python3 tests/e2e/tier1_feature_coverage/test_m3_tier1.py
   python3 tests/e2e/test_m3_challenger2_stress.py
   ```
