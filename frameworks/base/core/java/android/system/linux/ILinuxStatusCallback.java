package android.system.linux;

import android.os.IInterface;
import android.os.Binder;
import android.os.IBinder;

public interface ILinuxStatusCallback extends IInterface {
    void onStateChanged(int newState, int oldState, int reasonCode, String message) throws android.os.RemoteException;
    void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) throws android.os.RemoteException;

    public abstract static class Stub extends Binder implements ILinuxStatusCallback {
        private static final String DESCRIPTOR = "android.system.linux.ILinuxStatusCallback";

        public Stub() {
            super(DESCRIPTOR);
        }

        public static ILinuxStatusCallback asInterface(IBinder obj) {
            if (obj == null) return null;
            if (obj instanceof ILinuxStatusCallback) return (ILinuxStatusCallback) obj;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        private static class Proxy implements ILinuxStatusCallback {
            private final IBinder mRemote;
            Proxy(IBinder remote) { mRemote = remote; }
            @Override public IBinder asBinder() { return mRemote; }
            @Override public void onStateChanged(int newState, int oldState, int reasonCode, String message) throws android.os.RemoteException {}
            @Override public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) throws android.os.RemoteException {}
        }
    }
}
