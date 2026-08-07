# Requirement R2 (Milestone M2) 編譯與打包分析報告

## 核心摘要
本報告針對 Requirement R2 (Milestone M2) 之 Soong 模組編譯檢查、Rust bridge-agent 靜態構建、AVB 2.0 簽署 Guest 鏡像打包以及 `scripts/run_m2_verification.sh` 之執行進行深入分析，並提供 Worker 執行任務之精確命令步驟與注意事項。

---

## 1. Soong 模組編譯檢查分析

AOSP 專案中針對 R2 所需之三個主要 Soong 模組及其定義如下：

### 1.1 LinuxManagerService (`services.linux` / `android.system.linux`)
- **原始碼路徑**:
  - API 介面: `frameworks/base/core/java/android/system/linux/`
  - 服務實作: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - 密鑰管理: `frameworks/base/services/core/java/com/android/server/linux/LinuxCeKeyManager.java`
- **Soong 定義 (`Android.bp`)**:
  - `java_sdk_library` 模組 `android.system.linux` (Line 3-12)
  - `java_library` 模組 `services.linux` (Line 21-30)
- **AOSP 編譯命令**:
  ```bash
  m services.linux
  ```
- **本地模擬編譯命令**:
  ```bash
  mkdir -p build_out/classes
  find frameworks/base/core/java frameworks/base/services/core/java tests/unit/LinuxManagerServiceTest.java -name "*.java" > build_out/sources.txt
  javac -d build_out/classes @build_out/sources.txt
  java -cp build_out/classes tests.unit.LinuxManagerServiceTest
  ```

### 1.2 linux_manager.te (SELinux Policy Domain)
- **原始碼路徑**: `system/sepolicy/private/linux_manager.te`
- **原則約束**:
  - 允許 `system_server` Binder 呼叫、`kvm_device` 存取、`/data/system/linux/` 檔案操作與 `vsock_socket` 傳輸。
  - 嚴格 NEVERALLOW 規則：禁止存取 `efs_file`、修改 `system_data_file`、讀寫 `block_device`、存取 `radio`/`bluetooth`/`nfc` 資料及 `su`/`init` 進程轉換。
- **AOSP 編譯檢查命令**:
  ```bash
  m selinux_policy
  ```
- **本地語法與合規檢查命令**:
  ```bash
  # 語法與存在性檢查
  test -f system/sepolicy/private/linux_manager.te && grep -q "type linux_manager, domain, coredomain;" system/sepolicy/private/linux_manager.te
  ```

### 1.3 LinuxTerminal.apk (Terminal UI & JNI Library)
- **原始碼路徑**:
  - JNI Native: `packages/apps/LinuxTerminal/jni/` (`libvterm_jni.cpp`, `terminal_renderer.cpp`, `vterm_parser.cpp`, etc.)
  - Java App: `packages/apps/LinuxTerminal/src/`
- **Soong 定義 (`packages/apps/LinuxTerminal/Android.bp`)**:
  - `cc_library_shared` 模組 `libvterm_jni` (Line 3-39)
  - `android_app` 模組 `LinuxTerminal` (Line 41-60)
- **AOSP 編譯命令**:
  ```bash
  m LinuxTerminal
  ```

---

## 2. Rust bridge-agent 靜態構建 (android-bridge-agent)

### 2.1 模組結構
- **目錄路徑**: `guest/bridge-agent/`
- **組態與依賴 (`Cargo.toml`)**:
  - Package 名稱: `android-bridge-agent` (edition 2021)
  - 依賴項: `hex`, `hmac`, `sha2`, `zeroize`, `libc`
- **核心功能**:
  - `src/main.rs`: 程式進入點與服務事件循環
  - `src/auth.rs`: HMAC-SHA256 挑戰應答認證
  - `src/vsock.rs`: Guest 側 VSOCK 通訊介面
  - `src/ota_rollback.rs`: OTA 看門狗與版本回退保護

### 2.2 構建與測試命令
- **開發檢查與單元測試命令**:
  ```bash
  cd guest/bridge-agent && cargo check && cargo test
  ```
- **ARM64 靜態 Release 構建命令 (用於 Guest OS 部署)**:
  ```bash
  cd guest/bridge-agent && cargo build --release --target aarch64-unknown-linux-musl
  # 產出路徑: guest/bridge-agent/target/aarch64-unknown-linux-musl/release/android-bridge-agent
  ```

---

