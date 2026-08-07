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
import android.util.Slog;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host SystemServer service managing hardware portal bridge endpoints:
 * Camera2 HAL streaming, AudioRecord PCM streaming, LocationManager updates, and AppOps enforcement.
 * {@hide}
 */
public class LinuxPortalService {
    private static final String TAG = "LinuxPortalService";

    // AppOps Constants
    public static final String OP_CAMERA = "OP_CAMERA";
    public static final String OP_RECORD_AUDIO = "OP_RECORD_AUDIO";
    public static final String OP_FINE_LOCATION = "OP_FINE_LOCATION";
    public static final String OP_COARSE_LOCATION = "OP_COARSE_LOCATION";

    public static final String MODE_ALLOWED = "ALLOWED";
    public static final String MODE_DENIED = "DENIED";
    public static final String MODE_PROMPT = "PROMPT";

    private final Context mContext;
    private final Map<String, Map<String, String>> mAppOpsStore = new ConcurrentHashMap<>();
    private final Map<String, CameraSession> mCameraSessions = new ConcurrentHashMap<>();
    private final Map<String, MicSession> mMicSessions = new ConcurrentHashMap<>();
    private final Map<String, LocationSession> mLocationSessions = new ConcurrentHashMap<>();

    private boolean mMicPrivacyToggleOn = false;
    private boolean mHardwareCameraPluggedIn = true;
    private boolean mAndroidAppActiveForCamera = false;
    private boolean mGpsMasterEnabled = true;

    public static class CameraSession {
        public final String appId;
        public final String sessionId;
        public int width;
        public int height;
        public int fps;
        public boolean isActive;

        public CameraSession(String appId, String sessionId, int width, int height, int fps) {
            this.appId = appId;
            this.sessionId = sessionId;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.isActive = true;
        }
    }

    public static class MicSession {
        public final String appId;
        public final String sessionId;
        public int sampleRate;
        public int channels;
        public boolean isRecording;
        public boolean isForeground;

        public MicSession(String appId, String sessionId, int sampleRate, int channels) {
            this.appId = appId;
            this.sessionId = sessionId;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.isRecording = true;
            this.isForeground = true;
        }
    }

    public static class LocationSession {
        public final String appId;
        public final String sessionId;
        public double lastLatitude;
        public double lastLongitude;
        public double lastTimestamp;
        public boolean isActive;

        public LocationSession(String appId, String sessionId) {
            this.appId = appId;
            this.sessionId = sessionId;
            this.isActive = true;
        }
    }

    public LinuxPortalService(Context context) {
        mContext = context;
    }

    // AppOps Permission Control
    public void setAppOp(String appId, String op, String mode) {
        mAppOpsStore.computeIfAbsent(appId, k -> new ConcurrentHashMap<>()).put(op, mode);
        Slog.i(TAG, "AppOps set: " + appId + " [" + op + "] -> " + mode);
    }

    public String checkAppOp(String appId, String op) {
        Map<String, String> ops = mAppOpsStore.get(appId);
        if (ops != null && ops.containsKey(op)) {
            return ops.get(op);
        }
        return MODE_PROMPT;
    }

    private boolean resolveAppOpOrPrompt(String appId, String op) {
        String mode = checkAppOp(appId, op);
        if (MODE_ALLOWED.equals(mode)) {
            return true;
        }
        if (MODE_DENIED.equals(mode)) {
            return false;
        }
        // MODE_PROMPT state -> Trigger permission prompt Activity if context exists
        Slog.i(TAG, "AppOp MODE_PROMPT for " + appId + " [" + op + "], launching permission dialog...");
        if (mContext != null) {
            LinuxPermissionActivity.launchPrompt(mContext, appId, op);
        }
        String updatedMode = checkAppOp(appId, op);
        return MODE_ALLOWED.equals(updatedMode);
    }

    // F-R5-001: XDG Portal Camera Bridge
    public boolean requestCameraAccess(String appId) {
        if (!resolveAppOpOrPrompt(appId, OP_CAMERA)) {
            Slog.w(TAG, "Camera access denied by AppOps/Prompt for " + appId);
            return false;
        }
        if (!mHardwareCameraPluggedIn) {
            Slog.e(TAG, "Hardware disconnected: USB camera unplugged");
            throw new ConnectionError("HardwareDisconnected: USB Camera unplugged during stream");
        }
        if (mAndroidAppActiveForCamera) {
            Slog.w(TAG, "Camera contention: native Android app active, denying guest stream");
            return false;
        }
        return true;
    }

