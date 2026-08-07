package android.content.res;

import android.util.DisplayMetrics;

public class Resources {
    private final DisplayMetrics mMetrics = new DisplayMetrics();
    private final Configuration mConfig = new Configuration();

    public DisplayMetrics getDisplayMetrics() { return mMetrics; }
    public Configuration getConfiguration() { return mConfig; }
}
