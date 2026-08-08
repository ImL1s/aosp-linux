# Handoff Report: Challenger 1 — Milestone M5 (Real System Hardware Portals - R5 Verification)

## Verdict: REJECT

---

## 1. Observation

Empirical testing and source code analysis of `LinuxPortalService.java` (`frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`) revealed **6 concrete defects**:

### 1.1 Audio Multi-Session Thread Hardcoded Closure Lockout [CRITICAL]
- **File**: `LinuxPortalService.java` (lines 359-373)
- **Observed Behavior**: `mAudioRecordThread` is instantiated inside `startMicStream` when `mAudioRecord == null`. The lambda captures the method parameter `sessionId` for the first session. On subsequent calls to `startMicStream` for additional sessions (e.g. `s2`), `mAudioRecord` is non-null, so no new thread is started. The running thread executes `mMicSessions.get(sessionId)` hardcoded to `s1`. When `stopMicStream("s1")` is called, `mMicSessions.remove("s1")` removes `s1`. In `mAudioRecordThread`, `mMicSessions.get("s1")` returns `null`. `processMicPcmFrame(null, pcm)` returns `new byte[0]`, permanently suppressing `sendVsockAudioPayload(processed)` for all remaining active sessions in `mMicSessions`.

### 1.2 Permanent Camera Session Death After Contention Ends [HIGH]
- **File**: `LinuxPortalService.java` (lines 326-334)
- **Observed Behavior**: When native Android camera usage starts, `setAndroidAppActiveForCamera(true)` sets `s.isActive = false` for all active `CameraSession` instances in `mCameraSessions`. However, when native camera usage ends and `setAndroidAppActiveForCamera(false)` is invoked, `mAndroidAppActiveForCamera` is reset to `false`, but `s.isActive` remains `false` and hardware camera streaming is not restored for guest sessions.

### 1.3 Coarse Location AppOps Hardcoding & Dead Obfuscation Code [HIGH]
- **File**: `LinuxPortalService.java` (lines 456, 466-473, 481-488, 541-548)
- **Observed Behavior**:
  1. In `requestLocationAccess(appId)` (line 456), `resolveAppOpOrPrompt(appId, OP_FINE_LOCATION)` is hardcoded. An application granted `OP_COARSE_LOCATION` but denied `OP_FINE_LOCATION` is rejected with `PermissionError`.
  2. `getObfuscatedLocation` (lines 481-488) is defined in `LinuxPortalService.java` but is never called anywhere in the service.
  3. `sendGeoClueLocationUpdate` (lines 541-548) and `onLocationChanged` (lines 466-473) stream raw, exact GPS coordinates (`location.getLatitude()`, `location.getLongitude()`) over GeoClue D-Bus without applying coarse obfuscation rounding for coarse location subscribers.

### 1.4 Missing Camera Resolution Input Sanitization [MEDIUM]
- **File**: `LinuxPortalService.java` (lines 260-264)
- **Observed Behavior**: `startCameraStream(appId, sessionId, requestedW, requestedH, requestedFps)` caps max dimensions at 1920x1080@30fps, but does not check for non-positive values. Invoking `startCameraStream("cam_app", "neg_s", -640, -480, -30)` produces a `CameraSession` object with `width = -640`, `height = -480`, which causes `ImageReader.newInstance` to throw `IllegalArgumentException` in SystemServer context.

### 1.5 Active Camera Stream Ignores USB Hot-Unplug Events [MEDIUM]
- **File**: `LinuxPortalService.java` (lines 322-324)
- **Observed Behavior**: `setHardwareCameraPluggedIn(false)` sets `mHardwareCameraPluggedIn = false`, but does not iterate through active camera sessions, set `s.isActive = false`, or close `ImageReader` / camera device listeners. Active listeners continue attempting frame capture on an unplugged device.

