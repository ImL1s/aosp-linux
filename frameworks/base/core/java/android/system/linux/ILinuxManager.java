package android.system.linux;

import android.os.IInterface;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.util.List;

public interface ILinuxManager extends IInterface {
    int getState() throws android.os.RemoteException;
    boolean startVm() throws android.os.RemoteException;
    boolean stopVm(boolean force) throws android.os.RemoteException;
    boolean suspendVm() throws android.os.RemoteException;
    boolean resumeVm() throws android.os.RemoteException;
    String createTerminalSession(int width, int height, ILinuxTerminalCallback callback) throws android.os.RemoteException;
    void resizeTerminalSession(String sessionId, int width, int height) throws android.os.RemoteException;
    void closeTerminalSession(String sessionId) throws android.os.RemoteException;
    void writeTerminalInput(String sessionId, byte[] data) throws android.os.RemoteException;
    List<LinuxAppInfo> getInstalledApps() throws android.os.RemoteException;
    boolean launchLinuxApp(String appId, int displayId) throws android.os.RemoteException;
    boolean installGuestImage(ParcelFileDescriptor imageFd, long size) throws android.os.RemoteException;
    void registerStatusCallback(ILinuxStatusCallback callback) throws android.os.RemoteException;
    void unregisterStatusCallback(ILinuxStatusCallback callback) throws android.os.RemoteException;

    public abstract static class Stub extends Binder implements ILinuxManager {
        private static final String DESCRIPTOR = "android.system.linux.ILinuxManager";

        public Stub() {
            super(DESCRIPTOR);
        }

        public static ILinuxManager asInterface(IBinder obj) {
            if (obj == null) return null;
            if (obj instanceof ILinuxManager) return (ILinuxManager) obj;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        private static class Proxy implements ILinuxManager {
            private final IBinder mRemote;
            Proxy(IBinder remote) { mRemote = remote; }
            @Override public IBinder asBinder() { return mRemote; }
            @Override public int getState() throws android.os.RemoteException { return 0; }
            @Override public boolean startVm() throws android.os.RemoteException { return true; }
            @Override public boolean stopVm(boolean force) throws android.os.RemoteException { return true; }
            @Override public boolean suspendVm() throws android.os.RemoteException { return true; }
            @Override public boolean resumeVm() throws android.os.RemoteException { return true; }
            @Override public String createTerminalSession(int width, int height, ILinuxTerminalCallback callback) throws android.os.RemoteException { return ""; }
            @Override public void resizeTerminalSession(String sessionId, int width, int height) throws android.os.RemoteException {}
            @Override public void closeTerminalSession(String sessionId) throws android.os.RemoteException {}
            @Override public void writeTerminalInput(String sessionId, byte[] data) throws android.os.RemoteException {}
            @Override public List<LinuxAppInfo> getInstalledApps() throws android.os.RemoteException { return null; }
            @Override public boolean launchLinuxApp(String appId, int displayId) throws android.os.RemoteException { return true; }
            @Override public boolean installGuestImage(ParcelFileDescriptor imageFd, long size) throws android.os.RemoteException { return true; }
            @Override public void registerStatusCallback(ILinuxStatusCallback callback) throws android.os.RemoteException {}
            @Override public void unregisterStatusCallback(ILinuxStatusCallback callback) throws android.os.RemoteException {}
        }
    }
}
