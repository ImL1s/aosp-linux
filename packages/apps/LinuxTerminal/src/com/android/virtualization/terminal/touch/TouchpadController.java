package com.android.virtualization.terminal.touch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.virtualization.terminal.net.PtySender;
import com.android.virtualization.terminal.net.VsockPtyFramer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Controller for TOUCHPAD_MODE relative touch motion tracking, gesture classification,
 * virtual cursor positioning, and DEC SGR 1006 mouse protocol packet dispatch.
 */
public class TouchpadController {
    private static final long LONG_PRESS_TIMEOUT_MS = 500L;

    private float mVirtualCursorX;
    private float mVirtualCursorY;
    private int mVirtualCursorCol = 1;
    private int mVirtualCursorRow = 1;
    private int mTotalCols = 80;
    private int mTotalRows = 24;
    private int mCellWidth = 20;
    private int mCellHeight = 40;

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
    private final Handler mHandler;
    private Runnable mLongPressRunnable;

    public TouchpadController(Context context) {
        if (context != null) {
            mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        } else {
            mTouchSlop = 8;
        }
        Handler h = null;
        try {
            if (Looper.myLooper() != null) {
                h = new Handler(Looper.myLooper());
            }
        } catch (Throwable ignored) {}
        mHandler = h;
        initGrid(80, 24, 20, 40);
    }

    public TouchpadController(int totalCols, int totalRows, int cellWidth, int cellHeight) {
        mTouchSlop = 8;
        Handler h = null;
        try {
            if (Looper.myLooper() != null) {
                h = new Handler(Looper.myLooper());
            }
        } catch (Throwable ignored) {}
        mHandler = h;
        initGrid(totalCols, totalRows, cellWidth, cellHeight);
    }

    public void initGrid(int totalCols, int totalRows, int cellWidth, int cellHeight) {
        this.mTotalCols = Math.max(1, totalCols);
        this.mTotalRows = Math.max(1, totalRows);
        this.mCellWidth = Math.max(1, cellWidth);
        this.mCellHeight = Math.max(1, cellHeight);

        this.mVirtualCursorCol = mTotalCols / 2;
        this.mVirtualCursorRow = mTotalRows / 2;
        this.mVirtualCursorX = (mVirtualCursorCol - 0.5f) * mCellWidth;
        this.mVirtualCursorY = (mVirtualCursorRow - 0.5f) * mCellHeight;
        updateGridCoordinates();
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
        this.mCellWidth = Math.max(1, cellWidth);
        this.mCellHeight = Math.max(1, cellHeight);
        this.mTotalCols = Math.max(1, totalCols);
        this.mTotalRows = Math.max(1, totalRows);

        this.mVirtualCursorX = Math.max(0, Math.min(x, mTotalCols * mCellWidth));
        this.mVirtualCursorY = Math.max(0, Math.min(y, mTotalRows * mCellHeight));
        updateGridCoordinates();
    }

    private void updateGridCoordinates() {
        int col = (int) (mVirtualCursorX / mCellWidth) + 1;
        int row = (int) (mVirtualCursorY / mCellHeight) + 1;
        mVirtualCursorCol = Math.max(1, Math.min(mTotalCols, col));
        mVirtualCursorRow = Math.max(1, Math.min(mTotalRows, row));
    }

    public byte[] handleRelativeMove(float dx, float dy) {
        int oldCol = mVirtualCursorCol;
        int oldRow = mVirtualCursorRow;

        mVirtualCursorX = Math.max(0, Math.min(mTotalCols * mCellWidth, mVirtualCursorX + dx));
        mVirtualCursorY = Math.max(0, Math.min(mTotalRows * mCellHeight, mVirtualCursorY + dy));
        updateGridCoordinates();

        if (mIsLongPressed && (mVirtualCursorCol != oldCol || mVirtualCursorRow != oldRow)) {
            return SgrMouseProtocolGenerator.formatSgrPacket(32, mVirtualCursorCol, mVirtualCursorRow, true).getBytes(StandardCharsets.US_ASCII);
        }
        return new byte[0];
    }

