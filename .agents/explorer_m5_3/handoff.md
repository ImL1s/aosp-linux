# Milestone M5 Investigation Report: Hardware Portals (R5) & Verification Architecture

## 1. Observation

### 1.1 `LinuxPortalService` Integration Boundaries

- **File Location**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`

#### 1. AppOpsManager Integration (Lines 44–46, 110–139)
- **Current Observation**:
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
- **Defect & Missing Boundary**: `LinuxPortalService` maintains an in-memory hash map (`mAppOpsStore`) to query and set permissions (`OP_CAMERA`, `OP_RECORD_AUDIO`, `OP_FINE_LOCATION`). It does not invoke Android system `AppOpsManager` (`mContext.getSystemService(AppOpsManager.class)` or `AppOpsManager.noteOpNoThrow()`).
- **Target Contract**: `LinuxPortalService` must query system `AppOpsManager` via `noteOpNoThrow(AppOpsManager.OP_*, callingUid, packageName)` or `unsafeCheckOpRaw()`. When AppOps returns `MODE_ERRORED` or `MODE_IGNORED`, portal requests must be denied or routed to `LinuxPermissionActivity.launchPrompt(mContext, appId, op)`.

#### 2. CameraManager Integration (Lines 55–71, 142–195)
- **Current Observation**:
  ```java
  public CameraSession startCameraStream(String appId, String sessionId, int requestedW, int requestedH, int requestedFps) {
      if (!requestCameraAccess(appId)) return null;
      int finalW = (requestedW > 1920) ? 1920 : requestedW;
      int finalH = (requestedH > 1080) ? 1080 : requestedH;
      int finalFps = (requestedFps > 60) ? 30 : requestedFps;
      CameraSession session = new CameraSession(appId, sessionId, finalW, finalH, finalFps);
      mCameraSessions.put(sessionId, session);
      return session;
  }
  ```
- **Defect & Missing Boundary**: `startCameraStream()` performs resolution clamping in memory and returns a dummy `CameraSession` struct. No call is made to `android.hardware.camera2.CameraManager.openCamera()` or `CameraDevice`. No video frames are captured or pushed over vsock to guest `v4l2loopback` (`/dev/video0`).
- **Target Contract**: Must acquire `CameraManager` from `mContext`, open hardware camera via `openCamera()`, capture frames using `ImageReader` / `SurfaceTexture`, format frames to YUYV / MJPEG, and pipe them over vsock port 5000 (Portal Vsock Channel) to guest `bridge-agent` / `v4l2loopback`. Handle camera contention when native Android apps request camera focus by invoking `setAndroidAppActiveForCamera(true)` to close guest streams.

#### 3. AudioRecord Integration (Lines 73–89, 197–258)
- **Current Observation**:
  ```java
  public byte[] processMicPcmFrame(MicSession session, byte[] rawInput) {
      if (session == null || !session.isRecording || !session.isForeground) return new byte[0];
      if (mMicPrivacyToggleOn) {
          byte[] silence = new byte[rawInput.length];
          Arrays.fill(silence, (byte) 0);
          return silence;
      }
      ...
  }
  ```
- **Defect & Missing Boundary**: `processMicPcmFrame()` only manipulates caller-provided byte arrays in memory (zero-filling on privacy toggle or padding underflows). It does not instantiate `android.media.AudioRecord` to record real PCM audio from the device microphone.
- **Target Contract**: Must instantiate `android.media.AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)`, launch a background recording thread calling `audioRecord.read()`, enforce privacy toggle (zero-filling PCM buffers), interact with `LinuxAudioPolicyHandler` for `AudioFocus` requests, and stream PCM audio to guest PipeWire / ALSA virtual soundcard (`virtio-snd`).

#### 4. LocationManager Integration (Lines 91–104, 260–289)
- **Current Observation**:
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
- **Defect & Missing Boundary**: `getObfuscatedLocation()` rounds input doubles. It does not register a `LocationListener` with `android.location.LocationManager` to receive real GPS fix updates.
- **Target Contract**: Must call `LocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, minDistance, listener)`. On location update, format location data into NMEA / GeoClue D-Bus structure (`{"latitude": ..., "longitude": ..., "accuracy": ...}`), apply coarse obfuscation if restricted by AppOps (`OP_COARSE_LOCATION`), and stream over vsock port 5000 to guest GeoClue daemon.

#### 5. LinuxManagerService Lifecycle Interaction
- **Current Observation**: `LinuxPortalService` operates as an independent class without direct listeners or callbacks registered on `LinuxManagerService` or `LinuxManagerInternal`.
- **Target Contract**: When `LinuxManagerService` transitions VM state to `STATE_STOPPED`, `STATE_SUSPENDED`, or `STATE_ERROR`, `LinuxPortalService` must be notified to release all hardware resources (close `CameraDevice`, stop `AudioRecord`, unregister `LocationListener`, and wipe session maps).

---

### 1.2 `LinuxStorageProvider` Integration Boundaries

- **File Location**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`

