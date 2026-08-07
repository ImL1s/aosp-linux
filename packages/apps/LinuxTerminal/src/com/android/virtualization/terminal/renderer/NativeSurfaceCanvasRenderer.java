package com.android.virtualization.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.Log;
import android.view.SurfaceHolder;
import com.android.virtualization.terminal.ime.CjkComposingWindow;

/**
 * High-performance off-UI thread Native Surface Canvas Renderer for Linux Terminal.
 * Aligns frame updates to 60/120 FPS VSync budget with dirty rect local refresh.
 */
public class NativeSurfaceCanvasRenderer implements Runnable {
    private static final String TAG = "NativeSurfaceCanvasRenderer";

    private final SurfaceHolder mSurfaceHolder;
    private final TerminalScreenMatrix mScreenMatrix;
    private final TextPaint mTextPaint;
    private final Paint mBgPaint;
    private final Paint mCursorPaint;
    private final CjkComposingWindow mComposingWindow;

    private Thread mRenderThread;
    private volatile boolean mIsRunning = false;

    private float mFontSizePx = 36f;
    private float mCellWidth = 20f;
    private float mCellHeight = 40f;
    private float mFontBaseline = 32f;

    private String mInlineComposingText = "";
    private int mInlineComposingCursor = 0;

    public interface OnGridDimensionsChangedListener {
        void onGridSizeChanged(int rows, int cols);
    }

    private OnGridDimensionsChangedListener mGridListener;

    public NativeSurfaceCanvasRenderer(SurfaceHolder surfaceHolder, TerminalScreenMatrix screenMatrix) {
        this.mSurfaceHolder = surfaceHolder;
        this.mScreenMatrix = screenMatrix;
        this.mComposingWindow = new CjkComposingWindow();

        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTypeface(Typeface.MONOSPACE);
        mTextPaint.setTextSize(mFontSizePx);

        mBgPaint = new Paint();
        mBgPaint.setStyle(Paint.Style.FILL);

        mCursorPaint = new Paint();
        mCursorPaint.setColor(Color.GREEN);
        mCursorPaint.setStyle(Paint.Style.FILL);

        recalculateMetrics();
    }

    public void setOnGridDimensionsChangedListener(OnGridDimensionsChangedListener listener) {
        this.mGridListener = listener;
    }

    public void setFontSize(float fontSizePx) {
        if (fontSizePx >= 12f && fontSizePx <= 72f) {
            this.mFontSizePx = fontSizePx;
            mTextPaint.setTextSize(fontSizePx);
            recalculateMetrics();
        }
    }

    public float getCellWidth() {
        return mCellWidth;
    }

    public float getCellHeight() {
        return mCellHeight;
    }

    public synchronized void setInlineComposing(String text, int cursorPosition) {
        this.mInlineComposingText = (text != null) ? text : "";
        this.mInlineComposingCursor = cursorPosition;
        mScreenMatrix.markAllDirty();
    }

    private void recalculateMetrics() {
        mCellWidth = mTextPaint.measureText("M");
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        mCellHeight = (float) Math.ceil(fm.bottom - fm.top + fm.leading);
        mFontBaseline = -fm.top;
    }

    public void onSurfaceChanged(int width, int height) {
        recalculateMetrics();
        int cols = (int) (width / Math.max(1f, mCellWidth));
        int rows = (int) (height / Math.max(1f, mCellHeight));
        cols = Math.max(1, cols);
        rows = Math.max(1, rows);

        mScreenMatrix.resize(rows, cols);
        if (mGridListener != null) {
            mGridListener.onGridSizeChanged(rows, cols);
        }
    }

    public synchronized void start() {
        if (mRenderThread == null || !mRenderThread.isAlive()) {
            mIsRunning = true;
            mRenderThread = new Thread(this, "TerminalRenderThread");
            mRenderThread.start();
        }
    }

    public synchronized void stop() {
        mIsRunning = false;
        if (mRenderThread != null) {
            try {
                mRenderThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mRenderThread = null;
        }
    }

    @Override
    public void run() {
        long targetFrameNanos = 16_666_666L; // 60 FPS target (~16.66ms)
        Rect dirtyGridRect = new Rect();

        while (mIsRunning) {
            long startTime = System.nanoTime();

            if (mSurfaceHolder.getSurface().isValid()) {
                if (mScreenMatrix.getAndClearDirtyRect(dirtyGridRect)) {
                    renderFrame(dirtyGridRect);
                }
            }

            long elapsed = System.nanoTime() - startTime;
            long sleepNanos = targetFrameNanos - elapsed;
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void renderFrame(Rect dirtyGridRect) {
        Canvas canvas = null;
        try {
            Rect pixelDirty = new Rect(
                (int) (dirtyGridRect.left * mCellWidth),
                (int) (dirtyGridRect.top * mCellHeight),
                (int) (Math.ceil(dirtyGridRect.right * mCellWidth)),
                (int) (Math.ceil(dirtyGridRect.bottom * mCellHeight))
            );

            canvas = mSurfaceHolder.lockCanvas(pixelDirty);
            if (canvas == null) {
                return;
            }

            canvas.drawColor(Color.BLACK);

            int rows = mScreenMatrix.getRows();
            int cols = mScreenMatrix.getCols();

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    TerminalCell cell = mScreenMatrix.getCell(r, c);
                    if (cell == null || cell.width == 0) {
                        continue; // Skip CJK continuation cell
                    }

                    float left = c * mCellWidth;
                    float top = r * mCellHeight;

                    int fg = cell.fgColor;
                    int bg = cell.bgColor;
                    if ((cell.attributes & TerminalCell.ATTR_REVERSE) != 0) {
                        int tmp = fg;
                        fg = bg;
                        bg = tmp;
                    }

                    // Background Rect
                    if (bg != Color.BLACK) {
                        mBgPaint.setColor(bg);
                        canvas.drawRect(left, top, left + cell.width * mCellWidth, top + mCellHeight, mBgPaint);
                    }

                    // Text Character
                    mTextPaint.setColor(fg);
                    mTextPaint.setFakeBoldText((cell.attributes & TerminalCell.ATTR_BOLD) != 0);
                    mTextPaint.setTextSkewX((cell.attributes & TerminalCell.ATTR_ITALIC) != 0 ? -0.25f : 0f);
                    mTextPaint.setUnderlineText((cell.attributes & TerminalCell.ATTR_UNDERLINE) != 0);
                    mTextPaint.setStrikeThruText((cell.attributes & TerminalCell.ATTR_STRIKE) != 0);

                    String str = new String(Character.toChars(cell.codepoint));
                    canvas.drawText(str, left, top + mFontBaseline, mTextPaint);
                }
            }

            // Draw Block Cursor
            if (mScreenMatrix.isCursorVisible()) {
                int curRow = mScreenMatrix.getCursorRow();
                int curCol = mScreenMatrix.getCursorCol();
                float curLeft = curCol * mCellWidth;
                float curTop = curRow * mCellHeight;
                canvas.drawRect(curLeft, curTop, curLeft + mCellWidth, curTop + mCellHeight, mCursorPaint);
            }

            // Draw Inline CJK Composing Window
            if (!mInlineComposingText.isEmpty()) {
                int curRow = mScreenMatrix.getCursorRow();
                int curCol = mScreenMatrix.getCursorCol();
                mComposingWindow.drawInlineComposing(canvas, mInlineComposingText, curCol, curRow, mCellWidth, mCellHeight);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error rendering canvas frame", e);
        } finally {
            if (canvas != null) {
                mSurfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }
}
