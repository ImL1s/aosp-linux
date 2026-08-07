# Project: AOSP Dual-OS Verification & Deployment

## Architecture
- Dual-OS architecture combining AOSP host with virtualized guest Linux subsystem.
- LinuxManagerService (`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`) manages lifecycle, VSOCK IPC, and LUKS2 storage keys.
- SELinux policy `linux_manager.te` enforces isolation constraints.
- `LinuxTerminal.apk` provides UI terminal emulator bound via JNI (`libvterm_jni`).
- `android-bridge-agent` (Rust binary) runs inside guest VM for authentication, VSOCK control, and OTA rollback watchdog.
- 4-layer storage layout (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`) protected by AVB 2.0 signatures (`vbmeta.img` RSA-4096).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | R1: E2E & Stress Test Execution | Run all 430+ automated E2E & empirical stress tests via runner.py and produce JSON verification report | M1 | ORIGINAL_REQUEST / R1 |
| 2 | R2: Soong & Rust & AVB Build | Execute Soong compilation checks, Rust static build, and AVB 2.0 signed guest image packaging | M2 | ORIGINAL_REQUEST / R2 |
| 3 | R3: Deployment & Target Verification | Deploy generated artifacts to build_out/deployment/ and execute simulated target verification | M3 | ORIGINAL_REQUEST / R3 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Automated E2E & Empirical Stress Test Execution (R1) | Run python3 tests/e2e/runner.py, execute all 430+ unit/stress/E2E test suites, write e2e_report.json | none | DONE |
| M2 | Soong Module Compilation, Rust Build & AVB 2.0 Image Packaging (R2) | Build LinuxManagerService, linux_manager.te, LinuxTerminal.apk, android-bridge-agent, init 4-layer storage layout & AVB 2.0 signed images | M1 | IN_PROGRESS |
| M3 | Artifact Deployment & Simulated Target Verification (R3) | Create build_out/deployment/ layout, copy artifacts, verify non-empty integrity, execute verification runner | M2 | PLANNED |

## Interface Contracts
### Host (AOSP) ↔ Guest (Linux)
- VSOCK IPC protocol on port 1024 (`system/linux_bridge/` & `guest/bridge-agent/src/vsock.rs`).
- AVB 2.0 key verification via `guest_root_key.pub` / `AvbVerifier.cpp`.
- Deployment layout spec:
  - `build_out/deployment/framework/LinuxManagerService.class`
  - `build_out/deployment/sepolicy/linux_manager.te`
  - `build_out/deployment/apps/LinuxTerminal.apk`
  - `build_out/deployment/guest/bin/android-bridge-agent`
  - `build_out/deployment/guest/images/` (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`, `vm_config.json`)

## Code Layout
- `tests/e2e/`: E2E test framework, runner.py, and Tiers 1-4 test cases
- `tests/unit/`: Unit test suite (41 files)
- `tests/stress/`: Empirical stress tests
- `frameworks/base/services/core/java/com/android/server/linux/`: LinuxManagerService
- `system/sepolicy/private/`: linux_manager.te
- `packages/apps/LinuxTerminal/`: LinuxTerminal APK & libvterm_jni
- `guest/bridge-agent/`: Rust bridge agent
- `guest/scripts/`: Image packaging, storage layout, VM launch
- `scripts/`: Verification scripts (`run_m1_verification.sh`, `run_m2_verification.sh`, etc.)
- `build_out/deployment/`: Target deployment destination
