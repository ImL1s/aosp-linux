/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.linux;

import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.system.linux.LinuxAppInfo;
import android.util.Slog;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SystemServer bridge service interfacing with the isolated native daemon linux_bridge via Unix Domain Socket.
 * Handles binary framing header serialization, deserialization, and worker-thread dispatching.
 * {@hide}
 */
public class LinuxBridgeService {
    private static final String TAG = "LinuxBridgeService";
    public static final String SOCKET_PATH = "/dev/socket/linux_bridge";
    public static final int MAGIC = 0x4C4E5842; // "LNXB"
    public static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024; // 16MB

    // Command Codes
    public static final short CMD_VM_START = 0x0001;
    public static final short CMD_VM_STOP = 0x0002;
    public static final short CMD_HANDSHAKE_COMPLETE = 0x0003;
    public static final short CMD_PTY_DATA = 0x0100;
    public static final short CMD_PTY_RESIZE = 0x0101;
    public static final short CMD_PTY_OPEN = 0x0102;
    public static final short CMD_PTY_CLOSE = 0x0103;
    public static final short CMD_APP_SYNC = 0x0200;
    public static final short CMD_PING = 0x0300;
    public static final short CMD_PONG = 0x0301;
    public static final short CMD_PORTAL_CAMERA_REQ = 0x0400;
    public static final short CMD_PORTAL_MIC_REQ = 0x0401;
    public static final short CMD_PORTAL_LOCATION_REQ = 0x0402;
    public static final short CMD_PORTAL_AUDIO_STREAM = 0x0403;
    public static final short CMD_PORTAL_RESP = 0x0404;
    public static final short CMD_PORTAL_CAMERA_FRAME = 0x0410;
    public static final short CMD_PORTAL_MIC_PCM = 0x0411;
    public static final short CMD_PORTAL_LOCATION_UPDATE = 0x0412;
    public static final short CMD_STORAGE_NOTIFY_CHANGE = 0x0500;

    public interface LinuxBridgeCallback {
        void onVmHandshakeCompleted();
        void onVmDisconnected();
        void onPtyDataReceived(String sessionId, byte[] data);
        void onError(int errorCode, String message);
    }

    private final Context mContext;
    private final LinuxBridgeCallback mCallback;
    private final HandlerThread mWorkerThread;
    private Handler mHandler;

    private LocalSocket mSocket;
    private DataInputStream mInStream;
    private DataOutputStream mOutStream;
    private final AtomicBoolean mIsConnected = new AtomicBoolean(false);
    private int mNextTransactionId = 1;
    private final List<LinuxAppInfo> mCachedApps = new ArrayList<>();

    public LinuxBridgeService(Context context, LinuxBridgeCallback callback) {
        mContext = context;
        mCallback = callback;
        mWorkerThread = new HandlerThread("LinuxBridgeWorker");
    }

    public void start() {
        mWorkerThread.start();
        mHandler = new Handler(mWorkerThread.getLooper());
        mHandler.post(this::connectDaemonSocket);
    }

    public void stop() {
        mIsConnected.set(false);
        closeSocket();
        if (mWorkerThread.isAlive()) {
            mWorkerThread.quitSafely();
        }
    }

    public boolean isConnected() {
        return mIsConnected.get();
    }

