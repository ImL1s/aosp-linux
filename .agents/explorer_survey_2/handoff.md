# Handoff Report: Requirement R2 Build & Packaging Investigation

## 1. Observation

### 1.1 Soong `Android.bp` Modules
- **`LinuxManagerService`**:
  - File: `Android.bp` (Lines 21-30)
    ```bp
    java_library {
        name: "services.linux",
        srcs: [
            "frameworks/base/services/core/java/com/android/server/linux/**/*.java",
        ],
        libs: [
            "services.core",
            "android.system.linux",
        ],
    }
    ```
  - Java Source: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- **`linux_manager.te`**:
  - File: `system/sepolicy/private/linux_manager.te` (Lines 4-6)
    ```te
    type linux_manager, domain, coredomain;
    type linux_manager_exec, exec_type, file_type, system_file_type;
    type linux_vm_data_file, file_type, data_file_type, core_data_file_type;
    ```
- **`LinuxTerminal.apk`**:
  - File: `packages/apps/LinuxTerminal/Android.bp` (Lines 41-60)
    ```bp
    android_app {
        name: "LinuxTerminal",
        srcs: [
            "src/**/*.java",
        ],
        resource_dirs: ["res"],
        platform_apis: true,
        certificate: "platform",
        privileged: true,
        jni_libs: [
            "libvterm_jni",
        ],
        static_libs: [
            "androidx.appcompat_appcompat",
            "android.system.linux",
        ],
    }
    ```
  - Shared JNI Library: `libvterm_jni` in `packages/apps/LinuxTerminal/Android.bp` (Lines 3-39) and `packages/apps/LinuxTerminal/jni/Android.bp` (Lines 1-37).

### 1.2 Rust Guest Bridge Agent (`android-bridge-agent`)
- Location: `guest/bridge-agent/`
- Configuration File: `guest/bridge-agent/Cargo.toml` (Lines 1-12)
  ```toml
  [package]
  name = "android-bridge-agent"
  version = "0.1.0"
  edition = "2021"

  [dependencies]
  hex = "0.4"
  hmac = "0.12"
  sha2 = "0.10"
  zeroize = "1.7"
  libc = "0.2"
  ```
- Rust Sources: `guest/bridge-agent/src/main.rs`, `auth.rs`, `vsock.rs`, `ota_rollback.rs`.

### 1.3 AVB 2.0 Guest Image Packaging & Verification
- Packaging Script: `guest/scripts/init_storage_layout.sh` (Allocates 4-layer layout: `base_rootfs.img` 2500MB RO, `custom_overlay.img` 4000MB RW, `user_home.img` 5000MB LUKS2, `vm_state.snapshot`).
- Verification Engine: `system/vold/AvbVerifier.h` & `system/vold/AvbVerifier.cpp`.
- Public Key File: `system/etc/security/avb/guest_root_key.pub` (4096-bit RSA PEM key).
- AVB Verification Logic (`system/vold/AvbVerifier.cpp` Lines 88-103):
  Reads PEM public key, asserts key bit length `EVP_PKEY_get_bits(pkey) == 4096`, calculates SHA-256 image digest, checks `AVB0` header magic, and enforces anti-rollback index (`enforceRollbackIndex()`).

---

## 2. Logic Chain

1. **Soong Module Resolution**:
   - Inspection of root `Android.bp` and `packages/apps/LinuxTerminal/Android.bp` directly maps `LinuxManagerService` to `services.linux` (`service-linux`), and `LinuxTerminal.apk` to the `LinuxTerminal` module with native `libvterm_jni` shared library bindings.
   - Inspection of `system/sepolicy/private/linux_manager.te` shows that SELinux domain definitions for `linux_manager` are isolated in private sepolicy and compiled via `secilc` / `m selinux_policy`.

2. **Rust Agent Build Resolution**:
   - Inspection of `guest/bridge-agent/Cargo.toml` confirms cargo crate name `android-bridge-agent`.
   - The daemon can be checked/tested via `cargo check` and `cargo test`, and statically compiled for ARM64 Linux guest deployment using `cargo build --release` or target static flags (`aarch64-unknown-linux-musl` / `crt-static`).

3. **AVB 2.0 Signed Image Packaging Resolution**:
   - `guest/scripts/init_storage_layout.sh` creates the base guest rootfs images.
   - `AvbVerifier.cpp` and `guest_root_key.pub` demonstrate how AVB 2.0 verification works (SHA-256 digest + RSA-4096 signature + anti-rollback check).
   - Standard `avbtool` invocation packages the EROFS base rootfs image into a signed `vbmeta.img` descriptor.

4. **Exact Build Commands Resolution**:
   - Verified via analysis of `scripts/run_m1_verification.sh`, `scripts/run_m2_verification.sh`, and `scripts/run_m5_verification.sh`.

---

## 3. Caveats

- **No Active Compilation Executed**: Per prompt rules ("Do NOT edit source code files or run builds yourself yet, just explore and report"), build commands were identified and verified by static file/script inspection without triggering compiler outputs during this survey turn.
- **crosvm / Target Kernel Dependency**: In non-target/simulated environments, `crosvm` and `/dev/kvm` execution falls back to simulated modes as handled in `launch_vm.sh`.

---

## 4. Conclusion

All 4 sub-questions for Requirement R2 are fully answered with concrete evidence:
- Soong `Android.bp` modules for `LinuxManagerService` (`services.linux`), `linux_manager.te` (`system/sepolicy/private/linux_manager.te`), and `LinuxTerminal.apk` (`packages/apps/LinuxTerminal/Android.bp`) are clearly located and defined.
- Rust `android-bridge-agent` source and Cargo build configuration are at `guest/bridge-agent/`, ready for `cargo check`/`cargo build --release`.
- AVB 2.0 guest image packaging scripts (`init_storage_layout.sh`) and verification binaries (`AvbVerifier.cpp`, `guest_root_key.pub`) are fully specified.
- Precise build commands for Soong, Cargo, SELinux, and AVB packaging are documented in `analysis.md`.

---

## 5. Verification Method

To independently verify these findings:
1. Inspect Soong modules:
   - `view_file` on `/Users/iml1s/Documents/mine/aosp-linux/Android.bp`
   - `view_file` on `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/Android.bp`
2. Inspect Rust bridge-agent:
   - `view_file` on `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/Cargo.toml`
   - Run syntax check: `cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent && cargo check`
3. Inspect AVB 2.0 & Storage layout:
   - `view_file` on `/Users/iml1s/Documents/mine/aosp-linux/system/vold/AvbVerifier.cpp`
   - `view_file` on `/Users/iml1s/Documents/mine/aosp-linux/guest/scripts/init_storage_layout.sh`
4. Inspect full verification suites:
   - `view_file` on `/Users/iml1s/Documents/mine/aosp-linux/scripts/run_m2_verification.sh`
   - `view_file` on `/Users/iml1s/Documents/mine/aosp-linux/scripts/run_m5_verification.sh`
