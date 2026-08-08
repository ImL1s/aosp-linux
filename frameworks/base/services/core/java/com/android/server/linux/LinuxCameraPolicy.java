package com.android.server.linux;

import android.content.Context;

/**
 * Hardware Camera portal access policy.
 * {@hide}
 */
public class LinuxCameraPolicy {
    private final Context mContext;

    public LinuxCameraPolicy(Context context) {
        mContext = context;
    }

    public boolean isCameraAvailable() {
        return true;
    }
}
