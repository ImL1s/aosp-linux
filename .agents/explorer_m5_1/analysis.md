# Technical Analysis & Implementation Strategy: Milestone M5 — Hardware Portals & Audio Subsystem (F-R5-001 to F-R5-006)

## 1. Overview & Scope Definition

This report provides the technical investigation and architectural blueprint for **Milestone M5 (Hardware Portals & Audio Subsystem)**, covering features **F-R5-001 through F-R5-006**:
1. **F-R5-001**: XDG Portal Camera Bridge (`org.freedesktop.portal.Camera` interception & Camera2 HAL streaming).
2. **F-R5-002**: XDG Portal Microphone Bridge (`org.freedesktop.portal.Microphone` interception & AudioRecord streaming).
3. **F-R5-003**: XDG Portal Location Bridge (`org.freedesktop.portal.Location` interception & LocationManager streaming).
4. **F-R5-004**: AppOps Permission Prompt (Mandatory Host runtime permission dialog & `AppOpsManager` enforcement for `OP_CAMERA`, `OP_RECORD_AUDIO`, `OP_FINE_LOCATION`).
5. **F-R5-005**: virtio-snd Audio Mapping (`virtio-snd` guest driver mapping to Host `AudioService` / `AudioTrack`).
6. **F-R5-006**: AudioFocus Policy Handler (`LinuxAudioPolicyHandler.java` for ducking/pausing/stopping audio on calls, alarms, and notifications).

The architecture adheres strictly to the **Fail-Closed Security & Isolation Principle**: Guest Linux processes run unprivileged relative to Host AOSP. Guest processes cannot directly open `/dev/video*`, `/dev/snd/pcm*`, or hardware device nodes, nor call Binder APIs directly. All hardware access is intercepted by Guest XDG Portals, forwarded across the authenticated Vsock IPC bridge (`virtio-vsock` Port 5000 / 5003), evaluated by Host `AppOpsManager`, prompted via Host UI dialogs, and serviced via Host system services.

---

## 2. Codebase Audit & Baseline Inspection Findings

### 2.1 Existing System Framework State
- **`frameworks/base/services/core/java/com/android/server/SystemServer.java`**:
  - Currently instantiates and starts `LinuxManagerService`.
  - Missing registration for `LinuxPortalService` and `LinuxAudioPolicyHandler`.
- **`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`**:
  - Manages VM lifecycle (`STATE_STOPPED`, `STATE_STARTING`, `STATE_RUNNING`, `STATE_SUSPENDED`, `STATE_ERROR`).
  - Owns `LinuxBridgeService` for Unix domain socket communication (`/dev/socket/linux_bridge`).
- **`frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`**:
  - Handles binary framing over socket (`MAGIC = 0x4C4E5842`).
  - Command codes defined: `CMD_VM_START` (0x0001), `CMD_VM_STOP` (0x0002), `CMD_HANDSHAKE_COMPLETE` (0x0003), `CMD_PTY_*` (0x0100..0x0103), `CMD_APP_SYNC` (0x0200).
  - Needs expansion for portal IPC commands: `CMD_PORTAL_CAMERA_REQ` (0x0400), `CMD_PORTAL_MIC_REQ` (0x0401), `CMD_PORTAL_LOCATION_REQ` (0x0402), `CMD_PORTAL_AUDIO_STREAM` (0x0403), `CMD_PORTAL_RESP` (0x0404).

### 2.2 Missing Components to Implement
1. **`com.android.server.linux.LinuxPortalService.java`**: Host SystemServer service managing hardware portal bridge endpoints (Camera2, AudioRecord, LocationManager, AppOps enforcement).
2. **`com.android.server.linux.LinuxAudioPolicyHandler.java`**: Host framework audio policy handler implementing `AudioManager.OnAudioFocusChangeListener` for Linux audio ducking/pausing.
3. **`com.android.server.linux.LinuxPermissionActivity.java`**: Host system UI permission prompt Activity triggered when guest portal requests access.
4. **Vsock IPC Protocol Extensions**: Command packets for camera frame streaming, mic PCM streaming, location GeoClue payloads, and permission prompt responses.

---

## 3. Feature-by-Feature Technical Implementation Strategy

---

### 3.1 F-R5-001: XDG Portal Camera Bridge

