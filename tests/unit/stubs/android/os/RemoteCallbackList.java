package android.os;

import java.util.concurrent.ConcurrentHashMap;

public class RemoteCallbackList<E extends IInterface> {
    private final ConcurrentHashMap<IBinder, E> mCallbacks = new ConcurrentHashMap<>();
    private Object[] mSnapshot;

    public boolean register(E callback) {
        if (callback != null && callback.asBinder() != null) {
            mCallbacks.put(callback.asBinder(), callback);
            return true;
        }
        return false;
    }

    public boolean register(E callback, Object cookie) {
        return register(callback);
    }

    public boolean unregister(E callback) {
        if (callback != null && callback.asBinder() != null) {
            return mCallbacks.remove(callback.asBinder()) != null;
        }
        return false;
    }

    public synchronized int beginBroadcast() {
        mSnapshot = mCallbacks.values().toArray();
        return mSnapshot.length;
    }

    @SuppressWarnings("unchecked")
    public synchronized E getBroadcastItem(int index) {
        if (mSnapshot != null && index >= 0 && index < mSnapshot.length) {
            return (E) mSnapshot[index];
        }
        return null;
    }

    public Object getBroadcastCookie(int index) {
        return null;
    }

    public synchronized void finishBroadcast() {
        mSnapshot = null;
    }

    public void kill() {
        mCallbacks.clear();
    }
}
