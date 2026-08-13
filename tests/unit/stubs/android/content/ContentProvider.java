package android.content;

public abstract class ContentProvider {
    private Context mContext;

    public ContentProvider() {}

    public boolean onCreate() {
        return true;
    }

    public Context getContext() {
        return mContext;
    }

    public void attachInfoForTest(Context context) {
        mContext = context;
    }
}
