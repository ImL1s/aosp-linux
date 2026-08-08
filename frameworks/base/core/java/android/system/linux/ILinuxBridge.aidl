package android.system.linux;

/**
 * Interface for linux_bridge daemon management and communication.
 * {@hide}
 */
interface ILinuxBridge {
    boolean isDaemonConnected();
    boolean sendControlMessage(String msg);
}
