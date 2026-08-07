# Requirement R2 Investigation Report: Build & Packaging Analysis

## Executive Summary

本報告針對 Requirement R2（"Execute Soong Android.bp module compilation checks, Rust bridge-agent static build, and AVB 2.0 signed guest image packaging"）進行了完整的程式碼庫與建置包裝架構調查。調查涵蓋四大核心項目：
1. **Soong `Android.bp` 模組位置與結構**（`LinuxManagerService`、`linux_manager.te`、`LinuxTerminal.apk`）。
2. **Rust `android-bridge-agent` 原始碼、Cargo 設定與靜態編譯方式**。
3. **AVB 2.0 虛擬機器映像檔打包指令指令碼、工具與 RSA-4096 簽章驗證機制**。
4. **各組件的精確建置指令（Soong / Make / Cargo / Clang / Shell Invocations）**。

---

## 1. Soong Android.bp 模組定位

### 1.1 `LinuxManagerService` (Framework / Service 模組)
- **定義檔案**: `/Users/iml1s/Documents/mine/aosp-linux/Android.bp` (第 21-37 行)
- **模組名稱**: `services.linux` (`java_library`)，並靜態打包於 `service-linux`。
- **原始碼路徑**: 
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - 介面定義: `frameworks/base/core/java/android/system/linux/ILinuxManager.aidl`
  - Client SDK 模組: `android.system.linux` (`java_sdk_library`，`Android.bp` 第 3-12 行)
- **模組特徵**: 採用 AOSP 標準 `java_library` 宣告，依賴 `services.core` 與 `android.system.linux` SDK 庫。

### 1.2 `linux_manager.te` (SELinux 安全性原則模組)
- **定義檔案**: `/Users/iml1s/Documents/mine/aosp-linux/system/sepolicy/private/linux_manager.te` (第 1-35 行)
- **對應領域與 context**: 宣告 `type linux_manager, domain, coredomain;` 及存取權限規則與 `neverallow` 邊界保護。
- **建置納入點**: SELinux policy 由 AOSP `system/sepolicy` 編譯器 (`secilc` / `m selinux_policy`) 彙整 `system/sepolicy/private/` 目錄下的所有 `.te` 與 `file_contexts` 檔案進行編譯。

