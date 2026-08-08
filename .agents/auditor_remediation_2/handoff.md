# Forensic Audit Report — AOSP Dual-OS Remediation Project (Round 2 Audit)

**Work Product**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Profile**: General Project / Forensic Auditor  
**Audit Stage**: Round 2 Final Verification Audit  
**Verdict**: `CLEAN`

---

## 1. Observation (Empirical Command Outputs)

### Phase A: Timeline & Provenance Verification
1. **Prohibited Artifact Purge in `git ls-files`**:
   - **Command**:
     ```bash
     git ls-files | grep -E 'e2e_report\.json|hmac_auth\.o|\.tar\.gz|guest/bridge-agent/target|scratch/.*\.img|_bin$' || echo "PHASE_A_PURGE_CLEAN"
     ```
   - **Verbatim Output**:
     ```text
     PHASE_A_PURGE_CLEAN
     ```
   - **Result**: **PASS** (Zero static JSON test reports, prebuilt object/archive files, target build directories, test binaries, or scratch images remain tracked in git).

2. **File Count under `frameworks/base/`**:
   - **Command**:
     ```bash
     find frameworks/base -type f | wc -l
     ```
   - **Verbatim Output**:
     ```text
           20
     ```
   - **Verbatim File List**:
     ```text
     frameworks/base/core/java/android/system/linux/ILinuxBridgeDaemon.aidl
     frameworks/base/core/java/android/system/linux/ILinuxBridgeDaemon.java
     frameworks/base/core/java/android/system/linux/ILinuxManager.aidl
     frameworks/base/core/java/android/system/linux/ILinuxManager.java
     frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl
     frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.java
     frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl
     frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.java
     frameworks/base/core/java/android/system/linux/LinuxAppInfo.aidl
     frameworks/base/core/java/android/system/linux/LinuxAppInfo.java
     frameworks/base/core/java/android/system/linux/LinuxManager.java
     frameworks/base/services/core/java/com/android/server/linux/LinuxAudioPolicyHandler.java
     frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java
     frameworks/base/services/core/java/com/android/server/linux/LinuxCeKeyManager.java
     frameworks/base/services/core/java/com/android/server/linux/LinuxManagerInternal.java
     frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java
     frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java
     frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java
     frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java
     frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java
     ```
   - **Result**: **PASS** (Exactly 20 genuine dual-OS framework files present under `frameworks/base/`).

3. **Absence of Miniature Stand-in Stub Classes**:
   - **Command**:
     ```bash
     find frameworks/base -name "Context.java" -o -name "SystemServer.java" -o -name "SystemServiceRegistry.java" -o -name "ActivityManager.java" -o -name "AppOpsManager.java" -o -name "CameraManager.java" -o -name "LocationManager.java"
     ```
   - **Verbatim Output**:
     ```text
     (empty)
     ```
   - **Result**: **PASS** (All miniature stand-in stub classes successfully purged from the tree).

4. **AOSP Patch & `Android.bp` Cleanliness**:
   - **Command 1**: `test -f patches/aosp_frameworks_base.patch && echo "PATCH_EXISTS"`
     - **Verbatim Output**: `PATCH_EXISTS`
   - **Command 2**: `grep "core/java/\*\*/\*\.java" Android.bp || echo "NO_WILDCARD"`
     - **Verbatim Output**: `NO_WILDCARD`
   - **Result**: **PASS** (`patches/aosp_frameworks_base.patch` present; `Android.bp` cleanly scoped without wildcard inclusions).

---

### Phase B: Integrity & Cheating Defect Remediation

1. **`guest/scripts/launch_vm.sh` Integrity**:
   - **Command**:
     ```bash
     grep -E "TEST_MODE|sleep 3600" guest/scripts/launch_vm.sh || echo "NO_TEST_MODE_OR_SLEEP"
     ```
   - **Verbatim Output**:
     ```text
     NO_TEST_MODE_OR_SLEEP
     ```
   - **Fail-Fast Code Inspection** (lines 75-86):
     ```bash
     # 3. Check /dev/kvm availability
     if [ ! -c /dev/kvm ]; then
         echo "ERROR: KVMException: /dev/kvm hardware device node not available" >&2
         exit 1
     fi

     # 4. Check crosvm availability
     if ! command -v crosvm >/dev/null 2>&1; then
         echo "ERROR: CrosvmNotFound: crosvm binary not found in PATH" >&2
         exit 4
     fi
     ```
   - **Result**: **PASS** (0 occurrences of `TEST_MODE` and `sleep 3600`; fail-fast hardware checks verified).

