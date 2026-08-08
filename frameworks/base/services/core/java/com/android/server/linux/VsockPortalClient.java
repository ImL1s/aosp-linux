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

import android.system.Os;
import android.system.OsConstants;
import android.system.VmSocketAddress;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Host SystemServer client managing AF_VSOCK (family 40) portal connections to Guest VM.
 * Implements 13-byte Big-Endian VSOK frame packing over VSOCK_PORTAL_PORT (5000).
 * {@hide}
 */
public class VsockPortalClient {
    private static final String TAG = "VsockPortalClient";
    private static final int AF_VSOCK = 40;
    private static final int VSOCK_PORTAL_PORT = 5000;
    private static final int VSOK_MAGIC = 0x56534F4B; // "VSOK"

    private FileDescriptor mSocketFd;
    private InputStream mInputStream;
    private OutputStream mOutputStream;
    private int mSequenceId = 0;
    private boolean mConnected = false;
    private int mGuestCid = 3;

    private byte[] mAuthToken;

    public VsockPortalClient() {
        this(3);
    }

    public VsockPortalClient(int guestCid) {
        this.mGuestCid = guestCid;
    }

    public synchronized void setAuthToken(byte[] authToken) {
        this.mAuthToken = authToken;
    }

    public synchronized void setGuestCid(int guestCid) {
        this.mGuestCid = guestCid;
    }

    public synchronized boolean connect() {
        return connect(mGuestCid);
    }

    public synchronized boolean connect(int guestCid) {
        close();
        this.mGuestCid = guestCid;
        try {
            mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
            VmSocketAddress address = new VmSocketAddress(VSOCK_PORTAL_PORT, guestCid);
            Os.connect(mSocketFd, address);

            mInputStream = new FileInputStream(mSocketFd);
            mOutputStream = new FileOutputStream(mSocketFd);

            if (mAuthToken != null && mAuthToken.length > 0) {
                byte[] nonceToken = new byte[32];
                new java.security.SecureRandom().nextBytes(nonceToken);

                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(mAuthToken, "HmacSHA256"));
                byte[] signature = mac.doFinal(nonceToken);

                mOutputStream.write(nonceToken);
                mOutputStream.write(signature);
                mOutputStream.flush();

                byte[] respBuf = new byte[4];
                int read = mInputStream.read(respBuf);
                if (read < 4) {
                    close();
                    Slog.w(TAG, "AF_VSOCK portal auth response incomplete: " + read + " bytes");
                    return false;
                }
                int status = ByteBuffer.wrap(respBuf).order(ByteOrder.BIG_ENDIAN).getInt();
                if (status != 0x00000200) { // 0x200 = STATUS_SUCCESS
                    close();
                    Slog.w(TAG, String.format("AF_VSOCK portal auth rejected with status: 0x%08X", status));
                    return false;
                }
            }

            mConnected = true;
            Slog.i(TAG, "Connected AF_VSOCK Portal socket to CID " + guestCid + ":" + VSOCK_PORTAL_PORT);
            return true;
        } catch (Exception e) {
            close();
            Slog.d(TAG, "AF_VSOCK connection attempt failed for CID " + guestCid + ":" + VSOCK_PORTAL_PORT + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized void sendPortalFrame(byte frameType, byte[] payload) throws IOException {
        if (!mConnected || mOutputStream == null) {
            if (!connect()) {
                throw new IOException("VsockPortalClient is not connected to AF_VSOCK CID " + mGuestCid);
            }
        }
        int payloadLen = payload != null ? payload.length : 0;

        // Construct 13-byte Packed VSOK Header (Big-Endian / Network Order)
        ByteBuffer headerBuf = ByteBuffer.allocate(13);
        headerBuf.order(ByteOrder.BIG_ENDIAN);
        headerBuf.putInt(VSOK_MAGIC);      // 4 bytes: Magic 0x56534F4B ("VSOK")
        headerBuf.put(frameType);          // 1 byte : FrameType (0x01 DATA/PORTAL)
        headerBuf.putInt(payloadLen);      // 4 bytes: Payload Length
        headerBuf.putInt(++mSequenceId);   // 4 bytes: Sequence ID

        try {
            mOutputStream.write(headerBuf.array());
            if (payloadLen > 0) {
                mOutputStream.write(payload);
            }
            mOutputStream.flush();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public synchronized boolean isConnected() {
        return mConnected;
    }

    public synchronized void close() {
        mConnected = false;
        if (mInputStream != null) {
            try { mInputStream.close(); } catch (Exception ignored) {}
            mInputStream = null;
        }
        if (mOutputStream != null) {
            try { mOutputStream.close(); } catch (Exception ignored) {}
            mOutputStream = null;
        }
        if (mSocketFd != null && mSocketFd.valid()) {
            try { Os.close(mSocketFd); } catch (Exception ignored) {}
            mSocketFd = null;
        }
    }
}
