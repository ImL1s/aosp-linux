package android.hardware.camera2;

import android.os.Handler;

public class CameraManager {
    public static abstract class AvailabilityCallback {
        public void onCameraAvailable(String cameraId) {}
        public void onCameraUnavailable(String cameraId) {}
    }

    public static abstract class StateCallback extends CameraDevice.StateCallback {}

    public String[] getCameraIdList() throws Exception {
        return new String[]{"0"};
    }

    public void openCamera(String cameraId, CameraDevice.StateCallback callback, Handler handler) throws Exception {}

    public void registerAvailabilityCallback(AvailabilityCallback callback, Handler handler) {}
    public void unregisterAvailabilityCallback(AvailabilityCallback callback) {}
}
