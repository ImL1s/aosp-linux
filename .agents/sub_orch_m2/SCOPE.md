# Scope: Milestone M2 — AVF Guest Setup & CE Storage Encryption (`SCOPE.md`)

## Executive Summary
Milestone M2 implements the AVF (Android Virtualization Framework) Non-Protected Debian 12 ARM64 Guest VM bring-up, 4-layer storage image layout (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`), LUKS2 storage encryption bound to Android Credential Encrypted (CE) keys, 3-port vsock allocation (5000 Control, 5001 PTY, 5002 Wayland), and single-use 256-bit token injection with HMAC-SHA256 authenticated handshake.

Status: **DONE** (Passed all gate checks in Iteration 3)

---

## Feature Inventory for Milestone M2

| # | Feature ID | Feature Name | Description | Source | Status |
|---|------------|--------------|-------------|--------|--------|
| 1 | F-R2-001 | Non-Protected Debian VM | AVF crosvm Non-Protected Debian 12 ARM64 guest environment config & boot scripts | PROJECT.md | DONE |
| 2 | F-R2-002 | 4-Layer Storage Image Layout | 4-layer layout (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`) | PROJECT.md | DONE |
| 3 | F-R2-003 | LUKS2 CE Storage Encryption | `user_home.img` LUKS2 encryption bound to Android Credential Encrypted (CE) key via Keymaster/Keystore2 | PROJECT.md | DONE |
| 4 | F-R2-004 | Vsock 3-Port Allocation | Vsock allocation (Port 5000 Control RPC, Port 5001 PTY Stream, Port 5002 Wayland Display) | PROJECT.md | DONE |
| 5 | F-R2-005 | HMAC-SHA256 Auth Handshake | 256-bit single-use token injection & HMAC-SHA256 authenticated handshake between Host & Guest `android-bridge-agent` | PROJECT.md | DONE |

---

## Implementation Components & Files

1. **AVF & Crosvm Guest Configuration**:
   - `packages/modules/Virtualization/vm_config/debian_guest.json` & `guest/config/vm_config.json` (crosvm Non-Protected ARM64 VM config, vCPU=4, RAM=2048MB/4096MB, vsock cid=3).
   - `system/linux_bridge/src/avf_launcher.cpp` / `avf_launcher.h` (C++ AVF lifecycle manager: start, stop, suspend, resume, status checks).
   - `guest/scripts/launch_vm.sh` (Host VM boot script with `exec 200<` read-only locking & dynamic JSON config parsing).

2. **4-Layer Storage Manager**:
   - `system/linux_bridge/src/storage_manager.cpp` / `storage_manager.h` (Manages image paths under `/data/misc/linux/`, overlayfs mounting configs).
   - `guest/scripts/init_storage_layout.sh` (Sparse creation and `[ ! -f ] || [ ! -s ]` auto-recovery for 0-byte/corrupted images).
   - `guest/scripts/guest_mount_overlay.sh` (OverlayFS upperdir/workdir setup with ENOSPC space check & retry/fallback).

3. **LUKS2 CE Encryption Module**:
   - `system/linux_bridge/src/luks_crypto.cpp` / `luks_crypto.h` (`libcryptsetup` / `dm-crypt` integration, LUKS2 format/open/close).
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` & `LinuxCeKeyManager.java` (Android CE master key persistence under `/data/system/users/<userId>/linux_ce_master.key`, HKDF-SHA256 512-bit LUKS key derivation, and lock screen `Arrays.fill` zeroing).

4. **Vsock Multiplexer & HMAC Auth Handshake**:
   - `system/linux_bridge/src/vsock_auth.cpp` / `vsock_auth.h` & `hmac_auth.cpp` / `hmac_auth.h` (Standalone C++ FIPS 180-4 SHA-256 and RFC 2104 HMAC-SHA256 engine, constant-time compare, 5s timeout, single-use anti-replay token table).
   - `system/linux_bridge/src/vsock_server.cpp` / `vsock_server.h` (Strict unauthenticated port 5001/5002 binding rejection & CID 3 filtering).
   - `guest/bridge-agent/src/main.rs`, `src/vsock.rs`, `src/auth.rs` (Debian Guest Rust daemon for POSIX `AF_VSOCK` CID 2 Port 5000 4-step challenge-response handshake, `hmac`/`sha2` calculation, and `zeroize` volatile memory wiping).

5. **Verification & Test Suite**:
   - `scripts/run_m2_verification.sh` (Master 6-stage verification script).
   - `tests/e2e/runner.py`, `test_m2_tier1.py`, `test_m2_tier2.py` (Authentic subprocess execution E2E test runner, 430/430 tests passing).
   - Empirical stress tests (`challenger_m2_empirical_stress_test.py`, `challenger_m2_empirical_test.py`, native C++ binaries).

---

## Completion Criteria Checklist
1. [x] `run_m2_verification.sh` passes 100% with exit code 0.
2. [x] Reviewer APPROVE verdicts from 2 independent reviewers (Reviewer 1 & 2 APPROVE in Iteration 3).
3. [x] Challenger APPROVE verdicts from 2 stress test harnesses (Challenger 1 & 2 APPROVE in Iteration 3).
4. [x] Forensic Auditor CLEAN verdict from `teamwork_preview_auditor` (Auditor CLEAN in Iteration 3).