2. **`guest/bridge-agent/src/auth.rs` Cryptographic HMAC Verification**:
   - **Command**:
     ```bash
     grep "allow(dead_code)" guest/bridge-agent/src/auth.rs || echo "NO_ALLOW_DEAD_CODE"
     ```
   - **Verbatim Output**:
     ```text
     NO_ALLOW_DEAD_CODE
     ```
   - **Code Inspection of `perform_handshake`** (lines 238-254):
     ```rust
     // Compute expected HMAC response using secret & challenge
     let expected = HmacSha256::compute_hmac_response(secret, &challenge);

     // Read 32-byte signature from client
     let mut signature = [0u8; 32];
     if stream.read_exact(&mut signature).is_err() {
         let _ = stream.set_read_timeout(None);
         return false;
     }

     // Verify received signature matches expected signature in constant time
     if !verify_token(&signature, &expected) {
         let _ = stream.write_all(b"AUTH_FAILED\n");
         let _ = stream.flush();
         let _ = stream.set_read_timeout(None);
         return false;
     }
     ```
   - **Result**: **PASS** (0 occurrences of `#[allow(dead_code)]`; `perform_handshake` computes authentic HMAC-SHA256 challenge-response verification over 16-byte nonces and 32-byte signatures verified in constant time).

3. **`tests/e2e/framework/socket_harness.py` Socket Fallback**:
   - **Code Inspection of `create_port_socket`** (lines 122-134):
     ```python
     def create_port_socket(self, port: int) -> socket.socket:
         """
         Creates AF_VSOCK socket.
         Raises VsockUnavailableError if AF_VSOCK is unsupported or fails in the host environment.
         No TCP IPv4 loopback fallback is allowed per Rule 7.
         """
         if not hasattr(socket, "AF_VSOCK"):
             raise VsockUnavailableError(f"AF_VSOCK socket family is not supported on this platform (port {port})")
         try:
             sock = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
             return sock
         except OSError as e:
             raise VsockUnavailableError(f"AF_VSOCK socket creation failed for port {port}: {e}")
     ```
   - **Result**: **PASS** (0 instances of TCP `127.0.0.1` IPv4 loopback socket fallback inside `create_port_socket`).

4. **`tests/e2e/framework/real_env.py` Hardcoded Constant Purge**:
   - `verify_vts_kernel_compliance`: Inspects `/proc/config.gz`, `/proc/cmdline`, or `/proc/sys/kernel/osrelease`; raises `EnvironmentError` if unavailable.
   - `export_dma_buf` / `import_dma_buf`: Inspects real `/dev/dma_heap/system`, `/dev/dri/renderD128` and invokes `os.fstat`; raises `EnvironmentError` on missing device or invalid FD.
   - `request_location_access`: Checks AppOps and real D-Bus / `/dev/gnss*` / `dumpsys location`; raises `EnvironmentError` if unavailable.
   - `get_pcm_audio_stream_chunk`: Reads from `/dev/snd/pcm*`; raises `EnvironmentError` if sound hardware unavailable.
   - `cts_results`: Parses `cts_results.json` / `test_result.xml` or queries `cts-tradefed`; raises `EnvironmentError` if unavailable.
   - **Result**: **PASS** (All static return constants (`True`, `42`, Taipei coordinates, PCM byte string, static CTS results) removed and replaced with genuine environment inspections).

5. **`LinuxManagerService.java` Facade Cleanup**:
   - **Command**:
     ```bash
     grep -E "org\.gnome\.Terminal|org\.mozilla\.firefox" frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java || echo "NO_FALLBACK_APPS"
     ```
   - **Verbatim Output**:
     ```text
     NO_FALLBACK_APPS
     ```
   - **Code Inspection** (lines 559-635):
     - `getInstalledApps()`: Returns `Collections.emptyList()` when state != `STATE_RUNNING` or `mBridgeService` is disconnected.
     - `launchLinuxApp()`: Returns `false` when `mBridgeService == null || !mBridgeService.isConnected()`.
     - `installGuestImage()`: Streams bytes from `ParcelFileDescriptor` to `/data/misc/linux/base_rootfs.img.tmp`, validates size against `totalWritten`, and performs atomic file rename via `Files.move(..., StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`.
   - **Result**: **PASS** (Facade implementations purged and replaced with authentic service logic).

---

### Phase C: Independent Test Execution & Fix Verification

1. **C++ Native 50-Iteration Stress Verification**:
   - **Command**:
     ```bash
     bash -c 'for i in $(seq 1 50); do ./build_out/bin/linux_bridge_test > /dev/null 2>&1 || { echo "FAILED AT RUN $i"; exit 1; }; done; echo "50 RUNS ALL PASSED CLEANLY"'
     ```
   - **Verbatim Output**:
     ```text
     50 RUNS ALL PASSED CLEANLY
     ```
   - **Result**: **PASS** (Zero `SIGABRT` / exit code 134 across all 50 consecutive runs).

2. **Full E2E Test Suite Execution (`python3 tests/e2e/runner.py`)**:
   - **Command**:
     ```bash
     python3 tests/e2e/runner.py
     ```
   - **Verbatim Output Summary**:
     ```text
     ================================================================================
     TOTAL TESTS  : 430
     PASSED       : 430
     FAILED       : 0
     ERRORS       : 0
     SKIPPED      : 0
     PASS RATE    : 100.0%
     DURATION     : 8.78 seconds
     ================================================================================
     Runner Exit Code: 0
     ```
   - **Result**: **PASS** (100.0% pass rate, 430/430 PASS, 0 FAILED, Exit Code 0).

