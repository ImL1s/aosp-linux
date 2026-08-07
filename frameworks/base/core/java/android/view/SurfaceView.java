package android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;

public class SurfaceView extends View {
    private final SurfaceHolder mHolder = new SurfaceHolder() {
        @Override public void addCallback(Callback callback) {}
        @Override public void removeCallback(Callback callback) {}
        @Override public Surface getSurface() { return new Surface(); }
        @Override public Canvas lockCanvas() { return new Canvas(); }
        @Override public Canvas lockCanvas(Rect dirty) { return new Canvas(); }
        @Override public void unlockCanvasAndPost(Canvas canvas) {}
    };

    public SurfaceView(Context context) {
        super(context);
    }

    public SurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SurfaceHolder getHolder() {
        return mHolder;
    }
}
