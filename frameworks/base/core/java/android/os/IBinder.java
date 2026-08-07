package android.os;

public interface IBinder {
    public interface DeathRecipient {
        void binderDied();
    }
    void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException;
    boolean unlinkToDeath(DeathRecipient recipient, int flags);
    String getInterfaceDescriptor() throws RemoteException;
}
