# Forensic Audit Handoff Report: Milestone M2 Iteration 3 Re-Audit

**Work Product**: Milestone M2 Implementation (`system/linux_bridge/*`, `guest/bridge-agent/*`, `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`, `guest/scripts/*`, `tests/e2e/*`)  
**Profile**: General Project  
**Verdict**: **CLEAN**  

---

## 1. Observation

A complete forensic re-audit of the Milestone M2 codebase, shell scripts, native C++/Rust source, Java services, and E2E test suites was conducted following Iteration 3 remediations. Direct observations and evidence:

### 1.1 C++ HMAC Auth & Vsock Server (`system/linux_bridge/`)
- **`system/linux_bridge/hmac_auth.cpp`**:
  - OpenSSL HMAC-SHA256 (`HMAC(EVP_sha256(), ...)`) is used when available.
  - Non-OpenSSL fallback is a complete standalone FIPS 180-4 SHA-256 implementation (`sha256_internal::sha256`) and RFC 2104 HMAC inner/outer padding engine (`ipad = k ^ 0x36`, `opad = k ^ 0x5C`). Zero dummy XOR fallback routines exist.
  - Constant-time signature comparison (`constantTimeCompare`) prevents timing side-channel attacks.
  - Single-use token tracking via `sUsedTokens` hash set protected by `sTokenMutex` blocks replay attacks.
  - 5.0-second handshake expiration window (`HANDSHAKE_TIMEOUT_SEC`) strictly enforced.
- **`system/linux_bridge/vsock_server.cpp`**:
  - Bound POSIX `AF_VSOCK` sockets with guest CID verification (`clientAddr.svm_cid != ALLOWED_GUEST_CID`). Connections from unauthorized CIDs are rejected immediately.

### 1.2 Rust Guest Bridge Agent (`guest/bridge-agent/`)
- **`guest/bridge-agent/src/auth.rs`**:
  - Uses standard `hmac::Hmac<sha2::Sha256>` crate for authentic HMAC-SHA256 computation (`compute_hmac_response`).
  - Uses `zeroize::Zeroize` crate (`zeroize_token`) for volatile memory sanitization.
- **`guest/bridge-agent/src/vsock.rs`**:
  - Genuine POSIX `AF_VSOCK` socket connection established via `libc::socket(AF_VSOCK, libc::SOCK_STREAM, 0)` and `libc::connect`.
- **`guest/bridge-agent/src/main.rs`**:
  - Token extracted from `/proc/cmdline`, passed into `perform_host_handshake()`, transmitted over AF_VSOCK Port 5000 with 13-byte header and 64-byte payload, and immediately zeroized via `auth::zeroize_token()`.

### 1.3 Android CE Master Key & Storage Manager (`LinuxManagerService.java`)
- **`LinuxManagerService.java`**:
  - `getOrGeneratePersistentMasterKey(int userId)` reads or generates a persistent 32-byte key at `/data/system/users/<userId>/linux_ce_master.key`.
  - Key derivation uses HKDF-SHA256 via `javax.crypto.Mac` ("HmacSHA256") to derive 64-byte LUKS2 master key.
  - `onUserLocked(int userId)` executes memory zeroing via `java.util.Arrays.fill(mCeKeyBytes, (byte) 0)` and revokes `mCeKeyAvailable`.

### 1.4 Host & Guest Shell Scripts (`guest/scripts/`)
- **`guest/scripts/launch_vm.sh`**:
  - Read-only file descriptor redirection (`exec 200<"$BASE_IMG"`, `exec 201<"$OVERLAY_IMG"`) used for file locking via `flock -n 200` or `fcntl.flock`, completely preventing POSIX write truncation (`O_TRUNC`).
  - Lock failure produces `ERROR: ResourceBusy: base_rootfs.img is locked by another process` and exits with status 3.
  - JSON parameters dynamically extracted via inline Python (`python3 -c`).
