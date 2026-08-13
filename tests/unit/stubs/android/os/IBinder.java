package android.os;

import java.io.FileDescriptor;

public interface IBinder {
    int FIRST_CALL_TRANSACTION = 1;
    int LAST_CALL_TRANSACTION = 0x00ffffff;
    int INTERFACE_TRANSACTION = 0x0546524e;
    int FLAG_ONEWAY = 0x00000001;
    String getInterfaceDescriptor() throws RemoteException;
    boolean pingBinder();
    boolean isBinderAlive();
    IInterface queryLocalInterface(String descriptor);
    void dump(FileDescriptor fd, String[] args) throws RemoteException;
    void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException;
    boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException;
    public interface DeathRecipient {
        void binderDied();
    }
    void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException;
    boolean unlinkToDeath(DeathRecipient recipient, int flags);
}
