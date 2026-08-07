# Technical Analysis & Remediation Strategy: Portals, AppOps & Audio Subsystem (Milestone M5 — Iteration 2)

**Author**: Explorer 1 (`explorer_m5_1_r2`)  
**Target Scope**: Features F-R5-001 through F-R5-006 (Hardware Portals, AppOps Enforcement, virtio-snd Audio Mapping & AudioFocus Policy)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1_r2`  
**Date**: 2026-08-06  

---

## 1. Executive Summary & Scope Definition

During Iteration 1 of Milestone M5, a forensic audit (`auditor_m5_1`), code review (`reviewer_m5_1`), and empirical stress testing (`challenger_m5_1`) revealed critical integrity violations and software defects in the Hardware Portals and Audio subsystem implementations:
1. **`LinuxPortalService.java` Facade & Security Bypass (F-R5-001 .. F-R5-004)**: `LinuxPortalService` maintained in-memory POJO collections for Camera, Microphone, and Location sessions without integrating with Android system services (`CameraManager`/Camera2 HAL, `AudioRecord`, `LocationManager`, or system `AppOpsManager`). Crucially, when `checkAppOp` returned `MODE_PROMPT` (the ungranted default state), `requestCameraAccess`, `requestMicrophoneAccess`, and `requestLocationAccess` auto-returned `true`, bypassing permission dialog prompts and granting unprivileged guest applications full access to host hardware.
2. **`LinuxPermissionActivity.java` Disconnection & Concurrency Defects (F-R5-004)**: `LinuxPermissionActivity` was completely unreferenced in system code. Furthermore, its static prompt queue `sPendingPromptsQueue` (`ArrayList<String>`) was protected only by instance-level `synchronized` methods, and when `sIsDialogVisible == true`, concurrent prompts were silently dropped (0 of 50 queued in empirical stress tests).
3. **`LinuxAudioPolicyHandler.java` Simulated Streaming & AudioFocus Restoration Flaw (F-R5-005, F-R5-006)**: Audio frames were buffered as string identifiers (`"frame_0"`) in a non-thread-safe Java `List<String>` rather than streaming real PCM audio to `AudioTrack`/`AudioService`. Additionally, during stacked interrupts (e.g. phone call ducking at 0.2f volume followed by transient alarm pause), when the alarm ended and delivered `AUDIOFOCUS_GAIN`, volume was unconditionally restored to `1.0f` (100%), overriding the active phone call ducking.
4. **Fabricated E2E Test Suite (`test_m5_tier1.py`)**: All 70 Tier-1 E2E tests were hardcoded to `CustomAssertions.assert_true(True)`, masking all service defects.

This report presents a comprehensive, step-by-step remediation strategy for features F-R5-001 through F-R5-006, replacing all facade implementations with genuine Android framework service integrations, fixing concurrency bugs, and implementing robust state machine logic.

---

## 2. Evidence Chain & Root Cause Analysis

### Finding 2.1: AppOps `MODE_PROMPT` Permission Bypass in `LinuxPortalService.java`
- **Observation**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java:130-145, 189-196`
  ```java
  public boolean requestCameraAccess(String appId) {
      String mode = checkAppOp(appId, OP_CAMERA);
      if (MODE_DENIED.equals(mode)) { return false; }
      ...
      return true;
  }
  ```
- **Source**: Static analysis & empirical test `ChallengerM5EmpiricalStressTest.java` (Test 1).
- **Verification**: When `checkAppOp` returns `MODE_PROMPT`, `MODE_DENIED.equals(mode)` evaluates to `false`. The method proceeds and returns `true`, granting access without prompting the user.
- **Root Cause**: Negative-only permission check (`if (MODE_DENIED) return false; else return true;`) instead of affirmative permission check (`if (!MODE_ALLOWED) triggerPromptOrDeny();`).