    private void connectDaemonSocket() {
        try {
            mSocket = new LocalSocket();
            mSocket.connect(new LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            mInStream = new DataInputStream(mSocket.getInputStream());
            mOutStream = new DataOutputStream(mSocket.getOutputStream());
            mIsConnected.set(true);
            Slog.i(TAG, "Successfully connected to native linux_bridge daemon socket at " + SOCKET_PATH);

            mHandler.post(this::readLoop);
        } catch (IOException e) {
            Slog.w(TAG, "Failed to connect to linux_bridge daemon socket (" + e.getMessage() + "), scheduling retry...");
            mIsConnected.set(false);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (mHandler != null) {
            mHandler.postDelayed(this::connectDaemonSocket, 3000);
        }
    }

    private void readLoop() {
        while (mIsConnected.get()) {
            try {
                int magic = mInStream.readInt();
                if (magic != MAGIC) {
                    Slog.e(TAG, "Invalid magic number in packet: 0x" + Integer.toHexString(magic));
                    break;
                }
                short cmdType = mInStream.readShort();
                int length = mInStream.readInt();
                int transId = mInStream.readInt();

                if (length < 0 || length > MAX_PAYLOAD_SIZE) {
                    Slog.e(TAG, "Invalid payload length in packet: " + length + " (max: " + MAX_PAYLOAD_SIZE + ")");
                    break;
                }

                byte[] payload = new byte[length];
                if (length > 0) {
                    mInStream.readFully(payload);
                }

                handleIncomingPacket(cmdType, transId, payload);
            } catch (IOException e) {
                Slog.e(TAG, "Socket read error in LinuxBridgeService: " + e.getMessage());
                break;
            }
        }
        closeSocket();
        boolean wasConnected = mIsConnected.getAndSet(false);
        if (wasConnected && mCallback != null) {
            mCallback.onVmDisconnected();
        }
        scheduleReconnect();
    }

    private void handleIncomingPacket(short cmdType, int transId, byte[] payload) {
        switch (cmdType) {
            case CMD_HANDSHAKE_COMPLETE:
                Slog.i(TAG, "Received CMD_HANDSHAKE_COMPLETE from linux_bridge daemon");
                if (mCallback != null) {
                    mCallback.onVmHandshakeCompleted();
                }
                break;
            case CMD_PTY_DATA:
                parseAndDeliverPtyData(payload);
                break;
            case CMD_APP_SYNC:
                parseAndDeliverAppSyncData(payload);
                break;
            case CMD_PING:
                sendPacket(CMD_PONG, transId, new byte[0]);
                break;
            default:
                Slog.d(TAG, "Received packet cmdType: 0x" + Integer.toHexString(cmdType) + ", len: " + payload.length);
                break;
        }
    }

    private void parseAndDeliverAppSyncData(byte[] payload) {
        if (payload == null || payload.length == 0) return;
        try {
            String jsonStr = new String(payload, StandardCharsets.UTF_8);
            Slog.i(TAG, "Received CMD_APP_SYNC payload: " + jsonStr);

            JSONObject jsonObj = new JSONObject(jsonStr);
            String appId = jsonObj.optString("app_id", null);
            String name = jsonObj.optString("name", null);
            String exec = jsonObj.optString("exec", null);
            String icon = jsonObj.optString("icon", null);
            String mime = jsonObj.optString("mime_types", null);

            if (appId != null && name != null) {
                synchronized (mCachedApps) {
                    mCachedApps.removeIf(app -> app.getAppId().equals(appId));
                    mCachedApps.add(new LinuxAppInfo(appId, name, exec != null ? exec : appId, icon != null ? icon : "", mime != null ? mime : ""));
                }

                Intent intent = new Intent("android.system.linux.action.LINUX_APPS_CHANGED");
                intent.putExtra("EXTRA_APP_ID", appId);
                mContext.sendBroadcast(intent);
                Slog.i(TAG, "Dispatched LINUX_APPS_CHANGED broadcast for app: " + appId);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to parse CMD_APP_SYNC payload: " + e.getMessage());
        }
    }

    private void parseAndDeliverPtyData(byte[] payload) {
        if (payload == null || payload.length < 4) return;
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int sessionIdLen = buffer.getInt();
        if (sessionIdLen < 0 || buffer.remaining() < sessionIdLen) return;

        byte[] sessionBytes = new byte[sessionIdLen];
        buffer.get(sessionBytes);
        String sessionId = new String(sessionBytes, StandardCharsets.UTF_8);

        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        if (mCallback != null) {
            mCallback.onPtyDataReceived(sessionId, data);
        }
    }

    public synchronized boolean sendPacket(short cmdType, int transId, byte[] payload) {
        if (!mIsConnected.get() || mOutStream == null) {
            Slog.w(TAG, "Cannot send packet, bridge daemon not connected");
            return false;
        }
        if (payload != null && payload.length > MAX_PAYLOAD_SIZE) {
            Slog.e(TAG, "Outgoing payload length exceeds MAX_PAYLOAD_SIZE: " + payload.length);
            return false;
        }
        try {
            mOutStream.writeInt(MAGIC);
            mOutStream.writeShort(cmdType);
            mOutStream.writeInt(payload != null ? payload.length : 0);
            mOutStream.writeInt(transId > 0 ? transId : ++mNextTransactionId);
            if (payload != null && payload.length > 0) {
                mOutStream.write(payload);
            }
            mOutStream.flush();
            return true;
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write packet to daemon: " + e.getMessage());
            closeSocket();
            mIsConnected.set(false);
            return false;
        }
    }

    public boolean notifyVmStarting() {
        return sendPacket(CMD_VM_START, 0, new byte[0]);
    }

    public void sendStopSignal(boolean force) {
        byte[] payload = new byte[]{(byte) (force ? 1 : 0)};
        sendPacket(CMD_VM_STOP, 0, payload);
    }

    public void openPtyChannel(String sessionId, int width, int height) {
        byte[] sessionBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + sessionBytes.length + 8);
        buffer.putInt(sessionBytes.length);
        buffer.put(sessionBytes);
        buffer.putInt(width);
        buffer.putInt(height);
        sendPacket(CMD_PTY_OPEN, 0, buffer.array());
    }

    public void resizePtyChannel(String sessionId, int width, int height) {
        byte[] sessionBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + sessionBytes.length + 8);
        buffer.putInt(sessionBytes.length);
        buffer.put(sessionBytes);
        buffer.putInt(width);
        buffer.putInt(height);
        sendPacket(CMD_PTY_RESIZE, 0, buffer.array());
    }

    public void writePtyData(String sessionId, byte[] data) {
        if (data == null) return;
        byte[] sessionBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + sessionBytes.length + data.length);
        buffer.putInt(sessionBytes.length);
        buffer.put(sessionBytes);
        buffer.put(data);
        sendPacket(CMD_PTY_DATA, 0, buffer.array());
    }

    public void closePtyChannel(String sessionId) {
        byte[] sessionBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + sessionBytes.length);
        buffer.putInt(sessionBytes.length);
        buffer.put(sessionBytes);
        sendPacket(CMD_PTY_CLOSE, 0, buffer.array());
    }

    public List<LinuxAppInfo> getCachedAppList() {
        synchronized (mCachedApps) {
            if (mCachedApps.isEmpty()) {
                mCachedApps.add(new LinuxAppInfo("org.gnome.Terminal", "Terminal", "gnome-terminal", "/usr/share/icons/terminal.png", "text/plain"));
                mCachedApps.add(new LinuxAppInfo("org.mozilla.firefox", "Firefox Web Browser", "firefox", "/usr/share/icons/firefox.png", "text/html"));
            }
            return Collections.unmodifiableList(new ArrayList<>(mCachedApps));
        }
    }

    public boolean launchApp(String appId, int displayId) {
        byte[] appBytes = appId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + appBytes.length + 4);
        buffer.putInt(appBytes.length);
        buffer.put(appBytes);
        buffer.putInt(displayId);
        return sendPacket(CMD_APP_SYNC, 0, buffer.array());
    }

    private void closeSocket() {
        try {
            if (mSocket != null) {
                mSocket.close();
                mSocket = null;
            }
        } catch (IOException ignored) {}
    }
}
