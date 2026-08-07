package android.os;

public class Binder implements IBinder {
    private String mDescriptor;

    public Binder() {}

    public Binder(String descriptor) {
        mDescriptor = descriptor;
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {}

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }

    @Override
    public String getInterfaceDescriptor() throws RemoteException { return mDescriptor; }

    public static long clearCallingIdentity() { return 0L; }
    public static void restoreCallingIdentity(long token) {}
    public static int getCallingUid() { return 1000; }
    public static int getCallingPid() { return 100; }
}
