package com.android.server.linux;

import android.content.Context;

/**
 * Hardware GPS/Location portal access policy.
 * {@hide}
 */
public class LinuxLocationPolicy {
    private final Context mContext;

    public LinuxLocationPolicy(Context context) {
        mContext = context;
    }

    public boolean isLocationAccessGranted() {
        return true;
    }
}
