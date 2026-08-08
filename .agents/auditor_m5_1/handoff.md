# Forensic Audit Report: Milestone M5 (Real System Hardware Portals & SAF Provider)

**Work Product**: Milestone M5 Implementation (`LinuxPortalService.java`, `LinuxStorageProvider.java`, `LinuxManagerService.java`, `LinuxManagerInternal.java`, and test suites)  
**Profile**: General Project  
**Integrity Mode**: Development (from `ORIGINAL_REQUEST.md`)  
**Verdict**: CLEAN  

---

## 1. Observation

### 1.1 Source Code Verification (`LinuxPortalService.java`)
- **File**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- **Observations**:
  1. **AppOpsManager Enforcement**: Lines 186–211 implement `checkAppOp(String appId, String op)`. When `mContext != null`, it retrieves system `AppOpsManager` and executes `appOps.unsafeCheckOpRaw(opStr, Process.myUid(), appId)` mapping `OP_CAMERA` to `AppOpsManager.OPSTR_CAMERA`, `OP_RECORD_AUDIO` to `OPSTR_RECORD_AUDIO`, `OP_FINE_LOCATION` to `OPSTR_FINE_LOCATION`, and `OP_COARSE_LOCATION` to `OPSTR_COARSE_LOCATION`.
  2. **CameraManager Hardware Integration**: Lines 153–178 & 267–288 initialize `CameraManager`, register `AvailabilityCallback` (`onCameraUnavailable` / `onCameraAvailable`) for hardware contention with native Android apps, instantiate `ImageReader` (`YUV_420_888`), and perform resolution fallback (capping at 1080p 30fps for 4K inputs).
  3. **AudioRecord PCM Streaming**: Lines 350–378 construct real `AudioRecord` instances using `MediaRecorder.AudioSource.MIC` and `AudioFormat.ENCODING_PCM_16BIT`, reading frames in a background thread `LinuxAudioPortalThread`. Privacy toggle zero-filling (lines 385–394) and stereo-to-mono downmixing (lines 405–407) are genuinely computed.
  4. **LocationManager Updates**: Lines 463–479 register a `LocationListener` on `LocationManager.GPS_PROVIDER` to dispatch GeoClue JSON updates over vsock port 5000. Coarse obfuscation (lines 481–488) rounds coordinates to 2 decimal places.
  5. **Hardware Cleanup Hook**: Lines 506–521 (`onVmStoppedOrSuspended()`) close active camera image readers, stop audio recording threads, and remove location updates when the Linux VM powers down or suspends.

### 1.2 Storage Provider & Dynamic LocalServices Binding (`LinuxStorageProvider.java`)
- **File**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
- **Observations**:
  1. **No Manual Setter/Boolean Pollution**: Manual fields (`mVmRunning`, `mCeKeyAvailable`, `mIsReadOnlyMount`) and setter methods were completely removed.
  2. **Dynamic LocalServices Evaluation**: Lines 100–102 & 108–121 query `LocalServices.getService(LinuxManagerInternal.class)` dynamically in `checkVmStateAndLock()` to evaluate real-time VM state (`isVmRunning()`) and LUKS2 key availability (`isCeKeyAvailable()`), throwing `ConnectionError` (`VMOfflineException`) or `PermissionError` (`EncryptedStorageException`).
  3. **Storage Lifecycle Listener**: Lines 72–88 define `StorageStateListener` which invokes `getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)` when VM state or storage encryption state changes.
  4. **Security & System Root Protection**: Lines 135–187 block system root directories (`/sys`, `/proc`, `/etc`, `/dev`) and enforce canonical path boundary checks (`targetFile.getCanonicalPath().startsWith(baseDir.getCanonicalPath())`) to prevent directory traversal attacks.

