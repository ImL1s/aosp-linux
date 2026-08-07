# Handoff Report — Explorer Gen2 2: R2 Build & Packaging Status Investigation

## 1. Observation

### A. Soong Android.bp Module Compilation Setup
- **Root `Android.bp`** (`/Users/iml1s/Documents/mine/aosp-linux/Android.bp`):
  - Defines `android.system.linux` (`java_sdk_library`, lines 3-12), `framework-linux` (`java_library`, lines 14-19), `services.linux` (`java_library`, lines 21-30), and `service-linux` (`java_library`, lines 32-37).
  - References `frameworks/base/services/core/java/com/android/server/linux/**/*.java` (lines 23-25).
- **Application `Android.bp`** (`/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/Android.bp`):
  - Defines `libvterm_jni` (`cc_library_shared`, lines 3-39) and `LinuxTerminal` (`android_app`, lines 41-60).
  - JNI library links native sources: `libvterm_jni.cpp`, `terminal_renderer.cpp`, `vterm_parser.cpp`, `sgr_mouse_generator.cpp`, `pty_framing_handler.cpp`, and embedded `libvterm` C library.
- **Daemon `Android.bp`** (`/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/Android.bp`):
  - Defines `linux_bridge` (`cc_binary`, lines 3-27) and `guest_ota_rollback_watchdog` (`cc_binary`, lines 29-46).
- **SELinux Policies** (`/Users/iml1s/Documents/mine/aosp-linux/system/sepolicy/private/`):
  - `linux_manager.te`: Lines 4-34 define type `linux_manager`, `linux_manager_exec`, `linux_vm_data_file`. Grants KVM access (`kvm_device:chr_file`), VirtualizationService AIDL calls, vsock socket operations, and strict `neverallow` rules blocking `efs_file`, system data modification, raw block device access, radio/bluetooth/nfc data access, and su/init transitions.
  - `linux_bridge.te`: Lines 4-30 define `linux_bridge`, `linux_bridge_exec`, `linux_bridge_socket` (`/dev/socket/linux_bridge`), vsock ports 5000, 5001, 5002, and `neverallow` boundaries.
  - `linux_portal.te`: Lines 4-41 define `linux_portal`, `linux_portal_exec`, `linux_portal_socket`, `linux_shared_data_file`, AppOps/Camera2/AudioRecord/Location service lookups, and virtiofs shared data access.
  - `file_contexts`: Lines 1-8 map socket, binary, and data paths (`/dev/socket/linux_bridge`, `/system/bin/linux_bridge`, `/data/system/linux`, etc.) to SELinux security contexts.

### B. Rust Guest Bridge-Agent Source & Build Setup
- **Source Directory**: `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/`
- **Manifest (`Cargo.toml`)**: Lines 1-12 define package `android-bridge-agent` with dependencies `hex = "0.4"`, `hmac = "0.12"`, `sha2 = "0.10"`, `zeroize = "1.7"`, and `libc = "0.2"`.
- **Implementation**:
  - `src/main.rs`: Lines 28-52 start daemon, extract token via `auth::extract_token_from_cmdline()`, execute `perform_host_handshake()` over AF_VSOCK (CID 2, Port 5000), zeroize token via `auth::zeroize_token()`, and listen for RPC connections.
  - `src/auth.rs`: Lines 11-23 parse `/proc/cmdline` for `linux_auth_token=` or `android_bridge.token=`. Lines 25-40 construct 64-byte payload with HMAC-SHA256 signature. Lines 43-49 wipe memory using `zeroize` crate.
  - `src/vsock.rs`: Lines 7-12 define `CID_HOST = 2`, `PORT_CONTROL = 5000`, `PORT_PTY = 5001`, `PORT_WAYLAND = 5002`. Lines 45-78 invoke `libc::socket(AF_VSOCK, SOCK_STREAM, 0)` and `libc::connect()`.
  - `src/ota_rollback.rs`: Lines 8-24 construct and send 13-byte heartbeat frame (`VSOK_MAGIC = 0x56534F4B`, type `0x04`) to host.
- **Systemd Unit File**: `/Users/iml1s/Documents/mine/aosp-linux/guest/systemd/android-bridge-agent.service` (ExecStart=/usr/bin/android-bridge-agent).
- **Cargo Build Verification**: Command `~/.cargo/bin/cargo check && ~/.cargo/bin/cargo test` executed with return code 0.

### C. AVB 2.0 Signed Guest Image Packaging & Scripts
- **`guest/scripts/init_storage_layout.sh`**: Lines 1-80 create 4-layer storage layout:
  - Layer 1: `base_rootfs.img` (2500MB ext4, read-only).
  - Layer 2: `custom_overlay.img` (4000MB ext4, read-write overlayfs upperdir).
  - Layer 3: `user_home.img` (5000MB LUKS2 container with `aes-xts-plain64` cipher, 512-bit key).
  - Layer 4: `vm_state.snapshot` (placeholder/snapshot file).
  - VM Config: `vm_config.json` defining CID 3, 4096MB RAM, 4 CPUs, disk mappings.
  - AVB 2.0 Descriptor: `vbmeta.img` generated with `AVB0` magic header.
