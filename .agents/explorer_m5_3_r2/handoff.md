# Handoff Report: Remediation Strategy for OTA Watchdog Metadata & AVB Verifier Crypto (F-R5-013 & F-R5-014)

**Agent**: Explorer 3 (`explorer_m5_3_r2`)  
**Target**: Remediation Design for F-R5-013 (AVB Signature Validation) and F-R5-014 (Boot Watchdog Rollback Engine)  
**Date**: 2026-08-06  
**Type**: Hard Handoff Report  

---

## 1. Observation

Direct observations from forensic audit reports (`auditor_m5_1/analysis.md`), reviewer reports (`reviewer_m5_2/analysis.md`), and workspace code inspection:

1. **`guest_ota_rollback_watchdog.cpp:40-57`**:
   - `loadMetadata()` opens `mMetadataPath`, but if opened, it executes only `mMetadata.activeSlot = "slot_a";`, bypassing JSON parsing.
   - `saveMetadata()` is an empty stub function: `void BootWatchdogEngine::saveMetadata() { // Save metadata simulation }`.
   - Result: Metadata state (active slot, attempt counters, successful boot flags) is never persisted to disk.

2. **`guest_ota_rollback_watchdog_test.cpp:16-24`**:
   - The test invokes `handleBootTimeout("slot_a")` 3 times without ever calling `startWatchdog()`, so `bootAttempts` remains `0`.
   - `handleBootTimeout()` logs attempt `0` three times without triggering automatic rollback.
   - The test manually calls `watchdog.performSlotRollback("slot_a", "slot_b")` to force the test assertion `assert(watchdog.getActiveSlot() == "slot_b")` to pass.

3. **`AvbVerifier.cpp:25-56`**:
   - `verifyGuestImage()` accepts `imagePath` but suppresses it via `(void)imagePath;`. Base image block data is never read or verified.
   - `verifyGuestImage()` checks magic header `"AVB0"` and rollback index, and checks `trustedPubKeyPath` file readability, but performs zero RSA-4096 cryptographic signature verification.
   - `verifyImageDigest()` compares user-supplied strings rather than computing the SHA-256 digest of the image file.

4. **`scripts/run_m5_verification.sh:69-72`**:
   - Compiler invocation `clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" ...` does not link OpenSSL/crypto libraries required for real RSA/SHA-256 operations.

---

## 2. Logic Chain

1. **Observation**: `saveMetadata()` is empty, and `loadMetadata()` ignores JSON parsing.
   - **Reasoning**: Without persistent file write and parse capabilities, daemon restarts lose boot attempt counts and slot transitions.
   - **Conclusion**: Implement JSON formatting in `saveMetadata()` and JSON parsing in `loadMetadata()`.

2. **Observation**: `guest_ota_rollback_watchdog_test.cpp` calls `performSlotRollback()` manually because `bootAttempts` stayed at `0`.
   - **Reasoning**: A test must exercise the automatic rollback trigger inside `handleBootTimeout()` when `bootAttempts >= maxBootAttempts` (3).
   - **Conclusion**: Refactor the unit test to call `startWatchdog()` or accumulate boot attempt counters, asserting that `handleBootTimeout()` automatically triggers rollback and persists state to file.

3. **Observation**: `AvbVerifier::verifyGuestImage()` suppresses `imagePath` with `(void)imagePath;` and skips RSA verification.
   - **Reasoning**: AVB verification requires validating the base image hash and verifying the RSA-4096 signature against the trusted public key.
   - **Conclusion**: Implement SHA-256 hashing for `imagePath` via `calculateImageDigest()` and RSA-4096 public key verification using OpenSSL `PEM_read_PUBKEY` & `EVP_DigestVerify` in `AvbVerifier.cpp`.

4. **Observation**: Linking flags for OpenSSL are missing in `scripts/run_m5_verification.sh`.
   - **Reasoning**: OpenSSL headers (`<openssl/pem.h>`, `<openssl/evp.h>`) require linking against `-lcrypto`.
   - **Conclusion**: Add `pkg-config --cflags --libs openssl` to the compiler command line in `scripts/run_m5_verification.sh`.

---

## 3. Caveats

1. **Read-Only Role**: Per agent instructions, Explorer 3 is a read-only investigation agent. Source code edits must be executed by an Implementer agent based on the step-by-step remediation plan in `analysis.md`.
2. **OpenSSL Dependency**: OpenSSL 3 is available on the local macOS host (`/opt/homebrew/opt/openssl@3`). The build script must use `pkg-config` to ensure cross-platform compatibility with Linux build environments.

---

## 4. Conclusion

A complete, actionable remediation plan has been designed for F-R5-013 and F-R5-014:
1. **`guest_ota_rollback_watchdog.cpp`**: Replace stubbed serialization/parsing with genuine JSON disk persistence in `saveMetadata()` and `loadMetadata()`.
2. **`guest_ota_rollback_watchdog_test.cpp`**: Exercise automatic rollback countdown and verify disk state reloading.
3. **`AvbVerifier.cpp`**: Implement SHA-256 block hash calculation in `calculateImageDigest()` and RSA-4096 public key verification using OpenSSL.
4. **`scripts/run_m5_verification.sh`**: Add OpenSSL linking flags (`OPENSSL_CFLAGS` & `OPENSSL_LIBS`).

All detailed design specs, before/after code snippets, and verification procedures are documented in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3_r2/analysis.md`.

---

## 5. Verification Method

To independently verify the remediations once implemented:

1. **Run M5 Verification Suite**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux
   ./scripts/run_m5_verification.sh
   ```
2. **Inspect Generated C++ Test Output**:
   Confirm that both `./build_out/bin/guest_ota_rollback_watchdog_test` and `./build_out/bin/avb_verifier_test` execute cleanly with exit code `0` and print `PASS`.
3. **Verify Metadata JSON File**:
   Inspect `/tmp/test_slot_metadata.json` or `/data/system/linux/slot_metadata.json` to verify valid JSON formatting and active slot state persistence.