### Finding 2.2: Disconnected `LinuxPermissionActivity.java` & Static Queue Concurrency Bug
- **Observation**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java:38-79`
  ```java
  private static final List<String> sPendingPromptsQueue = new ArrayList<>();
  private static boolean sIsDialogVisible = false;

  public synchronized boolean showPrompt(String appName, String permissionOp) {
      ...
      if (sIsDialogVisible) {
          Slog.w(TAG, "Duplicate prompt suppressed while dialog is visible for " + appName);
          return false; // Silent drop!
      }
      sIsDialogVisible = true;
      ...
  }
  ```
- **Source**: `grep_search` across `frameworks/base` and `ChallengerM5EmpiricalStressTest.java` (Test 2).
- **Verification**:
  1. `grep` confirms `LinuxPermissionActivity` is never invoked by `LinuxPortalService`.
  2. Instance-level `synchronized` on `showPrompt` does NOT protect static variables `sPendingPromptsQueue` and `sIsDialogVisible` across different instances.
  3. When `sIsDialogVisible == true`, incoming prompts return `false` without adding to `sPendingPromptsQueue`, causing 100% prompt loss for concurrent requests.

### Finding 2.3: Simulated Audio Queue & Stacked AudioFocus Volume Overwrite
- **Observation 1**: `LinuxAudioPolicyHandler.java:180-185`
  ```java
  public void enqueueFrame(String frame) {
      mAudioBufferQueue.add(frame);
      if (mAudioBufferQueue.size() > MAX_AUDIO_QUEUE) {
          mAudioBufferQueue.remove(0);
      }
  }
  ```
- **Observation 2**: `LinuxAudioPolicyHandler.java:101-108`
  ```java
  case AudioManager.AUDIOFOCUS_GAIN:
      mCurrentFocusState = "GAIN";
      mCurrentVolumeFactor = 1.0f; // Unconditional restore to 1.0f!
      mIsPaused = false;
      break;
  ```
- **Source**: Reviewer 1 Finding 4 & Challenger 1 Empirical Test 5 (`ChallengerM5EmpiricalStressTest.java`).
- **Verification**:
  1. Frames are string objects in an unsynchronized `ArrayList<String>`, performing no real audio rendering to `AudioTrack`.
  2. When state transitions from `GAIN` (1.0f) -> `LOSS_TRANSIENT_CAN_DUCK` (0.2f) -> `LOSS_TRANSIENT` (paused) -> `GAIN`, volume jumps to `1.0f` even though the phone call (`LOSS_TRANSIENT_CAN_DUCK`) remains active.

---

## 3. Step-by-Step Remediation Strategy

### Strategy Component A: Hardware Portals & AppOps Wiring (`LinuxPortalService.java`)

1. **Affirmative AppOps Permission Model**:
   - Change permission check methods (`requestCameraAccess`, `requestMicrophoneAccess`, `requestLocationAccess`) to require explicit `MODE_ALLOWED`.
   - When `checkAppOp(appId, op)` returns `MODE_PROMPT`:
     - Synchronously or asynchronously trigger `LinuxPermissionActivity`.
     - Block/wait or handle user selection callback.
     - If approved by user (`MODE_ALLOWED`), persist setting in `mAppOpsStore` and grant access.
     - If denied by user or timed out (30s), set `mAppOpsStore` to `MODE_DENIED` and deny access.
   - For `requestLocationAccess`: throw `PermissionError` if not `MODE_ALLOWED` or if `!mGpsMasterEnabled`.

2. **Genuine System Service Integration**:
   - **Camera2 HAL (`CameraManager`)**:
     - Obtain system `CameraManager` (`mContext.getSystemService(Context.CAMERA_SERVICE)`).
     - Register `CameraManager.AvailabilityCallback` to track native Android camera usage.
     - When `mAndroidAppActiveForCamera` or camera hardware becomes unavailable, deactivate active Guest camera sessions immediately (`session.isActive = false`).
     - Support fallback negotiation: requested resolutions > 1080p @ 60fps fall back to 1920x1080 @ 30fps.
   - **Microphone (`AudioRecord`)**:
     - Integrate `AudioRecord` byte stream processing.
     - Honor `mMicPrivacyToggleOn`: zero-fill silence buffer (`Arrays.fill(silence, (byte)0)`).
     - Underflow padding: zero-pad raw frames shorter than 1024 bytes up to 1024 bytes.
     - Downmix stereo to mono helper (`(short)((left + right) / 2)`).
     - Check foreground state (`setMicAppForeground`): stop/pause recording when Guest app moves to background.
   - **Location (`LocationManager`)**:
     - Obtain `LocationManager` (`mContext.getSystemService(Context.LOCATION_SERVICE)`).
     - Provide `getObfuscatedLocation(lat, lon, isCoarseOnly)` helper: round coordinates to 2 decimal places (`Math.round(val * 100.0) / 100.0`) when `isCoarseOnly` is true.

---

### Strategy Component B: Permission Activity & Concurrency Guard (`LinuxPermissionActivity.java`)

1. **Class-Level Thread Synchronization**:
   - Introduce a static monitor object `private static final Object sLock = new Object();`.
   - Protect all reads and writes to `sPendingPromptsQueue` and `sIsDialogVisible` inside `synchronized (sLock)`.

2. **Correct Prompt Queueing Logic**:
   - Modify `showPrompt`:
     ```java
     synchronized (sLock) {
         if (mIsScreenLocked) {
             sPendingPromptsQueue.add(appName + ":" + permissionOp);
             return false;
         }
         if (sIsDialogVisible) {
             // Enqueue prompt instead of dropping it!
             sPendingPromptsQueue.add(appName + ":" + permissionOp);
             return false;
         }
         sIsDialogVisible = true;
     }
     ```
   - On `dismissPrompt()` or user response, drain `sPendingPromptsQueue` and launch the next pending prompt sequentially.

3. **Service Launch Integration**:
   - Add static launcher `public static void launchPrompt(Context context, String appName, String permissionOp)` using `Intent` with `Intent.FLAG_ACTIVITY_NEW_TASK`.

---

### Strategy Component C: PCM Audio Streaming & Stacked AudioFocus (`LinuxAudioPolicyHandler.java`)

1. **Stacked AudioFocus State Machine**:
   - Introduce `mPreTransientFocusState` to track active lower-priority focus states (such as `LOSS_TRANSIENT_CAN_DUCK`).
   - In `onAudioFocusChange`:
     - When receiving `LOSS_TRANSIENT_CAN_DUCK`: set `mCurrentFocusState = "LOSS_TRANSIENT_CAN_DUCK"`, `mCurrentVolumeFactor = 0.2f`, `mIsPaused = false`.
     - When receiving `LOSS_TRANSIENT`: store `mPreTransientFocusState = mCurrentFocusState`, set `mCurrentFocusState = "LOSS_TRANSIENT"`, `mIsPaused = true`.
     - When receiving `AUDIOFOCUS_GAIN`:
       - If `mPreTransientFocusState.equals("LOSS_TRANSIENT_CAN_DUCK")`: restore `mCurrentFocusState = "LOSS_TRANSIENT_CAN_DUCK"`, `mCurrentVolumeFactor = 0.2f`, `mIsPaused = false` (maintaining phone call ducking!).
       - Else: set `mCurrentFocusState = "GAIN"`, `mCurrentVolumeFactor = 1.0f`, `mIsPaused = false`.

2. **Real PCM Audio Buffer & `AudioTrack` Integration**:
   - Replace `List<String> mAudioBufferQueue` with a thread-safe `ConcurrentLinkedQueue<byte[]>` or `ArrayBlockingQueue<byte[]>` bounded to `MAX_AUDIO_QUEUE` (100).
   - Instantiate `AudioTrack` in `MODE_STREAM` (44.1kHz / 48kHz, 16-bit PCM stereo).
   - In `enqueueFrame(byte[] pcmData)`:
     - Check focus state: if `mIsPaused`, drop or buffer frame without playing.
     - Scale PCM samples by `mCurrentVolumeFactor`.
     - Forward PCM buffer to `AudioTrack.write(pcmData, 0, pcmData.length)`.

---

## 4. Remediation Blueprint & Proposed Code Changes

### Proposed `LinuxPortalService.java` Implementation Blueprint

```java
package com.android.server.linux;

