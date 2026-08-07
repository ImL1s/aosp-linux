package android.os;

public class RemoteException extends Exception {
    public RemoteException() { super(); }
    public RemoteException(String message) { super(message); }
    public RemoteException(String message, Throwable cause) { super(message, cause); }

    public RuntimeException rethrowFromSystemServer() {
        throw new RuntimeException(this);
    }
}
