package android.graphics;

public class BitmapFactory {
    public static Bitmap decodeFile(String pathName) {
        if (pathName == null) return null;
        return new Bitmap(64, 64);
    }
}
