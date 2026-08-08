package android.media;

public abstract class Image implements AutoCloseable {
    public abstract int getWidth();
    public abstract int getHeight();
    public abstract int getFormat();
    @Override
    public abstract void close();
}
