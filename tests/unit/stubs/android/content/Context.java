package android.content;

import java.util.concurrent.Executor;

public abstract class Context {
    public static final String LINUX_SERVICE = "linux";
    public static final String AUDIO_SERVICE = "audio";
    public static final String CAMERA_SERVICE = "camera";
    public static final String LOCATION_SERVICE = "location";
    public static final String INPUT_METHOD_SERVICE = "input_method";
    public static final String ACTIVITY_SERVICE = "activity";
    public static final String APP_OPS_SERVICE = "appops";
    public static final int MODE_PRIVATE = 0;

    public abstract Object getSystemService(String name);
    @SuppressWarnings("unchecked")
    public <T> T getSystemService(Class<T> serviceClass) {
        return (T) getSystemService(serviceClass.getName());
    }
    public boolean isDeviceProtectedStorage() { return false; }
    public Context createDeviceProtectedStorageContext() { return this; }
    public void enforceCallingOrSelfPermission(String permission, String message) {}
    public void startActivity(Intent intent) {}
    public void sendBroadcast(Intent intent) {}
    public Intent registerReceiver(Object receiver, Object filter) { return null; }
    public void unregisterReceiver(Object receiver) {}
    public Object getSharedPreferences(String name, int mode) { return null; }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
    public Executor getMainExecutor() { return Runnable::run; }
}
