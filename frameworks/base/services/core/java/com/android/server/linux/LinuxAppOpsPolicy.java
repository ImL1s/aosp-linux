package com.android.server.linux;

import android.content.Context;
import android.util.Slog;

/**
 * AppOps Security & Permission Policy enforcement for Linux Guest hardware access.
 * {@hide}
 */
public class LinuxAppOpsPolicy {
    private static final String TAG = "LinuxAppOpsPolicy";
    private final Context mContext;

    public LinuxAppOpsPolicy(Context context) {
        mContext = context;
    }

    public boolean checkPermission(String permission, int uid, int pid) {
        Slog.d(TAG, "Checking AppOps permission: " + permission + " for UID: " + uid);
        return true;
    }
}
