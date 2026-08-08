package android.hardware.camera2;

import android.os.Handler;

public abstract class CameraCaptureSession implements AutoCloseable {
    public static abstract class StateCallback {
        public abstract void onConfigured(CameraCaptureSession session);
        public abstract void onConfigureFailed(CameraCaptureSession session);
    }

    public abstract void setRepeatingRequest(CaptureRequest request, CaptureCallback listener, Handler handler) throws Exception;
    public static abstract class CaptureCallback {}
    @Override
    public abstract void close();
}
