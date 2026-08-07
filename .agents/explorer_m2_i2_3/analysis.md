# Analysis & Remediation Plan: Authentic E2E Test Suite & Verification Script for Milestone M2

**Author**: Explorer 3 (Iteration 2, Milestone M2)  
**Target Scope**: `tests/e2e/runner.py`, `tests/e2e/tier1_feature_coverage/test_m2_tier1.py`, `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`, `scripts/run_m2_verification.sh`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i2_3`  
**Date**: 2026-08-06  

---

## 1. Executive Summary & Forensic Audit Findings Context

During Iteration 1 of Milestone M2, the Forensic Auditor (`auditor_m2_1`) identified severe integrity violations across the codebase, resulting in a verdict of **INTEGRITY VIOLATION**. Specifically, **Finding 4** highlighted that the E2E Test Suite (`tests/e2e/runner.py`) ran 430 test cases in 0.09 seconds exclusively against `MockEnvironment` in Python memory, asserting against hardcoded dictionaries and string literals without executing a single compiled binary, C++ unit test, Rust daemon, or bash script.

### Key Observations from Audit Finding 4:
1. **Self-Certifying Mock Execution**: `tests/e2e/runner.py` line 141 instantiates `mock_env = MockEnvironment()` and passes it to test cases. Tests verify against in-memory dictionary fields (e.g. `self.mock_env.storage_mounts`) rather than system state.
2. **Hardcoded Assertions**: `tests/e2e/tier1_feature_coverage/test_m2_tier1.py` tests create local python dictionaries (e.g., `kernel_version = "Linux debian 6.6.0-arm64-vmpatch..."` in lines 39-42) and assert against them.
3. **Mock Boundary Functions**: `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` tests define inline Python functions (e.g., `def launch_crosvm(kvm_available): ...` in lines 25-30) that raise pre-scripted Python exceptions instead of running real shell scripts.
4. **Missing Verification Script**: `scripts/run_m2_verification.sh` does not exist in the repository; only `run_m1_verification.sh` exists.

This analysis provides an authentic, production-grade remediation plan to eliminate mock shortcuts, integrate `subprocess`-based binary execution into `runner.py` and test cases, and create `scripts/run_m2_verification.sh`.

---

## 2. Binary Target Inventory for Milestone M2

To achieve authentic verification, test cases must execute the compiled artifacts and shell scripts produced during the build process:

| Target Name | Type | Source Location | Build Command / Executable Path |
|-------------|------|-----------------|--------------------------------|
| `linux_bridge_test` | C++ Native Binary | `system/linux_bridge/tests/linux_bridge_test.cpp` | `clang++ -std=c++20 ... -o build_out/bin/linux_bridge_test` |
| `challenger_m2_hmac_test` | C++ Unit Test | `tests/unit/challenger_m2_hmac_test.cpp` | `clang++ -std=c++20 ... -o build_out/bin/challenger_m2_hmac_test` |
| `challenger_m2_framing_test` | C++ Unit Test | `tests/unit/challenger_m2_framing_test.cpp` | `clang++ -std=c++20 ... -o build_out/bin/challenger_m2_framing_test` |
| `challenger_m2_empirical_test` | C++ Unit Test | `tests/unit/challenger_m2_empirical_test.cpp` | `clang++ -std=c++20 ... -o build_out/bin/challenger_m2_empirical_test` |
| `android-bridge-agent` | Rust Guest Binary | `guest/bridge-agent/src/main.rs` | `cargo build --manifest-path guest/bridge-agent/Cargo.toml` -> `build_out/bin/android-bridge-agent` |
| `LinuxManagerServiceTest` | Java Service Unit Test | `tests/unit/LinuxManagerServiceTest.java` | `javac -d build_out/classes ...` -> `java -cp build_out/classes tests.unit.LinuxManagerServiceTest` |
| `launch_vm.sh` | Bash Script | `guest/scripts/launch_vm.sh` | `bash guest/scripts/launch_vm.sh [config_file] [token]` |
| `init_storage_layout.sh` | Bash Script | `guest/scripts/init_storage_layout.sh` | `bash guest/scripts/init_storage_layout.sh [target_dir]` |
| `guest_mount_overlay.sh` | POSIX Shell Script | `guest/scripts/guest_mount_overlay.sh` | `sh guest/scripts/guest_mount_overlay.sh` |

---

## 3. Subprocess-Based Remediation Plan for `tests/e2e/runner.py`

### 3.1 Architecture Overview
`tests/e2e/framework/command_runner.py` already implements `CommandRunner.run(cmd, cwd, timeout)` returning `CommandResult(command, exit_code, stdout, stderr, duration_sec)`.

We extend `runner.py` to:
1. **Pre-flight Binary Compilation Verification**: Before running tests, check if `build_out/bin/` binaries exist. If absent, trigger build compilation or notify the user.
2. **Subprocess Test Execution**: Ensure test cases instantiate and invoke real binaries via `CommandRunner` rather than reading memory state from `MockEnvironment`.
3. **Execution Context Injection**: Provide executable paths (`BUILD_BIN_DIR`, `SCRIPTS_DIR`) to test instances so tests can execute real files dynamically based on workspace paths.

### 3.2 Key Changes in `runner.py`
- Modify `main()` in `tests/e2e/runner.py`:
```python
WORKSPACE_ROOT = os.path.abspath(os.path.join(BASE_DIR, "..", ".."))
BUILD_BIN_DIR = os.path.join(WORKSPACE_ROOT, "build_out", "bin")