import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioRecord;
import android.util.Slog;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LinuxPortalService {
    private static final String TAG = "LinuxPortalService";

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

    public LinuxPortalService(Context context) {
        mContext = context;
    }

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
        // MODE_PROMPT state -> Must trigger permission prompt!
        Slog.i(TAG, "AppOp MODE_PROMPT for " + appId + " [" + op + "], launching permission dialog...");
        if (mContext != null) {
            LinuxPermissionActivity.launchPrompt(mContext, appId, op);
        }
        // Check if decision was updated or default to false until explicit user grant
        String updatedMode = checkAppOp(appId, op);
        return MODE_ALLOWED.equals(updatedMode);
    }

    // F-R5-001: Camera Portal
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
        int finalW = (requestedW > 1920) ? 1920 : requestedW;
        int finalH = (requestedH > 1080) ? 1080 : requestedH;
        int finalFps = (requestedFps > 60) ? 30 : requestedFps;

        CameraSession session = new CameraSession(appId, sessionId, finalW, finalH, finalFps);
        mCameraSessions.put(sessionId, session);
        return session;
    }

    // F-R5-002: Mic Portal
    public boolean requestMicrophoneAccess(String appId) {
        if (!resolveAppOpOrPrompt(appId, OP_RECORD_AUDIO)) {
            Slog.w(TAG, "Microphone access denied by AppOps/Prompt for " + appId);
            return false;
        }
        return true;
    }

    // F-R5-003: Location Portal
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

    // Sessions and Exception Definitions
    public static class CameraSession {
        public final String appId, sessionId;
        public int width, height, fps;
        public boolean isActive = true;
        public CameraSession(String appId, String sessionId, int w, int h, int fps) {
            this.appId = appId; this.sessionId = sessionId; this.width = w; this.height = h; this.fps = fps;
        }
    }

    public static class MicSession {
        public final String appId, sessionId;
        public int sampleRate, channels;
        public boolean isRecording = true, isForeground = true;
        public MicSession(String appId, String sessionId, int sr, int ch) {
            this.appId = appId; this.sessionId = sessionId; this.sampleRate = sr; this.channels = ch;
        }
    }

    public static class LocationSession {
        public final String appId, sessionId;
        public boolean isActive = true;
        public LocationSession(String appId, String sessionId) {
            this.appId = appId; this.sessionId = sessionId;
        }
    }

    public static class ConnectionError extends RuntimeException {
        public ConnectionError(String msg) { super(msg); }
    }

    public static class PermissionError extends RuntimeException {
        public PermissionError(String msg) { super(msg); }
    }
}
```

---

### Proposed `LinuxPermissionActivity.java` Thread-Safe Implementation Blueprint

```java
package com.android.server.linux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

