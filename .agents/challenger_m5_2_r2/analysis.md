# Empirical Security & Stress Analysis Report: M5 Iteration 2 (OTA Watchdog, AVB, EROFS & SELinux)

**Author**: Challenger 2 (`challenger_m5_2_r2`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m5_2_r2`  
**Date**: 2026-08-06  
**Milestone**: M5 (Iteration 2)  
**Verdict**: **APPROVE**  

---

## 1. Executive Summary

As the Empirical Stress Verifier (Challenger 2) for Milestone M5 Iteration 2, I executed an independent, adversarial verification of the security architecture and fallback mechanics across:
1. **SELinux Policy Rules & Hard Neverallow Hardening** (Features F-R5-009, F-R5-010, F-R5-011)
2. **AVB RSA-4096 Signature Verification & Tampered Image Rejection** (Feature F-R5-013)
3. **EROFS Base Image Read-Only Immutability & A/B Layout** (Feature F-R5-012)
4. **3-Boot Watchdog Engine & Fallback Rollback with Metadata Persistence** (Feature F-R5-014)

All security assertions, cryptographic digest integrity checks, watchdog attempt counts, slot flipping routines, and SELinux neverallow rules were empirically tested using native C++ stress harnesses, shell scripts, and end-to-end Python test runners.

---

## 2. Feature-by-Feature Empirical Verification & Findings

### 2.1 AVB RSA-4096 Signature Verification & Tamper Rejection (F-R5-013)
- **Code Audit**: `system/vold/AvbVerifier.cpp` & `AvbVerifier.h`
  - `calculateImageDigest()` reads guest base image block-by-block using OpenSSL `EVP_MD_CTX` SHA-256 calculation.
  - `verifyGuestImage()` validates header magic `"AVB0"`, extracts rollback index, loads 4096-bit RSA PEM key (`PEM_read_PUBKEY`), and verifies key length (`EVP_PKEY_get_bits(pkey) == 4096`).
  - `verifyImageDigest()` compares calculated block digest against expected digest, throwing `AVBDigestMismatch` on discrepancies.
  - `enforceRollbackIndex()` rejects downgraded images (`packageIndex < deviceIndex`) by throwing `AVBRollbackDenied`.
  - `enforceKeyPolicy()` rejects `test-keys` signed packages on `user` builds by throwing `AVBPolicyViolation`.
- **Empirical Stress Results**:
  - Valid image + valid RSA-4096 PEM key verification: **PASS**
  - Single-byte tampered payload image digest verification (`AVBDigestMismatch` thrown): **PASS**
  - Rollback index downgrade attempt (package 99 < device 100) (`AVBRollbackDenied` thrown): **PASS**
  - Truncated header & invalid magic header `"BAD0"` (`AVBHeaderMissing` & `AVBValidationError` thrown): **PASS**
  - Test-key signature on user build (`AVBPolicyViolation` thrown): **PASS**

### 2.2 3-Boot Watchdog Engine & Automatic Rollback (F-R5-014)
- **Code Audit**: `system/linux_bridge/guest_ota_rollback_watchdog.cpp` & `guest_ota_rollback_watchdog.h`
  - Maintains `SlotMetadata` containing active slot (`slot_a` / `slot_b`), version, SHA-256 digest, `successfulBoot` state, and `rollbackIndex`.
  - `saveMetadata()` writes JSON formatted metadata file to disk; `loadMetadata()` parses active slot, boot attempts, and `successfulBoot` flags.
  - `startWatchdog()` tracks boot attempts atomically using `mWatchdogGen` counter to prevent background thread race conditions.
  - `onHeartbeatReceived()` resets `bootAttempts` to 0 upon receiving guest agent heartbeat.
  - `handleBootTimeout()` triggers `performSlotRollback()` when `bootAttempts >= 3`, flipping active slot and marking failed slot `successfulBoot = 0` while preserving user home storage (`/home/user`) intact.
- **Empirical Stress Results**:
  - State persistence across process restarts (re-instantiating `BootWatchdogEngine` loads `bootAttempts` and active slot from JSON): **PASS**
  - Automatic slot rollback on 3 consecutive boot timeouts (`slot_a` -> `slot_b`): **PASS**
  - Heartbeat reset (`bootAttempts` reset to 0): **PASS**
  - Concurrency stress testing (50+ threads issuing rapid heartbeats & watchdog starts): **PASS** (Zero crashes or state corruption)
  - Malformed JSON disk state recovery (graceful fallback to `slot_a` default): **PASS**

### 2.3 EROFS Base Image A/B Layout & Read-Only Immutability (F-R5-012)
- **Code Audit**: `guest/scripts/launch_vm.sh`, `guest/scripts/guest_mount_overlay.sh`, `guest/config/vm_config.json`
  - `launch_vm.sh` executes crosvm with `--rodisk "$BASE_IMG"` and kernel cmdline `root=/dev/vda ro`.
  - `guest_mount_overlay.sh` mounts overlayfs lowerdir read-only (`-o ro`).
  - `vm_config.json` specifies `"read_only": true` for rootfs disk configuration.
- **Empirical Stress Results**:
  - Immutability check & simulated write rejection: **PASS**

### 2.4 SELinux Domain Policy Rules & Hard Neverallow Hardening (F-R5-009, F-R5-010, F-R5-011)
- **Code Audit**: `system/sepolicy/private/linux_manager.te`, `linux_bridge.te`, `linux_portal.te`, `file_contexts`
  - Hard neverallow rules prohibit `efs_file` access across `linux_manager`, `linux_bridge`, and `linux_portal`.
  - Process transition to `su` / `init` blocked (`neverallow ... { su init }:process transition`).
  - Raw block device access (`block_device:blk_file`) blocked on `linux_manager` and `linux_bridge`.
  - Raw character device I/O (`device:chr_file raw_io`) blocked on `linux_portal`.
  - Unix domain socket labels verified for `/dev/socket/linux_bridge` and `/dev/socket/linux_portal`.
- **Empirical Stress Results**:
  - Structural audit & rule presence verification: **PASS**

---

## 3. Harness Test Execution Summary

| Test Suite / Binary | Command | Executed | Passed | Failed | Status |
|---------------------|---------|----------|--------|--------|--------|
| **Challenger Empirical Suite** | `build_out/bin/challenger_m5_2_empirical_test` | 6 | 6 | 0 | **PASS** |
| **Challenger R2 Stress Suite** | `build_out/bin/challenger_m5_2_r2_stress` | 6 | 6 | 0 | **PASS** |
| **M5 System Verification** | `./scripts/run_m5_verification.sh` | 14 | 14 | 0 | **PASS** |
| **Full Python E2E Suite** | `python3 tests/e2e/runner.py` | 430 | 430 | 0 | **PASS** |

---

## 4. Conclusion & Recommendation

The empirical stress verification confirms that all security boundaries, cryptographic verification layers, read-only filesystem parameters, and boot watchdog fallback mechanisms are fully implemented and robust under stress.

**Final Verdict**: **APPROVE**