# Pass workspace context to test classes
for test_cls in test_classes:
    test_instance = test_cls(workspace_root=WORKSPACE_ROOT, bin_dir=BUILD_BIN_DIR)
    result = test_instance.execute()
```

---

## 4. Remediation Plan for Milestone M2 Test Cases

### 4.1 Remediating `test_m2_tier1.py` (Tier 1 Happy Path Coverage)

#### F-R2-001 (Non-Protected Debian VM):
- **T1-26 (Launch crosvm instance)**: Execute `bash guest/scripts/launch_vm.sh /data/misc/linux/vm_config.json 0000...` via `CommandRunner.run()`. Assert exit code `0` or expected check behavior, and verify stdout contains `[Launch Script] Starting VM launch procedure...` and `Launching crosvm Non-Protected VM`.
- **T1-27 (Kernel Boot & Config)**: Parse `guest/config/vm_config.json` via python `json.load()` and verify parameters (`cpus: 4`, `mem_mb: 4096`, `cid: 3`, `kernel` path). Execute `launch_vm.sh` and verify kernel commandline `console=ttyS0 root=/dev/vda ro init=/sbin/init android_bridge.token=...`.
- **T1-28 & T1-29 (Systemd & Bridge Agent)**: Parse `guest/systemd/android-bridge-agent.service` and verify `ExecStart=/usr/bin/android-bridge-agent`, `Restart=always`, `WantedBy=multi-user.target`. Execute compiled Rust binary `build_out/bin/android-bridge-agent --help` or test harness via `CommandRunner.run()`.
- **T1-30 (CPU & RAM Allocation)**: Parse `guest/config/vm_config.json` and verify `cpus == 4` and `mem_mb == 4096`.

#### F-R2-002 (4-Layer Storage Image Layout):
- **T1-31 .. T1-35**: Execute `bash guest/scripts/init_storage_layout.sh /tmp/e2e_storage_test` in a temporary workspace directory via `CommandRunner.run()`. Verify created files (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`) via `os.path.exists()` and file size checks (`os.path.getsize()`). Execute `guest/scripts/guest_mount_overlay.sh` and verify overlayfs parameters in stdout. Clean up temporary directory after execution.

#### F-R2-003 (LUKS2 CE Storage Encryption):
- **T1-36 .. T1-40**: Execute compiled C++ binary `build_out/bin/challenger_m2_hmac_test` via `CommandRunner.run()`. Verify exit code `0` and stdout containing `Initial verification: PASS`. Execute Java unit test `java -cp build_out/classes tests.unit.LinuxManagerServiceTest` via subprocess to verify CE key derivation and unlock/lock key lifecycle.