#### Architecture & Class Definitions
- **Class Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java` (Camera Module)
- **Guest Portal Endpoint**: `org.freedesktop.portal.Camera` D-Bus interface in `portal-agent`.
- **IPC Command**: `CMD_PORTAL_CAMERA_REQ` (0x0400) / `CMD_PORTAL_CAMERA_FRAME` (0x0410).

```java
public class LinuxPortalService {
    // Camera session state
    private static class CameraSession {
        final String appId;
        final String sessionId;
        CameraDevice cameraDevice;
        CameraCaptureSession captureSession;
        ImageReader imageReader;
        boolean isActive;
    }
}
```

#### Detailed Workflow
1. **Interception & Request**:
   - Guest app calls `org.freedesktop.portal.Camera.AccessCamera()`.
   - Guest `portal-agent` serializes request payload `{"app_id": "org.gnome.Cheese", "session_id": "cam_001"}` and sends `CMD_PORTAL_CAMERA_REQ` to Host `LinuxBridgeService`.
2. **Host AppOps Check & Permission Prompt**:
   - `LinuxPortalService.handleCameraAccessRequest()` queries `AppOpsManager.checkOpNoThrow(AppOpsManager.OP_CAMERA, uid, packageName)`.
   - If `MODE_ALLOWED`: proceeds to open camera.
   - If `MODE_ERRORED` / `MODE_IGNORED`: returns `AccessDenied` error frame to Guest.
   - If `MODE_DEFAULT` (Prompt): triggers `LinuxPermissionActivity` (see F-R5-004).
3. **Host Camera2 HAL Pipeline**:
   - Opens camera using `CameraManager.openCamera(cameraId, stateCallback, handler)`.
   - Configures `ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)`.
   - Registers `OnImageAvailableListener`: when frame is available, extracts byte buffers (Y, U, V planes or H.264 encoded frame), packs into `CMD_PORTAL_CAMERA_FRAME` payload, and transmits across Vsock.
   - Guest `portal-agent` writes frames to guest `v4l2loopback` device node (`/dev/video0`) or PipeWire video source node.

#### Safeguards & Boundary Case Strategies (T2-116 .. T2-120)
- **T2-116 (Denied Prompt)**: Immediately send D-Bus `org.freedesktop.portal.Error.Failed` ("AccessDenied by user") and drop session.
- **T2-117 (Resource Release on Exit)**: On guest app exit or `closeTerminalSession` / `destroySurface`, `LinuxPortalService` releases `CameraDevice.close()` and `ImageReader.close()`.
- **T2-118 (Camera Contention Resolution)**: Host native Android app camera requests take priority. If Host `CameraManager.AvailabilityCallback` signals camera unavailable or pre-empted by foreground Android app, `LinuxPortalService` pauses guest stream and notifies guest `portal-agent`.
- **T2-119 (Resolution Mismatch Fallback)**: If requested resolution (e.g. 4K 120fps) is unsupported by host camera hardware, fallback automatically to highest supported resolution (e.g. 1080p@30fps `(1920, 1080, 30)`).
- **T2-120 (Hardware Disconnect)**: If USB camera is physically unplugged during streaming, `CameraDevice.StateCallback.onError()` catches error `ERROR_CAMERA_DISCONNECTED` and emits `ConnectionError("HardwareDisconnected")` to guest.

---

### 3.2 F-R5-002: XDG Portal Microphone Bridge

#### Architecture & Class Definitions
- **Class Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java` (Microphone Module)
- **Guest Portal Endpoint**: `org.freedesktop.portal.Microphone` D-Bus interface in `portal-agent`.
- **IPC Command**: `CMD_PORTAL_MIC_REQ` (0x0401) / `CMD_PORTAL_MIC_PCM` (0x0411).

```java
private static class MicSession {
    final String appId;
    AudioRecord audioRecord;
    Thread recordingThread;
    volatile boolean isRecording;
}
```

#### Detailed Workflow
1. **Interception & Request**:
   - Guest app (e.g. Audacity, Discord) requests mic access via D-Bus `org.freedesktop.portal.Microphone.CreateSession()`.
   - Guest `portal-agent` transmits `CMD_PORTAL_MIC_REQ` across Vsock 5000.
