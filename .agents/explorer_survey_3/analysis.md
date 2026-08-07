# Analysis Report: AOSP Dual-OS Artifact Deployment & Simulated Target Verification (Requirement R3)

**Author**: `teamwork_preview_explorer` (Codebase Explorer - Deployment & Target Verification)  
**Date**: 2026-08-06  
**Target Path**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_survey_3/analysis.md`

---

## Executive Summary

This report provides a thorough read-only investigation of **Requirement R3**:
> "Deploy generated AOSP artifacts (LinuxManagerService, linux_manager.te, LinuxTerminal.apk, android-bridge-agent, guest images) to `build_out/deployment/` directory and perform simulated target verification."

Key findings:
1. **Directory Existence**: `build_out/deployment/` does **not** exist currently. `build_out/` contains only `bin/`, `classes/`, and `sources.txt`. It must be dynamically created via deployment scripts.
2. **Scripts, Tools & Manifests**: Build, packaging, and verification tools are distributed across `scripts/`, `guest/scripts/`, `Android.bp`, `packages/apps/LinuxTerminal/Android.bp`, `system/linux_bridge/Android.bp`, `guest/bridge-agent/Cargo.toml`, and `guest/config/vm_config.json`.
3. **Artifact Mapping**: All 5 requested AOSP artifact classes (`LinuxManagerService`, `linux_manager.te`, `LinuxTerminal.apk`, `android-bridge-agent`, `guest images`) have explicit source locations and defined target paths under `build_out/deployment/`.
4. **Simulated Verification**: Target verification is executed via a combination of component structural checks (`[ -s <file> ]`), binary compilation/unit tests (`scripts/run_m*_verification.sh`), and simulated target environment testing via `tests/e2e/runner.py`.

---

## Detailed Investigation Answers

### 1. Is `build_out/deployment/` directory existing or created by deployment scripts?

* **Current Status**: **Does NOT exist**.
* **Evidence**:
  - Listing `build_out/` reveals:
    - `build_out/bin/` (contains C++ test executables: `challenger_m2_empirical_test`, `challenger_m2_framing_test`, `challenger_m2_hmac_test`, `linux_bridge_test`)
    - `build_out/classes/` (contains compiled Java bytecode `.class` files)
    - `build_out/sources.txt`
  - There is no `deployment` subfolder inside `build_out/`.
* **Conclusion**: `build_out/deployment/` (and its subdirectories `framework/`, `sepolicy/`, `apps/`, `guest/bin/`, `guest/images/`) must be created by the deployment script using `mkdir -p build_out/deployment/...`.

---

### 2. Where are the deployment scripts, tools, or manifests located?

The build, packaging, configuration, and policy infrastructure consists of the following files across the workspace:

#### A. Build & Milestone Verification Scripts (`scripts/` & `guest/scripts/`)
- `scripts/run_m1_verification.sh`: Compiles Java framework/service and C++ `linux_bridge` native daemon.
- `scripts/run_m2_verification.sh`: Builds Rust `android-bridge-agent` guest binary, C++ HMAC/vsock test executables, and validates guest shell scripts.
- `scripts/run_m4_verification.sh`: Compiles Wayland buffer sharing native code, Java GUI components, and Launcher3 integration.
- `scripts/run_m5_verification.sh`: Compiles SELinux/AVB/Watchdog native modules, Java SAF provider/Portals, and executes full M5 verification.
- `guest/scripts/init_storage_layout.sh`: Initializes the 4-Layer guest storage image layout (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`).
- `guest/scripts/launch_vm.sh`: Script for invoking crosvm with `vm_config.json`.
- `guest/scripts/guest_mount_overlay.sh`: Guest init script for mounting EROFS base and overlayfs.

#### B. Build System & Module Declarations (`Android.bp` files & `Cargo.toml`)
- `Android.bp` (Root): Defines `android.system.linux` (SDK library), `services.linux` (SystemServer service library), and `framework-linux`.
- `packages/apps/LinuxTerminal/Android.bp`: Defines `android_app` module `LinuxTerminal` and shared JNI library `libvterm_jni`.
- `system/linux_bridge/Android.bp`: Defines `cc_binary` module `linux_bridge` and `guest_ota_rollback_watchdog`.
- `guest/bridge-agent/Cargo.toml`: Package manifest for compiling Rust binary `android-bridge-agent`.

#### C. SELinux Security & System Manifests (`system/sepolicy/` & `guest/config/`)
- `system/sepolicy/private/linux_manager.te`: SELinux domain definition for `LinuxManagerService`.
- `system/sepolicy/private/linux_bridge.te`: SELinux domain policy for native vsock IPC daemon.
- `system/sepolicy/private/linux_portal.te`: SELinux domain policy for XDG portals / AppOps bridge.
- `system/sepolicy/private/file_contexts`: Security context file assignments.
- `system/etc/security/avb/guest_root_key.pub`: Public key for AVB 2.0 signed image verification.
- `guest/config/vm_config.json`: AVF / crosvm configuration file defining guest CPU, RAM, disk mounts, and vsock ports (5000, 5001, 5002).

---

### 3. What are the exact target paths and filenames required in `build_out/deployment/`?

To satisfy Requirement R3, generated artifacts must be deployed to the following target paths under `build_out/deployment/`:

