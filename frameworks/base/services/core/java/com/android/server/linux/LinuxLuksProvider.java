package com.android.server.linux;

import android.content.Context;

/**
 * LUKS2 CE encryption volume provider and key manager.
 * {@hide}
 */
public class LinuxLuksProvider {
    private final Context mContext;

    public LinuxLuksProvider(Context context) {
        mContext = context;
    }

    public boolean isCeKeyAvailable() {
        return true;
    }
}