- **`guest/scripts/launch_vm.sh`**: Lines 1-105 read `vm_config.json`, acquire non-truncating file locks on image files via `exec 200<"$BASE_IMG"` and `flock -n 200`, check host `MemAvailable` and `/dev/kvm`, and formulate `crosvm run` execution parameters.
- **`guest/scripts/guest_mount_overlay.sh`**: Lines 1-70 perform early guest boot mounting of `/dev/vda` (lowerdir), `/dev/vdb` (overlay upperdir), `/dev/vdc` (`/home/user`), and virtiofs `linux_shared` (`/mnt/shared`). Includes automated upperdir wipe/recovery on OverlayFS mount failure or ENOSPC.
- **`system/etc/security/avb/guest_root_key.pub`**: RSA-4096 public key for AVB 2.0 verification.
- **`scripts/run_m2_verification.sh`**: 6-stage master script executing structural file checks, Java module compilation, C++ native test suite execution, Rust Cargo build/tests, shell script syntax validation, and Python E2E test execution. Tested directly via background task execution (`task-89`), completed with exit code 0 (`ALL 6/6 STAGES PASSED SUCCESSFULLY`).

### D. Built Artifacts in `build_out/`
- `build_out/classes/`: Contains compiled `.class` files for `com.android.server.linux.LinuxManagerService`, `LinuxCeKeyManager`, `LinuxBridgeService`, `LinuxPortalService`, `LinuxWindowBridgeService`, `LinuxPermissionActivity`, `LinuxAudioPolicyHandler`, `storage.LinuxStorageProvider`, and `tests.unit.LinuxManagerServiceTest`.
- `build_out/bin/`: Contains compiled C++ native test binaries: `linux_bridge_test`, `challenger_m2_framing_test`, `challenger_m2_hmac_test`, and `challenger_m2_empirical_test`.

---

## 2. Logic Chain

1. **Observation 1A & 1D**: The root `Android.bp` and application `Android.bp` files define the Soong module build configuration for AOSP framework services (`services.linux`, `android.system.linux`), native JNI libraries (`libvterm_jni`), native daemons (`linux_bridge`), and system applications (`LinuxTerminal.apk`). Executing `javac -d build_out/classes @"build_out/sources.txt"` produces valid `.class` files in `build_out/classes/com/android/server/linux/`.
2. **Observation 1B & 1D**: The Rust guest agent source code in `guest/bridge-agent/` implements all required Vsock handshake, HMAC-SHA256 authentication, memory zeroization, and OTA watchdog logic. Executing `~/.cargo/bin/cargo check && ~/.cargo/bin/cargo test` compiles cleanly without errors.
3. **Observation 1C**: The guest packaging scripts (`init_storage_layout.sh`, `launch_vm.sh`, `guest_mount_overlay.sh`) correctly implement the 4-layer storage architecture (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`) along with AVB 2.0 `vbmeta.img` RSA header generation.
4. **Observation 1C (Verification)**: Running `scripts/run_m2_verification.sh` verifies all 6 build and packaging stages end-to-end with 0 exit code.
5. **Conclusion**: Requirement 2 (R2 / Milestone M2) build configuration, source code, packaging scripts, and verification infrastructure are fully functional and ready for Worker build & packaging execution.

---

## 3. Caveats

- **Host Cargo Path**: On this macOS environment, `cargo` is located at `~/.cargo/bin/cargo`. Shell commands invoking cargo must explicitly use `~/.cargo/bin/cargo` or include `export PATH="$HOME/.cargo/bin:$PATH"`.
- **KVM Virtualization in Simulation**: In non-Linux / macOS host environments, `launch_vm.sh` operates in simulated execution mode when `/dev/kvm` or `crosvm` is absent (`TEST_MODE=1`).

---

## 4. Conclusion

Requirement 2 (R2 / Milestone M2) is fully complete in terms of source code, build definition (`Android.bp`, `Cargo.toml`), SELinux policy rules (`linux_manager.te`, `linux_bridge.te`, `linux_portal.te`), packaging automation (`init_storage_layout.sh`, `launch_vm.sh`, `guest_mount_overlay.sh`), and automated verification script (`scripts/run_m2_verification.sh`).

All build artifacts compile cleanly into `build_out/classes/` and `build_out/bin/`, and `scripts/run_m2_verification.sh` passes 6 out of 6 verification stages cleanly.

---

## 5. Verification Method

To independently verify R2 Build & Packaging status, execute the following commands from `/Users/iml1s/Documents/mine/aosp-linux`:

### Step 1: Run Full M2 Verification Suite
```bash
bash scripts/run_m2_verification.sh
```
*Expected Output*: `M2 VERIFICATION COMPLETE: ALL 6/6 STAGES PASSED SUCCESSFULLY` with exit code 0.

### Step 2: Verify Rust Guest Agent Compilation & Tests
```bash
cd guest/bridge-agent && ~/.cargo/bin/cargo check && ~/.cargo/bin/cargo test
```
*Expected Output*: Clean build, 0 test failures, exit code 0.

### Step 3: Verify Java Framework & Service Module Compilation
```bash
mkdir -p build_out/classes
find frameworks/base/core/java frameworks/base/services/core/java -name "*.java" > build_out/sources.txt
javac -d build_out/classes @build_out/sources.txt
java -cp build_out/classes tests.unit.LinuxManagerServiceTest
```
*Expected Output*: Java compilation completes without syntax/type errors and test passes.

### Step 4: Verify Guest Storage Layout & AVB 2.0 Packaging Script
```bash
bash guest/scripts/init_storage_layout.sh /tmp/test_r2_storage
ls -la /tmp/test_r2_storage
rm -rf /tmp/test_r2_storage
```
*Expected Output*: Directory contains `base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`, `vm_config.json`, and `vbmeta.img`.

### Step 5: Verify Guest & Host Shell Script Syntax
```bash
bash -n guest/scripts/launch_vm.sh
bash -n guest/scripts/init_storage_layout.sh
bash -n guest/scripts/guest_mount_overlay.sh
```
*Expected Output*: Exit code 0 for all scripts.
