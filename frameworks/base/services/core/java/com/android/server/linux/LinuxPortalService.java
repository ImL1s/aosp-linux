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

import android.app.AppOpsManager;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import android.system.linux.ILinuxPortalService;
import android.util.Slog;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
    private static volatile LinuxPortalService sInstance;

    // AppOps Constants
    public static final String OP_CAMERA = "OP_CAMERA";
    public static final String OP_RECORD_AUDIO = "OP_RECORD_AUDIO";
    public static final String OP_FINE_LOCATION = "OP_FINE_LOCATION";
    public static final String OP_COARSE_LOCATION = "OP_COARSE_LOCATION";

    public static final String MODE_ALLOWED = "ALLOWED";
    public static final String MODE_DENIED = "DENIED";
    public static final String MODE_PROMPT = "PROMPT";

    private static final int VSOCK_PORTAL_PORT = 5000;

    private final Context mContext;
    private final Map<String, Map<String, String>> mAppOpsStore = new ConcurrentHashMap<>();
    private final Map<String, CameraSession> mCameraSessions = new ConcurrentHashMap<>();
    private final Map<String, MicSession> mMicSessions = new ConcurrentHashMap<>();
    private final Map<String, LocationSession> mLocationSessions = new ConcurrentHashMap<>();

    private boolean mMicPrivacyToggleOn = false;
    private boolean mHardwareCameraPluggedIn = true;
    private boolean mAndroidAppActiveForCamera = false;
    private boolean mGpsMasterEnabled = true;

    private CameraManager mCameraManager;
    private AudioRecord mAudioRecord;
    private Thread mAudioRecordThread;
    private LocationManager mLocationManager;

    private CameraDevice mActiveCameraDevice;
    private CameraCaptureSession mActiveCaptureSession;
    private String mActiveCameraId;
    private String mOpeningCameraId;
    private ImageReader mActiveImageReader;
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;
    private int mAudioRecordChannelConfig = AudioFormat.CHANNEL_IN_MONO;

    private LocationListener mSystemLocationListener;

    private VsockPortalClient mVsockPortalClient = new VsockPortalClient();

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
        public final boolean isCoarseOnly;
        public double lastLatitude;
        public double lastLongitude;
        public double lastTimestamp;
        public boolean isActive;

        public LocationSession(String appId, String sessionId, boolean isCoarseOnly) {
            this.appId = appId;
            this.sessionId = sessionId;
            this.isCoarseOnly = isCoarseOnly;
            this.isActive = true;
        }

        public LocationSession(String appId, String sessionId) {
            this(appId, sessionId, false);
        }
    }

    public static LinuxPortalService getInstance() {
        return sInstance;
    }

    public LinuxPortalService(Context context) {
        mContext = context;
        sInstance = this;
        initSystemServices();
    }

    private void initSystemServices() {
        if (mContext == null) {
            return;
        }
        try {
            mCameraManager = mContext.getSystemService(CameraManager.class);
            if (mCameraManager != null) {
                mCameraManager.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
                    @Override
                    public void onCameraUnavailable(String cameraId) {
                        if ((mActiveCameraId != null && mActiveCameraId.equals(cameraId))
                                || (mOpeningCameraId != null && mOpeningCameraId.equals(cameraId))) {
                            Slog.i(TAG, "Ignoring AvailabilityCallback self-cancellation for camera " + cameraId);
                            return;
                        }
                        Slog.w(TAG, "Camera " + cameraId + " unavailable (contention with native Android app)");
                        setAndroidAppActiveForCamera(true);
                    }

                    @Override
                    public void onCameraAvailable(String cameraId) {
                        Slog.i(TAG, "Camera " + cameraId + " available again");
                        setAndroidAppActiveForCamera(false);
                    }
                }, null);
            }
        } catch (Exception e) {
            Slog.w(TAG, "CameraManager init skipped or failed: " + e.getMessage());
        }

        try {
            mLocationManager = mContext.getSystemService(LocationManager.class);
        } catch (Exception e) {
            Slog.w(TAG, "LocationManager init skipped or failed: " + e.getMessage());
        }
    }

    // AppOps Permission Control
    public void setAppOp(String appId, String op, String mode) {
        mAppOpsStore.computeIfAbsent(appId, k -> new ConcurrentHashMap<>()).put(op, mode);
        Slog.i(TAG, "AppOps set: " + appId + " [" + op + "] -> " + mode);
    }

    public void setAppOp(String appId, String op, int mode) {
        String modeStr = (mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_FOREGROUND)
                ? MODE_ALLOWED : MODE_DENIED;
        setAppOp(appId, op, modeStr);
    }

    public void setAppOp(String appId, int op, int mode) {
        String opStr = LinuxPermissionActivity.mapOpIntToString(op);
        setAppOp(appId, opStr, mode);
    }

    public void setAppOp(String appId, int op, String mode) {
        String opStr = LinuxPermissionActivity.mapOpIntToString(op);
        setAppOp(appId, opStr, mode);
    }

    public int noteAppOp(String appId, String op) {
        if (mContext != null) {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
            if (appOps != null) {
                String opStr = mapOpToOpStr(op);
                if (opStr != null) {
                    try {
                        int uid = Process.myUid();
                        return appOps.noteOpNoThrow(opStr, uid, appId);
                    } catch (Exception ignored) {}
                }
            }
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    public String checkAppOp(String appId, String op) {
        if (mContext != null) {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
            if (appOps != null) {
                String opStr = mapOpToOpStr(op);
                if (opStr != null) {
                    try {
                        int uid = Process.myUid();
                        int mode = appOps.unsafeCheckOpRaw(opStr, uid, appId);
                        if (mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_FOREGROUND) {
                            return MODE_ALLOWED;
                        } else if (mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED) {
                            return MODE_DENIED;
                        }
                    } catch (Exception ignored) {
                        // Fall back to in-memory store if system call throws (e.g. unknown package in unit test)
                    }
                }
            }
        }

        Map<String, String> ops = mAppOpsStore.get(appId);
        if (ops != null && ops.containsKey(op)) {
            return ops.get(op);
        }
        return MODE_PROMPT;
    }

    private String mapOpToOpStr(String op) {
        if (OP_CAMERA.equals(op)) return AppOpsManager.OPSTR_CAMERA;
        if (OP_RECORD_AUDIO.equals(op)) return AppOpsManager.OPSTR_RECORD_AUDIO;
        if (OP_FINE_LOCATION.equals(op)) return AppOpsManager.OPSTR_FINE_LOCATION;
        if (OP_COARSE_LOCATION.equals(op)) return AppOpsManager.OPSTR_COARSE_LOCATION;
        return null;
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
        noteAppOp(appId, OP_CAMERA);
        return true;
    }

    public CameraSession startCameraStream(String appId, String sessionId, int requestedW, int requestedH, int requestedFps) {
        if (requestedW <= 0 || requestedH <= 0 || requestedFps <= 0) {
            Slog.w(TAG, "Invalid camera stream parameters: " + requestedW + "x" + requestedH + "@" + requestedFps);
            return null;
        }
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

        openHardwareCamera(finalW, finalH);

        CameraSession session = new CameraSession(appId, sessionId, finalW, finalH, finalFps);
        mCameraSessions.put(sessionId, session);
        return session;
    }

    private void openHardwareCamera(int width, int height) {
        if (mContext == null || mCameraManager == null || !mHardwareCameraPluggedIn || mAndroidAppActiveForCamera) {
            return;
        }
        try {
            String[] cameraIds = mCameraManager.getCameraIdList();
            if (cameraIds.length == 0) return;
            String cameraId = cameraIds[0];

            if (mCameraThread == null) {
                mCameraThread = new HandlerThread("LinuxCameraPortalThread");
                mCameraThread.start();
                mCameraHandler = new Handler(mCameraThread.getLooper());
            }

            if (mActiveImageReader == null) {
                mActiveImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
                mActiveImageReader.setOnImageAvailableListener(reader -> {
                    try (android.media.Image img = reader.acquireNextImage()) {
                        if (img != null) {
                            byte[] nv21 = convertYuv420ToNv21(img);
                            sendVsockCameraFramePayload(width, height, img.getTimestamp(), nv21);
                        }
                    } catch (Exception ignored) {}
                }, mCameraHandler);
            }

            if (mActiveCameraDevice == null) {
                mOpeningCameraId = cameraId;
                mCameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        mActiveCameraDevice = camera;
                        mActiveCameraId = cameraId;
                        mOpeningCameraId = null;
                        Slog.i(TAG, "Hardware camera opened successfully for ID: " + cameraId);
                        try {
                            if (mActiveImageReader != null && mActiveImageReader.getSurface() != null) {
                                camera.createCaptureSession(Arrays.asList(mActiveImageReader.getSurface()),
                                        new CameraCaptureSession.StateCallback() {
                                            @Override
                                            public void onConfigured(CameraCaptureSession session) {
                                                mActiveCaptureSession = session;
                                                try {
                                                    CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                                    builder.addTarget(mActiveImageReader.getSurface());
                                                    session.setRepeatingRequest(builder.build(), null, mCameraHandler);
                                                    Slog.i(TAG, "CameraCaptureSession configured and setRepeatingRequest started");
                                                } catch (Exception e) {
                                                    Slog.w(TAG, "Failed to start camera repeating request: " + e.getMessage());
                                                }
                                            }

                                            @Override
                                            public void onConfigureFailed(CameraCaptureSession session) {
                                                Slog.w(TAG, "CameraCaptureSession configuration failed");
                                                closeHardwareCamera();
                                            }
                                        }, mCameraHandler);
                            }
                        } catch (Exception e) {
                            Slog.w(TAG, "Failed to create CameraCaptureSession: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        mOpeningCameraId = null;
                        closeHardwareCamera();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        mOpeningCameraId = null;
                        closeHardwareCamera();
                    }
                }, mCameraHandler);
            }
        } catch (Exception e) {
            mOpeningCameraId = null;
            Slog.w(TAG, "Failed to open hardware camera: " + e.getMessage());
        }
    }

    public void stopCameraStream(String sessionId) {
        CameraSession session = mCameraSessions.remove(sessionId);
        if (session != null) {
            session.isActive = false;
            Slog.i(TAG, "Camera session stopped and hardware resource released for " + sessionId);
        }
        if (mCameraSessions.isEmpty()) {
            closeHardwareCamera();
        }
    }

    private void closeHardwareCamera() {
        if (mActiveCaptureSession != null) {
            try { mActiveCaptureSession.close(); } catch (Exception ignored) {}
            mActiveCaptureSession = null;
        }
        if (mActiveCameraDevice != null) {
            try { mActiveCameraDevice.close(); } catch (Exception ignored) {}
            mActiveCameraDevice = null;
        }
        mActiveCameraId = null;
        mOpeningCameraId = null;
        if (mActiveImageReader != null) {
            try { mActiveImageReader.close(); } catch (Exception ignored) {}
            mActiveImageReader = null;
        }
        if (mCameraThread != null) {
            mCameraThread.quitSafely();
            mCameraThread = null;
            mCameraHandler = null;
        }
    }

    public void setHardwareCameraPluggedIn(boolean pluggedIn) {
        mHardwareCameraPluggedIn = pluggedIn;
        if (!pluggedIn) {
            Slog.w(TAG, "Hardware camera unplugged -> deactivating camera sessions");
            for (CameraSession s : mCameraSessions.values()) {
                s.isActive = false;
            }
            closeHardwareCamera();
        }
    }

    public void setAndroidAppActiveForCamera(boolean active) {
        mAndroidAppActiveForCamera = active;
        if (active) {
            for (CameraSession s : mCameraSessions.values()) {
                s.isActive = false;
            }
            closeHardwareCamera();
        } else {
            boolean hasActiveSessions = false;
            int maxW = 0, maxH = 0;
            for (CameraSession s : mCameraSessions.values()) {
                s.isActive = true;
                hasActiveSessions = true;
                if (s.width > maxW) maxW = s.width;
                if (s.height > maxH) maxH = s.height;
            }
            if (hasActiveSessions && mHardwareCameraPluggedIn && mActiveCameraDevice == null) {
                openHardwareCamera(maxW > 0 ? maxW : 1920, maxH > 0 ? maxH : 1080);
            }
        }
    }

    // F-R5-002: XDG Portal Microphone Bridge
    public boolean requestMicrophoneAccess(String appId) {
        if (!resolveAppOpOrPrompt(appId, OP_RECORD_AUDIO)) {
            Slog.w(TAG, "Microphone access denied by AppOps/Prompt for " + appId);
            return false;
        }
        noteAppOp(appId, OP_RECORD_AUDIO);
        return true;
    }

    public MicSession startMicStream(String appId, String sessionId, int sampleRate, int channels) {
        if (!requestMicrophoneAccess(appId)) {
            return null;
        }

        if (mContext != null && mAudioRecord == null) {
            try {
                int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
                mAudioRecordChannelConfig = channelConfig;
                int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
                int bufSize = Math.max(minBuf, 2048);
                mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufSize);

                if (mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    mAudioRecord.startRecording();
                    mAudioRecordThread = new Thread(() -> {
                        byte[] buffer = new byte[1024];
                        while (mAudioRecord != null && mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            int read = mAudioRecord.read(buffer, 0, buffer.length);
                            if (read > 0) {
                                byte[] rawPcm = Arrays.copyOf(buffer, read);
                                for (MicSession session : mMicSessions.values()) {
                                    if (session.isRecording && session.isForeground) {
                                        byte[] processed = processMicPcmFrame(session, rawPcm);
                                        if (processed.length > 0) {
                                            sendVsockAudioPayload(processed);
                                        }
                                    }
                                }
                            }
                        }
                    }, "LinuxAudioPortalThread");
                    mAudioRecordThread.start();
                }
            } catch (Exception e) {
                Slog.w(TAG, "AudioRecord init failed for session " + sessionId + ": " + e.getMessage());
            }
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

        byte[] pcmToProcess = rawInput;
        if (mAudioRecordChannelConfig == AudioFormat.CHANNEL_IN_STEREO && session.channels == 1 && rawInput.length >= 4) {
            int numFrames = rawInput.length / 4;
            byte[] monoBytes = new byte[numFrames * 2];
            for (int i = 0; i < numFrames; i++) {
                short left = (short) ((rawInput[i * 4] & 0xFF) | ((rawInput[i * 4 + 1] & 0xFF) << 8));
                short right = (short) ((rawInput[i * 4 + 2] & 0xFF) | ((rawInput[i * 4 + 3] & 0xFF) << 8));
                short mono = downmixStereoToMono(left, right);
                monoBytes[i * 2] = (byte) (mono & 0xFF);
                monoBytes[i * 2 + 1] = (byte) ((mono >> 8) & 0xFF);
            }
            pcmToProcess = monoBytes;
        }

        // Buffer underflow mitigation
        if (pcmToProcess.length < 1024) {
            byte[] padded = new byte[1024];
            System.arraycopy(pcmToProcess, 0, padded, 0, pcmToProcess.length);
            Arrays.fill(padded, pcmToProcess.length, 1024, (byte) 0);
            return padded;
        }
        return pcmToProcess;
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
        if (mMicSessions.isEmpty()) {
            stopHardwareAudio();
        }
    }

    private void stopHardwareAudio() {
        mAudioRecordChannelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
                mAudioRecord.release();
            } catch (Exception ignored) {}
            mAudioRecord = null;
        }
        if (mAudioRecordThread != null) {
            try {
                mAudioRecordThread.join(500);
            } catch (InterruptedException ignored) {}
            mAudioRecordThread = null;
        }
        // Hardware audio stopped
    }

    // F-R5-003: XDG Portal Location Bridge
    public boolean requestLocationAccess(String appId) {
        if (!mGpsMasterEnabled) {
            Slog.w(TAG, "GPS master switch is disabled for " + appId);
            throw new PermissionError("PermissionError: Location access denied or GPS disabled");
        }
        boolean fineAllowed = resolveAppOpOrPrompt(appId, OP_FINE_LOCATION);
        boolean coarseAllowed = fineAllowed || resolveAppOpOrPrompt(appId, OP_COARSE_LOCATION);
        if (!coarseAllowed) {
            Slog.w(TAG, "Location access denied by AppOps/Prompt for " + appId);
            throw new PermissionError("PermissionError: Location access denied or GPS disabled");
        }
        noteAppOp(appId, fineAllowed ? OP_FINE_LOCATION : OP_COARSE_LOCATION);
        registerSystemLocationListener();
        return true;
    }

    public LocationSession startLocationStream(String appId, String sessionId) {
        if (!requestLocationAccess(appId)) {
            return null;
        }
        boolean fineAllowed = MODE_ALLOWED.equals(checkAppOp(appId, OP_FINE_LOCATION));
        LocationSession session = new LocationSession(appId, sessionId, !fineAllowed);
        mLocationSessions.put(sessionId, session);
        return session;
    }

    private void registerSystemLocationListener() {
        if (mContext != null && mLocationManager != null && mSystemLocationListener == null) {
            try {
                mSystemLocationListener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        if (location != null) {
                            double rawLat = location.getLatitude();
                            double rawLon = location.getLongitude();
                            float rawAcc = location.getAccuracy();

                            for (LocationSession session : mLocationSessions.values()) {
                                if (session.isActive) {
                                    double[] coords = getObfuscatedLocation(rawLat, rawLon, session.isCoarseOnly);
                                    float accuracy = session.isCoarseOnly ? Math.max(rawAcc, 1000.0f) : rawAcc;
                                    session.lastLatitude = coords[0];
                                    session.lastLongitude = coords[1];
                                    session.lastTimestamp = System.currentTimeMillis();
                                    sendGeoClueLocationUpdate(coords[0], coords[1], accuracy);
                                }
                            }
                            if (mLocationSessions.isEmpty()) {
                                sendGeoClueLocationUpdate(rawLat, rawLon, rawAcc);
                            }
                        }
                    }
                };
                mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, mSystemLocationListener);
            } catch (Exception e) {
                Slog.w(TAG, "LocationManager request updates failed: " + e.getMessage());
            }
        }
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
        if (mLocationSessions.isEmpty() && mContext != null && mLocationManager != null && mSystemLocationListener != null) {
            try {
                mLocationManager.removeUpdates(mSystemLocationListener);
                mSystemLocationListener = null;
            } catch (Exception ignored) {}
        }
    }

    // VM Lifecycle Cleanup Hook
    public void onVmStoppedOrSuspended() {
        Slog.i(TAG, "VM stopped or suspended -> releasing hardware portal resources");
        for (String sessionId : mCameraSessions.keySet()) {
            stopCameraStream(sessionId);
        }
        for (String sessionId : mMicSessions.keySet()) {
            stopMicStream(sessionId);
        }
        mLocationSessions.clear();
        if (mContext != null && mLocationManager != null && mSystemLocationListener != null) {
            try {
                mLocationManager.removeUpdates(mSystemLocationListener);
                mSystemLocationListener = null;
            } catch (Exception ignored) {}
        }
        // VM stopped or suspended cleanup complete
    }

    // YUV_420_888 to NV21 converter
    public byte[] convertYuv420ToNv21(android.media.Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];
        android.media.Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        yBuffer.get(nv21, 0, Math.min(ySize, width * height));

        int rowStride = planes[2].getRowStride();
        int pixelStride = planes[2].getPixelStride();
        int nv21Pos = width * height;

        byte[] vBytes = new byte[vSize];
        byte[] uBytes = new byte[uSize];
        vBuffer.get(vBytes);
        uBuffer.get(uBytes);

        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vIndex = row * rowStride + col * pixelStride;
                int uIndex = planes[1].getRowStride() * row + col * planes[1].getPixelStride();
                if (vIndex < vSize) {
                    nv21[nv21Pos++] = vBytes[vIndex];
                } else {
                    nv21[nv21Pos++] = 0;
                }
                if (uIndex < uSize) {
                    nv21[nv21Pos++] = uBytes[uIndex];
                } else {
                    nv21[nv21Pos++] = 0;
                }
            }
        }
        return nv21;
    }

    // Vsock streaming helper routines (Port 5000 over VsockPortalClient)
    private synchronized void sendVsockCameraFramePayload(int width, int height, long timestampNs, byte[] nv21Bytes) {
        try {
            int payloadLen = nv21Bytes != null ? nv21Bytes.length : 0;
            ByteBuffer buf = ByteBuffer.allocate(28 + payloadLen);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0x43414D46); // "CAMF" SubType
            buf.putInt(width);
            buf.putInt(height);
            buf.putInt(ImageFormat.NV21); // 0x11
            buf.putLong(timestampNs);
            buf.putInt(payloadLen);
            if (payloadLen > 0) {
                buf.put(nv21Bytes);
            }
            mVsockPortalClient.sendPortalFrame((byte) 0x01, buf.array());
        } catch (Exception e) {
            Slog.w(TAG, "Failed to send VSOCK camera frame payload: " + e.getMessage());
        }
    }

    private synchronized void sendVsockAudioPayload(byte[] pcmData) {
        try {
            int payloadLen = pcmData != null ? pcmData.length : 0;
            ByteBuffer buf = ByteBuffer.allocate(8 + payloadLen);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0x4155444F); // "AUDO" SubType
            buf.putInt(payloadLen);
            if (payloadLen > 0) {
                buf.put(pcmData);
            }
            mVsockPortalClient.sendPortalFrame((byte) 0x01, buf.array());
        } catch (Exception e) {
            Slog.w(TAG, "Failed to send VSOCK audio payload: " + e.getMessage());
        }
    }

    private synchronized void sendGeoClueLocationUpdate(double lat, double lon, float accuracy) {
        try {
            String json = "{\"Latitude\":" + lat + ",\"Longitude\":" + lon + ",\"Accuracy\":" + accuracy + "}\n";
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(8 + jsonBytes.length);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0x47454F43); // "GEOC" SubType
            buf.putInt(jsonBytes.length);
            buf.put(jsonBytes);
            mVsockPortalClient.sendPortalFrame((byte) 0x01, buf.array());
        } catch (Exception e) {
            Slog.w(TAG, "Failed to send VSOCK location update: " + e.getMessage());
        }
    }

    public String getCameraStatus() {
        return mCameraSessions.isEmpty() ? "IDLE" : "ACTIVE";
    }

    public String getAudioStatus() {
        return mMicSessions.isEmpty() ? "IDLE" : "ACTIVE";
    }

    public String getLocation() {
        return mLocationSessions.isEmpty() ? "IDLE" : "ACTIVE";
    }

    private final ILinuxPortalService.Stub mBinderService = new ILinuxPortalService.Stub() {
        @Override
        public String getCameraStatus() {
            return LinuxPortalService.this.getCameraStatus();
        }

        @Override
        public String getAudioStatus() {
            return LinuxPortalService.this.getAudioStatus();
        }

        @Override
        public String getLocation() {
            return LinuxPortalService.this.getLocation();
        }
    };

    public ILinuxPortalService.Stub getBinderService() {
        return mBinderService;
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