### 1.6 Missing AppOps Auditing & False Handoff Claim (`noteOpNoThrow`) [LOW/AUDIT]
- **File**: `LinuxPortalService.java` (line 193) vs `worker_m5_1/handoff.md` (Section 1.1)
- **Observed Behavior**: Worker 1 claimed in `handoff.md` that `noteOpNoThrow` was integrated for AppOps permission auditing. Inspection of `LinuxPortalService.java` shows 0 occurrences of `noteOpNoThrow`. `checkAppOp` only calls `unsafeCheckOpRaw` passing `Process.myUid()` (SystemServer UID instead of the target app's UID), which does not record access timestamps or app usage statistics in Android's AppOps auditing subsystem.

---

## 2. Logic Chain

1. **Audio Multi-Session Failure**:
   - `startMicStream("app1", "s1", ...)` -> `mAudioRecord` initialized -> `Thread` spawned with closure capturing `sessionId = "s1"`.
   - `startMicStream("app2", "s2", ...)` -> `mAudioRecord` non-null -> no new thread -> `mMicSessions` contains `s1` and `s2`.
   - `stopMicStream("s1")` -> `mMicSessions.remove("s1")` -> thread continues executing `mMicSessions.get("s1")` which returns `null`.
   - `processMicPcmFrame(null, pcm)` returns `new byte[0]` -> audio stream for `s2` is silenced permanently.

2. **Camera Contention Failure**:
   - `setAndroidAppActiveForCamera(true)` -> sets `s.isActive = false` for all sessions in `mCameraSessions`.
   - `setAndroidAppActiveForCamera(false)` -> sets `mAndroidAppActiveForCamera = false`, but leaves `s.isActive = false` in `mCameraSessions`.
   - Result: Guest camera sessions remain dead (`isActive == false`) after contention ends.

3. **Coarse Location Failure**:
   - `requestLocationAccess("coarse_app")` -> checks `OP_FINE_LOCATION` -> returns `false` / throws `PermissionError`.
   - `sendGeoClueLocationUpdate` receives raw coordinates -> streams `lat`, `lon` directly to vsock -> coarse obfuscation logic bypassed.

4. **Empirical Verification**:
   - Executed `EmpiricalPortalTester.java` harness. Confirmed all 6 defects with empirical assertions failing as expected.

---

## 3. Caveats

- `LinuxStorageProvider.java` path traversal protection and `LinuxManagerInternal` local service binding were verified to work correctly (`EmpiricalStorageTester.java` passed 100%).
- The basic single-session happy-path unit test (`LinuxPortalServiceTest.java`) passed because it only tested a single audio/camera session without multi-session teardown or post-contention recovery.

---

## 4. Conclusion

Milestone M5 (**Real System Hardware Portals**) is **REJECTED** due to critical defects in `LinuxPortalService.java`:
1. Audio multi-session streaming breaks whenever any prior audio session is stopped.
2. Guest camera sessions cannot recover after native Android app contention ends.
3. Coarse location AppOps checks fail and location obfuscation is bypassed.
4. Input validation and hardware hot-unplug handling are missing.
5. AppOps auditing (`noteOpNoThrow`) was not integrated as claimed.

Worker 1 must remediate `LinuxPortalService.java` to fix these 6 defects.

---

## 5. Verification Method

### 1. Run Empirical Challenger Portal Test Harness
```bash
javac -d build_out/classes -cp build_out/classes .agents/challenger_m5_1/EmpiricalPortalTester.java
java -cp build_out/classes tests.challenger.EmpiricalPortalTester
```
*Expected Output upon successful remediation*: `=== Harness Complete. Total Empirical Bugs Confirmed: 0 ===`

### 2. Run Full M5 Verification Suite
```bash
./scripts/run_m5_verification.sh
```

### 3. Invalidation Conditions
- Any audio session losing PCM data when another session stops.
- Any camera session failing to resume (`isActive == true`) after `setAndroidAppActiveForCamera(false)`.
- Any location request for an app with `OP_COARSE_LOCATION` throwing `PermissionError` or streaming raw high-precision coordinates over GeoClue D-Bus.
