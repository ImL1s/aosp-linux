package android.os;

public class Process {
    public static final int SYSTEM_UID = 1000;
    public static final int FIRST_APPLICATION_UID = 10000;

    public static int myUid() {
        return SYSTEM_UID;
    }
}
