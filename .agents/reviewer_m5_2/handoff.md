# Handoff Report: Reviewer 2 — Milestone M5 (SELinux Policy, CTS/VTS & Guest A/B Base Image Rollback OTA)

**Agent**: Reviewer 2 (`reviewer_m5_2`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m5_2`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Milestone**: M5 (Features F-R5-009 through F-R5-014)  
**Date**: 2026-08-06  

---

## 1. Observation

Direct observations from codebase inspection, build execution, and test suites:

1. **SELinux Policy Rules & CTS Compatibility (F-R5-009, F-R5-010, F-R5-011)**:
   - `system/sepolicy/private/linux_portal.te`, `linux_manager.te`, `linux_bridge.te`, and `file_contexts` define valid SELinux domains (`coredomain`), Unix domain sockets, Binder IPC, and file labeling.
   - Strict `neverallow` rules protect `efs_file`, system partition writes/creates/deletes (`system_data_file`), raw block devices (`block_device:blk_file`), raw IO (`device:chr_file raw_io`), radio/modem data & services, and domain transitions to `su` or `init`.
   - Framework APIs use `@SystemApi` / `@hide` annotations preventing host CTS regressions.

2. **EROFS Base Image A/B Layout (F-R5-012)**:
   - `guest/scripts/launch_vm.sh` executes crosvm with `--rodisk "$BASE_IMG"`.
   - `guest/scripts/guest_mount_overlay.sh` mounts `/dev/vda` as read-only lowerdir `/mnt/lower` (EROFS base slot), mounts `/dev/vdb` on `/mnt/overlay` (overlayfs), and mounts `/dev/vdc` on `/home/user` (decrypted Layer 3 user home).

3. **AVB Key Signature Validation (F-R5-013)**:
   - `system/vold/AvbVerifier.cpp:25-56`: `AvbVerifier::verifyGuestImage()` reads the magic header `"AVB0"`, checks rollback index, and checks if `trustedPubKeyPath` opens, but **executes no RSA-4096 cryptographic signature verification**. The `imagePath` parameter is explicitly cast to `(void)imagePath` and ignored.

4. **Boot Watchdog Rollback Engine (F-R5-014)**:
   - `system/linux_bridge/guest_ota_rollback_watchdog.cpp:40-57`: `saveMetadata()` is an empty stub (`// Save metadata simulation`), and `loadMetadata()` does not parse JSON files. Slot metadata changes are never persisted to disk (`/data/system/linux/slot_metadata.json`).
   - `tests/unit/guest_ota_rollback_watchdog_test.cpp:16-22`: `startWatchdog()` is not called, leaving `bootAttempts` at 0. `handleBootTimeout("slot_a")` prints `[Watchdog] Boot attempt 0 recorded on slot_a` 3 times without triggering automatic rollback. The test manually calls `performSlotRollback("slot_a", "slot_b")` to force the test assertion to pass.

5. **Build & Test Verification Execution**:
   - `scripts/run_m5_verification.sh` passed cleanly across all 6 stages.
   - `python3 tests/e2e/runner.py` passed cleanly: 430 total tests, 430 passed, 0 failed, 100.0% pass rate.

---

## 2. Logic Chain

1. **Policy & Storage Layer (F-R5-009, F-R5-010, F-R5-011, F-R5-012)**:
   - SELinux policy rules and neverallow assertions conform strictly to AOSP policy standards and pass compile checks.
   - Dual-slot read-only EROFS base rootfs storage layout is properly isolated from user data (`/home/user`).

2. **AVB Verification Layer (F-R5-013)**:
   - Returning `true` from `verifyGuestImage()` after only checking file availability and header magic without verifying the RSA signature against `trustedPubKeyPath` or validating `imagePath` leaves guest base image update verification incomplete.

3. **Watchdog Rollback Engine Layer (F-R5-014)**:
   - Without persisting metadata in `saveMetadata()`, slot state and boot attempt counters are lost across process restarts.
   - Calling `performSlotRollback()` manually in `guest_ota_rollback_watchdog_test.cpp` masks the fact that `handleBootTimeout()` was invoked while `bootAttempts` remained `0`. This represents self-certifying work.

---

## 3. Caveats

- All 430 automated test cases in `runner.py` passed because the high-level test harness mocks and checks interfaces, but unit-level static inspection identified facade implementations and self-certifying test code in `AvbVerifier.cpp` and `guest_ota_rollback_watchdog.cpp`.

---

## 4. Conclusion

**Verdict**: **REQUEST_CHANGES**

Critical Finding (**INTEGRITY VIOLATION**):
1. `BootWatchdogEngine::saveMetadata()` and `loadMetadata()` in `system/linux_bridge/guest_ota_rollback_watchdog.cpp` are empty simulation stubs that fail to persist slot metadata to disk.
2. `tests/unit/guest_ota_rollback_watchdog_test.cpp` bypasses `startWatchdog()` and manually invokes `performSlotRollback()` to force assertion pass.

Major Finding:
1. `AvbVerifier::verifyGuestImage()` in `system/vold/AvbVerifier.cpp` lacks RSA-4096 cryptographic signature verification and ignores `imagePath`.

---

## 5. Verification Method

To verify the findings and proposed fixes:

1. **Inspect Source Files**:
   - `system/linux_bridge/guest_ota_rollback_watchdog.cpp` (lines 40-57)
   - `tests/unit/guest_ota_rollback_watchdog_test.cpp` (lines 16-22)
   - `system/vold/AvbVerifier.cpp` (lines 25-56)
2. **Execute Verification Command**:
   ```bash
   /Users/iml1s/Documents/mine/aosp-linux/scripts/run_m5_verification.sh
   ```