### 1.3 `LinuxTerminal.apk` (Native Touch Terminal 應用程式模組)
- **定義檔案**: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/Android.bp` (第 41-60 行)
- **模組名稱**: `LinuxTerminal` (`android_app`)
- **JNI Native 依賴庫**: `libvterm_jni` (`cc_library_shared`，定義於 `packages/apps/LinuxTerminal/Android.bp` 第 3-39 行及 `packages/apps/LinuxTerminal/jni/Android.bp` 第 1-37 行)
- **原始碼路徑**:
  - Java 源碼: `packages/apps/LinuxTerminal/src/` (含 `LinuxAppProxyActivity.java` 等)
  - Resource: `packages/apps/LinuxTerminal/res/`
  - JNI 源碼: `packages/apps/LinuxTerminal/jni/` (含 `libvterm_jni.cpp`, `vterm_parser.cpp`, `terminal_renderer.cpp` 等)
- **模組特徵**: 設定 `platform_apis: true`, `certificate: "platform"`, `privileged: true`，靜態連結 `android.system.linux` 與 `androidx.appcompat_appcompat`。

---

## 2. Rust bridge-agent 原始碼與 Cargo 設定

### 2.1 專案結構與源碼位置
- **專案根目錄**: `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/`
- **Cargo 設定檔**: `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/Cargo.toml`
- **源碼檔案**:
  - `src/main.rs`: 虛擬機器內部背景守護程序 (daemon) 入口點，負責處理 vsock control 埠 5000 握手、通訊與事件迴圈。
  - `src/auth.rs`: `/proc/cmdline` 金鑰 Token 擷取、HMAC-SHA256 挑戰回應計算與 `zeroize` 記憶體抹除。
  - `src/vsock.rs`: `AF_VSOCK` socket 通訊封裝 (`CID_HOST` 2, `PORT_CONTROL` 5000)。
  - `src/ota_rollback.rs`: 啟動失敗 watchdog 備份槽退回機制。

### 2.2 Cargo 依賴與靜態建置設定
- **Cargo.toml 內容**:
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
- **靜態建置 (Static Build) 設定**:
  - 在 Linux 虛擬機器環境中，為了達成與系統庫無關的獨立部署，可透過 `musl` target 或 `crt-static` 進行靜態編譯。
  - 靜態編譯指令:
    ```bash
    RUSTFLAGS="-C target-feature=+crt-static" cargo build --target aarch64-unknown-linux-musl --release --manifest-path guest/bridge-agent/Cargo.toml
    ```
  - 一般 Release 建置指令:
    ```bash
    cargo build --release --manifest-path guest/bridge-agent/Cargo.toml
    ```
  - 產出執行檔路徑: `guest/bridge-agent/target/release/android-bridge-agent`

---

## 3. AVB 2.0 Guest Image 打包腳本、工具與簽章機制

### 3.1 映像檔架構與初始化腳本
- **初始化指令碼**: `/Users/iml1s/Documents/mine/aosp-linux/guest/scripts/init_storage_layout.sh`
- **4 層儲存架構 (4-Layer Storage Image Layout)**:
  - **Layer 1 (`base_rootfs.img` / `base_a.img` / `base_b.img`)**: 2500MB，唯讀不可變 EROFS / ext4 基底映像檔（A/B 雙 Slot 佈局）。
  - **Layer 2 (`custom_overlay.img`)**: 4000MB，ext4 格式，OverlayFS 可讀寫 upperdir。
  - **Layer 3 (`user_home.img`)**: 5000MB，LUKS2 加密容器 (採用 `aes-xts-plain64` 演算法、512-bit 金鑰)。
  - **Layer 4 (`vm_state.snapshot`)**: 快照預留檔。
- **掛載與啟動指令碼**:
  - `guest/scripts/guest_mount_overlay.sh`: Guest 啟動時掛載 OverlayFS 與解密 home。
  - `guest/scripts/launch_vm.sh`: Host 透過 `crosvm` 帶入 `--rodisk base_rootfs.img` 啟動 VM。

### 3.2 AVB 2.0 驗證引擎與簽章金鑰
- **AVB 2.0 驗證器**:
  - 標頭檔: `/Users/iml1s/Documents/mine/aosp-linux/system/vold/AvbVerifier.h`
  - 實作檔: `/Users/iml1s/Documents/mine/aosp-linux/system/vold/AvbVerifier.cpp`
- **信任根公鑰**:
  - `/Users/iml1s/Documents/mine/aosp-linux/system/etc/security/avb/guest_root_key.pub` (4096-bit RSA PEM Public Key)。

### 3.3 AVB 2.0 打包與簽章流程
1. **基底映像檔打包**:
   使用 `mkfs.erofs` 將 Debian 12 根檔案系統打包為唯讀 `base_a.img` / `base_b.img`。
2. **AVB 2.0 Vbmeta 描述子與簽章生成 (`avbtool`)**:
   使用 `avbtool` 工具計算 SHA-256 Block Digest，設定 Rollback Index，並以對應 `guest_root_key.pem` 之私鑰進行 RSA-4096 簽章：
   ```bash
   avbtool make_vbmeta_image \
       --output build_out/vbmeta.img \
       --key system/etc/security/avb/guest_root_key.pem \
       --algorithm SHA256_RSA4096 \
       --rollback_index 1 \
       --include_descriptors_from_image build_out/base_a.img
   ```
3. **Runtime AVB 驗證 (Host `AvbVerifier`)**:
   - `AvbVerifier::calculateImageDigest()` 計算映像檔 SHA-256 Digest。
   - `AvbVerifier::verifyGuestImage()` 驗證 `vbmeta.img` 的 `AVB0` 魔數標頭。
   - 透過 OpenSSL `PEM_read_PUBKEY` 與 `EVP_PKEY_get_bits()` 驗證公鑰為 4096-bit RSA，並比對公鑰指紋。
   - `enforceRollbackIndex()` 確保 `packageIndex >= deviceIndex` 防範退級攻擊。

---

## 4. 各組件精確建置指令與 Invocation 彙整

| 組件名稱 | 類型 / 工具 | 建置指令 / Invocation | 產出目標檔案 |
|---|---|---|---|
| **LinuxManagerService** | Soong / Java | `m services.linux` (或 `m service-linux`)<br>*(離線編譯測試: `javac -d build_out/classes @"build_out/sources.txt"`)* | `out/target/product/.../system/framework/services.jar` |
| **linux_manager.te** | Soong / SELinux | `m selinux_policy`<br>*(Direct: `secilc -m -M true -G -c 30 system/sepolicy/private/linux_manager.te -o build_out/sepolicy`)* | `out/target/product/.../system/etc/selinux/plat_sepolicy.cil` |
| **LinuxTerminal.apk** | Soong / Android App | `m LinuxTerminal`<br>*(包含編譯 JNI `libvterm_jni.so`)* | `out/target/product/.../system/priv-app/LinuxTerminal/LinuxTerminal.apk` |
| **android-bridge-agent** | Cargo / Rust | `cargo check --manifest-path guest/bridge-agent/Cargo.toml`<br>`cargo test --manifest-path guest/bridge-agent/Cargo.toml`<br>`cargo build --release --manifest-path guest/bridge-agent/Cargo.toml` | `guest/bridge-agent/target/release/android-bridge-agent` |
| **Guest Storage Images** | Bash / Storage Tools | `bash guest/scripts/init_storage_layout.sh build_out/guest_images` | `build_out/guest_images/base_rootfs.img`<br>`custom_overlay.img`<br>`user_home.img` |
| **AvbVerifier Native Test** | Clang++ / OpenSSL | `clang++ -std=c++20 -Wall -Wextra -pthread -I. $(pkg-config --cflags openssl) system/vold/AvbVerifier.cpp tests/unit/avb_verifier_test.cpp $(pkg-config --libs openssl) -o build_out/bin/avb_verifier_test` | `build_out/bin/avb_verifier_test` |
| **全模組驗證 Suite** | Shell Scripts | `bash scripts/run_m1_verification.sh`<br>`bash scripts/run_m2_verification.sh`<br>`bash scripts/run_m5_verification.sh` | 執行全部單元測試、編譯檢查與 E2E 測試 |

---

## 證據鏈與驗證清單 (Evidence Chain Verification)

1. **`services.linux` & `LinuxManagerService`**:
   - `view_file` 查驗 `/Users/iml1s/Documents/mine/aosp-linux/Android.bp` (第 21-30 行) 證實 `java_library` `services.linux` 定義。
   - `scripts/run_m1_verification.sh` 證實 `javac` 編譯與 `LinuxManagerServiceTest` 測試流程。
2. **`linux_manager.te`**:
   - `view_file` 查驗 `/Users/iml1s/Documents/mine/aosp-linux/system/sepolicy/private/linux_manager.te` (第 1-35 行) 證實 `coredomain` 與 `neverallow` 定義。
3. **`LinuxTerminal.apk`**:
   - `view_file` 查驗 `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/Android.bp` (第 41-60 行) 證實 `android_app` 模組與 JNI 依賴。
4. **`android-bridge-agent`**:
   - `view_file` 查驗 `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/Cargo.toml` 與 `src/main.rs` 證實套件名稱與 HMAC 握手邏輯。
   - `scripts/run_m2_verification.sh` 證實 `cargo check` 與 `cargo test` 執行方式。
5. **AVB 2.0 & Image Packaging**:
   - `view_file` 查驗 `system/vold/AvbVerifier.cpp` 與 `system/etc/security/avb/guest_root_key.pub` 證實 RSA-4096 簽章與 SHA-256 Digest 檢查機制。
   - `view_file` 查驗 `guest/scripts/init_storage_layout.sh` 證實 4-Layer 映像檔創建流程。
