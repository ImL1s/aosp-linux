package com.android.virtualization.terminal.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.InputMethodManager;

/**
 * Renders Inline CJK Composing Window overlay on Terminal Surface Canvas and dispatches CursorAnchorInfo updates to Android IME.
 */
public class CjkComposingWindow {
    private final Paint mBgPaint;
    private final Paint mTextPaint;
    private final Paint mUnderlinePaint;

    private String mComposingText = "";
    private int mCursorCol = 0;
    private int mCursorRow = 0;
    private boolean mVisible = false;

    public CjkComposingWindow() {
        mBgPaint = new Paint();
        mBgPaint.setColor(0xCC223344); // Semi-transparent dark blue-gray
        mBgPaint.setStyle(Paint.Style.FILL);

        mTextPaint = new Paint();
        mTextPaint.setColor(Color.YELLOW);
        mTextPaint.setTextSize(36f);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setUnderlineText(true);

        mUnderlinePaint = new Paint();
        mUnderlinePaint.setColor(Color.YELLOW);
        mUnderlinePaint.setStrokeWidth(3f);
        mUnderlinePaint.setStyle(Paint.Style.STROKE);
    }

    public CjkComposingWindow(View targetView) {
        this();
    }

    public void updateComposing(String text, int cursorCol, int cursorRow, int cellW, int cellH) {
        this.mComposingText = (text != null) ? text : "";
        this.mCursorCol = cursorCol;
        this.mCursorRow = cursorRow;
        this.mVisible = !mComposingText.isEmpty();
    }

    public void hide() {
        this.mComposingText = "";
        this.mVisible = false;
    }

    public boolean isVisible() {
        return mVisible;
    }

    public void draw(Canvas canvas) {
        if (mVisible && canvas != null) {
            drawInlineComposing(canvas, mComposingText, mCursorCol, mCursorRow, 20f, 40f);
        }
    }

    public void drawInlineComposing(Canvas canvas, String text, int cursorCol, int cursorRow, float cellWidth, float cellHeight) {
        if (text == null || text.isEmpty()) {
            return;
        }

        float left = cursorCol * cellWidth;
        float top = cursorRow * cellHeight;
        float textWidth = mTextPaint.measureText(text);
        float right = left + Math.max(textWidth + 10, cellWidth);
        float bottom = top + cellHeight;

        // Background Box
        RectF bgRect = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(bgRect, 4f, 4f, mBgPaint);

        // Inline Text
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float baseline = top - fm.top;
        canvas.drawText(text, left + 5, baseline, mTextPaint);

        // Underline Accent
        canvas.drawLine(left, bottom - 2, right, bottom - 2, mUnderlinePaint);
    }

    public void notifyCursorAnchorInfo(View view, int cursorCol, int cursorRow, float cellWidth, float cellHeight) {
        if (view == null || view.getContext() == null) {
            return;
        }
        int[] locationOnScreen = new int[2];
        view.getLocationOnScreen(locationOnScreen);

        float cursorX = locationOnScreen[0] + cursorCol * cellWidth;
        float cursorY = locationOnScreen[1] + cursorRow * cellHeight;

        CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setInsertionMarkerLocation(cursorX, cursorY, cursorY + cellHeight, cursorY + cellHeight, 0);

        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.updateCursorAnchorInfo(view, builder.build());
        }
    }
}

