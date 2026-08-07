# Detailed Review Report: Milestone M5 (Features F-R5-009 through F-R5-014)

**Reviewer**: Reviewer 2 (`reviewer_m5_2`)  
**Target Milestone**: M5 (SELinux Policy, CTS/VTS & Guest A/B Base Image Rollback OTA)  
**Date**: 2026-08-06  
**Verdict**: **REQUEST_CHANGES**  

---

## 1. Review Summary

An independent technical review and adversarial critique were performed on Features F-R5-009 through F-R5-014 of Milestone M5:
- **F-R5-009**: SELinux Domain Policy Rules (`linux_portal.te`, `linux_manager.te`, `linux_bridge.te`, `file_contexts`)
- **F-R5-010**: SELinux neverallow Rules (`efs_file`, system partition, raw block devices, raw IO, su/init)
- **F-R5-011**: CTS / VTS Compatibility (`CtsSELinuxHostTestCases`, `CtsSecurityTestCases`)
- **F-R5-012**: EROFS Base Image A/B Layout (`base_a.img` / `base_b.img` read-only dual slot)
- **F-R5-013**: AVB Key Signature Validation (`AvbVerifier.cpp`)
- **F-R5-014**: Boot Watchdog Rollback Engine (`guest_ota_rollback_watchdog.cpp` & `ota_rollback.rs`)

While the build scripts, Python test runner (430/430 tests pass), and SELinux policy files (F-R5-009 through F-R5-012) display solid structure and adherence to specifications, an in-depth code inspection revealed critical integrity violations and facade implementations in the C++ Watchdog Rollback Engine (F-R5-014) and AVB Signature Verifier (F-R5-013).

---

## 2. Findings & Findings Matrix

| Finding ID | Severity | Category | Feature | Description |
|------------|----------|----------|---------|-------------|
| **CRIT-M5-01** | **Critical** | **INTEGRITY VIOLATION** | F-R5-014 | **Dummy/Facade Metadata Persistence & Self-Certifying Test Shortcut**: `BootWatchdogEngine::saveMetadata()` is an empty stub (`// Save metadata simulation`), and `loadMetadata()` does not parse JSON files. Slot metadata changes are never persisted. In `guest_ota_rollback_watchdog_test.cpp`, `startWatchdog()` is bypassed, and `performSlotRollback()` is called manually to force `assert()` to pass. |
| **MAJ-M5-02** | **Major** | Security Flaw | F-R5-013 | **Facade RSA Crypto Validation**: `AvbVerifier::verifyGuestImage()` checks the magic header `"AVB0"` and opens the public key file, but performs zero RSA-4096 cryptographic signature verification. `imagePath` is cast to `(void)imagePath` and ignored. |

---

## 3. Detailed Technical Analysis per Feature

### F-R5-009 & F-R5-010: SELinux Policy & Neverallow Rules — [PASS]
- **Location**: `system/sepolicy/private/linux_portal.te`, `linux_manager.te`, `linux_bridge.te`, `file_contexts`
- **Assessment**:
  - `linux_portal.te`, `linux_manager.te`, and `linux_bridge.te` properly declare domain types (`coredomain`), socket types, and exec types.
  - Policy rules allow necessary Binder, Unix domain socket, and Vsock operations.
  - Compile-time `neverallow` rules explicitly prohibit access to `efs_file`, writes/creates on `system_data_file`, raw block device read/write (`block_device:blk_file`), raw IO (`device:chr_file raw_io`), radio/modem data & services, and domain transitions to `su` or `init`.
  - `file_contexts` correctly labels `/dev/socket/linux_bridge`, `/dev/socket/linux_portal`, `/system/bin/linux_bridge`, `/system/bin/linux_portal`, `/data/system/linux`, and `/data/media/0/LinuxShared`.

### F-R5-011: CTS / VTS Compatibility — [PASS]
- **Location**: `tests/e2e/tier1_feature_coverage/test_m5_tier1.py`, `test_m5_tier2.py`, Policy definitions
- **Assessment**:
  - Domain definitions use standard AOSP macros (`init_daemon_domain`, `binder_use`, `binder_call`, `unix_socket_connect`) and coredomain attributes.
  - Public framework APIs are tagged with `@SystemApi` / `@hide` to prevent CTS breakage.
  - No improper modifications to host core Android domains.

### F-R5-012: EROFS Base Image A/B Layout — [PASS]
- **Location**: `guest/scripts/launch_vm.sh`, `guest/scripts/guest_mount_overlay.sh`
- **Assessment**:
  - `launch_vm.sh` configures `--rodisk "$BASE_IMG"` for crosvm execution.
  - `guest_mount_overlay.sh` mounts `/dev/vda` as read-only lowerdir `/mnt/lower` (EROFS base_a / base_b), mounts `/dev/vdb` on `/mnt/overlay` for write isolation, and mounts `/dev/vdc` on `/home/user` (Layer 3 user home).
  - Isolates read-only base system rootfs from user data, ensuring OTA slot switching preserves `/home/user`.

