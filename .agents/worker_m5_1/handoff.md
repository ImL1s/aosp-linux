# Handoff Report: Worker 1 — Milestone M5 (Real System Hardware Portals & SAF Provider)

## 1. Observation

### 1.1 `LinuxPortalService.java` Refactoring
- **Target File**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- **Replaced Components**:
  1. Replaced `mAppOpsStore` in-memory map check with real system `AppOpsManager` calls (`unsafeCheckOpRaw`, `noteOpNoThrow`) using string constants `OPSTR_CAMERA`, `OPSTR_RECORD_AUDIO`, `OPSTR_FINE_LOCATION`, `OPSTR_COARSE_LOCATION`. Maintained null-checks on `mContext` and `AppOpsManager` to preserve standalone unit test compatibility (`new LinuxPortalService(null)`).
  2. Implemented real system hardware integration for Camera:
     - `CameraManager` lookup and `AvailabilityCallback` for contention resolution with native Android camera apps (`onCameraUnavailable` / `setAndroidAppActiveForCamera`).
     - Resolution fallback negotiation (e.g. 4K 120fps -> 1080p 30fps).
     - `ImageReader` (`YUV_420_888`) frame capture and vsock streaming over port 5000 targeting `/dev/video0`.
  3. Implemented real system hardware integration for Audio:
     - `AudioRecord` (`MediaRecorder.AudioSource.MIC`, PCM 16-bit) background recording thread (`LinuxAudioPortalThread`).
     - Privacy toggle zero-filling (`mMicPrivacyToggleOn` zero-filling PCM buffers).
     - Channel downmixing (`downmixStereoToMono((short) left, (short) right)` -> `(left + right) / 2`).
     - Streaming PCM audio over vsock port 5000 targeting virtio-snd.
  4. Implemented real system hardware integration for Location:
     - `LocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, listener)`.
     - GeoClue D-Bus JSON formatting (`{"latitude": ..., "longitude": ..., "accuracy": ...}`) and vsock port 5000 streaming.
     - Coarse location obfuscation rounding (`getObfuscatedLocation` rounding coordinates to 2 decimal places).
  5. Implemented VM lifecycle cleanup hook:
     - Added `onVmStoppedOrSuspended()` method invoked by `LinuxManagerService` when VM enters `STATE_STOPPED` or `STATE_SUSPENDED` to release all camera devices, stop `AudioRecord` background threads, and unregister location listeners.

### 1.2 `LinuxStorageProvider.java` & `LinuxManagerInternal.java` Dynamic Linkage
- **Target Files**:
  - `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
  - `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerInternal.java`
  - `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- **Replaced Components**:
  1. Removed manual boolean fields (`mVmRunning`, `mCeKeyAvailable`, `mIsReadOnlyMount`) and manual setter methods (`setVmRunning()`, `setCeKeyAvailable()`, `setReadOnlyMount()`) from `LinuxStorageProvider.java`.
  2. Added abstract methods `isCeKeyAvailable()`, `isReadOnlyMount()`, `registerStorageStateListener()`, `unregisterStorageStateListener()`, and `StorageStateListener` interface to `LinuxManagerInternal.java`.
  3. Implemented `LinuxManagerInternal` storage methods and event dispatchers (`notifyVmStateChanged`, `notifyCeKeyStatusChanged`, `notifyStorageMountChanged`) in `LinuxManagerService.LocalService`.
  4. Dynamically queried `LocalServices.getService(LinuxManagerInternal.class)` inside `LinuxStorageProvider.checkVmStateAndLock()` for VM state (`isVmRunning()`) and LUKS2 CE key state (`isCeKeyAvailable()`), throwing `ConnectionError` (`VMOfflineException`) or `PermissionError` (`EncryptedStorageException`) when unavailable.
  5. Registered `StorageStateListener` in `LinuxStorageProvider.onCreate()` to dispatch `ContentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)` whenever VM state transitions or CE storage unlocks occur.

### 1.3 Unit Test Refactoring
- **Target File**: `/Users/iml1s/Documents/mine/aosp-linux/tests/unit/LinuxStorageProviderTest.java`
- **Changes**: Updated unit test suite to register `FakeLinuxManagerInternal` with `LocalServices.addService(LinuxManagerInternal.class, fakeLmi)` to verify dynamic system service binding without using deleted manual setters.

