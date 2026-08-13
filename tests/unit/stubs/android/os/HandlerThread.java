package android.os;

public class HandlerThread extends Thread {
    public HandlerThread(String name) { super(name); }
    public HandlerThread(String name, int priority) { super(name); }
    public Looper getLooper() { return Looper.getMainLooper(); }
    public boolean quit() { return true; }
    public boolean quitSafely() { return true; }
}