2. **AppOps Check & Recording Setup**:
   - Checks `AppOpsManager.checkOpNoThrow(AppOpsManager.OP_RECORD_AUDIO, uid, packageName)`.
   - On approval, instantiates Host `AudioRecord`:
     - `AudioSource`: `MediaRecorder.AudioSource.MIC`
     - `SampleRate`: 44100 Hz or 48000 Hz
     - `ChannelConfig`: `AudioFormat.CHANNEL_IN_STEREO` or `CHANNEL_IN_MONO`
     - `AudioFormat`: `AudioFormat.ENCODING_PCM_16BIT`
3. **PCM Streaming Thread**:
   - Dedicated thread reads PCM buffer chunks from `AudioRecord.read(byteBuffer, size)`.
   - Transmits PCM audio payload `CMD_PORTAL_MIC_PCM` over Vsock.
   - Guest PipeWire/ALSA loopback driver ingests PCM stream.

#### Safeguards & Boundary Case Strategies (T2-121 .. T2-125)
- **T2-121 (Revoked Permission)**: Stop `AudioRecord` immediately and emit capture failure response.
- **T2-122 (Privacy Toggle Mute)**: If Android system mic privacy toggle (`SensorPrivacyManager.isSensorPrivatelyMuted(SENSOR_MICROPHONE)`) is enabled, zero-fill the PCM buffer (`Arrays.fill(pcmBuffer, (byte)0)`) to ensure silent frames without breaking stream timing.
- **T2-123 (Buffer Underflow Mitigation)**: If host PCM queue length falls below `MIN_BUFFER_SIZE` (1024 bytes), automatically insert zero-fill silence bytes `b"\x00"` to prevent audio glitching/underflow in guest PipeWire.
- **T2-124 (Stop Mic on Background)**: When Linux app moves out of foreground without holding a valid foreground service, stop `AudioRecord` streaming (`mic_recording_active = false`).
- **T2-125 (Stereo to Mono Downmixing)**: If guest app requests mono PCM from stereo host input, compute mono sample `(left_sample + right_sample) / 2`.

---

### 3.3 F-R5-003: XDG Portal Location Bridge

#### Architecture & Class Definitions
- **Class Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java` (Location Module)
- **Guest Portal Endpoint**: `org.freedesktop.portal.Location` & `org.freedesktop.GeoClue2.Location`.
- **IPC Command**: `CMD_PORTAL_LOCATION_REQ` (0x0402) / `CMD_PORTAL_LOCATION_UPDATE` (0x0412).

#### Detailed Workflow
1. **Interception & Request**:
   - Guest app (e.g. Marble, Maps) requests location via D-Bus `org.freedesktop.portal.Location.CreateSession()`.
   - Transmits `CMD_PORTAL_LOCATION_REQ` over Vsock.
2. **AppOps Check & LocationManager Hook**:
   - Checks `AppOpsManager.checkOpNoThrow(AppOpsManager.OP_FINE_LOCATION, uid, packageName)`.
   - Obtains Host `LocationManager` and registers `LocationListener`:
     `locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L /* 5s minTime */, 10.0f /* 10m minDistance */, listener)`.
3. **GeoClue D-Bus Formatting**:
   - On location fix, constructs JSON / D-Bus location payload:
     `{"latitude": loc.getLatitude(), "longitude": loc.getLongitude(), "altitude": loc.getAltitude(), "accuracy": loc.getAccuracy(), "speed": loc.getSpeed(), "timestamp": loc.getTime()}`.
   - Sends `CMD_PORTAL_LOCATION_UPDATE` packet over Vsock.
   - Guest `portal-agent` updates `/org/freedesktop/GeoClue2/Location/N` properties on system D-Bus.

#### Safeguards & Boundary Case Strategies (T2-126 .. T2-130)
- **T2-126 (Coarse Location Obfuscation)**: If user grants Coarse Location only (`OP_COARSE_LOCATION`), round latitude and longitude to 2 decimal places (e.g. `25.0330123 -> 25.03`), masking exact coordinates.
- **T2-127 (GPS Disabled Failure)**: If device GPS / location master toggle is off in Android Settings, return `PermissionError` / provider disabled error to guest.
- **T2-128 (Update Frequency Throttling)**: Throttle updates to enforce minimum interval of `5.0` seconds between location payloads to conserve battery.
- **T2-129 (Mock Location Filtering)**: Reject and filter out mock/spoofed location updates if system `allow_mock_locations` is false (`loc.isMock() && !allowMock`).
- **T2-130 (Unsubscribe on Session Exit)**: Remove `LocationListener` from `LocationManager` immediately when guest app terminates or closes portal session.

---

### 3.4 F-R5-004: AppOps Permission Prompt

#### Architecture & Class Definitions
- **Class Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java` & `LinuxPortalService.java`
- **Enforcement Manager**: `android.app.AppOpsManager`
- **Operations Handled**: `OP_CAMERA` (26), `OP_RECORD_AUDIO` (27), `OP_FINE_LOCATION` (1)

