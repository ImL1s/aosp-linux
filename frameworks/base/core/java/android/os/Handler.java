package android.os;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Handler {
    private static final ScheduledExecutorService sExecutor = Executors.newScheduledThreadPool(4);
    private final Looper mLooper;

    public Handler() {
        mLooper = Looper.getMainLooper();
    }

    public Handler(Looper looper) {
        mLooper = looper;
    }

    public Looper getLooper() {
        return mLooper;
    }

    public boolean post(Runnable r) {
        sExecutor.execute(r);
        return true;
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        sExecutor.schedule(r, delayMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    public void removeCallbacks(Runnable r) {}
}
