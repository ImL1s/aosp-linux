package android.graphics;

public class Bitmap {
    public enum Config {
        ARGB_8888,
        RGB_565
    }

    private final int mWidth;
    private final int mHeight;
    private boolean mIsRecycled = false;

    public Bitmap(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }

    public boolean isRecycled() { return mIsRecycled; }
    public void recycle() { mIsRecycled = true; }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(width, height);
    }
}