```java
public class LinuxPermissionActivity extends Activity {
    // Displays system permission prompt dialog for Linux portal requests
    // Controls options: ALLOW_ALWAYS, ALLOW_ONCE, DENY
}
```

#### Detailed Workflow
1. **Portal Interception**:
   - When portal request arrives for `OP_CAMERA`, `OP_RECORD_AUDIO`, or `OP_FINE_LOCATION`, `LinuxPortalService` calls `appOpsManager.checkOpNoThrow(op, callingUid, callingPackage)`.
2. **Permission State Evaluation**:
   - `MODE_ALLOWED`: Grant immediately.
   - `MODE_ERRORED` / `MODE_IGNORED`: Deny immediately.
   - `MODE_DEFAULT` (Unset/Prompt): Launch `LinuxPermissionActivity` dialog over Android UI.
3. **Dialog UI & User Decision**:
   - Displays requesting Linux app name (e.g. "Cheese"), icon, and requested hardware permission.
   - Options:
     - **Allow Always**: `appOpsManager.setMode(op, uid, packageName, AppOpsManager.MODE_ALLOWED)`
     - **Allow Only While Using App**: Sets transient allowed state bound to app foreground lifecycle.
     - **Deny**: `appOpsManager.setMode(op, uid, packageName, AppOpsManager.MODE_IGNORED)`
4. **Result Callback**:
   - `LinuxPermissionActivity` returns result to `LinuxPortalService`, which completes or fails the guest D-Bus request.

#### Safeguards & Boundary Case Strategies (T2-131 .. T2-135)
- **T2-131 (30-Second Timeout Auto-Rejection)**: A 30-second timer (`PROMPT_TIMEOUT = 30000ms`) starts when dialog is displayed. If user does not respond within 30s, dialog automatically dismisses and records `MODE_IGNORED` (default deny).
- **T2-132 (Duplicate Prompt Suppression)**: While a permission dialog is actively displayed for an app/op pair, secondary duplicate requests are queued or suppressed (`prompt_count = 1`).
- **T2-133 (Enterprise MDM Policy Override)**: If Enterprise MDM (`DevicePolicyManager`) enforces a restriction (e.g. `DISALLOW_CAMERA`), `LinuxPortalService` force-denies the request (`effective_mode = DENIED`) regardless of user input.
- **T2-134 (Dynamic Revocation in Settings)**: `LinuxPortalService` registers `AppOpsManager.OnOpChangedListener`. If user changes permission mode in Android Settings while a portal stream is active, `onOpChanged()` immediately aborts the active stream.
- **T2-135 (Screen Locked Prompt Queueing)**: If portal request arrives while device screen is locked (`KeyguardManager.isKeyguardLocked()`), prompt display is deferred in a pending prompt queue until screen unlock.

---

### 3.5 F-R5-005: virtio-snd Audio Mapping

