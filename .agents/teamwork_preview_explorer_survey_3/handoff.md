# Explorer 3 Survey Report: Defect R5 & Defect R6

## 1. Observation

### 1.1 Defect R5: Real System Hardware Portals & Storage Lifecycle

#### Target File 1: `LinuxPortalService.java`
- **Location**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- **Observation Details**:
  1. **In-Memory AppOps Map** (Lines 45, 111–122):
     ```java
     private final Map<String, Map<String, String>> mAppOpsStore = new ConcurrentHashMap<>();
     
     public void setAppOp(String appId, String op, String mode) {
         mAppOpsStore.computeIfAbsent(appId, k -> new ConcurrentHashMap<>()).put(op, mode);
     }
     
     public String checkAppOp(String appId, String op) {
         Map<String, String> ops = mAppOpsStore.get(appId);
         if (ops != null && ops.containsKey(op)) {
             return ops.get(op);
         }
         return MODE_PROMPT;
     }
     ```
     *Defect*: `LinuxPortalService` checks permissions against an internal hash map `mAppOpsStore` instead of calling Android system `AppOpsManager` (`context.getSystemService(AppOpsManager.class)` or `AppOpsManager.noteOpNoThrow()`).
  2. **In-Memory Dummy Session Models** (Lines 55–104):
     ```java
     public static class CameraSession { ... }
     public static class MicSession { ... }
     public static class LocationSession { ... }
     ```
     `CameraSession`, `MicSession`, and `LocationSession` are plain in-memory structs storing mock fields (`isActive`, `isRecording`, `lastLatitude`, `lastLongitude`).
  3. **Camera Streaming Simulation** (Lines 142–181):
     `startCameraStream()` clamps resolution values (e.g. 4K -> 1080p@30fps) and checks boolean flags `mHardwareCameraPluggedIn` and `mAndroidAppActiveForCamera`, but **never connects to Android `CameraManager` or `CameraDevice`** to capture real video hardware frames or pipe them to V4L2 loopback.
  4. **Microphone Capture Simulation** (Lines 197–232):
     `processMicPcmFrame()` zero-fills or pads caller-supplied `byte[] rawInput` depending on `mMicPrivacyToggleOn`, but **never instantiates `android.media.AudioRecord`** to capture real PCM hardware audio from host microphones.
  5. **Location Tracking Simulation** (Lines 260–288):
     `getObfuscatedLocation()` rounds float values passed as arguments, but **never registers a `LocationListener` with `android.location.LocationManager`** to receive real GPS position fixes.

#### Target File 2: `LinuxStorageProvider.java`
- **Location**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
- **Observation Details**:
  1. **Manual In-Memory Boolean Flags** (Lines 67–87):
     ```java
     private boolean mVmRunning = true;
     private boolean mCeKeyAvailable = true;
     private boolean mIsReadOnlyMount = false;
     
     public void setVmRunning(boolean running) { mVmRunning = running; }
     public void setCeKeyAvailable(boolean available) { mCeKeyAvailable = available; }
     public void setReadOnlyMount(boolean readOnly) { mIsReadOnlyMount = readOnly; }
     ```
     *Defect*: `LinuxStorageProvider` relies on manual external calls to setter methods to update VM state and encryption unlock state, instead of querying `LinuxManagerService` or listening to `LinuxCeKeyManager` / `vold` LUKS2 volume mount/unmount events.
  2. **Hardcoded Base Directory Paths** (Lines 120, 124, 163, 164):
     `/data/linux/home/user` and `/data/media/0/LinuxShared` are hardcoded strings without dynamic verification of virtiofs mount status.