#### 1. Integration with `LinuxManagerService` VM Lifecycle (Lines 67–102)
- **Current Observation**:
  ```java
  private boolean mVmRunning = true;
  private boolean mCeKeyAvailable = true;
  private boolean mIsReadOnlyMount = false;

  public void setVmRunning(boolean running) { mVmRunning = running; }
  public void setCeKeyAvailable(boolean available) { mCeKeyAvailable = available; }
  ```
- **Defect & Missing Boundary**: `LinuxStorageProvider` relies on manual external calls to setter methods (`setVmRunning`, `setCeKeyAvailable`) to update state.
- **Target Contract**: `LinuxStorageProvider` must obtain `LinuxManagerInternal` local service (`LocalServices.getService(LinuxManagerInternal.class)`). Inside `checkVmStateAndLock()`, it must dynamically query `LinuxManagerInternal.getVmState() == LinuxManager.STATE_RUNNING` and `LinuxManagerInternal.isCeKeyAvailable()`. If the VM is offline or LUKS2 volume is locked, SAF queries (`queryRoots`, `queryDocument`, `openDocument`) must fail immediately with `VMOfflineException` or `EncryptedStorageException`.

#### 2. Integration with `LinuxCeKeyManager` and `vold` (Lines 98–102, 120–125)
- **Current Observation**:
  ```java
  if (!mCeKeyAvailable) {
      throw new PermissionError("EncryptedStorageException: CE storage volume is locked");
  }
  ```
- **Defect & Missing Boundary**: Base paths `/data/linux/home/user` and `/data/media/0/LinuxShared` are hardcoded without verifying if the underlying LUKS2 container `/data/misc/linux/user_home.img` was decrypted by `LinuxCeKeyManager` (`cryptsetup open`) to `/dev/mapper/user_home_decrypted` and mounted via virtiofs.
- **Target Contract**: `LinuxStorageProvider` must query `LinuxCeKeyManager` / `vold` mount status. When an Android user unlocks the device (`onUserUnlocking`), `LinuxManagerService` derives the 512-bit key via HKDF-SHA256 from the CE key and mounts `/dev/mapper/user_home_decrypted`. `LinuxStorageProvider` must only expose `/home/user` roots when this LUKS2 volume is active. On device lock (`onUserLocked`), the key is zeroed from memory, the mapper closed, and `LinuxStorageProvider` revokes all active document PFDs.

---

### 1.3 Build and Test Target Architecture

#### 1. AOSP Module Build Targets (`Android.bp`)
- **Root `Android.bp`**:
  - `java_sdk_library` `"android.system.linux"`: Compiles public AIDLs (`ILinuxManager.aidl`, `ILinuxStatusCallback.aidl`, `ILinuxTerminalCallback.aidl`, `ILinuxBridgeDaemon.aidl`) and core client library `LinuxManager.java`.
  - `java_library` `"services.linux"` (and `"service-linux"`): Compiles framework SystemServer services (`LinuxPortalService.java`, `LinuxStorageProvider.java`, `LinuxManagerService.java`, `LinuxBridgeService.java`, `LinuxCeKeyManager.java`, `LinuxAudioPolicyHandler.java`).