## 3. AVB 2.0 簽署 Guest 鏡像打包 (init_storage_layout.sh & avbtool)

### 3.1 4-Layer 儲存佈局初始化 (`init_storage_layout.sh`)
- **腳本路徑**: `guest/scripts/init_storage_layout.sh`
- **執行命令**:
  ```bash
  bash guest/scripts/init_storage_layout.sh [TARGET_DIR]
  # 預設 TARGET_DIR 為 build_out/deployment/guest/images 或 /data/misc/linux
  ```
- **4 個分層規格**:
  1. **Layer 1: base_rootfs.img** — 2500MB，唯讀 immutable ext4/erofs 分區。
  2. **Layer 2: custom_overlay.img** — 4000MB，可讀寫 ext4 overlayfs upperdir 分區。
  3. **Layer 3: user_home.img** — 5000MB，LUKS2 加密容器 (`aes-xts-plain64` 密碼與 512-bit key size)。
  4. **Layer 4: vm_state.snapshot** — VM 狀態快照預留檔。

### 3.2 AVB 2.0 鏡像打包與 RSA-4096 簽署 (`avbtool`)
- **公私密鑰**:
  - 私鑰: `system/etc/security/avb/guest_root_key.pem`
  - 公鑰: `system/etc/security/avb/guest_root_key.pub` (4096-bit RSA PEM)
- **`avbtool` 打包與生成 `vbmeta.img` 標準命令**:
  ```bash
  # 1. 為 base_rootfs.img 添加 Hash Footer
  avbtool add_hash_footer \
      --image build_out/deployment/guest/images/base_rootfs.img \
      --partition_size 2621440000 \
      --partition_name base_rootfs \
      --key system/etc/security/avb/guest_root_key.pem \
      --algorithm SHA256_RSA4096

  # 2. 生成帶有 RSA-4096 簽章與 Rollback Index 之 vbmeta.img
  avbtool make_vbmeta_image \
      --output build_out/deployment/guest/images/vbmeta.img \
      --key system/etc/security/avb/guest_root_key.pem \
      --algorithm SHA256_RSA4096 \
      --rollback_index 1 \
      --include_descriptors_from_image build_out/deployment/guest/images/base_rootfs.img
  ```

---

## 4. `scripts/run_m2_verification.sh` 執行分析與修復指引

### 4.1 驗證腳本 6 大階段概覽
1. **[1/6] Structural & File Compliance**: 檢查 21 個必要檔案。
2. **[2/6] Compiling Java Service & Key Manager**: 透過 `javac` 編譯 Java 服務與單元測試。
3. **[3/6] Compiling and Running Native C++ Daemon Tests**: 使用 `clang++` 編譯 4 個原生測試二進位檔並執行。
4. **[4/6] Compiling and Testing Rust Guest Agent**: 執行 `cargo check` 與 `cargo test`。
5. **[5/6] Verifying Shell Script Syntax**: 檢查 `launch_vm.sh`、`init_storage_layout.sh` 與 `guest_mount_overlay.sh` 語法。
6. **[6/6] Running Python E2E Test Suites for Milestone M2**: 執行 Tier 1 與 Tier 2 (F-R2-001 ~ F-R2-005) 測試。

### 4.2 發現之 `run_m2_verification.sh` 問題與解決方案
- **觀察到的問題**:
  在原生執行 `scripts/run_m2_verification.sh` 時，[2/6] 階段指令：
  `find "${WORKSPACE_ROOT}/frameworks/base/core/java" "${WORKSPACE_ROOT}/frameworks/base/services/core/java" "${WORKSPACE_ROOT}/tests/unit" -name "*.java" > "${BUILD_DIR}/sources.txt"`
  搜尋了整個 `tests/unit` 目錄，其中包含 M3 階段的測試檔 (`ChallengerM3RepEmpiricalTest.java`, `LinuxAppTrackerTest.java` 等)，這些測試檔依賴尚未包含在 `sources.txt` 中的 `packages/apps/LinuxTerminal/src` 類別，導致 `javac` 報錯 Exit Code 1。
- **Worker 執行解決方案**:
  在執行 M2 驗證或修正 `run_m2_verification.sh` 時，應明確指定 `tests/unit/LinuxManagerServiceTest.java`（或添加 `packages/apps/LinuxTerminal/src`）：
  ```bash
  # 精確 M2 Java 編譯命令：
  find frameworks/base/core/java frameworks/base/services/core/java tests/unit/LinuxManagerServiceTest.java -name "*.java" > build_out/sources.txt
  javac -d build_out/classes @build_out/sources.txt
  java -cp build_out/classes tests.unit.LinuxManagerServiceTest
  ```