- **`guest/scripts/init_storage_layout.sh`**:
  - Checks `[ ! -f "$IMG" ] || [ ! -s "$IMG" ]` for 4 storage layers (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`), automatically re-creating and formatting 0-byte truncated files to their full capacity (2.5GB, 4.0GB, 5.0GB).
- **`guest/scripts/guest_mount_overlay.sh`**:
  - Includes upperdir wipe recovery on OverlayFS mount failure (`rm -rf "/mnt/overlay/upper/$dir"/*`).

### 1.5 Python E2E & Stress Test Suites (`tests/e2e/`, `tests/unit/`)
- In-memory mock dictionaries and hardcoded return values have been removed.
- `test_m2_tier1.py` and `test_m2_tier2.py` execute real shell scripts, compile and run C++ native binaries, and assert physical file sizes and lock contention exit codes.
- `test_m2_tier2.py` test `T2-33` verifies 0-byte image recovery (`2621440000`, `4194304000`, `5242880000` bytes).
- `test_m2_tier2.py` test `T2-35` verifies non-truncation of base_rootfs.img (2.5GB) and lock contention exit code 3 (`ResourceBusy`).

### 1.6 Empirical Build & Test Execution Output
1. **Rust Guest Agent**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"; cargo build
   # Result: Exit code 0
   ```
2. **Native C++ Daemon & Stress Tests**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test
   # Result: NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY

   clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_framing_test.cpp -o build_out/bin/challenger_m2_framing_test && ./build_out/bin/challenger_m2_framing_test
   # Result: VsockFraming C++ Stress Verification: ALL PASSED

   clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_hmac_test.cpp -o build_out/bin/challenger_m2_hmac_test && ./build_out/bin/challenger_m2_hmac_test
   # Result: HmacAuth C++ Stress Verification: ALL PASSED
   ```
3. **E2E Test Runner**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"; python3 tests/e2e/runner.py
   # Result: TOTAL TESTS: 430, PASSED: 430, FAILED: 0 (100.0% Pass Rate)
   ```
4. **Empirical Challenger 1 Stress Tests**:
   ```bash
   python3 tests/unit/challenger_m2_empirical_stress_test.py
   # Result: 11/11 tests passed (OK)
   ```

---

## 2. Logic Chain

1. **Static Code Inspection**:
   - Inspection of `hmac_auth.cpp`, `auth.rs`, `vsock.rs`, `LinuxManagerService.java`, `launch_vm.sh`, and `init_storage_layout.sh` confirmed zero instances of XOR facade routines, dummy key returns, hardcoded test shortcuts, or mock bypasses.
   - Cryptographic primitives in both host C++ (FIPS 180-4 / RFC 2104 / OpenSSL) and guest Rust (`sha2` / `hmac`) implement authentic HMAC-SHA256 challenge-response logic.

2. **Shell Script Security & Lock Verification**:
   - Inspection of `launch_vm.sh` verified read-only redirection `<` on file descriptors 200 and 201 before calling `flock`, preventing truncation.
   - Verification of `init_storage_layout.sh` confirmed `[ ! -s ]` size check, ensuring corrupted 0-byte images are restored.

3. **Behavioral & Assertion Integrity**:
   - E2E tests (`test_m2_tier1.py`, `test_m2_tier2.py`) perform genuine execution of compiled binaries and shell scripts with empirical assertions (verifying exit code 3, exact byte counts 2621440000/4194304000/5242880000, and cryptographic error rejection).

4. **Verdict Determination**:
   - Under General Project Integrity Forensics guidelines, all static, behavioral, and structural integrity checks pass cleanly. Zero prohibited patterns exist. The verdict is **CLEAN**.

---

## 3. Caveats

- **No caveats**: All 4 integrity forensic checks were independently verified through file inspection, native compilation, and test suite execution.

---

## 4. Conclusion & Forensic Audit Report

The Milestone M2 Iteration 3 implementation passes all forensic integrity checks with verdict **CLEAN**.

```markdown
## Forensic Audit Report

**Work Product**: Milestone M2 Implementation (`system/linux_bridge/`, `guest/bridge-agent/`, `LinuxManagerService.java`, `guest/scripts/`, `tests/e2e/`)  
**Profile**: General Project  
**Verdict**: CLEAN  

### Phase Results
- [Check 1: Static Code Inspection]: PASS — Real FIPS 180-4 SHA-256 / RFC 2104 HMAC C++ engine & Rust `hmac`/`sha2` crates verified. Zero XOR facade loops or dummy key returns found.
- [Check 2: Shell Script File Locking & Recovery]: PASS — `launch_vm.sh` read-only fd redirection `<` prevents file truncation. `init_storage_layout.sh` `[ ! -s ]` check recovers 0-byte files.
- [Check 3: Android CE Master Key & Memory Zeroing]: PASS — Persistent master key HKDF key derivation and `Arrays.fill` memory zeroing on lock verified.
- [Check 4: E2E Test Suite Integrity]: PASS — In-memory mocks replaced with compiled native test execution and empirical assertions.
- [Check 5: Prohibited Pattern Scan]: PASS — Zero prohibited patterns found across C++, Rust, Java, Shell, and Python files.

### Evidence
- `cargo build` in `guest/bridge-agent`: Exit Code 0.
- `./build_out/bin/linux_bridge_test`: NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY.
- `./build_out/bin/challenger_m2_framing_test`: VsockFraming C++ Stress Verification: ALL PASSED.
- `./build_out/bin/challenger_m2_hmac_test`: HmacAuth C++ Stress Verification: ALL PASSED.
- `python3 tests/e2e/runner.py`: 430/430 PASSED (100.0%).
- `python3 tests/unit/challenger_m2_empirical_stress_test.py`: 11/11 PASSED (100.0%).
```

---

## 5. Verification Method

To independently verify this audit result:

1. **Build Guest Rust Agent**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"
   cd guest/bridge-agent && cargo build
   ```

2. **Compile and Run Native C++ Tests**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -pthread -I. \
     system/linux_bridge/vsock_server.cpp \
     system/linux_bridge/hmac_auth.cpp \
     system/linux_bridge/vsock_framing.cpp \
     system/linux_bridge/socket_server.cpp \
     system/linux_bridge/tests/linux_bridge_test.cpp \
     -o build_out/bin/linux_bridge_test
   ./build_out/bin/linux_bridge_test
   ```

3. **Compile and Run Challenger Stress Tests**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -pthread -I. \
     system/linux_bridge/vsock_framing.cpp \
     tests/unit/challenger_m2_framing_test.cpp \
     -o build_out/bin/challenger_m2_framing_test
   clang++ -std=c++20 -Wall -Wextra -pthread -I. \
     system/linux_bridge/hmac_auth.cpp \
     system/linux_bridge/vsock_framing.cpp \
     tests/unit/challenger_m2_hmac_test.cpp \
     -o build_out/bin/challenger_m2_hmac_test
   ./build_out/bin/challenger_m2_framing_test
   ./build_out/bin/challenger_m2_hmac_test
   ```

4. **Run Python E2E & Empirical Stress Tests**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"
   python3 tests/e2e/runner.py
   python3 tests/unit/challenger_m2_empirical_stress_test.py
   ```