    public byte[] handleSingleTap() {
        String press = SgrMouseProtocolGenerator.formatSgrPacket(0, mVirtualCursorCol, mVirtualCursorRow, true);
        String release = SgrMouseProtocolGenerator.formatSgrPacket(0, mVirtualCursorCol, mVirtualCursorRow, false);
        return (press + release).getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] handleLongPress() {
        String press = SgrMouseProtocolGenerator.formatSgrPacket(2, mVirtualCursorCol, mVirtualCursorRow, true);
        String release = SgrMouseProtocolGenerator.formatSgrPacket(2, mVirtualCursorCol, mVirtualCursorRow, false);
        return (press + release).getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] handleTwoFingerScroll(float dyScroll) {
        int button = (dyScroll < 0) ? 65 : 64; // 65 = Scroll Down, 64 = Scroll Up
        return SgrMouseProtocolGenerator.formatSgrPacket(button, mVirtualCursorCol, mVirtualCursorRow, true).getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] processTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PtySender dummySender = new PtySender() {
            @Override
            public void sendBytes(byte[] data) {
                if (data != null) baos.write(data, 0, data.length);
            }

            @Override
            public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {}

            @Override
            public void sendResize(byte[] sessionId, int cols, int rows) {}
        };
        handleTouchpadEvent(event, cellWidth, cellHeight, totalCols, totalRows, dummySender, null);
        return baos.toByteArray();
    }

    public boolean handleTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows,
                                       PtySender ptySender, SgrMouseProtocolGenerator sgrGenerator) {
        if (event == null || ptySender == null) {
            return false;
        }

        this.mCellWidth = Math.max(1, cellWidth);
        this.mCellHeight = Math.max(1, cellHeight);
        this.mTotalCols = Math.max(1, totalCols);
        this.mTotalRows = Math.max(1, totalRows);

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

                int threshold = Math.max(1, mCellHeight);
                if (Math.abs(mAccumulatedScrollY) >= threshold) {
                    byte[] packet = handleTwoFingerScroll(mAccumulatedScrollY);
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

                scheduleLongPressTimer(ptySender);
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

                byte[] dragPacket = handleRelativeMove(dx, dy);
                if (dragPacket.length > 0) {
                    ptySender.sendBytes(dragPacket);
                }
                break;

            case MotionEvent.ACTION_UP:
                cancelLongPressTimer();
                long duration = System.currentTimeMillis() - mDownTime;

                if (mIsLongPressed) {
                    byte[] releaseRight = SgrMouseProtocolGenerator.formatSgrPacket(2, mVirtualCursorCol, mVirtualCursorRow, false).getBytes(StandardCharsets.US_ASCII);
                    ptySender.sendBytes(releaseRight);
                    mIsLongPressed = false;
                } else if (!mIsMoved && duration < 250) {
                    byte[] tapBytes = handleSingleTap();
                    ptySender.sendBytes(tapBytes);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                cancelLongPressTimer();
                if (mIsLongPressed) {
                    byte[] releaseRight = SgrMouseProtocolGenerator.formatSgrPacket(2, mVirtualCursorCol, mVirtualCursorRow, false).getBytes(StandardCharsets.US_ASCII);
                    ptySender.sendBytes(releaseRight);
                }
                mIsLongPressed = false;
                break;
        }

        return true;
    }

    private void scheduleLongPressTimer(PtySender ptySender) {
        cancelLongPressTimer();
        mLongPressRunnable = () -> {
            if (!mIsMoved && !mIsTwoFingerDrag) {
                mIsLongPressed = true;
                byte[] pressRight = SgrMouseProtocolGenerator.formatSgrPacket(2, mVirtualCursorCol, mVirtualCursorRow, true).getBytes(StandardCharsets.US_ASCII);
                ptySender.sendBytes(pressRight);
            }
        };
        if (mHandler != null) {
            mHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
        }
    }

    private void cancelLongPressTimer() {
        if (mLongPressRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mLongPressRunnable);
            mLongPressRunnable = null;
        }
    }
}
