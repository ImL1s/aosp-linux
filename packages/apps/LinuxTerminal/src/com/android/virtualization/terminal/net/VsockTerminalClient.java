package com.android.virtualization.terminal.net;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.VmSocketAddress;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketAddress;

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
        if (sessionId == null || sessionId.length != 16) {
            throw new IllegalArgumentException("Session ID must be exactly 16 bytes for VsockPtyFramer");
        }

        try {
            mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);

            SocketAddress address;
            try {
                address = new VmSocketAddress(VPORT_PTY, guestCid);
            } catch (Throwable t) {
                try {
                    Class<?> clazz = Class.forName("android.system.SocketAddressVmSockets");
                    java.lang.reflect.Constructor<?> ctor = clazz.getConstructor(int.class, int.class);
                    address = (SocketAddress) ctor.newInstance(VPORT_PTY, guestCid);
                } catch (Exception e) {
                    throw new IOException("Unable to construct vsock address for CID " + guestCid + ":" + VPORT_PTY, e);
                }
            }

            Os.connect(mSocketFd, address);
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
            Log.i(TAG, "Successfully connected AF_VSOCK socket to CID " + guestCid + ":" + VPORT_PTY);
        } catch (ErrnoException e) {
            close();
            throw new IOException("Failed to connect AF_VSOCK socket to CID " + guestCid + ":" + VPORT_PTY + " (errno: " + e.errno + ")", e);
        } catch (Exception e) {
            close();
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to connect AF_VSOCK socket to CID " + guestCid + ":" + VPORT_PTY, e);
        }
    }

    public synchronized void connectSocket(java.net.Socket socket, byte[] sessionId, TerminalStreamListener listener) throws IOException {
        if (sessionId == null || sessionId.length != 16) {
            throw new IllegalArgumentException("Session ID must be exactly 16 bytes for VsockPtyFramer");
        }

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
        if (mReadThread != null) {
            mReadThread.interrupt();
            mReadThread = null;
        }
        try {
            if (mInputStream != null) {
                mInputStream.close();
                mInputStream = null;
            }
            if (mOutputStream != null) {
                mOutputStream.close();
                mOutputStream = null;
            }
            if (mSocketFd != null && mSocketFd.valid()) {
                Os.close(mSocketFd);
                mSocketFd = null;
            }
        } catch (Exception ignored) {}
    }
}
