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

package com.android.server.linux;

/**
 * System-internal local service interface for cross-module communication within system_server.
 * {@hide}
 */
public abstract class LinuxManagerInternal {

    /**
     * Returns true if the guest Linux VM is currently running and ready for IPC.
     */
    public abstract boolean isVmRunning();

    /**
     * Gets the current VM state integer representation.
     */
    public abstract int getVmState();

    /**
     * Called when a target user unlocks their Credential Encrypted (CE) storage.
     */
    public abstract void onUserUnlocked(int userId);

    /**
     * Returns true if the LUKS2 CE storage volume is unlocked and available.
     */
    public abstract boolean isCeKeyAvailable();

    /**
     * Returns true if the storage volume is mounted read-only.
     */
    public abstract boolean isReadOnlyMount();

    /**
     * Registers a listener for storage and VM state changes.
     */
    public abstract void registerStorageStateListener(StorageStateListener listener);

    /**
     * Unregisters a storage state listener.
     */
    public abstract void unregisterStorageStateListener(StorageStateListener listener);

    /**
     * Interface for listening to storage and VM state change events.
     */
    public interface StorageStateListener {
        void onVmStateChanged(int newState, int oldState);
        void onCeKeyStatusChanged(boolean available);
        void onStorageMountChanged(boolean isReadOnly);
    }
}