#### Architecture & Class Definitions
- **Class Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxAudioPolicyHandler.java` / `LinuxPortalService.java`
- **Device Driver**: `virtio-snd` PCI guest driver / PipeWire audio sink.
- **Host Endpoint**: `android.media.AudioTrack` & `android.media.AudioService`.

```java
public class LinuxAudioTrackPlayer {
    private AudioTrack mAudioTrack;
    private int mSampleRate = 48000;
    private int mChannelConfig = AudioFormat.CHANNEL_OUT_STEREO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float mVolumeFactor = 1.0f;
}
```

#### Detailed Workflow
1. **Guest PCM Streaming**:
   - Guest PipeWire/ALSA outputs PCM audio to `virtio-snd` device ring buffer.
   - Vsock 5000 / 5003 packet stream `CMD_PORTAL_AUDIO_STREAM` transfers raw PCM frames to Host.
2. **Host AudioTrack Playback**:
   - Host instantiates `AudioTrack` with:
     - `AudioAttributes`: `USAGE_MEDIA`, `CONTENT_TYPE_MUSIC`
     - `AudioFormat`: 48000Hz stereo 16-bit PCM
     - `TransferMode`: `MODE_STREAM`
   - Writes incoming PCM chunks to `AudioTrack.write(data, offset, length)`.
3. **Volume Synchronization & Buffer Management**:
   - Synchronizes guest master volume slider with Host `AudioTrack.setVolume(volume * mVolumeFactor)`.
   - Low-latency buffer sizing tuned to ~20ms latency.

#### Safeguards & Boundary Case Strategies (T2-136 .. T2-140)
- **T2-136 (Buffer Overflow Under Load)**: Ring buffer enforces bounded queue size `MAX_AUDIO_QUEUE = 100`. Under heavy host CPU load, oldest unplayed frames are dropped to prevent memory inflation and unbounded audio delay.
- **T2-137 (Output Device Switching)**: If Bluetooth headset disconnects (`BLUETOOTH_A2DP`), Host `AudioManager` intent `ACTION_AUDIO_BECOMING_NOISY` seamlessly reroutes playback to `BUILTIN_SPEAKER` without crashing stream.
- **T2-138 (Zero-Fill Silent Frames on Underrun)**: On audio buffer underrun (`data_available == 0`), Host writes `REQUIRED_BYTES` (512 bytes) of zero-fill silence (`b"\x00"`) to prevent hardware popping noise.
- **T2-139 (Sample Format Conversion)**: Converts INT16 (`-32768` to `32767`) to FLOAT32 (`-1.0` to `1.0`) via `sample / 32768.0f` when high-precision processing is required.
- **T2-140 (Multi-Stream Audio Mixing)**: When multiple guest/host streams play concurrently, samples are mixed with clipping clamping `min(1.0, max(-1.0, stream1 + stream2))`.

---

### 3.6 F-R5-006: AudioFocus Policy Handler

#### Architecture & Class Definitions
- **Class Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxAudioPolicyHandler.java`
- **Interfaces**: `android.media.AudioManager.OnAudioFocusChangeListener`

```java
package com.android.server.linux;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.util.Slog;

/**
 * AudioFocus Policy Handler managing automatic Linux audio ducking, pausing, and stopping.
 * {@hide}
 */
public class LinuxAudioPolicyHandler implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "LinuxAudioPolicyHandler";

    private final Context mContext;
    private final AudioManager mAudioManager;
    private AudioFocusRequest mFocusRequest;
    private float mCurrentVolumeFactor = 1.0f;
    private boolean mIsPaused = false;
    private String mCurrentFocusState = "NONE";

    public LinuxAudioPolicyHandler(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean requestAudioFocus() {
        AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this)
                .build();

        int res = mAudioManager.requestAudioFocus(mFocusRequest);
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mCurrentFocusState = "GAIN";
            mCurrentVolumeFactor = 1.0f;
            mIsPaused = false;
            return true;
        }
        mCurrentFocusState = "NONE";
        return false;
    }

    public void abandonAudioFocus() {
        if (mFocusRequest != null) {
            mAudioManager.abandonAudioFocusRequest(mFocusRequest);
            mCurrentFocusState = "NONE";
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Slog.i(TAG, "AudioFocus GAIN -> restoring volume and resuming playback");
                mCurrentFocusState = "GAIN";
                mCurrentVolumeFactor = 1.0f;
                mIsPaused = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Slog.i(TAG, "AudioFocus LOSS_TRANSIENT_CAN_DUCK -> ducking volume to 0.2");
                mCurrentFocusState = "LOSS_TRANSIENT_CAN_DUCK";
                mCurrentVolumeFactor = 0.2f;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Slog.i(TAG, "AudioFocus LOSS_TRANSIENT -> pausing audio playback");
                mCurrentFocusState = "LOSS_TRANSIENT";
                mIsPaused = true;
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Slog.i(TAG, "AudioFocus LOSS -> stopping audio playback and releasing focus");
                mCurrentFocusState = "LOSS";
                mIsPaused = true;
                abandonAudioFocus();
                break;
        }
    }

    public float getVolumeFactor() {
        return mCurrentVolumeFactor;
    }

    public boolean isPaused() {
        return mIsPaused;
    }

    public String getFocusState() {
        return mCurrentFocusState;
    }
}
```

