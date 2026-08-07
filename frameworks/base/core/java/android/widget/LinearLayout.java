package android.widget;

import android.content.Context;
import android.view.View;

public class LinearLayout extends View {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public LayoutParams(int width, int height) {}
    }

    public LinearLayout(Context context) { super(context); }
    public void setOrientation(int orientation) {}
    public void addView(View child) {}
    public void addView(View child, LayoutParams params) {}
}
