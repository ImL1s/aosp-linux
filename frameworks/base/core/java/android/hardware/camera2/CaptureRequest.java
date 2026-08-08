package android.hardware.camera2;

import android.view.Surface;

public class CaptureRequest {
    public static class Builder {
        public void addTarget(Surface outputTarget) {}
        public CaptureRequest build() { return new CaptureRequest(); }
    }
}
