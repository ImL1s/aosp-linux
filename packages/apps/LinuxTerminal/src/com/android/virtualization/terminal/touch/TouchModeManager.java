package com.android.virtualization.terminal.touch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * Touch Mode Manager & Visual Badge Overlay (F-R3-005).
 */
public class TouchModeManager {
    private final TouchModeStateMachine mStateMachine;
    private final Paint mBadgePaint;
    private final Paint mTextPaint;

    public TouchModeManager(Context context) {
        mStateMachine = new TouchModeStateMachine(context);
        mBadgePaint = new Paint();
        mBadgePaint.setColor(0xCC112233); // Translucent badge background

        mTextPaint = new Paint();
        mTextPaint.setColor(Color.GREEN);
        mTextPaint.setTextSize(24f);
        mTextPaint.setAntiAlias(true);
    }

    public TouchModeStateMachine getStateMachine() {
        return mStateMachine;
    }

    public void drawBadge(Canvas canvas, int width, int height) {
        if (canvas == null) return;
        String badgeText = "MODE: " + mStateMachine.getCurrentMode().name();
        if (mStateMachine.isManualLocked()) {
            badgeText += " [LOCKED]";
        }
        canvas.drawRect(10, 10, 260, 45, mBadgePaint);
        canvas.drawText(badgeText, 20, 35, mTextPaint);
    }
}