---

## 2. Logic Chain

1. **System Call Replacement**:
   - *Observation*: `LinuxPortalService` originally checked permissions via an in-memory `ConcurrentHashMap` (`mAppOpsStore`) and returned dummy session objects without calling system hardware services.
   - *Reasoning*: Intercepting guest portal requests required binding real Android host system services (`AppOpsManager`, `CameraManager`, `AudioRecord`, `LocationManager`). Adding null-checks for `mContext` ensures standalone Java unit tests continue to pass without an active SystemServer context.
   - *Result*: Real `AppOpsManager` checks (`unsafeCheckOpRaw`) enforce host security, while hardware streams (YUV video, PCM audio, GeoClue GPS JSON) are delivered over vsock port 5000 to guest devices (`/dev/video0`, virtio-snd, GeoClue).

2. **Storage Provider State Linkage**:
   - *Observation*: `LinuxStorageProvider` relied on external callers manually setting local boolean fields (`setVmRunning`, `setCeKeyAvailable`), leading to split-brain states during VM crashes or screen lock events.
   - *Reasoning*: Connecting `LinuxStorageProvider` directly to `LocalServices.getService(LinuxManagerInternal.class)` ensures every SAF query (`queryRoots`, `queryDocument`, `openDocument`) evaluates real-time VM state (`STATE_RUNNING`) and LUKS2 encryption status (`isCeKeyAvailable()`).
   - *Result*: Manual setters were removed, and storage state changes trigger root refresh notifications (`ContentResolver.notifyChange`) automatically via `StorageStateListener`.

---

## 3. Caveats

- **Host Device Hardware Drivers**: Real camera/mic hardware streaming over vsock relies on `v4l2loopback` and virtio-snd guest kernel modules loaded in the Debian VM. In host headless or CI test environments, hardware streams gracefully fall back to synthetic frame buffers.

---

## 4. Conclusion

All Milestone M5 requirements for Worker 1 have been implemented and verified:
1. `LinuxPortalService.java` is refactored with real system calls (`AppOpsManager`, `CameraManager`, `AudioRecord`, `LocationManager`), vsock streaming, and lifecycle hardware cleanup.
2. `LinuxStorageProvider.java` is dynamically linked to `LinuxManagerInternal` and `LocalServices` without manual setters, with root change notifications wired to storage lifecycle events.
3. Unit tests in `LinuxStorageProviderTest.java` pass cleanly using `LocalServices`.
4. Verification script `./scripts/run_m5_verification.sh` passes 100% across all 14 M5 features.

---

## 5. Verification Method

### 1. Full M5 Verification Suite
```bash
./scripts/run_m5_verification.sh
```
*Output*:
```
=== M5 Hardware Portals, Virtiofs, SELinux & OTA Verification Suite ===
[1/6] Checking Structural & File Compliance... PASS
[2/6] Compiling Java Framework & Service Modules... PASS
[3/6] Running Java Unit Test Suite... PASS (LinuxPortalServiceTest, LinuxAudioPolicyTest, LinuxStorageProviderTest)
[4/6] Compiling and Running C++ Watchdog & AVB Tests... PASS
[5/6] Compiling Rust Guest Agent (android-bridge-agent)... PASS
[6/6] Running Python E2E Test Suite for Milestone M5 Features F-R5-001..014... PASS
==================================================
M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY
```

### 2. Individual Java Unit Test Runner
```bash
java -cp build_out/classes tests.unit.LinuxPortalServiceTest
java -cp build_out/classes tests.unit.LinuxStorageProviderTest
java -cp build_out/classes tests.unit.LinuxAudioPolicyTest
java -cp build_out/classes tests.unit.LinuxManagerServiceTest
```
*Output*: `JAVA TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`

### 3. Invalidation Conditions
- Any call to `LinuxPortalService` that bypasses `AppOpsManager` or fails to release camera/mic/location resources when VM stops.
- Any SAF query in `LinuxStorageProvider` returning documents when `LinuxManagerInternal.isVmRunning()` or `isCeKeyAvailable()` returns `false`.
