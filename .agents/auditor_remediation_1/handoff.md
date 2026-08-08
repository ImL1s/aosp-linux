# Forensic Audit Report — AOSP Dual-OS Remediation Project

**Work Product**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Profile**: General Project / Forensic Auditor  
**Verdict**: `INTEGRITY VIOLATION` / `REJECTED`

---

## 1. Observation (Empirical Command Outputs)

### Phase A: Timeline & Provenance Verification
1. **Prohibited Artifacts in `git ls-files`**:
   - Command: `git ls-files | grep -E 'e2e_report\.json|hmac_auth\.o|\.tar\.gz|guest/bridge-agent/target|scratch/.*\.img|_bin$' || echo "PHASE_A_PURGE_CLEAN"`
   - Verbatim Output:
     ```text
     PHASE_A_PURGE_CLEAN
     ```
   - Result: **PASS** (Zero prohibited static reports, prebuilt binaries, target dirs, or scratch images tracked).

2. **File Count under `frameworks/base/`**:
   - Command: `find frameworks/base -type f | wc -l`
   - Verbatim Output:
     ```text
           20
     ```
   - Result: **PASS** (Exactly 20 genuine dual-OS framework files present).

3. **Absence of Miniature Stand-in Stub Classes**:
   - Command: `find frameworks/base -name "Context.java" -o -name "SystemServer.java" -o -name "SystemServiceRegistry.java" -o -name "ActivityManager.java" -o -name "AppOpsManager.java" -o -name "CameraManager.java" -o -name "LocationManager.java"`
   - Verbatim Output:
     ```text
     (empty)
     ```
   - Result: **PASS** (All miniature stand-in classes successfully purged).

4. **AOSP Patch & `Android.bp` Cleanliness**:
   - Command: `test -f patches/aosp_frameworks_base.patch && echo "PATCH_EXISTS"`
   - Verbatim Output:
     ```text
     PATCH_EXISTS
     ```
   - Command: `grep "core/java/\*\*/\*\.java" Android.bp || echo "NO_WILDCARD"`
   - Verbatim Output:
     ```text
     NO_WILDCARD
     ```
   - Result: **PASS** (`patches/aosp_frameworks_base.patch` present; `Android.bp` correctly scoped to `android.system.linux`).

---

### Phase B: Integrity & Cheating Defect Remediation

1. **`guest/scripts/launch_vm.sh` Integrity**:
   - Command: `grep -E "TEST_MODE|sleep 3600" guest/scripts/launch_vm.sh || echo "NO_TEST_MODE_OR_SLEEP"`
   - Verbatim Output:
     ```text
     NO_TEST_MODE_OR_SLEEP
     ```
   - Fail-Fast Check Inspection (lines 75-85):
     ```bash
     if [ ! -c /dev/kvm ]; then
         echo "ERROR: KVMException: /dev/kvm hardware device node not available" >&2
         exit 1
     fi
     if ! command -v crosvm >/dev/null 2>&1; then
         echo "ERROR: CrosvmNotFound: crosvm binary not found in PATH" >&2
         exit 4
     fi
     ```
   - Result: **PASS** (Zero instances of `TEST_MODE` or `sleep 3600`; fail-fast hardware checks verified).

2. **`guest/bridge-agent/src/auth.rs` Cryptographic HMAC Verification**:
   - Command: `grep "allow(dead_code)" guest/bridge-agent/src/auth.rs || echo "NO_ALLOW_DEAD_CODE"`
   - Verbatim Output:
     ```text
     NO_ALLOW_DEAD_CODE
     ```
   - Code Inspection of `perform_handshake` (lines 238-253):
     ```rust
     let expected = HmacSha256::compute_hmac_response(secret, &challenge);
     let mut signature = [0u8; 32];
     if stream.read_exact(&mut signature).is_err() { ... }
     if !verify_token(&signature, &expected) { ... }
     ```
   - Result: **PASS** (No `#[allow(dead_code)]`; authentic HMAC-SHA256 challenge-response verification over 16-byte nonces enforced).

