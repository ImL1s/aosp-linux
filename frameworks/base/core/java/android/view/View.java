package android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

public class View {
    public interface OnClickListener {
        void onClick(View v);
    }

    private Context mContext;
    private int mWidth = 1024;
    private int mHeight = 768;

    public View() {}
    public View(Context context) { mContext = context; }
    public View(Context context, AttributeSet attrs) { mContext = context; }
    public View(Context context, AttributeSet attrs, int defStyleAttr) { mContext = context; }

    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }
    public Context getContext() { return mContext; }

    public void getLocationOnScreen(int[] outLocation) {
        if (outLocation != null && outLocation.length >= 2) {
            outLocation[0] = 0;
            outLocation[1] = 0;
        }
    }

    public void setFocusable(boolean focusable) {}
    public void setFocusableInTouchMode(boolean focusable) {}
    public boolean requestFocus() { return true; }
    public void invalidate() {}
    public void postInvalidate() {}
    public void setOnClickListener(OnClickListener l) {}
    public boolean onTouchEvent(MotionEvent event) { return false; }
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) { return null; }
    protected void onAttachedToWindow() {}
    protected void onDetachedFromWindow() {}
    protected void onDraw(Canvas canvas) {}
}
