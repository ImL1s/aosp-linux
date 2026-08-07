package android.content.res;

public class Configuration {
    public int screenWidthDp = 1280;
    public int screenHeightDp = 800;

    public Configuration() {}

    public Configuration(Configuration other) {
        if (other != null) {
            this.screenWidthDp = other.screenWidthDp;
            this.screenHeightDp = other.screenHeightDp;
        }
    }
}
