# Forensic Audit Report: Milestone M5 Iteration 2 (Real System Hardware Portals - R5)

**Work Product**: Milestone M5 Iteration 2 Implementation and Test Suite  
**Target Files Audited**:
- `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
**Integrity Mode**: Development (from `ORIGINAL_REQUEST.md`)  
**Verdict**: **CLEAN**

---

## 1. Observation

1. **AppOps `noteOpNoThrow` Security Auditing**:
   - `LinuxPortalService.java` (lines 202-216) defines `noteAppOp(String appId, String op)`, invoking `mContext.getSystemService(AppOpsManager.class).noteOpNoThrow(opStr, uid, appId)`.
   - Invocations verified at:
     - Line 285 in `requestCameraAccess(String appId)`: `noteAppOp(appId, OP_CAMERA);`
     - Line 430 in `requestMicrophoneAccess(String appId)`: `noteAppOp(appId, OP_RECORD_AUDIO);`
     - Line 571 in `requestLocationAccess(String appId)`: `noteAppOp(appId, fineAllowed ? OP_FINE_LOCATION : OP_COARSE_LOCATION);`

2. **Camera2 HAL Hardware Binding (`openCamera`)**:
   - `LinuxPortalService.java` (lines 312-361) implements `openHardwareCamera(int width, int height)`, invoking `mCameraManager.openCamera(cameraId, stateCallback, mCameraHandler)`.
   - Invocations verified at:
     - Line 305 in `startCameraStream(...)`: `openHardwareCamera(finalW, finalH);`
     - Line 419 in `setAndroidAppActiveForCamera(false)`: `openHardwareCamera(maxW > 0 ? maxW : 1920, maxH > 0 ? maxH : 1080);` upon camera contention clearance.
   - `AvailabilityCallback.onCameraUnavailable(cameraId)` (lines 169-173) checks `if (mActiveCameraId != null && mActiveCameraId.equals(cameraId))` to ignore self-cancellation callbacks.

3. **Audio Downmixing (`downmixStereoToMono`)**:
   - `LinuxPortalService.java` (lines 512-514) implements:
     ```java
     public short downmixStereoToMono(short left, short right) {
         return (short) ((left + right) / 2);
     }
     ```
   - Invocation verified at line 495 inside `processMicPcmFrame(MicSession session, byte[] rawInput)`:
     ```java
     if (session.channels == 1 && rawInput.length >= 4) {
         ...
         short mono = downmixStereoToMono(left, right);
         ...
     }
     ```
   - Multi-session iteration verified at lines 454-461 inside `mAudioRecordThread`: iterates over `mMicSessions.values()` rather than hardcoding session IDs.

4. **Coarse Location Obfuscation (`getObfuscatedLocation`)**:
   - `LinuxPortalService.java` (lines 620-627) implements:
     ```java
     public double[] getObfuscatedLocation(double exactLat, double exactLon, boolean isCoarseOnly) {
         if (isCoarseOnly) {
             double coarseLat = Math.round(exactLat * 100.0) / 100.0;
             double coarseLon = Math.round(exactLon * 100.0) / 100.0;
             return new double[]{coarseLat, coarseLon};
         }
         return new double[]{exactLat, exactLon};
     }
     ```
   - Invocation verified at line 599 inside `LocationListener.onLocationChanged(Location location)`:
     ```java
     for (LocationSession session : mLocationSessions.values()) {
         if (session.isActive) {
             double[] coords = getObfuscatedLocation(rawLat, rawLon, session.isCoarseOnly);
             ...
         }
     }
     ```
   - `requestLocationAccess(String appId)` (lines 565-570) allows access if either `OP_FINE_LOCATION` or `OP_COARSE_LOCATION` is granted.

5. **Storage Access Framework Provider (`LinuxStorageProvider.java`)**:
   - `checkVmStateAndLock()` (lines 108-121) verifies `lmi.isVmRunning()` (throws `ConnectionError("VMOfflineException...")` if false) and `lmi.isCeKeyAvailable()` (throws `PermissionError("EncryptedStorageException...")` if false).
   - `getFileForDocId(String documentId)` (lines 135-187) blocks system roots (`/sys`, `/proc`, `/etc`, `/dev`), maps `home/user` and `mnt/shared`, and enforces canonical path traversal checks (`!canonicalTarget.startsWith(canonicalBase + File.separator)`).
   - `openDocument(...)` (lines 243-264) enforces read-only mount checks (`isReadOnlyMount() && isWriteRequested`) throwing `SecurityException`.

6. **Empirical Execution & Log Authenticity Verification**:
   - Executed `./scripts/run_m5_verification.sh` directly in environment:
     - Java compilation & unit tests (`LinuxPortalServiceTest`, `LinuxAudioPolicyTest`, `LinuxStorageProviderTest`): PASSED.
     - C++ native tests (`guest_ota_rollback_watchdog_test`, `avb_verifier_test`): PASSED.
     - Rust guest agent (`android-bridge-agent`): PASSED.
     - Python E2E Tier 1 & Tier 2 tests (F-R5-001..014): PASSED.
     - Final summary output: `M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY`.

---

## 2. Logic Chain

1. **Hardcoded Test Return & Facade Detection**:
   - Inspection of `LinuxPortalService.java` and `LinuxStorageProvider.java` shows no hardcoded pass flags, dummy returns, or empty facades. All permission methods, hardware bindings, audio downmixing calculations, location obfuscations, and storage boundary checks compute outputs dynamically from real runtime state.

2. **Wiring and Invocation of Required Methods**:
   - `noteOpNoThrow`: Directly invoked via `noteAppOp` inside all 3 hardware portal access functions (`requestCameraAccess`, `requestMicrophoneAccess`, `requestLocationAccess`).
   - `openCamera`: Directly invoked via `openHardwareCamera` during stream creation (`startCameraStream`) and contention recovery (`setAndroidAppActiveForCamera(false)`). Self-cancellation is filtered via `mActiveCameraId` matching.
   - `downmixStereoToMono`: Directly invoked in `processMicPcmFrame` when `session.channels == 1` and raw PCM input is 16-bit stereo.
   - `getObfuscatedLocation`: Directly invoked in `LocationListener.onLocationChanged` for each active location session based on `session.isCoarseOnly`.

3. **Authenticity of Verification Outputs**:
   - Clean execution of `./scripts/run_m5_verification.sh` was performed directly. The test logs, binary output, and process return codes (exit code 0) were generated dynamically during execution without pre-populated artifact manipulation.

---

## 3. Caveats

- **Null Context Handling in Unit Tests**: `LinuxPortalService` and `LinuxStorageProvider` include null-checks for `mContext`, `mCameraManager`, `mLocationManager`, and `mAppOpsManager` to enable execution in standalone JVM unit test environments where full AOSP SystemServer framework context is not initialized. Operational logic falls back safely to in-memory state tracking under unit test mode.

---

## 4. Conclusion

- **Hardcoded Test Returns**: None found.
- **Facade Implementations**: None found.
- **Dead Helper Functions**: All key methods (`noteOpNoThrow`, `openCamera`, `downmixStereoToMono`, `getObfuscatedLocation`) are genuinely wired and invoked during operational flows.
- **Test Authenticity**: Confirmed through empirical test execution.

**Final Verdict**: **CLEAN**

---

## 5. Verification Method

To independently verify this forensic audit:

1. **Run Full M5 Verification Suite**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux
   ./scripts/run_m5_verification.sh
   ```
   *Expected Output*: `M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY`

2. **Inspect Wiring in `LinuxPortalService.java`**:
   - `noteOpNoThrow`: Lines 210, 285, 430, 571
   - `openCamera`: Lines 305, 338, 419
   - `downmixStereoToMono`: Lines 495, 512-514
   - `getObfuscatedLocation`: Lines 599, 620-627