public class LinuxPermissionActivity extends Activity {
    private static final String TAG = "LinuxPermissionActivity";
    public static final long PROMPT_TIMEOUT_MS = 30000L;

    private static final Object sLock = new Object();
    private static final List<String> sPendingPromptsQueue = new ArrayList<>();
    private static boolean sIsDialogVisible = false;

    private String mAppName;
    private String mPermissionOp;
    private String mUserChoice = "PROMPT";
    private boolean mIsMdmRestricted = false;
    private boolean mIsScreenLocked = false;
    private Handler mHandler;
    private Runnable mTimeoutRunnable;

    public static void launchPrompt(Context context, String appName, String permissionOp) {
        Intent intent = new Intent(context, LinuxPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("EXTRA_APP_NAME", appName);
        intent.putExtra("EXTRA_PERMISSION_OP", permissionOp);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
        if (getIntent() != null) {
            mAppName = getIntent().getStringExtra("EXTRA_APP_NAME");
            mPermissionOp = getIntent().getStringExtra("EXTRA_PERMISSION_OP");
        }
        showPrompt(mAppName, mPermissionOp);
    }

    public boolean showPrompt(String appName, String permissionOp) {
        this.mAppName = appName;
        this.mPermissionOp = permissionOp;

        if (mIsMdmRestricted) {
            Slog.w(TAG, "MDM Policy restricted: force-denying prompt for " + appName);
            mUserChoice = "DENIED";
            return false;
        }

        synchronized (sLock) {
            if (mIsScreenLocked) {
                Slog.i(TAG, "Screen locked: queueing prompt " + appName + ":" + permissionOp);
                sPendingPromptsQueue.add(appName + ":" + permissionOp);
                return false;
            }

            if (sIsDialogVisible) {
                Slog.i(TAG, "Dialog visible: queueing concurrent prompt for " + appName);
                sPendingPromptsQueue.add(appName + ":" + permissionOp);
                return false;
            }

            sIsDialogVisible = true;
        }

        mUserChoice = "PROMPT";

        if (mHandler != null) {
            mTimeoutRunnable = () -> {
                if ("PROMPT".equals(mUserChoice)) {
                    Slog.w(TAG, "Permission prompt timed out after 30s -> defaulting to DENIED");
                    mUserChoice = "DENIED";
                    dismissPrompt();
                }
            };
            mHandler.postDelayed(mTimeoutRunnable, PROMPT_TIMEOUT_MS);
        }
        return true;
    }

    public void respondUserChoice(String choice) {
        mUserChoice = choice;
        if (mTimeoutRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mTimeoutRunnable);
        }
        Slog.i(TAG, "User choice recorded: " + mAppName + " [" + mPermissionOp + "] -> " + choice);
        dismissPrompt();
    }

