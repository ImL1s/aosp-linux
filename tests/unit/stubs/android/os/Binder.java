package android.os;

import java.io.FileDescriptor;

public class Binder implements IBinder {
    public Binder() {}
    public Binder(String descriptor) {}
    public void attachInterface(IInterface owner, String descriptor) {}
    public IInterface queryLocalInterface(String descriptor) { return null; }
    public String getInterfaceDescriptor() { return null; }
    public boolean pingBinder() { return true; }
    public boolean isBinderAlive() { return true; }
    public void linkToDeath(DeathRecipient recipient, int flags) {}
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException { return true; }
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException { return true; }
    public void dump(FileDescriptor fd, String[] args) {}
    public void dumpAsync(FileDescriptor fd, String[] args) {}
}