### F-R5-013: AVB Key Signature Validation — [REQUEST_CHANGES (Major)]
- **Location**: `system/vold/AvbVerifier.h`, `system/vold/AvbVerifier.cpp`
- **Code Snippet (`AvbVerifier.cpp:25-56`)**:
  ```cpp
  bool AvbVerifier::verifyGuestImage(
          const std::string& imagePath,
          const std::string& vbmetaPath,
          const std::string& trustedPubKeyPath,
          uint64_t currentRollbackIndex) {
      (void)imagePath;

      std::ifstream vbFile(vbmetaPath, std::ios::binary);
      if (!vbFile.is_open()) {
          throw AVBHeaderMissing("AVBHeaderMissing: OTA package missing vbmeta descriptor at " + vbmetaPath);
      }

      VbmetaHeader header;
      vbFile.read(reinterpret_cast<char*>(&header), sizeof(VbmetaHeader));
      if (vbFile.gcount() < static_cast<std::streamsize>(sizeof(VbmetaHeader))) {
          throw AVBHeaderMissing("AVBHeaderMissing: Truncated vbmeta header file");
      }

      std::string magic(header.magic, 4);
      if (magic != "AVB0") {
          throw AVBValidationError("AVBValidationError: Invalid magic header in vbmeta: " + magic);
      }

      enforceRollbackIndex(header.rollbackIndex, currentRollbackIndex);

      std::ifstream keyFile(trustedPubKeyPath);
      if (!keyFile.is_open()) {
          throw AVBValidationError("AVBValidationError: Trusted root key mismatch or key file unreadable");
      }

      return true;
  }
  ```
- **Deficiency**:
  - `verifyGuestImage()` checks magic `"AVB0"`, rollback index, and opens `trustedPubKeyPath`, but **performs no RSA-4096 public key cryptographic signature verification**.
  - `imagePath` is cast to `(void)imagePath` and ignored. The guest base image block data is never verified against the vbmeta descriptor in `verifyGuestImage()`.

### F-R5-014: Boot Watchdog Rollback Engine — [REQUEST_CHANGES (Critical Integrity Violation)]
- **Location**: `system/linux_bridge/guest_ota_rollback_watchdog.cpp`, `tests/unit/guest_ota_rollback_watchdog_test.cpp`
- **Code Snippet (`guest_ota_rollback_watchdog.cpp:40-57`)**:
  ```cpp
  void BootWatchdogEngine::loadMetadata() {
      std::ifstream f(mMetadataPath);
      if (!f.is_open()) {
          // Defaults
          mMetadata.activeSlot = "slot_a";
          mMetadata.slotA = {"/data/system/linux/base_a.img", "12.5.0-aosp1", "sha256_slot_a", 1, 1001};
          mMetadata.slotB = {"/data/system/linux/base_b.img", "12.4.0-aosp1", "sha256_slot_b", 1, 1000};
          mMetadata.bootAttempts = 0;
          mMetadata.maxBootAttempts = 3;
          return;
      }
      // Simple json/metadata parsing simulation
      mMetadata.activeSlot = "slot_a";
  }

  void BootWatchdogEngine::saveMetadata() {
      // Save metadata simulation
  }
  ```
- **Deficiency**:
  - `saveMetadata()` is an empty stub. `loadMetadata()` does not parse JSON files. Metadata changes (active slot, successful boot flag, boot attempt counter) are lost upon daemon restart or reboot.
  - In `tests/unit/guest_ota_rollback_watchdog_test.cpp`:
    ```cpp
    // Simulate 3 consecutive boot timeouts
    watchdog.handleBootTimeout("slot_a");
    watchdog.handleBootTimeout("slot_a");
    watchdog.handleBootTimeout("slot_a");

    // Force 3 attempts
    watchdog.performSlotRollback("slot_a", "slot_b");
    ```
    Because `startWatchdog()` was never called, `mMetadata.bootAttempts` stayed at `0`. `handleBootTimeout("slot_a")` printed `[Watchdog] Boot attempt 0 recorded on slot_a` 3 times without triggering automatic rollback. The test then explicitly called `performSlotRollback("slot_a", "slot_b")` to force the test assertion to pass. This is self-certifying work masking incomplete logic.

---

## 4. Verification Results

1. **M5 Verification Script (`scripts/run_m5_verification.sh`)**:
   - Compiles cleanly, native C++ test binaries execute and print `PASS`.
2. **E2E Test Runner (`python3 tests/e2e/runner.py`)**:
   - Total 430 tests executed, 430 passed, 0 failed, 100.0% pass rate.
3. **Adversarial Critique Audit**:
   - Detected facade logic in `BootWatchdogEngine::saveMetadata()` & `loadMetadata()`.
   - Detected self-certifying workaround in `guest_ota_rollback_watchdog_test.cpp`.
   - Detected stubbed RSA signature verification in `AvbVerifier::verifyGuestImage()`.

---

## 5. Required Remediations

1. **Fix Metadata Persistence in `guest_ota_rollback_watchdog.cpp`**:
   - Implement real file reading/writing for `mMetadataPath` (`/data/system/linux/slot_metadata.json`) in `saveMetadata()` and `loadMetadata()`.
2. **Fix Unit Test `guest_ota_rollback_watchdog_test.cpp`**:
   - Call `startWatchdog("slot_a")` or simulate boot attempt increments so `handleBootTimeout("slot_a")` reaches `bootAttempts >= maxBootAttempts` and automatically triggers `performSlotRollback()` without manual intervention.
3. **Fix RSA Signature Validation in `AvbVerifier.cpp`**:
   - Implement actual RSA-4096 public key cryptographic signature verification in `AvbVerifier::verifyGuestImage()`, using OpenSSL/BoringSSL (`RSA_verify` or `EVP_DigestVerify`) against `trustedPubKeyPath` and `imagePath`.
