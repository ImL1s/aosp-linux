package android.os;

public class UserHandle {
    private final int mHandle;

    public UserHandle(int h) { mHandle = h; }
    public int getIdentifier() { return mHandle; }
    public static UserHandle of(int userId) { return new UserHandle(userId); }
}
