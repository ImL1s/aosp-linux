# Handoff Report: Challenger 1 — Milestone M5 Iteration 3 (Hardware Portals Remediation)

## 1. Observation

All 4 remediations in `LinuxPortalService.java` and `guest_ota_rollback_watchdog` were independently inspected and empirically stress-tested:

1. **CameraCaptureSession HAL Frame Streaming**:
   - Location: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java:348-376`.
   - Verified that `onOpened(CameraDevice camera)` invokes `camera.createCaptureSession(Arrays.asList(mActiveImageReader.getSurface()), ...)` and configures `setRepeatingRequest(builder.build(), null, mCameraHandler)` using `TEMPLATE_PREVIEW`.
   - `closeHardwareCamera()` cleanly closes `mActiveCaptureSession` before closing `mActiveCameraDevice`.

2. **`mOpeningCameraId` Race Condition Filter**:
   - Location: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java:174-180, 345, 350, 384, 390`.
   - Verified `mOpeningCameraId = cameraId` is synchronously assigned prior to calling `mCameraManager.openCamera(...)`.
   - `AvailabilityCallback.onCameraUnavailable(String cameraId)` evaluates `(mOpeningCameraId != null && mOpeningCameraId.equals(cameraId))` to ignore self-cancellation while opening is in progress.
   - Cleared cleanly in `onOpened`, `onDisconnected`, `onError`, and `closeHardwareCamera()`.

3. **Conditional Mono Downmix Behavior**:
   - Location: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java:533-544`.
   - Verified `processMicPcmFrame` checks `mAudioRecordChannelConfig == AudioFormat.CHANNEL_IN_STEREO && session.channels == 1 && rawInput.length >= 4`.
   - Raw mono PCM frames recorded from mono hardware pass through untouched without pitch distortion or sample reduction.

4. **Watchdog Thread Join Execution (Exit Code 134 Prevention)**:
   - Location: `system/linux_bridge/guest_ota_rollback_watchdog.h:47,50,56` and `guest_ota_rollback_watchdog.cpp:36-46,139-172`.
   - Replaced thread detachment with `stopWatchdogThread()` using atomic `mStopRequested`, `mCv.notify_all()`, and `mTimerThread.join()`.
   - Destructor `~BootWatchdogEngine()` calls `stopWatchdogThread()`, guaranteeing clean thread termination before deallocation.

5. **Empirical Execution Results**:
   - Full M5 Verification Suite (`./scripts/run_m5_verification.sh`): Exit code `0`, 14/14 features passed.
   - C++ Watchdog Thread Safety Stress Harness (5,000+ rapid lifecycles & multi-threaded concurrency): Exit code `0`, 0 crashes, 0 use-after-free, exit code 134 completely eliminated.
   - Java Remediation Adversarial Suite (`RemediationAdversarialTest.java` & `CameraRaceConditionTest.java`): Exit code `0`, downmix passthrough & race condition filter verified.

---

## 2. Logic Chain

1. **Camera2 Capture Session**:
   - *Observation*: `camera.createCaptureSession(...)` and `session.setRepeatingRequest(...)` are called inside `onOpened`.
   - *Logic*: Android Camera2 API requires continuous repeating requests to drive frame buffers to `ImageReader.getSurface()`. Registering this in `onOpened` ensures uninterrupted frame delivery to guest V4L2 loopback devices.

2. **Camera Open Race Filter**:
   - *Observation*: `mOpeningCameraId` is set synchronously before `mCameraManager.openCamera(...)`.
   - *Logic*: Asynchronous `AvailabilityCallback.onCameraUnavailable` triggers during camera open before `mActiveCameraId` is assigned. Checking `mOpeningCameraId` prevents the service from misinterpreting its own open request as external camera contention.

3. **Conditional Downmix**:
   - *Observation*: Downmix executes only when `mAudioRecordChannelConfig == AudioFormat.CHANNEL_IN_STEREO` and `session.channels == 1`.
   - *Logic*: When hardware input is already mono (`CHANNEL_IN_MONO`), downmixing 2-byte samples as 4-byte stereo frames corrupts sample frequencies by 2x. Conditional checking preserves raw mono audio integrity.

4. **Watchdog Thread Join**:
   - *Observation*: Destructor blocks on `mTimerThread.join()` after notifying `mCv` and setting `mStopRequested`.
   - *Logic*: Joining the thread guarantees the timer lambda exits before `this` goes out of scope, eliminating memory corruption and SIGABRT (exit code 134).

---

## 3. Caveats

- **No Caveats**: All 4 remediations have been verified both by code inspection and by direct empirical execution of full verification scripts and custom stress harnesses.

---

## 4. Conclusion

**Verdict: APPROVE**

Milestone M5 Iteration 3 remediation is fully verified, robust under stress, and meets all architectural requirements.

---

## 5. Verification Method

To independently re-verify:

1. **Run M5 Full Verification Suite**:
   ```bash
   ./scripts/run_m5_verification.sh
   ```
   *Expected Output*: Exit code `0`, `M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY`.

2. **Run C++ Native Watchdog Unit Test**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -pthread -I. \
       system/linux_bridge/guest_ota_rollback_watchdog.cpp \
       tests/unit/guest_ota_rollback_watchdog_test.cpp \
       -o build_out/bin/guest_ota_rollback_watchdog_test
   ./build_out/bin/guest_ota_rollback_watchdog_test
   ```
   *Expected Output*: Exit code `0`, `PASS: Guest Ota Rollback Watchdog Test Executed Successfully.`.

3. **Run Java Unit Test Suite**:
   ```bash
   java -cp build_out/classes tests.unit.LinuxPortalServiceTest
   ```
   *Expected Output*: Exit code `0`, `PASS: LinuxPortalServiceTest executed successfully.`.
