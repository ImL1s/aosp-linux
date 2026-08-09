package com.android.server.linux;

public abstract class LinuxManagerInternal {
    public interface StorageStateListener {
        void onVmStateChanged(int newState, int oldState);
        void onCeKeyStatusChanged(boolean available);
        void onStorageMountChanged(boolean readOnly);
    }

    public abstract boolean isVmRunning();
    public abstract int getVmState();
    public abstract boolean isCeKeyAvailable();
    public abstract boolean isReadOnlyMount();
    public abstract void onUserUnlocked(int userId);
    public abstract void registerStorageStateListener(StorageStateListener listener);
    public abstract void unregisterStorageStateListener(StorageStateListener listener);
}
