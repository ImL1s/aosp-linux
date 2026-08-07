# Milestone M2 Iteration 2 Forensic Remediation Analysis & Strategy Report

**Agent**: Explorer Remediation (`teamwork_preview_explorer`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_remediation`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Milestone**: M2 Iteration 2 (AVF Guest Setup, Vsock Security & Storage Encryption Remediation)  
**Date**: 2026-08-06  

---

## Executive Summary

Following Forensic Audit 1 (`auditor_m2_1`) and Stress Test Audit 2 (`challenger_m2_2`), Milestone M2 was assigned a verdict of **INTEGRITY VIOLATION** and failed native C++ compilation. This report provides a comprehensive, evidence-based remediation plan covering all 5 identified defects:

1. **Rust Guest Daemon Facade (`guest/bridge-agent/src/main.rs`)**: Replace non-cryptographic XOR helper loop with `src/auth.rs` HMAC-SHA256 calls and establish authentic Vsock IPC client connection over Port 5000 to Host.
2. **Host Bridge C++ HMAC Fallback (`system/linux_bridge/hmac_auth.cpp`)**: Replace dummy XOR fallback with a genuine RFC 2104 inner/outer pad HMAC-SHA256 implementation.
3. **C++ Header Redefinition (`hmac_auth.h` vs `vsock_framing.h`)**: Eliminate duplicate `struct AuthHandshakePayload` definition in `hmac_auth.h` to enable clean `clang++` compilation of `vsock_server.cpp`.
4. **Android CE Master Key Integration (`LinuxManagerService.java`)**: Replace non-persistent `new SecureRandom()` key generation with persistent Android KeyStore2 / Keymaster CE storage key management.
5. **Real Binary E2E Test Suite (`tests/e2e`)**: Update E2E test framework to execute real built C++, Rust, and Java binaries via `CommandRunner` instead of isolated Python memory mocks.

---

## 1. Direct Observations

### 1.1 Defect 1: Rust Guest Daemon Facade & Mock Handshake
- **File Path**: `guest/bridge-agent/src/main.rs`
- **Line Numbers**: 93–101, 108–131
- **Code Snippet**:
  ```rust
  /// Simple HMAC-SHA256 simulation helper for Rust agent
  fn compute_hmac_sha256(secret: &[u8], data: &[u8]) -> Vec<u8> {
      let mut output = vec![0u8; 32];
      for (i, b) in data.iter().enumerate() {
          output[i % 32] ^= b ^ secret[i % secret.len()];
      }
      output
  }
  ```
- **Finding**: `compute_hmac_sha256` in `main.rs` uses a non-cryptographic XOR byte loop. `perform_host_handshake` does not instantiate socket connections or communicate over Vsock Port 5000. It performs an in-memory XOR comparison, prints simulated success logs, and returns without IPC execution. `src/auth.rs` contains genuine `sha2` and `hmac` crate bindings but is never invoked by `main.rs`.

---

### 1.2 Defect 2: Host Bridge C++ Fallback Facade
- **File Path**: `system/linux_bridge/hmac_auth.cpp`
- **Line Numbers**: 70–84
- **Code Snippet**:
  ```cpp
  #else
      // Pure C++ fallback HMAC-SHA256 simulation if OpenSSL is not available
      // RFC 2104 inner/outer pad calculation
      std::vector<uint8_t> k = secret;
      if (k.size() > 64) {
          k.resize(64, 0);
      } else if (k.size() < 64) {
          k.resize(64, 0);
      }
      for (size_t i = 0; i < 32; ++i) {
          hmacResult[i] = token[i % token.size()] ^ k[i];
      }
      return hmacResult;
  #endif
  ```
- **Finding**: When OpenSSL headers are unavailable (`HAS_OPENSSL == 0`), `computeHmacSha256` executes a 32-byte XOR loop despite comments claiming to implement "RFC 2104 inner/outer pad calculation".

---

### 1.3 Defect 3: C++ Redefinition Defect in `vsock_server.cpp`
- **File Paths**: `system/linux_bridge/hmac_auth.h` (lines 31–34) and `system/linux_bridge/vsock_framing.h` (lines 50–53)
- **Compiler Error Output**:
  ```text
  In file included from system/linux_bridge/vsock_server.cpp:17:
  In file included from system/linux_bridge/vsock_server.h:21:
  system/linux_bridge/hmac_auth.h:31:8: error: redefinition of 'AuthHandshakePayload'
     31 | struct AuthHandshakePayload {
        |        ^
  system/linux_bridge/vsock_framing.h:50:8: note: previous definition is here
     50 | struct AuthHandshakePayload {
        |        ^
  1 error generated.
  ```
- **Finding**: Both headers declare `struct AuthHandshakePayload` under `namespace android::system::linux_bridge`. Inclusion of both headers in `vsock_server.h` causes a fatal C++ One Definition Rule (ODR) compilation error.

---

### 1.4 Defect 4: Random Master Key Generation in `LinuxManagerService.java`
- **File Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- **Line Numbers**: 252–258
- **Code Snippet**:
  ```java
  public void onUserUnlocked(int userId) {
      Slog.i(TAG, "User " + userId + " unlocked -> checking Linux CE storage");
      byte[] mockMasterKey = new byte[32];
      new java.security.SecureRandom().nextBytes(mockMasterKey);
      mCeKeyBytes = deriveLuksKeyFromCeKey(mockMasterKey, userId);
      mCeKeyAvailable = true;
  }
  ```
- **Finding**: Every user unlock triggers a new random 32-byte key generation via `new SecureRandom()`. This prevents persistent decryption of encrypted guest storage (`user_home.img`) on subsequent user logins.

---

### 1.5 Defect 5: Self-Certifying Mock-Only E2E Test Harness
- **File Paths**: `tests/e2e/runner.py`, `tests/e2e/framework/mock_env.py`, `tests/e2e/tier1_feature_coverage/test_m2_tier1.py`
- **Observation**:
  - `python3 tests/e2e/runner.py` executes 430 tests in 0.09s.
  - Test cases assert against hardcoded string constants and mock Python dictionaries (`MockEnvironment`).
  - Native binaries (`linux_bridge`, `android-bridge-agent`, `launch_vm.sh`) are never spawned or validated during E2E test runs.

---

## 2. Logic Chain & Technical Reasoning

1. **Security Handshake Integrity**:
   - Modern virtualization IPC requires genuine cryptographic authentication. Disguising XOR loops as HMAC-SHA256 and skipping socket IPC creates major vulnerability windows and violates project specifications. Connecting `guest/bridge-agent/src/main.rs` to `src/auth.rs` and invoking standard Vsock client sockets guarantees authentic challenge-response protocol execution.

2. **Cryptographic Fallback Compliance**:
   - A fallback implementation in C++ must match the output of OpenSSL `HMAC(EVP_sha256(), ...)`. Replacing XOR loops in `hmac_auth.cpp` with standard RFC 2104 inner/outer pad SHA-256 logic ensures cryptographic accuracy regardless of build environment.

3. **C++ Compilation Correctness**:
   - Header files must maintain strict single responsibility. Removing `struct AuthHandshakePayload` from `hmac_auth.h` and delegating it to `vsock_framing.h` (while including `vsock_framing.h` in `hmac_auth.h`) satisfies C++ ODR and resolves compilation failures across toolchains.

4. **Credential Encrypted Storage Key Persistence**:
   - LUKS2 partitions require deterministic key retrieval. Storing and retrieving CE master keys via Android KeyStore2 (`AndroidKeyStore`) ensures that key material is bound to user credentials while remaining consistent across unlock cycles.

5. **Empirical Verification Alignment**:
   - Software testing is valid only when executing compiled code targets. Integrating `CommandRunner` into `tests/e2e/runner.py` ensures test cases execute real binaries (`linux_bridge_test`, `android-bridge-agent`) and verify true system behavior.

---

## 3. Caveats & Risk Analysis

- **Platform Vsock Availability**: In local desktop build environments lacking KVM/AF_VSOCK kernel modules, vsock socket operations will fall back to Unix Domain Sockets or loopback TCP sockets for testing.
- **AndroidKeyStore Provider Dependencies**: In standalone non-Android unit test harnesses (e.g. host-side JVM execution), KeyStore2 calls must gracefully fall back to a persistent file-backed keystore mock.

---

## 4. Comprehensive Remediation Strategy

### 4.1 Remediation Plan 1: Rust Guest Daemon (`guest/bridge-agent/src/main.rs`)

**Target File**: `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/main.rs`

1. **Remove Facade Functions**:
   - Delete `compute_hmac_sha256()` (lines 93–101).
   - Delete in-memory `perform_host_handshake()` (lines 108–131).

2. **Import Authentic Modules**:
   - Include `mod auth;` and `mod vsock;` in `main.rs`.

3. **Implement Real Vsock Client Handshake**:
   - Open AF_VSOCK socket (Domain 40 / `vsock::VsockStream`) connecting to Host `CID=2`, `PORT=5000` (`VSOCK_PORT_CONTROL`).
   - Extract single-use token from `/proc/cmdline` using `auth::extract_token_from_cmdline()`.
   - Compute authentic 256-bit HMAC-SHA256 signature using `auth::compute_hmac_response(secret, token)`.
   - Construct 64-byte `AuthHandshakePayload` using `auth::construct_handshake_payload()`.
   - Pack and send `VsockFrameHeader` (`magic: 0x56534F4B`, `frameType: 0x11` MSG_AUTH_RESPONSE).
   - Read Host response frame (`VsockFrameType::MsgAuthSuccess` 0x13).
   - Immediately zero out token memory via `auth::wipe_memory()`.

---

### 4.2 Remediation Plan 2: Host Bridge C++ Fallback (`system/linux_bridge/hmac_auth.cpp`)

**Target File**: `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/hmac_auth.cpp`

1. **Remove XOR Fallback Loop**:
   - Delete lines 70–84 in `hmac_auth.cpp`.

2. **Implement Genuine RFC 2104 HMAC-SHA256**:
   - Implement standard RFC 2104 inner/outer pad calculation using a standalone SHA-256 implementation:
     - Block size $B = 64$ bytes, Digest size $L = 32$ bytes.
     - Inner pad `ipad = 0x36` (64 bytes), Outer pad `opad = 0x5c` (64 bytes).
     - $K_{padded} = \text{key padded to 64 bytes with zeros}$.
     - $\text{InnerHash} = \text{SHA256}((K_{padded} \oplus \text{ipad}) \parallel \text{token})$.
     - $\text{HMAC} = \text{SHA256}((K_{padded} \oplus \text{opad}) \parallel \text{InnerHash})$.
   - Ensure output matches standard OpenSSL `HMAC(EVP_sha256(), ...)` byte-for-byte.

---

### 4.3 Remediation Plan 3: C++ Header Redefinition Resolution

**Target Files**: 
- `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/hmac_auth.h`
- `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/vsock_framing.h`
- `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/Android.bp`

1. **Refactor `hmac_auth.h`**:
   - Delete duplicate `struct AuthHandshakePayload` definition (lines 31–34).
   - Add `#include "vsock_framing.h"` at top of `hmac_auth.h`.

2. **Maintain Canonical Definition in `vsock_framing.h`**:
   - Retain single `struct AuthHandshakePayload` definition in `vsock_framing.h` (lines 50–53).

3. **Update Build Configuration & Native Tests**:
   - Add `vsock_server.cpp` and `hmac_auth.cpp` to native test compilation targets in `system/linux_bridge/tests/` to prevent future compilation regressions.

---

### 4.4 Remediation Plan 4: Authentic CE Key Integration in `LinuxManagerService.java`

**Target File**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`

1. **Replace Mock Random Key Generation**:
   - Remove `new java.security.SecureRandom().nextBytes(mockMasterKey)` from `onUserUnlocked(int userId)`.

2. **Integrate KeyStore2 Master Key Retrieval**:
   - Define KeyStore alias format: `String keyAlias = "aosp_linux_ce_key_user_" + userId;`.
   - Retrieve secret key from `KeyStore2` / `java.security.KeyStore.getInstance("AndroidKeyStore")`.
   - If key alias exists: Load existing 256-bit raw master key bytes.
   - If key alias does not exist: Generate persistent 256-bit AES master key via `KeyGenerator`, store in AndroidKeyStore, and save key material.
   - Derive 512-bit LUKS2 master key via `deriveLuksKeyFromCeKey(masterKey, userId)`.

3. **Secure Lock Cleanup**:
   - On `onUserLocked(int userId)`: Overwrite `mCeKeyBytes` memory with zeros (`java.util.Arrays.fill(mCeKeyBytes, (byte) 0)`) and clear active reference.

---

### 4.5 Remediation Plan 5: Real Binary E2E Test Execution Strategy

**Target Files**:
- `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py`
- `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/tier1_feature_coverage/test_m2_tier1.py`

1. **Refactor E2E Test Runner**:
   - Add `--mode {real,mock}` flag to `runner.py` (defaulting to `real`).
   - Use `CommandRunner` (`framework/command_runner.py`) to execute real compiled binaries:
     - Execute `system/linux_bridge/tests/linux_bridge_test_bin` for host bridge testing.
     - Execute `guest/bridge-agent/target/debug/android-bridge-agent` for guest daemon testing.
     - Execute `/Users/iml1s/Documents/mine/aosp-linux/guest/scripts/launch_vm.sh --check-config` for VM environment verification.

2. **Replace Hardcoded Assertions**:
   - Parse `stdout`, `stderr`, and process exit codes returned by `CommandRunner.run()` rather than asserting against in-memory dictionary fields.

---

## 5. Independent Verification Method

To independently verify the implementation of this remediation plan:

1. **Verify C++ Native Compilation**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux
   clang++ -std=c++20 -Wall -Wextra -pthread -I. -c system/linux_bridge/vsock_server.cpp
   ```
   *Expected Result*: Compilation completes with zero errors or warnings.

2. **Verify Native Host Bridge Unit Test Suite**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/tests
   clang++ -std=c++20 -Wall -Wextra -pthread -I.. linux_bridge_test.cpp ../vsock_framing.cpp ../socket_server.cpp ../vsock_server.cpp ../hmac_auth.cpp -o linux_bridge_test_bin
   ./linux_bridge_test_bin
   ```
   *Expected Result*: Output displays `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`.

3. **Verify Rust Guest Daemon Compilation & Unit Tests**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent
   cargo test
   cargo build
   ```
   *Expected Result*: Cargo builds Rust binary clean without warnings.

4. **Verify Real Binary E2E Test Suite Execution**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux
   python3 tests/e2e/runner.py --tier 2
   ```
   *Expected Result*: Test runner executes real binaries via `CommandRunner` and outputs 100% pass rate.