- **Daemon `system/linux_bridge/Android.bp`**:
  - `cc_binary` `"linux_bridge"`: Compiles host daemon (`main.cpp`, `socket_server.cpp`, `vsock_server.cpp`, `vsock_framing.cpp`, `hmac_auth.cpp`, `guest_ota_rollback_watchdog.cpp`).
  - `cc_binary` `"guest_ota_rollback_watchdog"`: Standalone C++ watchdog binary.
- **Guest Agent `guest/bridge-agent/Cargo.toml`**:
  - Rust binary `android-bridge-agent` running inside Debian guest.

#### 2. Execution and Verification Scripts
- **Verification Script (`scripts/run_m5_verification.sh`)**:
  - **Step 1**: Checks 21 required M5 files.
  - **Step 2**: Compiles Java framework using `javac -d build_out/classes @"build_out/m5_sources.txt"`.
  - **Step 3**: Executes Java unit tests:
    - `java -cp build_out/classes tests.unit.LinuxPortalServiceTest`
    - `java -cp build_out/classes tests.unit.LinuxStorageProviderTest`
    - `java -cp build_out/classes tests.unit.LinuxAudioPolicyTest`
  - **Step 4**: Compiles and executes C++ native tests:
    - `clang++ -std=c++20 -pthread -I... system/linux_bridge/guest_ota_rollback_watchdog.cpp tests/unit/guest_ota_rollback_watchdog_test.cpp -o build_out/bin/guest_ota_rollback_watchdog_test`
    - `clang++ -std=c++20 -pthread -I... system/vold/AvbVerifier.cpp tests/unit/avb_verifier_test.cpp -lcrypto -o build_out/bin/avb_verifier_test`
  - **Step 5**: Runs Rust guest agent check (`cargo check` in `guest/bridge-agent`).
  - **Step 6**: Runs Python E2E test runner (`python3 tests/e2e/runner.py --tier 1 --feature F-R5-001..014`).

---

## 2. Logic Chain

1. **`LinuxPortalService` Integration Reasoning**:
   - *Observation*: `LinuxPortalService.java` currently uses `mAppOpsStore` (ConcurrentHashMap) and dummy structs (`CameraSession`, `MicSession`, `LocationSession`).
   - *Reasoning*: Because permission checks hit `mAppOpsStore` instead of system `AppOpsManager`, any guest portal request can bypass system security prompts if default entries exist. Because `CameraManager`, `AudioRecord`, and `LocationManager` calls are missing, all camera/audio/GPS streaming is simulated.
   - *Conclusion*: `LinuxPortalService` must be refactored to replace `mAppOpsStore` with binder calls to `AppOpsManager.noteOpNoThrow()`, bind `CameraManager` to stream YUYV video frames over vsock port 5000 to `v4l2loopback`, bind `AudioRecord` to stream PCM audio over vsock port 5000 to `virtio-snd`, and bind `LocationManager` to stream GeoClue D-Bus JSON updates over vsock port 5000.

2. **`LinuxStorageProvider` Integration Reasoning**:
   - *Observation*: `LinuxStorageProvider.java` relies on manual boolean setters (`setVmRunning()`, `setCeKeyAvailable()`).
   - *Reasoning*: Manual setters leave storage provider state out-of-sync with real system lifecycle events (e.g. VM power-off, user screen locking, LUKS2 volume unmounting via `vold`/`LinuxCeKeyManager`).
   - *Conclusion*: `LinuxStorageProvider` must fetch `LinuxManagerInternal` via `LocalServices.getService(LinuxManagerInternal.class)` and query `getVmState()` and `isCeKeyAvailable()` dynamically on every SAF query (`queryRoots`, `queryDocument`, `openDocument`).

3. **Build & Test Target Verification Reasoning**:
   - *Observation*: Standard AOSP build modules (`services.linux`, `linux_bridge`) are defined in `Android.bp` files, while local verification relies on `scripts/run_m5_verification.sh` compiling with `javac`, `clang++`, and `cargo`.
   - *Reasoning*: In addition to AOSP build target verification (`m services.linux`, `atest`), the project contains a self-contained local test harness that compiles Java framework classes and native C++ binaries directly into `build_out/`.
   - *Conclusion*: Verification of M5 require executing both Java unit tests (`LinuxPortalServiceTest`, `LinuxStorageProviderTest`), native C++ binaries (`avb_verifier_test`, `guest_ota_rollback_watchdog_test`), and Python E2E socket tests (`tests/e2e/runner.py`).

