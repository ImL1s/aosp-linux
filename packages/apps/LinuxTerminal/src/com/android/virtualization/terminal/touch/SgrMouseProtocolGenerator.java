package com.android.virtualization.terminal.touch;

import android.view.MotionEvent;
import java.nio.charset.StandardCharsets;

/**
 * Converts Android Touch / Gesture MotionEvents to DEC SGR 1006 Terminal Mouse Protocol packets.
 * Packet Format:
 *   Press / Motion: \033[<b;col;rowM
 *   Release:        \033[<b;col;rowm
 */
public class SgrMouseProtocolGenerator {
    private boolean mMouseTrackingEnabled = false;
    private boolean mSgrModeEnabled = true;

    private int mLastCol = -1;
    private int mLastRow = -1;
    private float mStartY = 0f;
    private float mAccumulatedScrollY = 0f;

    // Touchpad Mode state tracking variables
    private int mTouchpadCol = -1;
    private int mTouchpadRow = -1;
    private float mTouchpadLastX = 0f;
    private float mTouchpadLastY = 0f;
    private float mTouchpadAccumX = 0f;
    private float mTouchpadAccumY = 0f;
    private long mTouchpadDownTime = 0L;
    private float mTouchpadTotalMoveDist = 0f;
    private boolean mTouchpadIsDragging = false;
    private float mTouchpadScrollAccumY = 0f;
    private float mTouchpadVelocityScale = 1.0f;

    public void setMouseTrackingEnabled(boolean enabled) {
        this.mMouseTrackingEnabled = enabled;
    }

    public boolean isMouseTrackingEnabled() {
        return mMouseTrackingEnabled;
    }

    public void setSgrModeEnabled(boolean sgrMode) {
        this.mSgrModeEnabled = sgrMode;
    }

    public boolean isSgrModeEnabled() {
        return mSgrModeEnabled;
    }

    public void setTouchpadVelocityScale(float scale) {
        this.mTouchpadVelocityScale = scale > 0 ? scale : 1.0f;
    }

    public float getTouchpadVelocityScale() {
        return mTouchpadVelocityScale;
    }

    public int getTouchpadCol() {
        return mTouchpadCol;
    }

    public int getTouchpadRow() {
        return mTouchpadRow;
    }

    public void setTouchpadIsDragging(boolean dragging) {
        this.mTouchpadIsDragging = dragging;
    }

    public boolean isTouchpadDragging() {
        return mTouchpadIsDragging;
    }