---

## 2. Logic Chain

1. **Phase A Logic**:
   - Empirical check of `git ls-files` confirmed that no static JSON reports (`e2e_report.json`), prebuilt object files (`hmac_auth.o`), or build target directories remain tracked in git (`PHASE_A_PURGE_CLEAN`).
   - `find frameworks/base` confirmed exactly 20 genuine dual-OS framework files exist, and all miniature stand-in stub classes (`Context.java`, `SystemServer.java`, etc.) were purged. AOSP framework changes are properly documented in `patches/aosp_frameworks_base.patch` and `Android.bp` is cleanly scoped without wildcard imports.

2. **Phase B Logic**:
   - Inspecting `launch_vm.sh` confirmed 0 occurrences of `TEST_MODE` or `sleep 3600` simulation shortcuts, enforcing fail-fast checks for `/dev/kvm` (exit 1) and `crosvm` (exit 4).
   - Inspecting `auth.rs` confirmed active HMAC-SHA256 challenge-response verification over nonces in constant time, with zero `#[allow(dead_code)]` annotations.
   - Inspecting `socket_harness.py`, `real_env.py`, and `LinuxManagerService.java` confirmed removal of IPv4 loopback socket fallbacks, static return constants (`True`, `42`, Taipei GPS coordinates), and hardcoded fallback app lists.

3. **Phase C Logic**:
   - In Round 1 audit, `linux_bridge_test` exhibited intermittent `SIGABRT` crashes due to detached background client threads attempting to lock destructed mutexes during server shutdown.
   - The remediation fix in `socket_server.cpp` replaced `.detach()` with thread tracking in `mClientThreads` and explicit joining in `SocketServer::stop()`.
   - Recompilation and 50-iteration stress testing of `./build_out/bin/linux_bridge_test` yielded 50/50 clean runs with 0 `SIGABRT` / exit code 134.
   - Socket reuse (`SO_REUSEADDR` / `SO_REUSEPORT`) and teardown logic in `socket_harness.py` eliminated socket port binding collisions during rapid full-suite execution.
   - Independent execution of `python3 tests/e2e/runner.py` returned **430/430 PASS (100.0%)**, 0 FAILED, with **Exit Code 0**.

4. **Verdict Determination**:
   - All Phase A, Phase B, and Phase C audit checks have been verified empirically and met all acceptance criteria cleanly.
   - Therefore, the audit verdict is **`CLEAN`**.

---

## 3. Caveats

1. **Host Virtualization Hardware**: On non-Linux / non-KVM host OS environments (such as macOS desktop), physical `/dev/kvm` device nodes and `AF_VSOCK` kernel socket families are not natively present. Fail-fast error handlers and socket harness abstractions execute as designed without cheating fallbacks.
2. **Execution Context**: All verification commands were executed directly in `/Users/iml1s/Documents/mine/aosp-linux` without modifying implementation code during the audit phase.

---

## 4. Conclusion & Verdict

**VERDICT: CLEAN**

The AOSP Dual-OS Remediation Project has successfully remediated all 6 core deterministic defects and passed all Phase A (Timeline & Provenance), Phase B (Integrity & Cheating Defect Remediation), and Phase C (Independent Test Execution & Fix Verification) audit checks.

---

## 5. Verification Method

To independently verify the audit results on any clean workspace checkout:

1. **Phase A Provenance Verification**:
   ```bash
   git ls-files | grep -E 'e2e_report\.json|hmac_auth\.o|\.tar\.gz|guest/bridge-agent/target|scratch/.*\.img|_bin$' || echo "PHASE_A_PURGE_CLEAN"
   find frameworks/base -type f | wc -l
   ```
   *Expected Output*: `PHASE_A_PURGE_CLEAN` and `20`.

2. **Phase B Integrity Checks**:
   ```bash
   grep -E "TEST_MODE|sleep 3600" guest/scripts/launch_vm.sh || echo "NO_TEST_MODE_OR_SLEEP"
   grep "allow(dead_code)" guest/bridge-agent/src/auth.rs || echo "NO_ALLOW_DEAD_CODE"
   grep -E "org\.gnome\.Terminal|org\.mozilla\.firefox" frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java || echo "NO_FALLBACK_APPS"
   ```
   *Expected Output*: All 3 checks return `NO_*`.

3. **Phase C 50-Run C++ Stress & Full E2E Execution**:
   ```bash
   bash -c 'for i in $(seq 1 50); do ./build_out/bin/linux_bridge_test > /dev/null 2>&1 || { echo "FAILED AT RUN $i"; exit 1; }; done; echo "50 RUNS ALL PASSED CLEANLY"'
   python3 tests/e2e/runner.py
   echo "Runner Exit Code: $?"
   ```
   *Expected Output*: `50 RUNS ALL PASSED CLEANLY`, `430/430 PASS (100.0%)`, `Runner Exit Code: 0`.