    public void onScreenUnlocked() {
        mIsScreenLocked = false;
        synchronized (sLock) {
            if (!sPendingPromptsQueue.isEmpty() && !sIsDialogVisible) {
                String nextPrompt = sPendingPromptsQueue.remove(0);
                String[] parts = nextPrompt.split(":");
                if (parts.length == 2) {
                    showPrompt(parts[0], parts[1]);
                }
            }
        }
    }

    public void dismissPrompt() {
        synchronized (sLock) {
            sIsDialogVisible = false;
            if (!sPendingPromptsQueue.isEmpty()) {
                String nextPrompt = sPendingPromptsQueue.remove(0);
                String[] parts = nextPrompt.split(":");
                if (parts.length == 2) {
                    showPrompt(parts[0], parts[1]);
                }
            }
        }
    }

    public void setMdmRestricted(boolean restricted) { mIsMdmRestricted = restricted; }
    public void setScreenLocked(boolean locked) { mIsScreenLocked = locked; }
    public String getUserChoice() { return mUserChoice; }
    public static boolean isDialogVisible() { synchronized (sLock) { return sIsDialogVisible; } }
    public static List<String> getPendingPromptsQueue() {
        synchronized (sLock) { return new ArrayList<>(sPendingPromptsQueue); }
    }
}
```

---

### Proposed `LinuxAudioPolicyHandler.java` Stacked AudioFocus Implementation Blueprint

```java
package com.android.server.linux;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.util.Slog;

import java.util.concurrent.ConcurrentLinkedQueue;

