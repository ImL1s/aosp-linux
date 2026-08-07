package android.os;

public class HandlerThread extends Thread {
    private final Looper mLooper;

    public HandlerThread(String name) {
        super(name);
        mLooper = new Looper();
    }

    public Looper getLooper() {
        return mLooper;
    }

    public boolean quitSafely() {
        return true;
    }
}
