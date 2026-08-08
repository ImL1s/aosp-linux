package android.hardware.camera2;

import android.os.Handler;
import android.view.Surface;
import java.util.List;

public abstract class CameraDevice implements AutoCloseable {
    public static final int TEMPLATE_PREVIEW = 1;

    public static abstract class StateCallback {
        public abstract void onOpened(CameraDevice camera);
        public abstract void onDisconnected(CameraDevice camera);
        public abstract void onError(CameraDevice camera, int error);
    }

    public abstract String getId();
    public abstract void createCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler) throws Exception;
    public abstract CaptureRequest.Builder createCaptureRequest(int templateType) throws Exception;
    @Override
    public abstract void close();
}