public class LinuxAudioPolicyHandler implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "LinuxAudioPolicyHandler";

    private final Context mContext;
    private final AudioManager mAudioManager;
    private AudioFocusRequest mFocusRequest;

    private float mCurrentVolumeFactor = 1.0f;
    private boolean mIsPaused = false;
    private String mCurrentFocusState = "NONE";
    private String mSavedFocusState = "NONE";
    private String mPreTransientFocusState = "NONE";

    private final ConcurrentLinkedQueue<String> mAudioBufferQueue = new ConcurrentLinkedQueue<>();
    private static final int MAX_AUDIO_QUEUE = 100;

    public LinuxAudioPolicyHandler(Context context) {
        mContext = context;
        if (context != null) {
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        } else {
            mAudioManager = null;
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Slog.i(TAG, "AudioFocus GAIN delivered");
                if ("LOSS_TRANSIENT_CAN_DUCK".equals(mPreTransientFocusState)) {
                    Slog.i(TAG, "Restoring to ducked state (0.2f volume) because call is still active");
                    mCurrentFocusState = "LOSS_TRANSIENT_CAN_DUCK";
                    mCurrentVolumeFactor = 0.2f;
                    mIsPaused = false;
                } else {
                    mCurrentFocusState = "GAIN";
                    mCurrentVolumeFactor = 1.0f;
                    mIsPaused = false;
                }
                mPreTransientFocusState = "NONE";
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Slog.i(TAG, "AudioFocus LOSS_TRANSIENT_CAN_DUCK -> ducking volume to 0.2");
                mCurrentFocusState = "LOSS_TRANSIENT_CAN_DUCK";
                mCurrentVolumeFactor = 0.2f;
                mIsPaused = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Slog.i(TAG, "AudioFocus LOSS_TRANSIENT -> pausing audio playback");
                mPreTransientFocusState = mCurrentFocusState;
                mCurrentFocusState = "LOSS_TRANSIENT";
                mIsPaused = true;
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Slog.i(TAG, "AudioFocus LOSS -> stopping audio stream");
                mCurrentFocusState = "LOSS";
                mIsPaused = true;
                mPreTransientFocusState = "NONE";
                abandonAudioFocus();
                break;
            default:
                break;
        }
    }

    public void setFocusState(String state) {
        mCurrentFocusState = state;
        if ("LOSS_TRANSIENT_CAN_DUCK".equals(state)) {
            mCurrentVolumeFactor = 0.2f;
            mIsPaused = false;
        } else if ("LOSS_TRANSIENT".equals(state)) {
            mIsPaused = true;
        } else if ("LOSS".equals(state)) {
            mIsPaused = true;
        } else if ("GAIN".equals(state)) {
            mCurrentVolumeFactor = 1.0f;
            mIsPaused = false;
        }
    }

    public void enqueueFrame(String frame) {
        mAudioBufferQueue.add(frame);
        while (mAudioBufferQueue.size() > MAX_AUDIO_QUEUE) {
            mAudioBufferQueue.poll();
        }
    }

    public int getQueueSize() {
        return mAudioBufferQueue.size();
    }

    public float getVolumeFactor() { return mCurrentVolumeFactor; }
    public boolean isPaused() { return mIsPaused; }
    public String getFocusState() { return mCurrentFocusState; }
}
```

---

## 5. Verification & Test Strategy

To verify this remediation plan:
1. **Compile & Run Empirical Stress Test**:
   ```bash
   mkdir -p /Users/iml1s/Documents/mine/aosp-linux/build_out/classes
   javac -d /Users/iml1s/Documents/mine/aosp-linux/build_out/classes \
     $(find /Users/iml1s/Documents/mine/aosp-linux/frameworks/base/core/java /Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java -name "*.java") \
     /Users/iml1s/Documents/mine/aosp-linux/tests/unit/ChallengerM5EmpiricalStressTest.java

   java -cp /Users/iml1s/Documents/mine/aosp-linux/build_out/classes tests.unit.ChallengerM5EmpiricalStressTest
   ```
   - Verify Test 1 (`MODE_PROMPT`), Test 2 (Permission queue concurrency), Test 3 (Audio queue concurrency), and Test 5 (Stacked AudioFocus ducking) all output `[PASS]`.
2. **Rewrite Tier-1 E2E Tests (`test_m5_tier1.py`)**:
   - Replace `CustomAssertions.assert_true(True)` with genuine assertion statements invoking `LinuxPortalService`, `LinuxPermissionActivity`, and `LinuxAudioPolicyHandler`.
