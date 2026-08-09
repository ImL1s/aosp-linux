package android.system.linux;

/** {@hide} */
oneway interface ILinuxTerminalCallback {
    void onDataReceived(in byte[] data);
    void onSessionClosed(int exitCode);
}