### 1.3 Execution & Verification Results
- Executed `./scripts/run_m5_verification.sh` on 2026-08-08T14:22:06+08:00:
  - Step 1: File Structural Compliance — **PASS** (21/21 files present)
  - Step 2: Java Compilation — **PASS** (Framework & services compiled cleanly)
  - Step 3: Java Unit Tests — **PASS** (`LinuxPortalServiceTest`, `LinuxAudioPolicyTest`, `LinuxStorageProviderTest` all passed)
  - Step 4: C++ Watchdog & AVB Tests — **PASS** (`guest_ota_rollback_watchdog_test`, `avb_verifier_test` passed)
  - Step 5: Rust Guest Agent — **PASS** (`cargo check` passed)
  - Step 6: Python E2E Test Suite — **PASS** (Tier 1 & Tier 2 for features F-R5-001..014 passed 100%)

---

## 2. Logic Chain

1. **System Call Authenticity**:
   - *Observation*: `LinuxPortalService` calls real Android framework managers (`AppOpsManager`, `CameraManager`, `AudioRecord`, `LocationManager`).
   - *Reasoning*: Null-checks on `mContext` allow standalone Java unit tests to instantiate `LinuxPortalService(null)` while ensuring active `system_server` environments run genuine system call permission checks and hardware streams.
   - *Deduction*: Hardware interaction logic is authentic and non-facade.

2. **Storage Provider State Integration**:
   - *Observation*: `LinuxStorageProvider` queries `LocalServices.getService(LinuxManagerInternal.class)` on every SAF contract method (`queryRoots`, `queryDocument`, `queryChildDocuments`, `openDocument`).
   - *Reasoning*: The provider cannot be tricked into serving files while the VM is stopped or credential storage is locked because `checkVmStateAndLock()` evaluates live service state on each call.
   - *Deduction*: SAF dynamic state binding is real and fully robust.

3. **Test Suite Authenticity**:
   - *Observation*: `LinuxStorageProviderTest` registers a `FakeLinuxManagerInternal` via `LocalServices.addService(...)` and asserts that `ConnectionError` and `PermissionError` are thrown when VM state is offline or CE key is unavailable.
   - *Reasoning*: The unit test exercises the real path in `LinuxStorageProvider` through the `LocalServices` mechanism. `LinuxPortalServiceTest` exercises real resolution fallbacks, privacy toggle zeroing, and location rounding.
   - *Deduction*: Test outputs are authentic, execution-based, and non-fabricated.

---

## 3. Caveats

- **Host Device Hardware Fallback**: Hardware video/audio streaming over vsock relies on underlying guest kernel devices (`/dev/video0`, virtio-snd). In headless or simulated CI environments without physical USB cameras/microphones, hardware calls gracefully handle empty device lists or missing system services without crashing.

---

## 4. Conclusion

### Forensic Audit Phase Results
- **Hardcoded test result detection**: PASS — No embedded expected strings or hardcoded passes found.
- **Facade implementation detection**: PASS — Real system calls and genuine hardware handling implemented.
- **Pre-populated artifact detection**: PASS — All build artifacts compiled and executed dynamically during test run.
- **Self-certifying test check**: PASS — Independent assertions verify runtime exceptions and fallback behavior.
- **LocalServices / AppOps / Hardware integration**: PASS — Real system framework integration established.

**Final Verdict**: **CLEAN**

---

## 5. Verification Method

To independently verify this audit, run the following commands in `/Users/iml1s/Documents/mine/aosp-linux`:

1. **Full Verification Suite**:
   ```bash
   ./scripts/run_m5_verification.sh
   ```
   *Expected Output*: `M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY`

2. **Java Unit Test Suite**:
   ```bash
   javac -d build_out/classes @build_out/m5_sources.txt
   java -cp build_out/classes tests.unit.LinuxPortalServiceTest
   java -cp build_out/classes tests.unit.LinuxStorageProviderTest
   ```
   *Expected Output*: `PASS: LinuxPortalServiceTest executed successfully.` and `PASS: LinuxStorageProviderTest executed successfully.`
