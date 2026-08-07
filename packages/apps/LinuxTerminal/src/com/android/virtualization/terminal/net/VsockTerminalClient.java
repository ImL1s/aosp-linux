package com.android.virtualization.terminal.net;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Authentic AF_VSOCK socket client for Vsock Port 5001 PTY framing.
 */
public class VsockTerminalClient {
    private static final String TAG = "VsockTerminalClient";
    private static final int AF_VSOCK = 40;
    private static final int VPORT_PTY = 5001;

    private FileDescriptor mSocketFd;
    private java.io.InputStream mInputStream;
    private java.io.OutputStream mOutputStream;
    private Thread mReadThread;
    private volatile boolean mRunning = false;

    public interface TerminalStreamListener {
        void onDataReceived(byte[] data);
        void onError(Exception e);
    }

    public synchronized void connect(int guestCid, byte[] sessionId, TerminalStreamListener listener) throws IOException {
        try {
            mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
            mInputStream = new FileInputStream(mSocketFd);
            mOutputStream = new FileOutputStream(mSocketFd);
            mRunning = true;

            VsockPtyFramer.StreamParser parser = new VsockPtyFramer.StreamParser();

            mReadThread = new Thread(() -> {
                byte[] buffer = new byte[8192];
                while (mRunning) {
                    try {
                        int n = mInputStream.read(buffer);
                        if (n < 0) break;
                        parser.appendAndParse(buffer, 0, n, sessionId, new VsockPtyFramer.OnFrameParsedListener() {
                            @Override
                            public void onFrameParsed(VsockPtyFramer.Frame frame) {
                                if (frame.type == VsockPtyFramer.PacketType.DATA && listener != null) {
                                    listener.onDataReceived(frame.payload);
                                }
                            }

                            @Override
                            public void onError(Exception e) {
                                if (listener != null) listener.onError(e);
                            }
                        });
                    } catch (Exception e) {
                        if (mRunning && listener != null) listener.onError(e);
                        break;
                    }
                }
            }, "VsockReadThread");
            mReadThread.start();
        } catch (ErrnoException e) {
            throw new IOException("Failed to open AF_VSOCK socket to CID " + guestCid + ":" + VPORT_PTY, e);
        }
    }

    public synchronized void connectSocket(java.net.Socket socket, byte[] sessionId, TerminalStreamListener listener) throws IOException {
        mInputStream = socket.getInputStream();
        mOutputStream = socket.getOutputStream();
        mRunning = true;

        VsockPtyFramer.StreamParser parser = new VsockPtyFramer.StreamParser();

        mReadThread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            while (mRunning) {
                try {
                    int n = mInputStream.read(buffer);
                    if (n < 0) break;
                    parser.appendAndParse(buffer, 0, n, sessionId, new VsockPtyFramer.OnFrameParsedListener() {
                        @Override
                        public void onFrameParsed(VsockPtyFramer.Frame frame) {
                            if (frame.type == VsockPtyFramer.PacketType.DATA && listener != null) {
                                listener.onDataReceived(frame.payload);
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                            if (listener != null) listener.onError(e);
                        }
                    });
                } catch (Exception e) {
                    if (mRunning && listener != null) listener.onError(e);
                    break;
                }
            }
        }, "VsockReadThread");
        mReadThread.start();
    }

    public synchronized void sendFrame(byte[] frameBytes) throws IOException {
        if (mOutputStream != null) {
            mOutputStream.write(frameBytes);
            mOutputStream.flush();
        }
    }

    public synchronized void close() {
        mRunning = false;
        try {
            if (mInputStream != null) {
                mInputStream.close();
            }
            if (mOutputStream != null) {
                mOutputStream.close();
            }
            if (mSocketFd != null && mSocketFd.valid()) {
                Os.close(mSocketFd);
            }
        } catch (Exception ignored) {}
    }
}
