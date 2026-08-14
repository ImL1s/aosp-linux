package android.system.linux;

/** {@hide} */
interface ILinuxWindowBridge {
    void onSurfaceCreated(int surfaceId, in android.view.Surface surface);
    void onSurfaceChanged(int surfaceId, int width, int height);
    void onSurfaceDestroyed(int surfaceId);
}