#### Safeguards & Boundary Case Strategies (T2-141 .. T2-145)
- **T2-141 (Phone Call Ducking)**: Incoming phone call triggers `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`. `LinuxAudioPolicyHandler` immediately scales Linux media volume factor to `0.2f` (or mutes if configured), restoring to `1.0f` on call end.
- **T2-142 (Alarm Clock Pause)**: Alarm clock trigger emits `AUDIOFOCUS_LOSS_TRANSIENT`. `LinuxAudioPolicyHandler` pauses Linux audio playback stream until alarm stops.
- **T2-143 (Reject Focus Without Foreground Service)**: Audio focus request is rejected (`AUDIOFOCUS_REQUEST_FAILED`) if Linux app attempts background playback without an active Android foreground service.
- **T2-144 (Suspend Recovery)**: Preserves pre-suspend focus state (`saved_focus`). Upon VM resume, re-requests `AUDIOFOCUS_GAIN` and restores volume/playback state.
- **T2-145 (Rapid Toggle Stability)**: Synchronizes state transitions atomically to prevent race conditions during rapid sequence `GAIN -> LOSS_TRANSIENT -> GAIN -> LOSS -> GAIN`.

---

## 4. File & Module Blueprint Table

| Module / Component | Target File Absolute Path | Type | Role & Responsibilities |
|--------------------|---------------------------|------|-------------------------|
| **LinuxPortalService** | `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java` | NEW | SystemServer portal service handling Camera2, AudioRecord, LocationManager, AppOps |
| **LinuxAudioPolicyHandler** | `frameworks/base/services/core/java/com/android/server/linux/LinuxAudioPolicyHandler.java` | NEW | AudioFocus policy handler (`OnAudioFocusChangeListener`) for ducking/pausing |
| **LinuxPermissionActivity** | `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java` | NEW | System UI runtime permission dialog Activity for portal requests |
| **SystemServer** | `frameworks/base/services/core/java/com/android/server/SystemServer.java` | EXTEND | Register `LinuxPortalService` and `LinuxAudioPolicyHandler` in `startOtherServices()` |
| **LinuxBridgeService** | `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java` | EXTEND | Expand command constants (`CMD_PORTAL_*`) and framing parser for portal IPC |
| **LinuxManager** | `frameworks/base/core/java/android/system/linux/LinuxManager.java` | EXTEND | Expose portal status methods and permissions (`PERMISSION_MANAGE_LINUX_ENVIRONMENT`) |

---

## 5. Test Verification Strategy

### 5.1 E2E Test Commands
```bash
# Tier 1 Functional Coverage for M5 Features F-R5-001..006 (Tests T1-116 to T1-145)
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 1 --feature F-R5-001
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 1 --feature F-R5-002
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 1 --feature F-R5-003
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 1 --feature F-R5-004
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 1 --feature F-R5-005
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 1 --feature F-R5-006

# Tier 2 Boundary & Corner Case Coverage (Tests T2-116 to T2-145)
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 2 --feature F-R5-001
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 2 --feature F-R5-002
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 2 --feature F-R5-003
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 2 --feature F-R5-004
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 2 --feature F-R5-005
python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 2 --feature F-R5-006
```

### 5.2 Verification Matrix
- **T1-116..120 & T2-116..120**: Camera Portal AccessDenied, exit release, contention priority, resolution fallback, USB disconnect.
- **T1-121..125 & T2-121..125**: Mic Portal revocation, mic privacy toggle mute, buffer underflow zero-fill, background stop, mono downmixing.
- **T1-126..130 & T2-126..130**: Location Portal coarse rounding, GPS off failure, 5s throttling, mock filtering, session unsubscribe.
- **T1-131..135 & T2-131..135**: AppOps prompt 30s timeout, duplicate suppression, MDM override, dynamic revocation in Settings, lockscreen queueing.
- **T1-136..140 & T2-136..140**: virtio-snd load overflow frame drop, BT headset output switch, zero-fill underrun, INT16-FLOAT32 conversion, multi-stream mixing.
- **T1-141..145 & T2-141..145**: AudioFocus phone call ducking (0.2), alarm clock pause, background focus rejection, suspend recovery, rapid toggle stability.