#### F-R2-004 (Vsock 3-Port Allocation):
- **T1-41 .. T1-45**: Execute compiled C++ binaries `build_out/bin/linux_bridge_test` and `build_out/bin/challenger_m2_framing_test` via `CommandRunner.run()`. Verify exit code `0`, stdout confirming binding and bi-directional payload transmission on Ports 5000, 5001, 5002.

#### F-R2-005 (HMAC-SHA256 Auth Handshake):
- **T1-46 .. T1-50**: Execute compiled C++ binary `build_out/bin/challenger_m2_hmac_test` and compiled Rust binary `build_out/bin/android-bridge-agent` via `CommandRunner.run()`. Verify 32-byte token generation, HMAC verification, replay token rejection, and 5s timeout expiration.

---

### 4.2 Remediating `test_m2_tier2.py` (Tier 2 Boundary & Corner Cases)

- **T2-26 (Host KVM Missing)**: Execute `bash guest/scripts/launch_vm.sh` in a mocked non-KVM path or environment where `/dev/kvm` does not exist. Assert exit code `1` and stderr containing `KVMException: /dev/kvm not found`.
- **T2-27 (Insufficient RAM)**: Execute `bash guest/scripts/launch_vm.sh` with simulated low memory. Assert exit code `2` and stderr containing `OutOfMemory`.
- **T2-35 (Multi-Process Mount Lock)**: Launch two background subprocesses executing `guest/scripts/launch_vm.sh` simultaneously on the same storage image. Assert that the second subprocess receives exit code `3` and stderr containing `ResourceBusy: base_rootfs.img is locked`.
- **T2-41 .. T2-44 (Vsock Boundary & Framing)**: Execute `build_out/bin/challenger_m2_framing_test` via `CommandRunner.run()`. Verify corrupt magic signature `0xDEADBEEF` rejection and payload > 16MB rejection.
- **T2-46 .. T2-50 (HMAC Security Boundaries)**: Execute `build_out/bin/challenger_m2_hmac_test` via `CommandRunner.run()`. Verify invalid HMAC signature rejection, single-use token replay rejection, and 5s timeout expiration.

---

## 5. Master Blueprint for `scripts/run_m2_verification.sh`

`scripts/run_m2_verification.sh` will serve as the single source of truth for M2 verification.

### 5.1 Verification Pipeline Steps

```bash
#!/usr/bin/env bash
set -e

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${WORKSPACE_ROOT}/build_out"
PATH="${HOME}/.cargo/bin:${PATH}"

echo "=================================================="
echo "   M2 AVF Guest & CE Encryption Verification     "
echo "=================================================="
echo "Workspace Root: ${WORKSPACE_ROOT}"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}/bin" "${BUILD_DIR}/classes"

# Step 1: File Integrity Check
echo "[1/6] Checking M2 File Structure Compliance..."
# Verify vm_config.json, launch_vm.sh, init_storage_layout.sh, guest_mount_overlay.sh,
# hmac_auth.cpp/h, vsock_server.cpp/h, android-bridge-agent files.

# Step 2: Java Framework & Service Modules Compilation
echo "[2/6] Compiling Java Framework & Service Modules..."
find "${WORKSPACE_ROOT}/frameworks/base/core/java" \
     "${WORKSPACE_ROOT}/frameworks/base/services/core/java" \
     "${WORKSPACE_ROOT}/tests/unit" -name "*.java" > "${BUILD_DIR}/sources.txt"
javac -d "${BUILD_DIR}/classes" @"${BUILD_DIR}/sources.txt"

# Step 3: Native C++ Bridge Daemon & Challenger Tests Compilation
echo "[3/6] Compiling Native C++ Bridge Daemon & Challenger Test Suite..."
clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/socket_server.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_framing.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/hmac_auth.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_server.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/tests/linux_bridge_test.cpp" \
    -o "${BUILD_DIR}/bin/linux_bridge_test"

clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/hmac_auth.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/challenger_m2_hmac_test.cpp" \
    -o "${BUILD_DIR}/bin/challenger_m2_hmac_test"

clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_framing.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/challenger_m2_framing_test.cpp" \
    -o "${BUILD_DIR}/bin/challenger_m2_framing_test"

clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/hmac_auth.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/challenger_m2_empirical_test.cpp" \
    -o "${BUILD_DIR}/bin/challenger_m2_empirical_test"

# Step 4: Rust Guest Agent Compilation
echo "[4/6] Compiling Guest Rust android-bridge-agent..."
cargo build --manifest-path "${WORKSPACE_ROOT}/guest/bridge-agent/Cargo.toml" --target-dir "${BUILD_DIR}/cargo"
cp "${BUILD_DIR}/cargo/debug/android-bridge-agent" "${BUILD_DIR}/bin/android-bridge-agent"

# Step 5: Executing Native C++, Java, and Rust Verification Binaries
echo "[5/6] Executing Native C++, Java, & Rust Binaries..."
"${BUILD_DIR}/bin/linux_bridge_test"
"${BUILD_DIR}/bin/challenger_m2_hmac_test"
"${BUILD_DIR}/bin/challenger_m2_framing_test"
"${BUILD_DIR}/bin/challenger_m2_empirical_test"
java -cp "${BUILD_DIR}/classes" tests.unit.LinuxManagerServiceTest

# Step 6: Executing Authentic E2E Python Test Suite via Subprocess
echo "[6/6] Executing Authentic E2E Test Suite (Tier 1 & Tier 2)..."
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2

echo "=================================================="
echo "M2 VERIFICATION COMPLETE: ALL REQUIREMENTS PASSED"
```

