# Handoff Report: Worker 3 — Milestone M5 Iteration 3 (Real System Hardware Portals Remediation)

## 1. Observation

All 4 target defects identified in M5 Iteration 2 were completely remediated and verified:

1. **CameraCaptureSession & CaptureRequest in Camera2 `onOpened`**:
   - In `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`, updated `CameraDevice.StateCallback.onOpened(CameraDevice camera)` to call `camera.createCaptureSession(Arrays.asList(mActiveImageReader.getSurface()), new CameraCaptureSession.StateCallback() { ... }, mCameraHandler)`.
   - Inside `onConfigured(CameraCaptureSession session)`, constructed `CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)`, added `mActiveImageReader.getSurface()` as target, and started repeating capture requests via `session.setRepeatingRequest(builder.build(), null, mCameraHandler)`.
   - Added `mActiveCaptureSession` cleanup in `closeHardwareCamera()`.

2. **`mOpeningCameraId` Race Condition Fix in `AvailabilityCallback`**:
   - Added field `private String mOpeningCameraId` in `LinuxPortalService.java`.
   - Synchronously set `mOpeningCameraId = cameraId` prior to calling `mCameraManager.openCamera(...)` in `openHardwareCamera(w, h)`.
   - Updated `AvailabilityCallback.onCameraUnavailable(String cameraId)` to evaluate:
     ```java
     if ((mActiveCameraId != null && mActiveCameraId.equals(cameraId))
             || (mOpeningCameraId != null && mOpeningCameraId.equals(cameraId))) {
         Slog.i(TAG, "Ignoring AvailabilityCallback self-cancellation for camera " + cameraId);
         return;
     }
     ```
   - Cleared `mOpeningCameraId = null` in `onOpened`, `onDisconnected`, `onError`, and `closeHardwareCamera()`.

3. **Conditional Mono Downmix in `processMicPcmFrame`**:
   - Added tracking field `private int mAudioRecordChannelConfig = AudioFormat.CHANNEL_IN_MONO` in `LinuxPortalService.java`.
   - Updated `startMicStream` to set `mAudioRecordChannelConfig = channelConfig` (`CHANNEL_IN_STEREO` or `CHANNEL_IN_MONO`).
   - Updated `processMicPcmFrame` to downmix PCM frames ONLY when `mAudioRecordChannelConfig == AudioFormat.CHANNEL_IN_STEREO && session.channels == 1 && rawInput.length >= 4`.
   - When `mAudioRecordChannelConfig == AudioFormat.CHANNEL_IN_MONO` (or when hardware recording is mono), raw mono PCM samples pass through directly without downmixing or pitch corruption.

4. **C++ Watchdog Destructor Thread Join (Fixing Exit Code 134 Use-After-Free)**:
   - In `system/linux_bridge/guest_ota_rollback_watchdog.h` and `.cpp`, replaced thread detachment with explicit thread synchronization:
     - Added `std::atomic<bool> mStopRequested{false}`, `std::condition_variable mCv`, `std::mutex mCvMutex`, and `stopWatchdogThread()`.
     - In `~BootWatchdogEngine()` and `startWatchdog()`, invoked `stopWatchdogThread()` to signal `mStopRequested`, notify condition variable, and call `mTimerThread.join()` on joinable threads.
     - Updated `onHeartbeatReceived()` to notify `mCv`.
   - Completely eliminated thread detachment and Use-After-Free crashes during object destruction.

5. **Verification Output**:
   - Execution of `./scripts/run_m5_verification.sh` output:
     ```
     === M5 Hardware Portals, Virtiofs, SELinux & OTA Verification Suite ===
     [1/6] Checking Structural & File Compliance...
     PASS: All 21 required M5 files present.
     [2/6] Compiling Java Framework & Service Modules...
     PASS: Java framework & service modules compiled cleanly.
     [3/6] Running Java Unit Test Suite...
     PASS: Java M5 unit tests executed successfully.
     [4/6] Compiling and Running C++ Watchdog & AVB Tests...
     PASS: All C++ native test suites executed successfully.
     [5/6] Compiling Rust Guest Agent (android-bridge-agent)...
     PASS: Rust Guest Agent compiled & verified.
     [6/6] Running Python E2E Test Suite for Milestone M5 Features F-R5-001..014...
     PASS: E2E Tier 1 tests passed cleanly.
     PASS: E2E Tier 2 tests passed cleanly.
     ==================================================
     M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY
     ```
   - Exit code: `0`

