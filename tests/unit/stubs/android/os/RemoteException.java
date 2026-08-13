package android.os;

public class RemoteException extends Exception {
    public RemoteException() { super(); }
    public RemoteException(String message) { super(message); }
    public RuntimeException rethrowFromSystemServer() {
        return new RuntimeException(this);
    }
}
