package android.content;

import android.database.Cursor;
import android.net.Uri;

public abstract class ContentProvider {
    private Context mContext;

    public boolean onCreate() {
        return true;
    }

    public Context getContext() {
        return mContext;
    }

    public void attachInfo(Context context, Object info) {
        mContext = context;
    }
}