---

## 6. Code Proposal Snippets

### 6.1 `test_m2_tier1.py` Subprocess Example (T1-26 & T1-31)
```python
class TestR2_001_T1_26_LaunchCrosvmNonProtected(BaseTestCase):
    test_id = "T1-26"
    feature_id = "F-R2-001"
    title = "Launch crosvm instance with non-protected guest config"
    tier = 1

    def run_test(self):
        script_path = os.path.join(self.workspace_root, "guest", "scripts", "launch_vm.sh")
        config_path = os.path.join(self.workspace_root, "guest", "config", "vm_config.json")
        token = "a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890"

        res = CommandRunner.run(f"bash {script_path} {config_path} {token}")
        CustomAssertions.assert_equal(res.exit_code, 0, f"launch_vm.sh failed: {res.stderr}")
        CustomAssertions.assert_in("[Launch Script] Starting VM launch procedure...", res.stdout)
        CustomAssertions.assert_in("Launching crosvm Non-Protected VM", res.stdout)
```

### 6.2 `test_m2_tier1.py` Subprocess Example (T1-46 HMAC Test)
```python
class TestR2_005_T1_46_HostRandomTokenGeneration(BaseTestCase):
    test_id = "T1-46"
    feature_id = "F-R2-005"
    title = "Host generates single-use 256-bit random auth token"
    tier = 1

    def run_test(self):
        bin_path = os.path.join(self.bin_dir, "challenger_m2_hmac_test")
        res = CommandRunner.run(bin_path)
        CustomAssertions.assert_equal(res.exit_code, 0, f"challenger_m2_hmac_test failed: {res.stderr}")
        CustomAssertions.assert_in("=== HmacAuth C++ Stress Verification: ALL PASSED ===", res.stdout)
        CustomAssertions.assert_in("Initial verification: PASS", res.stdout)
```

---

## 7. Verification & Invalidation Criteria

### Verification Commands:
```bash
# 1. Build and run M2 full verification script
bash scripts/run_m2_verification.sh

# 2. Run E2E runner directly
python3 tests/e2e/runner.py --tier 1 --verbose
python3 tests/e2e/runner.py --tier 2 --verbose
```

### Invalidation Conditions:
1. Any test case in `tests/e2e` asserting against hardcoded python dictionary literals without calling `CommandRunner.run()` or executing a binary.
2. `tests/e2e/runner.py` finishing in < 0.2s for M2 tests without invoking `build_out/bin/*` binaries.
3. Absence of `scripts/run_m2_verification.sh` or failure to compile Java, C++, and Rust binaries in `scripts/run_m2_verification.sh`.