    /**
     * Converts MotionEvent to SGR mouse sequence bytes. Returns empty byte array if tracking is disabled.
     */
    public byte[] processMotionEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows) {
        if (!mMouseTrackingEnabled || event == null) {
            return new byte[0];
        }

        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        int col = Math.max(1, Math.min(totalCols, (int) (x / Math.max(1, cellWidth)) + 1));
        int row = Math.max(1, Math.min(totalRows, (int) (y / Math.max(1, cellHeight)) + 1));

        StringBuilder sb = new StringBuilder();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mLastCol = col;
                mLastRow = row;
                mStartY = y;
                mAccumulatedScrollY = 0f;
                sb.append(formatSgrPacket(0, col, row, true));
                break;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1) {
                    if (col != mLastCol || row != mLastRow) {
                        mLastCol = col;
                        mLastRow = row;
                        sb.append(formatSgrPacket(32, col, row, true));
                    }
                } else if (event.getPointerCount() >= 2) {
                    float dy = y - mStartY;
                    mStartY = y;
                    mAccumulatedScrollY += dy;
                    int threshold = Math.max(1, cellHeight);
                    if (Math.abs(mAccumulatedScrollY) >= threshold) {
                        int button = (mAccumulatedScrollY < 0) ? 65 : 64; // 65=Wheel Down, 64=Wheel Up
                        sb.append(formatSgrPacket(button, col, row, true));
                        mAccumulatedScrollY = 0f;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                col = (mLastCol > 0) ? mLastCol : col;
                row = (mLastRow > 0) ? mLastRow : row;
                sb.append(formatSgrPacket(0, col, row, false));
                mLastCol = -1;
                mLastRow = -1;
                break;
        }

        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Processes relative touch events in TOUCHPAD_MODE and generates DEC SGR 1006 mouse protocol packet bytes.
     */
    public byte[] processTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows) {
        if (!mMouseTrackingEnabled || event == null) {
            return new byte[0];
        }

        int safeCellW = Math.max(1, cellWidth);
        int safeCellH = Math.max(1, cellHeight);
        int safeCols = Math.max(1, totalCols);
        int safeRows = Math.max(1, totalRows);

        // Initialize simulated touchpad cursor position to screen grid center if uninitialized or out of bounds
        if (mTouchpadCol < 1 || mTouchpadCol > safeCols || mTouchpadRow < 1 || mTouchpadRow > safeRows) {
            mTouchpadCol = safeCols / 2;
            mTouchpadRow = safeRows / 2;
        }

        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();
        StringBuilder sb = new StringBuilder();

        // 1. Two-finger scroll (Two-finger Scroll -> SGR Scroll Wheel 64/65)
        if (pointerCount >= 2) {
            float avgY = (event.getY(0) + event.getY(1)) / 2f;
            if (action == MotionEvent.ACTION_MOVE) {
                float dy = avgY - mTouchpadLastY;
                mTouchpadScrollAccumY += dy;
                float threshold = safeCellH * 0.8f;
                if (Math.abs(mTouchpadScrollAccumY) >= threshold) {
                    // 64 = Wheel Up, 65 = Wheel Down
                    int button = (mTouchpadScrollAccumY < 0) ? 65 : 64;
                    sb.append(formatSgrPacket(button, mTouchpadCol, mTouchpadRow, true));
                    mTouchpadScrollAccumY = 0f;
                }
            }
            mTouchpadLastY = avgY;
            return sb.toString().getBytes(StandardCharsets.US_ASCII);
        }

        // 2. Single-finger relative motion & Single Tap / Drag
        float x = event.getX();
        float y = event.getY();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTouchpadLastX = x;
                mTouchpadLastY = y;
                mTouchpadDownTime = System.currentTimeMillis();
                mTouchpadTotalMoveDist = 0f;
                mTouchpadAccumX = 0f;
                mTouchpadAccumY = 0f;
                mTouchpadScrollAccumY = 0f;
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = (x - mTouchpadLastX) * mTouchpadVelocityScale;
                float dy = (y - mTouchpadLastY) * mTouchpadVelocityScale;
                mTouchpadLastX = x;
                mTouchpadLastY = y;
                mTouchpadTotalMoveDist += (float) Math.hypot(dx, dy);

                mTouchpadAccumX += dx;
                mTouchpadAccumY += dy;

                int colShift = (int) (mTouchpadAccumX / safeCellW);
                int rowShift = (int) (mTouchpadAccumY / safeCellH);

                if (colShift != 0) {
                    mTouchpadCol = Math.max(1, Math.min(safeCols, mTouchpadCol + colShift));
                    mTouchpadAccumX -= colShift * safeCellW;
                }
                if (rowShift != 0) {
                    mTouchpadRow = Math.max(1, Math.min(safeRows, mTouchpadRow + rowShift));
                    mTouchpadAccumY -= rowShift * safeCellH;
                }

                if (colShift != 0 || rowShift != 0) {
                    if (mTouchpadIsDragging) {
                        // Dragging with left button down (SGR Button 32: Button 0 + Motion 32)
                        sb.append(formatSgrPacket(32, mTouchpadCol, mTouchpadRow, true));
                    } else {
                        // Motion with no buttons pressed (SGR Button 35: Motion with no buttons)
                        sb.append(formatSgrPacket(35, mTouchpadCol, mTouchpadRow, true));
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                long duration = System.currentTimeMillis() - mTouchpadDownTime;
                // Single tap detection (< 250ms duration & < 20px movement)
                if (duration < 250 && mTouchpadTotalMoveDist < 20f) {
                    // Single tap -> Left click Press then Release (\033[<0;col;rowM\033[<0;col;rowm)
                    sb.append(formatSgrPacket(0, mTouchpadCol, mTouchpadRow, true));
                    sb.append(formatSgrPacket(0, mTouchpadCol, mTouchpadRow, false));
                } else if (mTouchpadIsDragging) {
                    // End drag -> Send Left click Up
                    sb.append(formatSgrPacket(0, mTouchpadCol, mTouchpadRow, false));
                    mTouchpadIsDragging = false;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                if (mTouchpadIsDragging) {
                    sb.append(formatSgrPacket(0, mTouchpadCol, mTouchpadRow, false));
                    mTouchpadIsDragging = false;
                }
                break;
        }

        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Directly formats an SGR packet for specific button codes (0=Left, 1=Middle, 2=Right, 64=ScrollUp, 65=ScrollDown).
     */
    public static String formatSgrPacket(int button, int col, int row, boolean isPress) {
        return String.format("\033[<%d;%d;%d%s", button, col, row, isPress ? "M" : "m");
    }

    public byte[] formatSgrPacketBytes(int button, int col, int row, boolean isPress) {
        return formatSgrPacket(button, col, row, isPress).getBytes(StandardCharsets.US_ASCII);
    }
}