#### Target File 3: `LinuxAudioPolicyHandler.java`
- **Location**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxAudioPolicyHandler.java`
- **Observation Details**:
  - Interfaces with `AudioManager` and `AudioFocusRequest` when `context != null` (lines 63–85), but falls back to mock `mCurrentFocusState = "GAIN"` when `context == null` (lines 87–91).

#### Target File 4: SELinux Policy (`linux_portal.te`)
- **Location**: `/Users/iml1s/Documents/mine/aosp-linux/system/sepolicy/private/linux_portal.te`
- **Observation Details**:
  - Rules exist for binder calls to `appops_service`, `camera_service`, `audioserver_service`, and `location_service` (lines 22–25). System permissions are granted in policy, but service logic in `LinuxPortalService.java` is missing actual binder calls to these services.

---

### 1.2 Defect R6: Clean & Honest E2E Test Suite

#### Target File 1: CI Workflow (`ci.yml`)
- **Location**: `/Users/iml1s/Documents/mine/aosp-linux/.github/workflows/ci.yml`
- **Observation Details** (Line 33):
  ```yaml
  - name: Verify Architecture Blueprints & Test Specs
    run: |
      python3 -c "import json; data=json.load(open('tests/e2e_report.json')); print('E2E Verification Total:', data['summary']['total']); assert data['summary']['failed'] == 0"
  ```
  *Defect*: The CI pipeline does **NOT** execute the E2E test runner or unit test binaries. Instead, it reads a pre-written static JSON file `tests/e2e_report.json` and asserts that `"failed" == 0`.

#### Target File 2: Static Test Report (`e2e_report.json`)
- **Location**: `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json`
- **Observation Details**:
  - Contains 4,744 lines of pre-generated static JSON results reporting 430 passes out of 430 total tests.

#### Target File 3: Mock Environment (`mock_env.py`)
- **Location**: `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/framework/mock_env.py`
- **Observation Details**:
  - Defines purely in-memory Python mock objects: `MockVsockBridge`, `MockSystemServer`, `MockSommelier`, `MockXdgPortal`, `MockEnvironment`.
  - Line 193 hardcodes CTS test results:
    ```python
    self.cts_results = {"passed": 170, "failed": 0}
    ```
  - Hardcodes SELinux rules, mount maps, AVB digests, and neverallow arrays directly in Python dictionary fields.

#### Target File 4: Fake Passes in E2E Test Files
- **Location**: `tests/e2e/tier1_feature_coverage/`, `tests/e2e/tier2_boundary_corner/`, `tests/e2e/tier3_cross_feature/`, `tests/e2e/tier4_real_world/`
- **Observation Details**:
  1. `tests/e2e/tier1_feature_coverage/test_m5_tier1.py`:
     - Line 65 (`T1-119`): `CustomAssertions.assert_equal("/dev/video0", "/dev/video0")`
     - Line 76 (`T1-120`): `delivered_frames = 5; CustomAssertions.assert_true(delivered_frames > 0)`
     - Line 128 (`T1-124`): `pcm_chunk = b"\x00\x7f" * 512; CustomAssertions.assert_equal(len(pcm_chunk), 1024)`
     - Line 190 (`T1-129`): Dict assertion on `{"Latitude": 25.0330}`
     - Line 450 (`T1-150`): `read_speed_mbps = 1200; CustomAssertions.assert_true(read_speed_mbps > 500)`
     - Line 624 (`T1-165`): `checkpolicy_exit_code = 0; CustomAssertions.assert_equal(checkpolicy_exit_code, 0)` (never executes `checkpolicy` binary)
     - Line 661 (`T1-168`): `vts_compliant = True; CustomAssertions.assert_true(vts_compliant)`
     - Line 683 (`T1-170`): `verifier_status = "PASS"; CustomAssertions.assert_equal(verifier_status, "PASS")`
  2. `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`:
     - Line 41–44 (`T2-117`): Checks local python booleans `camera_open = True; app_running = False; if not app_running: camera_open = False`.
     - Line 86–91 (`T2-120`): Defines a local dummy function `get_video_frame()` that raises `ConnectionError`, then asserts that calling it raises `ConnectionError`.
  3. `tests/e2e/tier1_feature_coverage/test_m1_tier1.py`:
     - Line 22–27 (`T1-01`): Asserts `"android.system.linux.LinuxManager".split(".")`.
     - Line 50–58 (`T1-03`): Asserts local Python dictionary getters.

#### Target File 5: Test Execution Scripts
- **Location**: `tests/e2e/runner.py`, `scripts/run_m5_verification.sh`
- **Observation Details**:
  - `scripts/run_m5_verification.sh:90-119` executes `python3 tests/e2e/runner.py` with stdout redirected to `/dev/null` (`> /dev/null`). `runner.py` only instantiates `MockEnvironment()` and runs the pure Python tautological test cases.

---

## 2. Logic Chain

1. **R5 Hardware Portals Logic**:
   - *Observation*: `LinuxPortalService.java` maintains internal `ConcurrentHashMap mAppOpsStore` and dummy structs (`CameraSession`, `MicSession`, `LocationSession`).
   - *Reasoning*: Because `LinuxPortalService` checks permissions against `mAppOpsStore` rather than Android's `AppOpsManager`, any guest app can bypass Android system permission prompts if `setAppOp()` is called or defaulted. Because no `CameraManager`, `AudioRecord`, or `LocationManager` calls exist, hardware hardware streaming is completely fake.
   - *Observation*: `LinuxStorageProvider.java` relies on `setVmRunning()`, `setCeKeyAvailable()`, and `setReadOnlyMount()` setters.
   - *Reasoning*: Because SAF provider state is managed via manual in-memory boolean setters, system storage events (VM power-off, LUKS2 CE volume lock/unlock via `vold`/`LinuxCeKeyManager`) will not update SAF accessibility, risking security breaches or stale document provider states.

2. **R6 E2E Test Suite Logic**:
   - *Observation*: `.github/workflows/ci.yml` line 33 reads static `tests/e2e_report.json` instead of running `tests/e2e/runner.py` or actual test commands.
   - *Reasoning*: CI passes without running a single line of application code or test code, concealing all real defects.
   - *Observation*: `tests/e2e/framework/mock_env.py` contains in-memory mock objects, and test files in `tests/e2e/tier*/` evaluate hardcoded string comparisons and local Python variables.
   - *Reasoning*: The test suite tests Python variable assignments rather than IPC socket frames, binary executions, or system service AIDL calls. A 100% pass rate is guaranteed even if production Java, C++, and Rust code is completely broken or missing.

---

## 3. Caveats

- This investigation is read-only. No source code edits were performed.
- Full E2E execution of `CameraManager`/`Camera2`, `AudioRecord`, and `LocationManager` on non-Android host environments (such as macOS macOS developer machines) requires socket-based test harnesses or Android emulator environments. Test runners must account for headless/CI socket fallback where physical hardware is absent.

---

## 4. Conclusion

### Defect R5 Summary:
- `LinuxPortalService.java` uses simulated in-memory models (`mAppOpsStore`, `CameraSession`, `MicSession`, `LocationSession`) without real system calls to `AppOpsManager`, `CameraManager`/`Camera2`, `AudioRecord`, and `LocationManager`.
- `LinuxStorageProvider.java` uses manual boolean setters (`setVmRunning`, `setCeKeyAvailable`, `setReadOnlyMount`) instead of binding dynamically to `LinuxManagerService` VM lifecycle and `vold`/`LinuxCeKeyManager` LUKS2 mount events.

### Defect R6 Summary:
- CI workflow (`ci.yml:33`) asserts against a static `tests/e2e_report.json` file.
- The E2E framework (`mock_env.py`) and test suites (`test_m1_tier1.py`, `test_m5_tier1.py`, `test_m5_tier2.py`, etc.) execute tautological Python checks on local variables and literal strings rather than real socket/IPC/system checks.

---

## 5. Remediation & Verification Method

### Remediation Plan for R5:
1. **`LinuxPortalService.java`**:
   - Replace `mAppOpsStore` with calls to `mContext.getSystemService(AppOpsManager.class)` (`noteOpNoThrow`, `unsafeCheckOpRaw`).
   - Implement `CameraManager` binding: open camera devices via `CameraManager.openCamera()`, handle frames, and pipe to V4L2 loopback / vsock stream.
   - Implement `AudioRecord` binding: capture PCM audio using `AudioRecord.read()` and stream over vsock to guest PipeWire/ALSA bridge.
   - Implement `LocationManager` binding: request updates via `LocationManager.requestLocationUpdates()`, format GeoClue / NMEA JSON, and stream over vsock.
2. **`LinuxStorageProvider.java`**:
   - Remove manual setters (`setVmRunning`, `setCeKeyAvailable`).
   - Bind `queryRoots` and `queryDocument` state directly to `LinuxManagerService.getVmState()` and `LinuxCeKeyManager.isCeKeyUnlocked()`.
   - Validate virtiofs mount status before resolving document IDs.

### Remediation Plan for R6:
1. **CI Workflow (`ci.yml`)**:
   - Remove line 33 static JSON check.
   - Add step to execute `python3 tests/e2e/runner.py --tier 1` and `tests/e2e/runner.py --tier 2` directly.
2. **E2E Test Runner & Suite**:
   - Update `MockEnvironment` to launch local test socket servers or spawn real daemon binaries (`linux_bridge`, `android-bridge-agent`).
   - Rewrite test cases in `test_m1_tier1.py` through `test_m5_tier1.py` and `test_m5_tier2.py` to send real IPC binary packets over LocalSockets (`/dev/socket/linux_bridge` or vsock sockets), assert real packet responses, and check binary return codes (e.g. running `checkpolicy` for SELinux rules).

### Verification Commands:
- Compile Java framework:
  `javac -d build_out/classes $(find frameworks/base -name "*.java")`
- Run Java unit tests:
  `java -cp build_out/classes tests.unit.LinuxPortalServiceTest`
  `java -cp build_out/classes tests.unit.LinuxStorageProviderTest`
- Run E2E test suite:
  `python3 tests/e2e/runner.py --tier 1`
