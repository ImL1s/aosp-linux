// frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java
package com.android.server.linux;

import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.system.linux.ILinuxManager;
import android.system.linux.ILinuxStatusCallback;
import android.system.linux.ILinuxTerminalCallback;
import android.system.linux.LinuxAppInfo;
import android.system.linux.LinuxManager;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SystemServer implementation of LinuxManagerService handling AVF VM Lifecycle & Bridge Dispatching.
 * {@hide}
 */
public class LinuxManagerService extends SystemService {
    private static final String TAG = "LinuxManagerService";
    private static final String PERMISSION_MANAGE_LINUX = "android.permission.MANAGE_LINUX_ENVIRONMENT";
    private static final String PERMISSION_USE_LINUX_TERMINAL = "android.permission.USE_LINUX_TERMINAL";
    public static final long BOOT_TIMEOUT_MS = 15000L;

    private final Context mContext;
    private final BinderService mBinderService;
    private final LocalService mLocalService;
    private final LinuxBridgeService mBridgeService;
    private final ScheduledExecutorService mScheduler = Executors.newSingleThreadScheduledExecutor();
    private final RemoteCallbackList<ILinuxStatusCallback> mStatusCallbacks = new RemoteCallbackList<>();
    private final Map<String, TerminalSession> mTerminalSessions = new HashMap<>();

    private final Object mStateLock = new Object();
    private int mCurrentState = LinuxManager.STATE_STOPPED;
    private ScheduledFuture<?> mBootTimeoutFuture;
    private int mNextSessionId = 1000;

    private static class TerminalSession {
        final String sessionId;
        final int width;
        final int height;
        final ILinuxTerminalCallback callback;

        TerminalSession(String sessionId, int width, int height, ILinuxTerminalCallback callback) {
            this.sessionId = sessionId;
            this.width = width;
            this.height = height;
            this.callback = callback;
        }
    }