---

## 3. Caveats

- **Read-Only Scope**: This report is produced strictly through read-only investigation. No source code files outside of agent state metadata were edited.
- **Hardware Abstraction on Non-Android Development Hosts**: Running `CameraManager`, `AudioRecord`, and `LocationManager` on standard macOS or Linux developer machines (outside a full AOSP emulator or hardware device) requires socket-based test mocks or loopback interfaces when executing unit/E2E test suites.

---

## 4. Conclusion

- **Defect R5 Root Causes Identified**:
  1. `LinuxPortalService.java` relies on in-memory simulated stores (`mAppOpsStore`, `CameraSession`, `MicSession`, `LocationSession`) without real system calls to `AppOpsManager`, `CameraManager`/`Camera2`, `AudioRecord`, and `LocationManager`.
  2. `LinuxStorageProvider.java` relies on manual in-memory setters (`setVmRunning`, `setCeKeyAvailable`) instead of dynamically querying `LinuxManagerInternal` VM lifecycle state and `LinuxCeKeyManager` / `vold` LUKS2 mount state.
- **Verification Infrastructure**: Build targets exist in `Android.bp` (`services.linux`, `linux_bridge`) and script target `scripts/run_m5_verification.sh` orchestrating `javac`, `clang++`, `cargo`, and `python3 tests/e2e/runner.py`.

---

## 5. Verification Method

### 1. Build Verification
- **AOSP Build Target**:
  ```bash
  m services.linux linux_bridge
  ```
- **Local Java Framework Compilation**:
  ```bash
  mkdir -p build_out/classes build_out/bin
  find frameworks/base -name "*.java" > build_out/m5_sources.txt
  echo "tests/unit/LinuxPortalServiceTest.java" >> build_out/m5_sources.txt
  echo "tests/unit/LinuxStorageProviderTest.java" >> build_out/m5_sources.txt
  echo "tests/unit/LinuxAudioPolicyTest.java" >> build_out/m5_sources.txt
  javac -d build_out/classes @build_out/m5_sources.txt
  ```

### 2. Unit Test Execution
- **Execute Java Unit Tests**:
  ```bash
  java -cp build_out/classes tests.unit.LinuxPortalServiceTest
  java -cp build_out/classes tests.unit.LinuxStorageProviderTest
  java -cp build_out/classes tests.unit.LinuxAudioPolicyTest
  ```
- **Execute C++ Native Unit Tests**:
  ```bash
  clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/guest_ota_rollback_watchdog.cpp tests/unit/guest_ota_rollback_watchdog_test.cpp -o build_out/bin/guest_ota_rollback_watchdog_test
  clang++ -std=c++20 -Wall -Wextra -pthread -I. $(pkg-config --cflags openssl 2>/dev/null || echo "-I/opt/homebrew/opt/openssl@3/include") system/vold/AvbVerifier.cpp tests/unit/avb_verifier_test.cpp $(pkg-config --libs openssl 2>/dev/null || echo "-L/opt/homebrew/opt/openssl@3/lib -lcrypto") -o build_out/bin/avb_verifier_test
  ./build_out/bin/guest_ota_rollback_watchdog_test
  ./build_out/bin/avb_verifier_test
  ```

### 3. Comprehensive Verification Script
- **Execute M5 Verification Suite**:
  ```bash
  ./scripts/run_m5_verification.sh
  ```

### 4. Invalidation Conditions
- Any call to `LinuxPortalService` that bypasses `AppOpsManager` or fails to release camera/mic/location resources when VM stops.
- Any query to `LinuxStorageProvider` that returns files when `LinuxManagerInternal.getVmState() != STATE_RUNNING` or `LinuxManagerInternal.isCeKeyAvailable() == false`.
- Failure of `javac` compilation or any assertion failure in `LinuxPortalServiceTest` or `LinuxStorageProviderTest`.
