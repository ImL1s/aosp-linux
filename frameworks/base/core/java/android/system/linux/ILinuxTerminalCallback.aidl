// frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl
package android.system.linux;

/**
 * Callback interface for receiving virtual terminal (PTY) output data and events.
 * {@hide}
 */
oneway interface ILinuxTerminalCallback {
    /**
     * Triggered when new raw UTF-8 / ANSI escape sequence data is received from PTY.
     *
     * @param sessionId The ID of the active terminal session.
     * @param data Byte array containing terminal stream payload.
     */
    void onDataReceived(in String sessionId, in byte[] data);

    /**
     * Triggered when the terminal title changes (e.g., via OSC escape sequence).
     *
     * @param sessionId The ID of the active terminal session.
     * @param title New terminal title string.
     */
    void onTitleChanged(in String sessionId, in String title);

    /**
     * Triggered when a terminal bell (\a / 0x07) signal occurs.
     *
     * @param sessionId The ID of the active terminal session.
     */
    void onBell(in String sessionId);

    /**
     * Triggered when the underlying PTY shell process terminates.
     *
     * @param sessionId The ID of the active terminal session.
     * @param exitCode Exit status code of the shell process.
     */
    void onSessionClosed(in String sessionId, int exitCode);
}
