package android.app;

import android.graphics.Bitmap;

public class ActivityManager {
    public static final int MOVE_TASK_WITH_HOME = 1;

    public static class TaskDescription {
        private final String mTitle;
        private final Bitmap mIcon;
        private final int mPrimaryColor;

        public TaskDescription(String title, Bitmap icon, int primaryColor) {
            mTitle = title;
            mIcon = icon;
            mPrimaryColor = primaryColor;
        }

        public String getTitle() { return mTitle; }
        public Bitmap getIcon() { return mIcon; }
        public int getPrimaryColor() { return mPrimaryColor; }

        public static class Builder {
            private String mTitle;
            private Bitmap mIcon;
            private int mPrimaryColor;

            public Builder setTitle(String title) {
                mTitle = title;
                return this;
            }

            public Builder setIcon(Bitmap icon) {
                mIcon = icon;
                return this;
            }

            public Builder setPrimaryColor(int primaryColor) {
                mPrimaryColor = primaryColor;
                return this;
            }

            public TaskDescription build() {
                return new TaskDescription(mTitle, mIcon, mPrimaryColor);
            }
        }
    }

    public void moveTaskToFront(int taskId, int flags) {}
}
