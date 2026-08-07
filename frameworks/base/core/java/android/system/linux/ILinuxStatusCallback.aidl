// frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl
package android.system.linux;

/**
 * Interface for receiving asynchronous status callbacks from LinuxManagerService.
 * {@hide}
 */
oneway interface ILinuxStatusCallback {
    /**
     * Called when the Linux VM execution state changes.
     *
     * @param newState The new state (e.g., STATE_RUNNING, STATE_SUSPENDED).
     * @param oldState The previous state.
     * @param reasonCode Reason code explaining the state transition.
     * @param message Human-readable status or diagnostic message.
     */
    void onStateChanged(int newState, int oldState, int reasonCode, in String message);

    /**
     * Called periodically with updated VM resource metrics.
     *
     * @param memoryUsedBytes Current memory used by guest VM in bytes.
     * @param memoryTotalBytes Total allocated memory for guest VM in bytes.
     * @param cpuUsagePercent Current guest CPU usage percentage (0.0 - 100.0).
     */
    void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent);
}
