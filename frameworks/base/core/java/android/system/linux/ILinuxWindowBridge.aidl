package android.system.linux;

/**
 * Interface for Wayland window forwarding and task binding.
 * {@hide}
 */
interface ILinuxWindowBridge {
    void onSurfaceCreated(int surfaceId, in android.view.Surface surface);
    void onSurfaceChanged(int surfaceId, int width, int height);
    void onSurfaceDestroyed(int surfaceId);
}
