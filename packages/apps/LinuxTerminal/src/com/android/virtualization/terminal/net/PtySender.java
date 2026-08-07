package com.android.virtualization.terminal.net;

/**
 * Interface for sending raw bytes or framed packets over Vsock Port 5001 to Guest pty-agent.
 */
public interface PtySender {
    void sendBytes(byte[] data);
    void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload);
    void sendResize(byte[] sessionId, int cols, int rows);
}
