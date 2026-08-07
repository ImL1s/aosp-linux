package android.view;

public class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;

    private int mAction;
    private float mX;
    private float mY;

    public MotionEvent() {}
    public MotionEvent(int action, float x, float y) {
        mAction = action;
        mX = x;
        mY = y;
    }

    public int getAction() { return mAction; }
    public int getActionMasked() { return mAction & 0xff; }
    public int getPointerCount() { return 1; }
    public float getX() { return mX; }
    public float getY() { return mY; }
    public float getX(int pointerIndex) { return mX; }
    public float getY(int pointerIndex) { return mY; }
}