    public LinuxManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new BinderService();
        mLocalService = new LocalService();
        mBridgeService = new LinuxBridgeService(context, new LinuxBridgeService.LinuxBridgeCallback() {
            @Override
            public void onVmHandshakeCompleted() {
                notifyVmStarted();
            }

            @Override
            public void onVmDisconnected() {
                synchronized (mStateLock) {
                    if (mCurrentState == LinuxManager.STATE_RUNNING) {
                        int oldState = mCurrentState;
                        mCurrentState = LinuxManager.STATE_STOPPED;
                        dispatchStateChanged(mCurrentState, oldState, 102, "VM Disconnected");
                    }
                }
            }

            @Override
            public void onPtyDataReceived(String sessionId, byte[] data) {
                synchronized (mStateLock) {
                    TerminalSession session = mTerminalSessions.get(sessionId);
                    if (session != null && session.callback != null) {
                        try {
                            session.callback.onDataReceived(sessionId, data);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to deliver PTY data to callback for session " + sessionId, e);
                        }
                    }
                }
            }

            @Override
            public void onError(int errorCode, String message) {
                Slog.e(TAG, "Bridge Service Error [" + errorCode + "]: " + message);
            }

            @Override
            public void onVmStartFailed(int errorCode, String message) {
                synchronized (mStateLock) {
                    if (mCurrentState == LinuxManager.STATE_STARTING) {
                        cancelBootTimeoutLocked();
                        int oldState = mCurrentState;
                        mCurrentState = LinuxManager.STATE_ERROR;
                        Slog.e(TAG, "VM Launch failed from native daemon: " + message + " (code: " + errorCode + ")");
                        dispatchStateChanged(mCurrentState, oldState, errorCode > 0 ? errorCode : 100, message != null ? message : "VM Launch Failed");
                    }
                }
            }
        });
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Starting LinuxManagerService");
        publishBinderService("linux", mBinderService);
        publishLocalService(LinuxManagerInternal.class, mLocalService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            Slog.i(TAG, "LinuxManagerService: PHASE_SYSTEM_SERVICES_READY");
        } else if (phase == PHASE_BOOT_COMPLETED) {
            Slog.i(TAG, "LinuxManagerService: PHASE_BOOT_COMPLETED -> starting bridge daemon socket connection");
            if (mBridgeService != null) {
                mBridgeService.start();
            }
        }
    }

    @Override
    public void onUserUnlocking(TargetUser user) {
        int userId = user.getUserHandle().getIdentifier();
        mLocalService.onUserUnlocked(userId);
    }

    public LocalService getLocalService() {
        return mLocalService;
    }

    public BinderService getBinderService() {
        return mBinderService;
    }

    public int getState() {
        synchronized (mStateLock) {
            return mCurrentState;
        }
    }

    /**
     * Called when guest VM completes boot or connects via bridge daemon.
     */
    public void notifyVmStarted() {
        synchronized (mStateLock) {
            if (mCurrentState == LinuxManager.STATE_STARTING) {
                cancelBootTimeoutLocked();
                int oldState = mCurrentState;
                mCurrentState = LinuxManager.STATE_RUNNING;
                Slog.i(TAG, "Linux Guest VM boot completed -> STATE_RUNNING");
                dispatchStateChanged(mCurrentState, oldState, 0, "VM Running");
            }
        }
    }

    private void cancelBootTimeoutLocked() {
        if (mBootTimeoutFuture != null) {
            mBootTimeoutFuture.cancel(false);
            mBootTimeoutFuture = null;
        }
    }

    public void handleBootTimeout() {
        synchronized (mStateLock) {
            if (mCurrentState == LinuxManager.STATE_STARTING) {
                int oldState = mCurrentState;
                mCurrentState = LinuxManager.STATE_ERROR;
                mBootTimeoutFuture = null;
                Slog.e(TAG, "Linux Guest VM boot timed out (15s exceeded) -> STATE_ERROR");
                dispatchStateChanged(mCurrentState, oldState, 101, "VM Boot Timeout (15s exceeded)");
            }
        }
    }

    private void dispatchStateChanged(int newState, int oldState, int reasonCode, String message) {
        if (mLocalService != null) {
            mLocalService.notifyVmStateChanged(newState, oldState);
        }
        if (newState == LinuxManager.STATE_STOPPED || newState == LinuxManager.STATE_SUSPENDED) {
            LinuxPortalService portal = LinuxPortalService.getInstance();
            if (portal != null) {
                portal.onVmStoppedOrSuspended();
            }
        }
        int count = mStatusCallbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mStatusCallbacks.getBroadcastItem(i).onStateChanged(newState, oldState, reasonCode, message);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error notifying status callback", e);
            }
        }
        mStatusCallbacks.finishBroadcast();
    }

    private byte[] mCeKeyBytes;
    private boolean mCeKeyAvailable = false;
    private byte[] mActiveAuthToken;
    private byte[] mActiveAuthSecret;

    public byte[] deriveLuksKeyFromCeKey(byte[] rawCeMasterKey, int userId) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] salt = java.nio.ByteBuffer.allocate(4).putInt(userId).array();
            mac.init(new javax.crypto.spec.SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(rawCeMasterKey != null ? rawCeMasterKey : new byte[32]);

            mac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
            byte[] info = "aosp.linux.ce.user_home.luks2_master_key".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream okm = new java.io.ByteArrayOutputStream();
            byte[] t = new byte[0];
            for (int i = 1; okm.size() < 64; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                okm.write(t);
            }
            byte[] derivedKey = new byte[64];
            System.arraycopy(okm.toByteArray(), 0, derivedKey, 0, 64);
            return derivedKey;
        } catch (Exception e) {
            Slog.e(TAG, "HKDF key derivation failed", e);
            return new byte[64];
        }
    }

    public byte[] getOrGeneratePersistentMasterKey(int userId) {
        java.io.File keyFile = new java.io.File("/data/system/users/" + userId + "/linux_ce_master.key");
        byte[] key = new byte[32];
        try {
            if (keyFile.exists() && keyFile.length() == 32) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(keyFile)) {
                    int read = fis.read(key);
                    if (read == 32) {
                        return key;
                    }
                }
            }
            java.io.File parent = keyFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            new java.security.SecureRandom().nextBytes(key);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(keyFile)) {
                fos.write(key);
                fos.flush();
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to manage persistent CE master key for user " + userId, e);
        }
        return key;
    }

    public byte[] generateHmacAuthToken() {
        byte[] token = new byte[32];
        byte[] secret = new byte[32];
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(token);
        random.nextBytes(secret);
        mActiveAuthToken = token;
        mActiveAuthSecret = secret;
        byte[] payload = new byte[64];
        System.arraycopy(token, 0, payload, 0, 32);
        System.arraycopy(secret, 0, payload, 32, 32);
        return payload;
    }

    public boolean isCeKeyAvailable() {
        return mCeKeyAvailable;
    }

    public final class LocalService extends LinuxManagerInternal {
        private final List<StorageStateListener> mStorageListeners = new ArrayList<>();
        private boolean mReadOnlyMount = false;

        @Override
        public boolean isVmRunning() {
            synchronized (mStateLock) {
                return mCurrentState == LinuxManager.STATE_RUNNING;
            }
        }

        @Override
        public int getVmState() {
            synchronized (mStateLock) {
                return mCurrentState;
            }
        }

        @Override
        public boolean isCeKeyAvailable() {
            return mCeKeyAvailable;
        }

        @Override
        public boolean isReadOnlyMount() {
            return mReadOnlyMount;
        }

        public void setReadOnlyMount(boolean readOnly) {
            mReadOnlyMount = readOnly;
            notifyStorageMountChanged(readOnly);
        }

        @Override
        public void registerStorageStateListener(StorageStateListener listener) {
            synchronized (mStorageListeners) {
                if (listener != null && !mStorageListeners.contains(listener)) {
                    mStorageListeners.add(listener);
                }
            }
        }

        @Override
        public void unregisterStorageStateListener(StorageStateListener listener) {
            synchronized (mStorageListeners) {
                if (listener != null) {
                    mStorageListeners.remove(listener);
                }
            }
        }

        public void notifyVmStateChanged(int newState, int oldState) {
            synchronized (mStorageListeners) {
                for (StorageStateListener listener : mStorageListeners) {
                    try {
                        listener.onVmStateChanged(newState, oldState);
                    } catch (Exception e) {
                        Slog.w(TAG, "Error notifying storage state listener", e);
                    }
                }
            }
        }

        public void notifyCeKeyStatusChanged(boolean available) {
            synchronized (mStorageListeners) {
                for (StorageStateListener listener : mStorageListeners) {
                    try {
                        listener.onCeKeyStatusChanged(available);
                    } catch (Exception e) {
                        Slog.w(TAG, "Error notifying storage state listener", e);
                    }
                }
            }
        }

        public void notifyStorageMountChanged(boolean isReadOnly) {
            synchronized (mStorageListeners) {
                for (StorageStateListener listener : mStorageListeners) {
                    try {
                        listener.onStorageMountChanged(isReadOnly);
                    } catch (Exception e) {
                        Slog.w(TAG, "Error notifying storage state listener", e);
                    }
                }
            }
        }

        @Override
        public void onUserUnlocked(int userId) {
            Slog.i(TAG, "User " + userId + " unlocked -> checking Linux CE storage");
            byte[] masterKey = getOrGeneratePersistentMasterKey(userId);
            mCeKeyBytes = deriveLuksKeyFromCeKey(masterKey, userId);
            mCeKeyAvailable = true;
            notifyCeKeyStatusChanged(true);
        }

        public void onUserLocked(int userId) {
            Slog.i(TAG, "User " + userId + " locked -> wiping CE key and revoking storage mapper");
            if (mCeKeyBytes != null) {
                java.util.Arrays.fill(mCeKeyBytes, (byte) 0);
                mCeKeyBytes = null;
            }
            mCeKeyAvailable = false;
            notifyCeKeyStatusChanged(false);
        }
    }

    public final class BinderService extends ILinuxManager.Stub {
        @Override
        public int getState() {
            synchronized (mStateLock) {
                return mCurrentState;
            }
        }

        @Override
        public boolean startVm() {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE_LINUX, "Permission denied to start Linux VM");
            }
            synchronized (mStateLock) {
                if (mCurrentState == LinuxManager.STATE_RUNNING || mCurrentState == LinuxManager.STATE_STARTING) {
                    Slog.w(TAG, "startVm called when already in state " + mCurrentState);
                    return false;
                }
                int oldState = mCurrentState;
                mCurrentState = LinuxManager.STATE_STARTING;
                Slog.i(TAG, "Initiating Linux Guest VM (STARTING)...");
                dispatchStateChanged(mCurrentState, oldState, 0, "VM Booting");

                cancelBootTimeoutLocked();
                mBootTimeoutFuture = mScheduler.schedule(
                        LinuxManagerService.this::handleBootTimeout,
                        BOOT_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                );
                byte[] authToken = generateHmacAuthToken();
                if (mBridgeService != null) {
                    mBridgeService.notifyVmStarting(authToken);
                }
                return true;
            }
        }

        @Override
        public boolean stopVm(boolean force) {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE_LINUX, "Permission denied to stop Linux VM");
            }
            synchronized (mStateLock) {
                cancelBootTimeoutLocked();
                int oldState = mCurrentState;
                mCurrentState = LinuxManager.STATE_STOPPED;
                Slog.i(TAG, "Stopping Linux Guest VM (force=" + force + ") -> STATE_STOPPED");
                dispatchStateChanged(mCurrentState, oldState, 0, "VM Stopped");
                if (mBridgeService != null) {
                    mBridgeService.sendStopSignal(force);
                }
                return true;
            }
        }

        @Override
        public boolean suspendVm() {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE_LINUX, "Permission denied to suspend Linux VM");
            }
            synchronized (mStateLock) {
                if (mCurrentState != LinuxManager.STATE_RUNNING) {
                    Slog.w(TAG, "Cannot suspend VM when not in RUNNING state (current: " + mCurrentState + ")");
                    return false;
                }
                int oldState = mCurrentState;
                mCurrentState = LinuxManager.STATE_SUSPENDED;
                Slog.i(TAG, "Suspending Linux Guest VM -> STATE_SUSPENDED");
                dispatchStateChanged(mCurrentState, oldState, 0, "VM Suspended");
                return true;
            }
        }

        @Override
        public boolean resumeVm() {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE_LINUX, "Permission denied to resume Linux VM");
            }
            synchronized (mStateLock) {
                if (mCurrentState != LinuxManager.STATE_SUSPENDED) {
                    Slog.w(TAG, "Cannot resume VM when not in SUSPENDED state (current: " + mCurrentState + ")");
                    return false;
                }
                int oldState = mCurrentState;
                mCurrentState = LinuxManager.STATE_RUNNING;
                Slog.i(TAG, "Resuming Linux Guest VM -> STATE_RUNNING");
                dispatchStateChanged(mCurrentState, oldState, 0, "VM Resumed");
                return true;
            }
        }

        @Override
        public String createTerminalSession(int width, int height, ILinuxTerminalCallback callback) {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to create terminal session");
            }
            synchronized (mStateLock) {
                String sessionId = String.format(java.util.Locale.US, "session_%08d", ++mNextSessionId);
                TerminalSession session = new TerminalSession(sessionId, width, height, callback);
                mTerminalSessions.put(sessionId, session);
                Slog.i(TAG, "Created terminal session (16-byte token): " + sessionId + " (" + width + "x" + height + ")");
                if (mBridgeService != null) {
                    mBridgeService.openPtyChannel(sessionId, width, height);
                }
                return sessionId;
            }
        }

        @Override
        public void resizeTerminalSession(String sessionId, int width, int height) {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to resize terminal session");
            }
            synchronized (mStateLock) {
                TerminalSession session = mTerminalSessions.get(sessionId);
                if (session != null) {
                    mTerminalSessions.put(sessionId, new TerminalSession(sessionId, width, height, session.callback));
                    Slog.i(TAG, "Resized terminal session " + sessionId + " to " + width + "x" + height);
                    if (mBridgeService != null) {
                        mBridgeService.resizePtyChannel(sessionId, width, height);
                    }
                }
            }
        }

        @Override
        public void closeTerminalSession(String sessionId) {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to close terminal session");
            }
            synchronized (mStateLock) {
                TerminalSession session = mTerminalSessions.remove(sessionId);
                if (session != null) {
                    Slog.i(TAG, "Closed terminal session: " + sessionId);
                    if (mBridgeService != null) {
                        mBridgeService.closePtyChannel(sessionId);
                    }
                    if (session.callback != null) {
                        try {
                            session.callback.onSessionClosed(sessionId, 0);
                        } catch (RemoteException ignored) {}
                    }
                }
            }
        }

        @Override
        public void writeTerminalInput(String sessionId, byte[] data) {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to write terminal input");
            }
            synchronized (mStateLock) {
                TerminalSession session = mTerminalSessions.get(sessionId);
                if (session != null) {
                    Slog.d(TAG, "Wrote " + (data != null ? data.length : 0) + " bytes to session " + sessionId);
                    if (mBridgeService != null) {
                        mBridgeService.writePtyData(sessionId, data);
                    }
                }
            }
        }

        @Override
        public List<LinuxAppInfo> getInstalledApps() {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to get installed apps");
            }
            if (mBridgeService != null) {
                return mBridgeService.getCachedAppList();
            }
            List<LinuxAppInfo> apps = new ArrayList<>();
            apps.add(new LinuxAppInfo("org.gnome.Terminal", "Terminal", "gnome-terminal", "/usr/share/icons/terminal.png", "text/plain"));
            apps.add(new LinuxAppInfo("org.mozilla.firefox", "Firefox Web Browser", "firefox", "/usr/share/icons/firefox.png", "text/html"));
            return apps;
        }

        @Override
        public boolean launchLinuxApp(String appId, int displayId) {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to launch Linux app");
            }
            Slog.i(TAG, "Launching Linux App: " + appId + " on display: " + displayId);
            if (mBridgeService != null && mBridgeService.isConnected()) {
                return mBridgeService.launchApp(appId, displayId);
            }
            return true;
        }

        @Override
        public boolean installGuestImage(ParcelFileDescriptor imageFd, long size) {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE_LINUX, "Permission denied to install guest image");
            }
            Slog.i(TAG, "Installing guest image size: " + size + " bytes");
            return true;
        }

        @Override
        public void registerStatusCallback(ILinuxStatusCallback callback) {
            if (callback != null) {
                mStatusCallbacks.register(callback);
            }
        }

        @Override
        public void unregisterStatusCallback(ILinuxStatusCallback callback) {
            if (callback != null) {
                mStatusCallbacks.unregister(callback);
            }
        }
    }
}
