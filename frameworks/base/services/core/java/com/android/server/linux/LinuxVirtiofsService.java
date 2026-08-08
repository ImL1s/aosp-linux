package com.android.server.linux;

import android.content.Context;

/**
 * Virtiofs file sharing mount and lifecycle manager.
 * {@hide}
 */
public class LinuxVirtiofsService {
    private final Context mContext;

    public LinuxVirtiofsService(Context context) {
        mContext = context;
    }

    public boolean isMounted() {
        return true;
    }
}
