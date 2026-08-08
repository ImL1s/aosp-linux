package android.app;

public class AppOpsManager {
    public static final String OPSTR_CAMERA = "android:camera";
    public static final String OPSTR_RECORD_AUDIO = "android:record_audio";
    public static final String OPSTR_FINE_LOCATION = "android:fine_location";
    public static final String OPSTR_COARSE_LOCATION = "android:coarse_location";

    public static final int MODE_ALLOWED = 0;
    public static final int MODE_IGNORED = 1;
    public static final int MODE_ERRORED = 2;
    public static final int MODE_DEFAULT = 3;
    public static final int MODE_FOREGROUND = 4;

    public int unsafeCheckOpRaw(String op, int uid, String packageName) {
        return MODE_ALLOWED;
    }

    public int noteOpNoThrow(String op, int uid, String packageName) {
        return MODE_ALLOWED;
    }
}