    public CameraSession startCameraStream(String appId, String sessionId, int requestedW, int requestedH, int requestedFps) {
        if (!requestCameraAccess(appId)) {
            return null;
        }
        // Negotiate resolution fallback (e.g. 4K 120fps -> 1080p 30fps)
        int finalW = (requestedW > 1920) ? 1920 : requestedW;
        int finalH = (requestedH > 1080) ? 1080 : requestedH;
        int finalFps = (requestedFps > 60) ? 30 : requestedFps;
        if (requestedW > 1920 || requestedH > 1080 || requestedFps > 60) {
            Slog.i(TAG, "Camera resolution mismatch fallback -> 1920x1080@30fps");
        }

        CameraSession session = new CameraSession(appId, sessionId, finalW, finalH, finalFps);
        mCameraSessions.put(sessionId, session);
        return session;
    }

    public void stopCameraStream(String sessionId) {
        CameraSession session = mCameraSessions.remove(sessionId);
        if (session != null) {
            session.isActive = false;
            Slog.i(TAG, "Camera session stopped and hardware resource released for " + sessionId);
        }
    }

    public void setHardwareCameraPluggedIn(boolean pluggedIn) {
        mHardwareCameraPluggedIn = pluggedIn;
    }

    public void setAndroidAppActiveForCamera(boolean active) {
        mAndroidAppActiveForCamera = active;
        if (active) {
            for (CameraSession s : mCameraSessions.values()) {
                s.isActive = false;
            }
        }
    }

    // F-R5-002: XDG Portal Microphone Bridge
    public boolean requestMicrophoneAccess(String appId) {
        if (!resolveAppOpOrPrompt(appId, OP_RECORD_AUDIO)) {
            Slog.w(TAG, "Microphone access denied by AppOps/Prompt for " + appId);
            return false;
        }
        return true;
    }

    public MicSession startMicStream(String appId, String sessionId, int sampleRate, int channels) {
        if (!requestMicrophoneAccess(appId)) {
            return null;
        }
        MicSession session = new MicSession(appId, sessionId, sampleRate, channels);
        mMicSessions.put(sessionId, session);
        return session;
    }

    public byte[] processMicPcmFrame(MicSession session, byte[] rawInput) {
        if (session == null || !session.isRecording || !session.isForeground) {
            return new byte[0];
        }
        // Privacy toggle check -> zero fill silent frames
        if (mMicPrivacyToggleOn) {
            byte[] silence = new byte[rawInput.length];
            Arrays.fill(silence, (byte) 0);
            return silence;
        }
        // Buffer underflow mitigation
        if (rawInput.length < 1024) {
            byte[] padded = new byte[1024];
            System.arraycopy(rawInput, 0, padded, 0, rawInput.length);
            Arrays.fill(padded, rawInput.length, 1024, (byte) 0);
            return padded;
        }
        return rawInput;
    }

    public short downmixStereoToMono(short left, short right) {
        return (short) ((left + right) / 2);
    }

    public void setMicPrivacyToggle(boolean privacyOn) {
        mMicPrivacyToggleOn = privacyOn;
    }

    public void setMicAppForeground(String sessionId, boolean isForeground) {
        MicSession session = mMicSessions.get(sessionId);
        if (session != null) {
            session.isForeground = isForeground;
            if (!isForeground) {
                session.isRecording = false;
            }
        }
    }

    public void stopMicStream(String sessionId) {
        MicSession session = mMicSessions.remove(sessionId);
        if (session != null) {
            session.isRecording = false;
        }
    }

    // F-R5-003: XDG Portal Location Bridge
    public boolean requestLocationAccess(String appId) {
        if (!mGpsMasterEnabled) {
            Slog.w(TAG, "GPS master switch is disabled for " + appId);
            throw new PermissionError("PermissionError: Location access denied or GPS disabled");
        }
        if (!resolveAppOpOrPrompt(appId, OP_FINE_LOCATION)) {
            Slog.w(TAG, "Location access denied by AppOps/Prompt for " + appId);
            throw new PermissionError("PermissionError: Location access denied or GPS disabled");
        }
        return true;
    }

    public double[] getObfuscatedLocation(double exactLat, double exactLon, boolean isCoarseOnly) {
        if (isCoarseOnly) {
            double coarseLat = Math.round(exactLat * 100.0) / 100.0;
            double coarseLon = Math.round(exactLon * 100.0) / 100.0;
            return new double[]{coarseLat, coarseLon};
        }
        return new double[]{exactLat, exactLon};
    }

    public void setGpsMasterEnabled(boolean enabled) {
        mGpsMasterEnabled = enabled;
    }

    public void unsubscribeLocationSession(String appId, String sessionId) {
        mLocationSessions.remove(sessionId);
        Slog.i(TAG, "Unsubscribed location session for " + appId);
    }

    // Custom Runtime Exception classes matching tests
    public static class ConnectionError extends RuntimeException {
        public ConnectionError(String msg) {
            super(msg);
        }
    }

    public static class PermissionError extends RuntimeException {
        public PermissionError(String msg) {
            super(msg);
        }
    }
}