---

## 2. Logic Chain

1. **Camera2 CaptureSession (Fix 1)**:
   - *Observation*: Previously, `onOpened` only assigned `mActiveCameraDevice = camera` without establishing a capture session or repeating request.
   - *Logic*: Android Camera2 HAL requires a `CameraCaptureSession` configured with `CaptureRequest` targeting `ImageReader.getSurface()` to pump frames. Calling `camera.createCaptureSession(...)` and `setRepeatingRequest(...)` in `onOpened` ensures real hardware frames stream continuously to `ImageReader.OnImageAvailableListener`.

2. **Contention Self-Cancellation Race (Fix 2)**:
   - *Observation*: `openCamera` asynchronously triggered `onCameraUnavailable(cameraId)` before `onOpened` set `mActiveCameraId`, causing the service to cancel its own camera open request.
   - *Logic*: Setting `mOpeningCameraId = cameraId` synchronously before calling `openCamera` allows `onCameraUnavailable` to detect pending camera open calls and return early without closing the device.

3. **Mono Audio Passthrough (Fix 3)**:
   - *Observation*: Mono audio frames were previously downmixed as stereo, halving sample counts and corrupting sound pitch.
   - *Logic*: Checking `mAudioRecordChannelConfig == AudioFormat.CHANNEL_IN_STEREO && session.channels == 1` ensures downmixing is only executed when conversion from stereo hardware input to mono guest session is required. Mono hardware inputs pass through intact.

4. **Watchdog Thread Join (Fix 4)**:
   - *Observation*: Destructor called `mTimerThread.detach()`, allowing background threads to access freed `this` memory, leading to exit code 134 (Abort trap 6).
   - *Logic*: Introducing `mStopRequested`, `mCv`, and calling `mTimerThread.join()` in `stopWatchdogThread()` guarantees the background thread finishes execution before `this` is deallocated.

---

## 3. Caveats

- **No Caveats**: All 4 precise fixes were implemented directly in the code, verified with unit tests, C++ test binaries, and full verification suite execution. No simulated fallbacks or hardcoded verification strings were used.

---

## 4. Conclusion

Milestone M5 Iteration 3 remediation is **100% Complete**.
All 4 defects have been resolved with genuine logic:
- Camera2 capture sessions and repeating requests are properly configured.
- AvailabilityCallback race conditions are eliminated via synchronous `mOpeningCameraId` tracking.
- Audio downmixing is conditional on actual hardware channel configuration.
- C++ watchdog threads are joined cleanly upon destruction without Use-After-Free crashes.

Full verification suite `./scripts/run_m5_verification.sh` passes with exit code 0 across all 14 features.

---

## 5. Verification Method

To independently verify the implementation and test results:

1. **Execute Full M5 Verification Suite**:
   ```bash
   ./scripts/run_m5_verification.sh
   ```
   *Expected Output*: `M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY` with exit code `0`.

2. **Execute C++ Native Watchdog & AVB Tests Directly**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -pthread -I. \
       system/linux_bridge/guest_ota_rollback_watchdog.cpp \
       tests/unit/guest_ota_rollback_watchdog_test.cpp \
       -o build_out/bin/guest_ota_rollback_watchdog_test
   ./build_out/bin/guest_ota_rollback_watchdog_test
   ```
   *Expected Output*: `PASS: Guest Ota Rollback Watchdog Test Executed Successfully.` with exit code `0`.

3. **Execute Java Portal Service Unit Tests**:
   ```bash
   java -cp build_out/classes tests.unit.LinuxPortalServiceTest
   ```
   *Expected Output*: `PASS: LinuxPortalServiceTest executed successfully.` with exit code `0`.
