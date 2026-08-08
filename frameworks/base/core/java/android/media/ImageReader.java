package android.media;

import android.os.Handler;

public class ImageReader implements AutoCloseable {
    public interface OnImageAvailableListener {
        void onImageAvailable(ImageReader reader);
    }

    public static ImageReader newInstance(int width, int height, int format, int maxImages) {
        return new ImageReader();
    }

    public void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {}
    public Image acquireNextImage() { return null; }
    public Image acquireLatestImage() { return null; }
    public android.view.Surface getSurface() { return new android.view.Surface(); }

    @Override
    public void close() {}
}
