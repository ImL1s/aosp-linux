/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.system.linux;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Public facade system service for managing the guest Linux Debian VM runtime, terminal sessions,
 * and desktop applications.
 *
 * Obtained via {@link Context#getSystemService(String)} using {@link Context#LINUX_SERVICE}.
 * @hide
 */
@SystemApi
@SystemService(LinuxManager.LINUX_SERVICE)
public class LinuxManager {
    private static final String TAG = "LinuxManager";

    public static final String LINUX_SERVICE = "linux";

    /** Permission required for managing Linux VM state and executing administrative actions. */
    public static final String PERMISSION_MANAGE_LINUX_ENVIRONMENT =
            "android.permission.MANAGE_LINUX_ENVIRONMENT";
    public static final String PERMISSION_MANAGE_LINUX_CONTAINER =
            "android.permission.MANAGE_LINUX_CONTAINER";
    public static final String PERMISSION_USE_LINUX_TERMINAL =
            "android.permission.USE_LINUX_TERMINAL";

    // --- State Constants ---
    public static final int STATE_OFF = 0;
    public static final int STATE_NOT_INSTALLED = 0;
    public static final int STATE_STOPPED = 0;

    public static final int STATE_STARTING = 1;
    public static final int STATE_BOOTING = 1;

    public static final int STATE_RUNNING = 2;

    public static final int STATE_SUSPENDED = 3;

    public static final int STATE_ERROR = 4;
    public static final int STATE_FAILED = 4;

    @IntDef(prefix = { "STATE_" }, value = {
            STATE_OFF,
            STATE_STARTING,
            STATE_RUNNING,
            STATE_SUSPENDED,
            STATE_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VmState {}

    // --- Reason Codes ---
    public static final int REASON_NORMAL = 0;
    public static final int REASON_USER_REQUESTED = 1;
    public static final int REASON_LOW_MEMORY = 2;
    public static final int REASON_GUEST_CRASH = 3;
    public static final int REASON_HOST_SHUTDOWN = 4;
    public static final int REASON_ERROR = 5;
    public static final int REASON_BOOT_TIMEOUT = 101;

    private final Context mContext;
    private final ILinuxManager mService;
    private final Object mLock = new Object();
    private final ArrayMap<StatusCallback, ILinuxStatusCallback> mStatusCallbacks = new ArrayMap<>();

    /**
     * Interface for listening to Linux VM lifecycle state transitions and resource usage updates.
     */
    public abstract static class StatusCallback {
        public void onStateChanged(@VmState int newState, @VmState int oldState, int reasonCode, @Nullable String message) {}
        public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
    }

    /**
     * Alias interface for StateCallback.
     */
    public interface StateCallback {
        void onStateChanged(@VmState int newState, @VmState int oldState, int reasonCode, @Nullable String message);
        default void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
    }

    /**
     * Interface for terminal PTY stream callbacks.
     */
    public interface TerminalCallback {
        void onDataReceived(@NonNull String sessionId, @NonNull byte[] data);
        void onTitleChanged(@NonNull String sessionId, @NonNull String title);
        void onBell(@NonNull String sessionId);
        void onSessionClosed(@NonNull String sessionId, int exitCode);
    }

    /** @hide */
    public LinuxManager(@NonNull Context context, @NonNull ILinuxManager service) {
        mContext = Objects.requireNonNull(context, "context must not be null");
        mService = Objects.requireNonNull(service, "service must not be null");
    }

    @VmState
    public int getState() {
        try {
            return mService.getState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @VmState
    public int getStatus() {
        return getState();
    }

    @RequiresPermission(PERMISSION_MANAGE_LINUX_ENVIRONMENT)
    public boolean startVm() {
        try {
            return mService.startVm();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(PERMISSION_MANAGE_LINUX_ENVIRONMENT)
    public boolean stopVm(boolean force) {
        try {
            return mService.stopVm(force);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(PERMISSION_MANAGE_LINUX_ENVIRONMENT)
    public boolean stopVm() {
        return stopVm(false);
    }

    @RequiresPermission(PERMISSION_MANAGE_LINUX_ENVIRONMENT)
    public boolean suspendVm() {
        try {
            return mService.suspendVm();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(PERMISSION_MANAGE_LINUX_ENVIRONMENT)
    public boolean resumeVm() {
        try {
            return mService.resumeVm();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void registerStatusCallback(@NonNull StatusCallback callback) {
        registerStatusCallback(mContext.getMainExecutor(), callback);
    }

    public void registerStatusCallback(@NonNull Executor executor, @NonNull StatusCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        Objects.requireNonNull(executor, "executor must not be null");

        synchronized (mLock) {
            if (mStatusCallbacks.containsKey(callback)) {
                return;
            }
            ILinuxStatusCallback binderCallback = new ILinuxStatusCallback.Stub() {
                @Override
                public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                    executor.execute(() -> callback.onStateChanged(newState, oldState, reasonCode, message));
                }

                @Override
                public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {
                    executor.execute(() -> callback.onResourceUsageUpdated(memoryUsedBytes, memoryTotalBytes, cpuUsagePercent));
                }
            };
            try {
                mService.registerStatusCallback(binderCallback);
                mStatusCallbacks.put(callback, binderCallback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    public void unregisterStatusCallback(@NonNull StatusCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        synchronized (mLock) {
            ILinuxStatusCallback binderCallback = mStatusCallbacks.remove(callback);
            if (binderCallback != null) {
                try {
                    mService.unregisterStatusCallback(binderCallback);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    @Nullable
    public String createTerminalSession(int width, int height, @NonNull Executor executor, @NonNull TerminalCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        ILinuxTerminalCallback aidlCallback = new ILinuxTerminalCallback.Stub() {
            @Override
            public void onDataReceived(String sessionId, byte[] data) {
                executor.execute(() -> callback.onDataReceived(sessionId, data));
            }

            @Override
            public void onTitleChanged(String sessionId, String title) {
                executor.execute(() -> callback.onTitleChanged(sessionId, title));
            }

            @Override
            public void onBell(String sessionId) {
                executor.execute(() -> callback.onBell(sessionId));
            }

            @Override
            public void onSessionClosed(String sessionId, int exitCode) {
                executor.execute(() -> callback.onSessionClosed(sessionId, exitCode));
            }
        };

        try {
            return mService.createTerminalSession(width, height, aidlCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void resizeTerminalSession(@NonNull String sessionId, int width, int height) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            mService.resizeTerminalSession(sessionId, width, height);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void writeTerminalInput(@NonNull String sessionId, @NonNull byte[] data) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(data, "data must not be null");
        try {
            mService.writeTerminalInput(sessionId, data);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void closeTerminalSession(@NonNull String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            mService.closeTerminalSession(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    public List<LinuxAppInfo> getInstalledApps() {
        try {
            List<LinuxAppInfo> apps = mService.getInstalledApps();
            return apps != null ? apps : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    public List<LinuxAppInfo> listInstalledApps() {
        return getInstalledApps();
    }

    public boolean launchLinuxApp(@NonNull String appId, int displayId) {
        Objects.requireNonNull(appId, "appId must not be null");
        try {
            return mService.launchLinuxApp(appId, displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean launchTerminal() {
        return launchTerminal(80, 24);
    }

    public boolean launchTerminal(int width, int height) {
        try {
            String sessionId = mService.createTerminalSession(width, height, null);
            return sessionId != null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(PERMISSION_MANAGE_LINUX_ENVIRONMENT)
    public boolean installGuestImage(@NonNull ParcelFileDescriptor imageFd, long size) {
        Objects.requireNonNull(imageFd, "imageFd must not be null");
        try {
            return mService.installGuestImage(imageFd, size);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