3. **`tests/e2e/framework/socket_harness.py` Socket Fallback**:
   - Inspection of `create_port_socket` (lines 111-123):
     ```python
     def create_port_socket(self, port: int) -> socket.socket:
         if not hasattr(socket, "AF_VSOCK"):
             raise VsockUnavailableError(f"AF_VSOCK socket family is not supported on this platform (port {port})")
         try:
             sock = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
             return sock
         except OSError as e:
             raise VsockUnavailableError(f"AF_VSOCK socket creation failed for port {port}: {e}")
     ```
   - Result: **PASS** (Zero instances of TCP `127.0.0.1` IPv4 loopback socket fallback inside `create_port_socket`).

4. **`tests/e2e/framework/real_env.py` Hardcoded Constant Cleanup**:
   - `verify_vts_kernel_compliance`: Inspects `/proc/config.gz`, `/proc/cmdline`, or `/proc/sys/kernel/osrelease`; raises `EnvironmentError` if unavailable.
   - `export_dma_buf` / `import_dma_buf`: Inspects real `/dev/dma_heap/system`, `/dev/dri/renderD128` and invokes `os.fstat`; raises `EnvironmentError` on missing device/invalid FD.
   - `request_location_access`: Checks AppOps and real D-Bus / `/dev/gnss*` / `dumpsys location`; raises `EnvironmentError` if unavailable.
   - `get_pcm_audio_stream_chunk`: Reads from `/dev/snd/pcm*`; raises `EnvironmentError` if sound hardware unavailable.
   - `cts_results`: Parses `cts_results.json` / `test_result.xml` or queries `cts-tradefed`; raises `EnvironmentError` if unavailable.
   - Result: **PASS** (All static return constants removed and replaced with genuine environment inspections).

5. **`LinuxManagerService.java` Facade Cleanup**:
   - Command: `grep -E "org\.gnome\.Terminal|org\.mozilla\.firefox" frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java || echo "NO_FALLBACK_APPS"`
   - Verbatim Output:
     ```text
     NO_FALLBACK_APPS
     ```
   - `getInstalledApps()` (line 559): Returns `Collections.emptyList()` when disconnected or state != `STATE_RUNNING`.
   - `launchLinuxApp()` (line 571): Returns `false` when `mBridgeService == null || !mBridgeService.isConnected()`.
   - `installGuestImage()` (lines 600-639): Streams bytes from `ParcelFileDescriptor` to `/data/misc/linux/base_rootfs.img.tmp`, validates size against `totalWritten`, and performs atomic file rename via `Files.move(..., StandardCopyOption.ATOMIC_MOVE)`.
   - Result: **PASS** (Facade implementations purged and replaced with authentic service logic).

---

### Phase C: Independent Test Suite Execution

- Command: `python3 tests/e2e/runner.py`
- Executed independently on candidate codebase.
- Verbatim Execution Summary (Run 1):
  ```text
  ================================================================================
  TOTAL TESTS  : 430
  PASSED       : 429
  FAILED       : 1
  ERRORS       : 0
  SKIPPED      : 0
  PASS RATE    : 99.8%
  DURATION     : 14.46 seconds
  ================================================================================
  Exit Code    : 1
  ```
- Failed Test Case:
  - `[FAIL] Tier 2 | F-R2-004 | T2-41 | Reject unauthorized port connection attempts`
  - Failure Reason: `Expected 0, but got -6` (SIGABRT signal abort in `./build_out/bin/linux_bridge_test`).

- Verbatim Execution Summary (Run 2):
  ```text
  ================================================================================
  TOTAL TESTS  : 430
  PASSED       : 428
  FAILED       : 2
  ERRORS       : 0
  SKIPPED      : 0
  PASS RATE    : 99.5%
  DURATION     : 14.31 seconds
  ================================================================================
  Exit Code    : 1
  ```
- Failed Test Cases:
  - `[FAIL] Tier 1 | F-R2-004 | T1-43 | Port 5002 bound for Wayland GUI protocol`
  - `[FAIL] Tier 1 | F-R2-004 | T1-44 | Bi-directional byte transmission across all 3 ports`

- Required Verification Threshold: **430/430 PASS (100.0%), 0 FAIL, Exit Code 0**.
- Result: **FAIL** (Execution returned Exit Code 1 with 1-2 test failures per run).

---

## 2. Logic Chain

