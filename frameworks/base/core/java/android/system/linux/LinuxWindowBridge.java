package android.system.linux;

import android.annotation.NonNull;
import android.content.Context;
import android.os.RemoteException;

/**
 * Facade class for Linux GUI Window Bridge.
 * {@hide}
 */
public class LinuxWindowBridge {
    private final Context mContext;
    private final ILinuxWindowBridge mService;

    public LinuxWindowBridge(@NonNull Context context, @NonNull ILinuxWindowBridge service) {
        mContext = context;
        mService = service;
    }

    public ILinuxWindowBridge getService() {
        return mService;
    }
}
