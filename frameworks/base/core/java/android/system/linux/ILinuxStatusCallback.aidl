package android.system.linux;

/** {@hide} */
oneway interface ILinuxStatusCallback {
    void onStateChanged(
        int newState,
        int oldState,
        int reasonCode,
        String message
    );

    void onResourceUsageUpdated(
        long memoryUsedBytes,
        long memoryTotalBytes,
        float cpuUsagePercent
    );
}