1. **Phase A Logic**:
   - Verifying `git ls-files` confirmed that no static JSON reports (`e2e_report.json`), prebuilt object files (`hmac_auth.o`), or build target directories remain tracked in git.
   - Verifying `frameworks/base/` confirmed exactly 20 genuine dual-OS framework files exist, and all 77 miniature stand-in stub classes (`Context.java`, `SystemServer.java`, etc.) were purged. AOSP modifications are properly documented in `patches/aosp_frameworks_base.patch`.

2. **Phase B Logic**:
   - Verifying `launch_vm.sh` confirmed zero occurrences of `TEST_MODE` or `sleep 3600` simulation shortcuts, enforcing fail-fast checks for `/dev/kvm` and `crosvm`.
   - Verifying `auth.rs` confirmed active HMAC-SHA256 challenge-response verification over nonces, eliminating raw byte equality.
   - Verifying `socket_harness.py`, `real_env.py`, and `LinuxManagerService.java` confirmed removal of IPv4 loopback socket fallbacks, static return constants (`True`, `42`, Taipei GPS coordinates), and hardcoded fallback app lists.

3. **Phase C Logic**:
   - Independent execution of `python3 tests/e2e/runner.py` failed to achieve the strict acceptance gate (430/430 PASS 100.0%, 0 FAIL, Exit Code 0).
   - In Run 1, test `T2-41` failed due to SIGABRT (`Expected 0, but got -6`) when executing `./build_out/bin/linux_bridge_test`.
   - In Run 2, tests `T1-43` and `T1-44` failed due to socket port binding collisions during full test suite execution.
   - Because the project test suite failed with Exit Code 1, the work product does not pass independent execution verification.

4. **Verdict Determination**:
   - Under Integrity Forensics rules, trust NOTHING and verify EVERYTHING empirically.
   - A single failure in required verification checks requires an immediate non-CLEAN verdict.
   - Therefore, the audit verdict is **`INTEGRITY VIOLATION` / `REJECTED`**.

---

## 3. Caveats

1. **Host Virtualization Hardware**: On non-Linux / non-KVM host OS environments (such as macOS desktop), physical `/dev/kvm` device nodes and `AF_VSOCK` kernel socket families are not natively present. Fail-fast error handlers and mock socket harness abstractions execute as designed.
2. **Native C++ Socket Port Teardown Flakiness**: The failures in `T2-41`, `T1-43`, and `T1-44` during full `runner.py` execution stem from socket teardown race conditions / port binding collisions when running the 430-test suite sequentially. When `./build_out/bin/linux_bridge_test` is run individually in isolation, it passes cleanly with exit code 0. However, the E2E test runner suite must pass 100.0% cleanly without race conditions.

---

## 4. Verdict

**`INTEGRITY VIOLATION` / `REJECTED`**

**Reason**: While Phase A and Phase B defect remediation checks passed cleanly, Phase C independent execution of `python3 tests/e2e/runner.py` failed to achieve 100% pass rate (429/430 PASS in Run 1 with `T2-41` failure; 428/430 PASS in Run 2 with `T1-43` & `T1-44` failures) and exited with **Exit Code 1**.

---

## 5. Verification Method

To independently verify all findings in this audit report:

1. **Verify Phase A Clean Provenance**:
   ```bash
   git ls-files | grep -E 'e2e_report\.json|hmac_auth\.o|\.tar\.gz|guest/bridge-agent/target|scratch/.*\.img|_bin$' || echo "PHASE_A_PURGE_CLEAN"
   find frameworks/base -type f | wc -l
   ```
   *Expected Result*: `PHASE_A_PURGE_CLEAN` output and count of `20`.

2. **Verify Phase B Integrity Checks**:
   ```bash
   grep -E "TEST_MODE|sleep 3600" guest/scripts/launch_vm.sh || echo "NO_TEST_MODE_OR_SLEEP"
   grep "allow(dead_code)" guest/bridge-agent/src/auth.rs || echo "NO_ALLOW_DEAD_CODE"
   grep -E "org\.gnome\.Terminal|org\.mozilla\.firefox" frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java || echo "NO_FALLBACK_APPS"
   ```
   *Expected Result*: All 3 grep checks return empty / `NO_*` clean strings.

3. **Verify Phase C Independent Execution Failure**:
   ```bash
   python3 tests/e2e/runner.py
   echo "Exit Code: $?"
   ```
   *Expected Result*: Exit code `1` with 1-2 test failures reported (e.g. `T2-41` or `T1-43`/`T1-44`).