| # | Requested Artifact Class | Source Location | Target Path in `build_out/deployment/` | Description |
|---|--------------------------|-----------------|----------------------------------------|-------------|
| 1 | **LinuxManagerService** | `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` (compiled: `build_out/classes/com/android/server/linux/LinuxManagerService.class`) | `build_out/deployment/framework/LinuxManagerService.class` (or `build_out/deployment/framework/services.linux.jar`) | Framework SystemServer service compiled bytecode |
| 2 | **linux_manager.te** | `system/sepolicy/private/linux_manager.te` | `build_out/deployment/sepolicy/linux_manager.te` | SELinux domain security policy specification |
| 3 | **LinuxTerminal.apk** | `packages/apps/LinuxTerminal/` (built APK package artifact) | `build_out/deployment/apps/LinuxTerminal.apk` | Native touch terminal Android application package |
| 4 | **android-bridge-agent** | `guest/bridge-agent/` (compiled Rust binary: `guest/bridge-agent/target/debug/android-bridge-agent`) | `build_out/deployment/guest/bin/android-bridge-agent` | Guest systemd static Rust IPC bridge binary |
| 5 | **guest images** | `guest/config/vm_config.json` & `guest/scripts/init_storage_layout.sh` generated outputs: `base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`, `vm_config.json` | `build_out/deployment/guest/images/` (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`, `vm_config.json`) | 4-layer storage images + AVF crosvm VM config manifest |

---

### 4. How is simulated target verification performed? Are there verification scripts or test runners for deployed artifacts?

Target verification of deployed artifacts is conducted via a 3-tier simulated verification pipeline:

#### Tier A: Deployment Existence & Integrity Verification
A deployment verification script iterates over all target paths in `build_out/deployment/` and asserts:
1. File existence (`[ -f "$PATH" ]`)
2. Non-zero size (`[ -s "$PATH" ]`)
3. ELF binary headers / ZIP archive headers (for APK and ELF binaries like `android-bridge-agent` and `LinuxTerminal.apk`).

#### Tier B: Unit & Subsystem Test Suite Execution
Verification scripts in `scripts/` run unit tests against compiled components:
- `scripts/run_m1_verification.sh`: `LinuxManagerServiceTest`, `LinuxManagerStressTest`, `linux_bridge_test`.
- `scripts/run_m2_verification.sh`: `challenger_m2_framing_test`, `challenger_m2_hmac_test`, `challenger_m2_empirical_test`, Rust `cargo test`.
- `scripts/run_m4_verification.sh`: `VirtioGpuDmabufTest`, `LinuxWindowBridgeServiceTest`, `LinuxAppProxyActivityTest`.
- `scripts/run_m5_verification.sh`: `guest_ota_rollback_watchdog_test`, `avb_verifier_test`.

#### Tier C: Full Simulated E2E Target Verification Runner
- **Master Test Runner**: `python3 tests/e2e/runner.py` (or `./tests/e2e/run_tests.sh --output-json tests/e2e/e2e_report.json`).
- **Simulated Environment Engine**: `tests/e2e/framework/mock_env.py` provides `MockEnvironment` with:
  - `MockVsockBridge` simulating Vsock ports 5000 (Control), 5001 (PTY framing), and 5002 (Wayland buffer sharing).
  - `MockSystemServer` running `LinuxManagerService` state machine transitions.
  - `MockSommelier` & `virtio-gpu` buffer sharing.
  - `MockXdgPortal` & AppOps permission enforcement.
  - SELinux policy inspector validating `linux_manager.te`, `linux_bridge.te`, and `linux_portal.te`.
- **Target Verification Criteria**: Executing `python3 tests/e2e/runner.py` must yield **430 / 430 PASS (100% Pass Rate)** with exit code `0`.

---

## Proposed Implementation Script Outline (For Deployment Task)

To execute Requirement R3 cleanly, the deployment agent should perform:

```bash
#!/usr/bin/env bash
set -e

WORKSPACE_ROOT="/Users/iml1s/Documents/mine/aosp-linux"
DEPLOY_DIR="${WORKSPACE_ROOT}/build_out/deployment"

echo "=== AOSP Dual-OS Artifact Deployment & Verification ==="

# 1. Create target deployment directory tree
mkdir -p "${DEPLOY_DIR}/framework"
mkdir -p "${DEPLOY_DIR}/sepolicy"
mkdir -p "${DEPLOY_DIR}/apps"
mkdir -p "${DEPLOY_DIR}/guest/bin"
mkdir -p "${DEPLOY_DIR}/guest/images"

# 2. Build & Copy Artifacts
# A. LinuxManagerService
cp -r "${WORKSPACE_ROOT}/build_out/classes/com/android/server/linux/" "${DEPLOY_DIR}/framework/"

# B. linux_manager.te
cp "${WORKSPACE_ROOT}/system/sepolicy/private/linux_manager.te" "${DEPLOY_DIR}/sepolicy/"

# C. LinuxTerminal.apk (or package build)
# (Package / copy APK artifact to ${DEPLOY_DIR}/apps/LinuxTerminal.apk)

# D. android-bridge-agent
cp "${WORKSPACE_ROOT}/guest/bridge-agent/target/debug/android-bridge-agent" "${DEPLOY_DIR}/guest/bin/"

# E. Guest images & config
bash "${WORKSPACE_ROOT}/guest/scripts/init_storage_layout.sh" "${DEPLOY_DIR}/guest/images"
cp "${WORKSPACE_ROOT}/guest/config/vm_config.json" "${DEPLOY_DIR}/guest/images/"

# 3. Perform Target Verification
# A. Check existence & size of all deployed artifacts
# B. Run E2E test runner
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py"
```

---

## Summary of Findings

- `build_out/deployment/` is currently missing and needs to be created.
- All deployment scripts, build blueprints, and config manifests are located in `scripts/`, `guest/scripts/`, `Android.bp`, `packages/apps/LinuxTerminal/`, `system/sepolicy/`, and `guest/bridge-agent/`.
- Target artifact paths in `build_out/deployment/` are clearly mapped for framework, sepolicy, apps, guest binaries, and guest storage images.
- Simulated target verification uses `runner.py` in Mock Architecture mode along with binary non-empty checks.
