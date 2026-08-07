package android.system.linux;

import android.os.IInterface;
import android.os.Binder;
import android.os.IBinder;

public interface ILinuxTerminalCallback extends IInterface {
    void onDataReceived(String sessionId, byte[] data) throws android.os.RemoteException;
    void onTitleChanged(String sessionId, String title) throws android.os.RemoteException;
    void onBell(String sessionId) throws android.os.RemoteException;
    void onSessionClosed(String sessionId, int exitCode) throws android.os.RemoteException;

    public abstract static class Stub extends Binder implements ILinuxTerminalCallback {
        private static final String DESCRIPTOR = "android.system.linux.ILinuxTerminalCallback";

        public Stub() {
            super(DESCRIPTOR);
        }

        public static ILinuxTerminalCallback asInterface(IBinder obj) {
            if (obj == null) return null;
            if (obj instanceof ILinuxTerminalCallback) return (ILinuxTerminalCallback) obj;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        private static class Proxy implements ILinuxTerminalCallback {
            private final IBinder mRemote;
            Proxy(IBinder remote) { mRemote = remote; }
            @Override public IBinder asBinder() { return mRemote; }
            @Override public void onDataReceived(String sessionId, byte[] data) throws android.os.RemoteException {}
            @Override public void onTitleChanged(String sessionId, String title) throws android.os.RemoteException {}
            @Override public void onBell(String sessionId) throws android.os.RemoteException {}
            @Override public void onSessionClosed(String sessionId, int exitCode) throws android.os.RemoteException {}
        }
    }
}