---

## 5. Worker 精確步驟與命令清單 (Execution Steps for Worker)

1. **環境與輸出目錄初始化**:
   ```bash
   mkdir -p build_out/bin build_out/classes build_out/deployment/guest/images
   ```

2. **Step 1: 結構與檔案合規檢查**:
   ```bash
   bash -c '
   required_files=(
       "guest/config/vm_config.json"
       "guest/scripts/launch_vm.sh"
       "guest/scripts/init_storage_layout.sh"
       "guest/scripts/guest_mount_overlay.sh"
       "guest/systemd/android-bridge-agent.service"
       "guest/bridge-agent/Cargo.toml"
       "guest/bridge-agent/src/main.rs"
       "guest/bridge-agent/src/auth.rs"
       "guest/bridge-agent/src/vsock.rs"
       "system/linux_bridge/vsock_framing.h"
       "system/linux_bridge/vsock_framing.cpp"
       "system/linux_bridge/hmac_auth.h"
       "system/linux_bridge/hmac_auth.cpp"
       "system/linux_bridge/vsock_server.h"
       "system/linux_bridge/vsock_server.cpp"
       "system/linux_bridge/socket_server.h"
       "system/linux_bridge/socket_server.cpp"
       "frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java"
       "frameworks/base/services/core/java/com/android/server/linux/LinuxCeKeyManager.java"
       "tests/e2e/tier1_feature_coverage/test_m2_tier1.py"
       "tests/e2e/tier2_boundary_corner/test_m2_tier2.py"
   )
   for f in "${required_files[@]}"; do
       [ -f "$f" ] || { echo "Missing $f"; exit 1; }
   done
   echo "Structural check passed!"
   '
   ```

3. **Step 2: Java 服務與單元測試編譯**:
   ```bash
   find frameworks/base/core/java frameworks/base/services/core/java tests/unit/LinuxManagerServiceTest.java -name "*.java" > build_out/sources.txt
   javac -d build_out/classes @build_out/sources.txt
   java -cp build_out/classes tests.unit.LinuxManagerServiceTest
   ```

4. **Step 3: C++ 原生測試檔編譯與執行**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -pthread -I"." system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test

   clang++ -std=c++20 -Wall -Wextra -pthread -I"." system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_framing_test.cpp -o build_out/bin/challenger_m2_framing_test

   clang++ -std=c++20 -Wall -Wextra -pthread -I"." system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_hmac_test.cpp -o build_out/bin/challenger_m2_hmac_test

   clang++ -std=c++20 -Wall -Wextra -pthread -I"." system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/challenger_m2_empirical_test.cpp -o build_out/bin/challenger_m2_empirical_test

   ./build_out/bin/linux_bridge_test
   ./build_out/bin/challenger_m2_framing_test
   ./build_out/bin/challenger_m2_hmac_test
   ./build_out/bin/challenger_m2_empirical_test
   ```

5. **Step 4: Rust Bridge Agent 構建與單元測試**:
   ```bash
   cd guest/bridge-agent && ~/.cargo/bin/cargo check && ~/.cargo/bin/cargo test && cd ../..
   ```

6. **Step 5: Shell 腳本語法檢查**:
   ```bash
   bash -n guest/scripts/launch_vm.sh
   bash -n guest/scripts/init_storage_layout.sh
   bash -n guest/scripts/guest_mount_overlay.sh
   ```

7. **Step 6: M2 E2E 自動化測試運作**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --feature F-R2-001
   python3 tests/e2e/runner.py --tier 1 --feature F-R2-002
   python3 tests/e2e/runner.py --tier 1 --feature F-R2-003
   python3 tests/e2e/runner.py --tier 1 --feature F-R2-004
   python3 tests/e2e/runner.py --tier 1 --feature F-R2-005
   python3 tests/e2e/runner.py --tier 2 --feature F-R2-001
   python3 tests/e2e/runner.py --tier 2 --feature F-R2-002
   python3 tests/e2e/runner.py --tier 2 --feature F-R2-003
   python3 tests/e2e/runner.py --tier 2 --feature F-R2-004
   python3 tests/e2e/runner.py --tier 2 --feature F-R2-005
   ```
