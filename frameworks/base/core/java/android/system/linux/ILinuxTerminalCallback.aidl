package android.system.linux;

/** {@hide} */
oneway interface ILinuxTerminalCallback {
    void onDataReceived(String sessionId, in byte[] data);
    void onTitleChanged(String sessionId, String title);
    void onBell(String sessionId);
    void onSessionClosed(String sessionId, int exitCode);
}
