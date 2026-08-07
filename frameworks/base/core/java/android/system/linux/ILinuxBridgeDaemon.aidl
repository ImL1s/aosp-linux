// frameworks/base/core/java/android/system/linux/ILinuxBridgeDaemon.aidl
package android.system.linux;

import android.os.ParcelFileDescriptor;

/**
 * AIDL interface for host-side linux_bridge native daemon process isolation.
 * {@hide}
 */
interface ILinuxBridgeDaemon {
    /**
     * Initializes the bridge daemon with vsock target CID and security token.
     */
    boolean initializeBridge(int vsockCid, in String sessionToken);

    /**
     * Shuts down the bridge daemon and releases active sockets.
     */
    boolean shutdownBridge();

    /**
     * Sends a control command packet to the guest bridge agent.
     */
    boolean sendVmControlCommand(int commandId, in byte[] payload);

    /**
     * Opens a PTY data channel and returns a ParcelFileDescriptor pipe to SystemServer.
     */
    ParcelFileDescriptor openPtyChannel(in String sessionId, int width, int height);

    /**
     * Sends a terminal window resize command to guest pty-agent.
     */
    void resizePtyChannel(in String sessionId, int width, int height);

    /**
     * Writes raw input byte stream to guest terminal PTY.
     */
    void writePtyData(in String sessionId, in byte[] data);

    /**
     * Closes an active PTY session.
     */
    void closePtyChannel(in String sessionId);

    /**
     * Returns true if guest VM vsock port 5000 is connected and authenticated.
     */
    boolean isGuestConnected();
}
