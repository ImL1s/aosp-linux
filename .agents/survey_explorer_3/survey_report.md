# AOSP Dual-OS 構建系統、測試架構與驗證流程調查報告 (`survey_report.md`)

## 摘要

本報告由 `survey_explorer_3` (Build Infra & Verification Explorer) 針對 **AOSP Dual-OS 專案** (`/Users/iml1s/Documents/mine/aosp-linux`) 之構建系統、編譯設定、測試套件及自動化驗證腳本進行深度分析與盤點。

---

## 1. 專案完整目錄結構與檔案映射 (Directory Layout Mapping)

專案根目錄 `/Users/iml1s/Documents/mine/aosp-linux` 包含 15 個子目錄與 9 個頂層檔案。以下為全域目錄結構盤點：

```
/Users/iml1s/Documents/mine/aosp-linux
├── Android.bp                       # 頂層 AOSP Soong 構建腳本 (定義 android.system.linux, services.linux 等)
├── DEAD_ENDS.md                     # 無效嘗試與架構避坑紀錄
├── LICENSE                          # 開源授權條款 (Apache 2.0)
├── ORIGINAL_REQUEST.md              # 原始需求與 remediation 任務清單
├── PROJECT.md                       # 專案架構規範、模組邊界與功能矩陣 (37 個 Features)
├── README.md                        # 專案說明文件與快速入門指南
├── TEST_INFRA.md                    # E2E 測試架構設計與 4-Tier 測試規格書
├── TEST_READY.md                    # E2E 測試發布與驗證報告
├── .agents/                         # 多 Agent 協作工作區 (包含 orchestrator, survey_explorer_* 等)
├── .github/                         # GitHub CI/CD 工作流與配置
├── build_out/                       # 獨立編譯輸出目錄 (包含 bin/ 執行檔, classes/ Java 類別檔)
├── docs/                            # 系統架構、信任邊界、API 規格書 (01-35 md 檔案)
├── frameworks/                      # AOSP Framework 與 SystemServer 修改代碼
│   └── base/
│       ├── core/java/android/system/linux/    # Framework API 及 AIDL 介面
│       └── services/core/java/com/android/server/linux/  # SystemServer 服務 (LinuxManagerService, LinuxPortalService 等)
├── guest/                           # Non-Protected Guest Linux 相關組件
│   ├── bridge-agent/                # Host-Guest 通訊中繼服務 (Rust, Cargo.toml)
│   ├── portal-agent/                # .desktop 檔案與 inotify 監控服務 (Rust, Cargo.toml)
│   ├── config/                      # VM 啟動與裝置配置 JSON (vm_config.json)
│   ├── scripts/                     # Guest 啟動與存儲初始化 Shell 腳本
│   └── systemd/                     # Systemd 服務單元 (android-bridge-agent.service)
├── packages/                        # Android 應用程式
│   └── apps/
│       ├── Launcher3/               # 桌面 Launcher 整合組件 (LinuxAppTracker.java)
│       └── LinuxTerminal/           # Touch Terminal App (Android.bp, JNI, CJK IME, Surface Canvas)
├── patches/                         # AOSP 補丁檔 (aosp_frameworks_base.patch)
├── scratch/                         # 測試用臨時映像檔與 AVB 元數據
├── scripts/                         # Milestone 獨立驗證 Shell 腳本 (run_m1/m2/m4/m5_verification.sh)
├── system/                          # Host 端 C++ Native Daemon 與系統配置
│   ├── etc/security/avb/            # AVB 簽名金鑰 (guest_root_key.pub)
│   ├── linux_bridge/                # linux_bridge Native C++ Daemon (Android.bp, vsock, HMAC, watchdog)
│   ├── sepolicy/private/            # SELinux 安全政策 (.te 檔案與 file_contexts)
│   └── vold/                        # Storage Decryption 與 AVB 驗證 (AvbVerifier.cpp)
├── tests/                           # 端到端 (E2E) 測試套件、壓力測試與單元測試
│   ├── e2e/                         # Python 4-Tier E2E 測試框架 (runner.py, run_tests.sh, tier1~4)
│   ├── stress/                      # 高壓與對抗性測試
│   └── unit/                        # Java / C++ 單元測試與 Empirical 測試檔案
└── unit/                            # C++ Native Mock 與 Fake Android 標頭檔 (challenger_m3_empirical_test.cpp)
```

---

## 2. 構建設定與腳本位置 (Build Files & Scripts Inventory)

本專案採用 **雙重構建與驗證體系**：
1. **AOSP Soong 構建系統 (`Android.bp`)**：用於完整的 AOSP 樹內編譯 (In-tree build)。
2. **獨立（Standalone）編譯與驗證腳本 (`scripts/*.sh` / `clang++` / `javac` / `cargo`)**：用於 Host 開發環境下快速進行單元測試與 E2E 驗證。

### (1) `Android.bp` 檔案清單
- **`/Users/iml1s/Documents/mine/aosp-linux/Android.bp`**:
  - 定義 `java_sdk_library` ("android.system.linux")，包含 `frameworks/base/core/java/**/*.java` 與 `.aidl`。
  - 定義 `java_library` ("framework-linux", "services.linux", "service-linux")。
- **`/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/Android.bp`**:
  - 定義 `android_app` ("LinuxTerminal") 與 `cc_library_shared` ("libvterm_jni")。
- **`/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/jni/Android.bp`**:
  - 定義 C++ JNI 共享庫 `cc_library_shared` ("libvterm_jni")。
- **`/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/Android.bp`**:
  - 定義 C++ Native 二進制檔 `cc_binary` ("linux_bridge" 與 "guest_ota_rollback_watchdog")。

### (2) Cargo 構建設定 (`Cargo.toml`)
- **`guest/bridge-agent/Cargo.toml`**:
  - 套件名稱: `bridge-agent` (Rust edition 2021)
  - 依賴: `libc = "0.2"`, `serde = { version = "1.0", features = ["derive"] }`, `serde_json = "1.0"`
  - 開發依賴: `tempfile = "3.8"`
- **`guest/portal-agent/Cargo.toml`**:
  - 套件名稱: `portal-agent` (Rust edition 2021)
  - 依賴: `libc = "0.2"`, `serde = { version = "1.0", features = ["derive"] }`, `serde_json = "1.0"`

### (3) 自動化驗證腳本 (Verification Scripts)
- **`scripts/run_m1_verification.sh`**: M1 架構編譯與測試腳本 (Java 服務 + `linux_bridge_test`)。
- **`scripts/run_m2_verification.sh`**: M2 VM 啟動與加密驗證腳本 (Java + Native C++ HMAC/Framing/Empirical + Rust check/test)。
- **`scripts/run_m4_verification.sh`**: M4 Wayland GUI 驗證腳本 (`VirtioGpuDmabufTest` + Java resize/tracker + E2E R4)。
- **`scripts/run_m5_verification.sh`**: M5 Portals, Virtiofs, SELinux & OTA 驗證腳本 (Java Portals + C++ Watchdog/AVB + Rust + E2E R5)。
- **`tests/e2e/run_tests.sh`**: Python E2E 測試執行 Shell 包裝檔。

---

## 3. 模組編譯、檢查與測試機制 (Compilation & Test Mechanisms)

### (1) Java 與 AIDL 檔案的編譯與檢查
- **AIDL 介面**:
  - 位於 `frameworks/base/core/java/android/system/linux/` (包括 `ILinuxManager.aidl`, `ILinuxBridge.aidl`, `ILinuxPortalService.aidl`, `ILinuxStatusCallback.aidl`, `ILinuxStorageProvider.aidl`, `ILinuxTerminalCallback.aidl`, `ILinuxWindowBridge.aidl`, `LinuxAppInfo.aidl`)。
  - `system/linux_bridge/` 下包含 `ILinuxBridgeDaemon.aidl`。
- **Standalone 檢查機制**:
  - 獨立環境中，`frameworks/base/core/java` 及 `frameworks/base/services/core/java` 包含 Android API 樁程式 (如 `Slog.java`, `ServiceManager.java`, `Context.java` 等)，允許使用標準 `javac` 直接進行語法檢查與編譯。
- **當前發現缺陷 (Defects Observed)**:
  - **`LinuxAppProxyActivity.java:270`** 存在語法錯誤 (重複且未關閉的 `attachSurfaceControlToBridge` 方法宣告，無法通過 `javac` 編譯)。
  - **`LinuxAppProxyActivity.java:277`** 使用反射 `Class.forName("com.android.server.linux.LinuxWindowBridgeService")`，違反 R2 Binder IPC 解耦要求。
  - **`scripts/run_m1_verification.sh:21`** 腳本路徑錯誤：誤將 `ILinuxBridgeDaemon.aidl` 設為 `frameworks/base/core/java/android/system/linux/ILinuxBridgeDaemon.aidl` (實際位於 `system/linux_bridge/ILinuxBridgeDaemon.aidl`)。
  - **`scripts/run_m2_verification.sh:32`** 腳本路徑錯誤：尋找不存在的 `LinuxCeKeyManager.java`。
  - **`scripts/run_m5_verification.sh:15`** 腳本路徑錯誤：尋找不存在的 `LinuxAudioPolicyHandler.java`。

### (2) Rust 組件檢查與測試 (`cargo check --target aarch64-unknown-linux-gnu`)
- **交叉編譯目標**: `aarch64-unknown-linux-gnu`。
- **檢查指令與結果**:
  - `~/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu` 於 `guest/bridge-agent`:
    - **結果**: 成功 (Exit Code 0)。
    - **警告**: 產生 2 個未使用代碼警告 (`reset_portal_state` 函式與 `Tcp` 枚舉變態)。
  - `~/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu` 於 `guest/portal-agent`:
    - **結果**: 成功 (Exit Code 0)。
    - **警告**: 產生 2 個未使用的 import 警告 (`Path` 與 `Sender`)。
- **單元與 Empirical 測試指令**:
  - `~/.cargo/bin/cargo test` 於 `guest/bridge-agent`: 執行 34 個單元測試與併發/FD 洩漏/HMAC 壓力測試，**34/34 全部通過 (100% Pass)**。
  - `~/.cargo/bin/cargo test` 於 `guest/portal-agent`: 執行 0 個測試，**0/0 通過** (產生 5 個警告)。

### (3) Native C++ 組件與測試 (Clang++)
- **編譯工具**: `clang++` (需加入 `-std=c++20` 或 `-std=c++17`，以及 `-I/opt/homebrew/opt/openssl@3/include -L/opt/homebrew/opt/openssl@3/lib -lcrypto`)。
- **編譯產物**:
  - `build_out/bin/linux_bridge_test`
  - `build_out/bin/challenger_m2_framing_test`
  - `build_out/bin/challenger_m2_hmac_test`
  - `build_out/bin/challenger_m2_empirical_test`
  - `build_out/bin/VirtioGpuDmabufTest`
  - `build_out/bin/guest_ota_rollback_watchdog_test`
  - `build_out/bin/avb_verifier_test`
- **測試執行結果**: 所有 C++ Native 測試二進制檔均能正常執行並輸出 PASS。

### (4) Python E2E 測試框架 (End-to-End Test Suite)
- **進入點**: `tests/e2e/run_tests.sh` 或 `python3 tests/e2e/runner.py`。
- **測試層級結構 (4 Tiers / 430 個測試用例)**:
  - **Tier 1 (Feature Coverage)**: 37 個功能點 × 5 個快樂路徑測試 = 185 個測試。
  - **Tier 2 (Boundary & Corner)**: 37 個功能點 × 5 個邊界/異常測試 = 185 個測試。
  - **Tier 3 (Cross-Feature Pairwise)**: 40 個跨功能模組組合測試。
  - **Tier 4 (Real-World Scenarios)**: 20 個完整業務場景與安全性測試。
- **目前執行數據 (Real Status)**:
  - 總執行用例數: 430
  - 通過 (PASS): 386
  - 失敗 (FAIL): 44
  - 通過率: **89.8%** (註：`TEST_READY.md` 聲稱 100%，實測為 89.8%，主因在於部分 Java / C++ 測試檔與環境依賴未先編譯至指定目錄)。

---

## 4. 既存測試檔案與 Harness 盤點 (Test Inventory)

### (1) C++ Native 單元與壓力測試 (`tests/unit/` & `system/linux_bridge/tests/`)
1. `tests/unit/linux_bridge_test.cpp`: Bridge Daemon 核心 Socket 通訊測試。
2. `tests/unit/challenger_m2_framing_test.cpp`: Vsock 封包 Frame 格式驗證。
3. `tests/unit/challenger_m2_hmac_test.cpp`: HMAC-SHA256 金鑰與簽名驗證測試。
4. `tests/unit/challenger_m2_empirical_test.cpp`: Vsock 實測與連線生命週期測試。
5. `tests/unit/challenger_m2_i3_2_empirical_test.cpp`: M2 Iteration 3.2 實測測試。
6. `tests/unit/challenger_m2_i3_2_vsock_stress.cpp`: Vsock 壓力測試。
7. `tests/unit/VirtioGpuDmabufTest.cpp`: virtio-gpu dma-buf 記憶體共享測試。
8. `tests/unit/guest_ota_rollback_watchdog_test.cpp`: 啟動 Watchdog 與 Rollback 測試。
9. `tests/unit/avb_verifier_test.cpp`: AVB RSA-4096 簽名驗證測試。
10. `tests/unit/m3_native_terminal_test.cpp`: Terminal 繪製與 ANSI 解析測試。
11. `tests/unit/m3_native_challenger2_stress.cpp`: Terminal 渲染與輸入壓力測試。
12. `unit/challenger_m3_empirical_test.cpp`: CJK IME 與繪製經驗測試。
13. `system/linux_bridge/tests/linux_bridge_test.cpp`: Native Bridge 單元測試。
14. `system/linux_bridge/tests/linux_bridge_stress_test.cpp`: Native Bridge 壓力測試。

### (2) Java 單元與壓力測試 (`tests/unit/` & `tests/stress/`)
1. `tests/unit/LinuxManagerServiceTest.java`: LinuxManagerService 狀態機與 IPC 測試。
2. `tests/unit/LinuxManagerStressTest.java`: Service 高併發呼叫壓力測試。
3. `tests/unit/LinuxPortalServiceTest.java`: Portal 權限與鏡頭/麥克風 mapping 測試。
4. `tests/unit/LinuxAudioPolicyTest.java`: AudioFocus 搶佔與鴨音 (Ducking) 測試。
5. `tests/unit/LinuxStorageProviderTest.java`: SAF DocumentsProvider 存取測試。
6. `tests/unit/LinuxWindowBridgeServiceTest.java`: Window Bridge 視窗對接測試。
7. `tests/unit/LinuxAppProxyActivityTest.java`: Proxy Activity 生命週期測試。
8. `tests/unit/LinuxAppTrackerTest.java`: Launcher3 應用追蹤器測試。
9. `tests/unit/TerminalAppUnitTest.java`: Terminal App 元件測試。
10. `tests/unit/VsockTerminalClientEmpiricalTest.java`: Vsock 終端客戶端經驗測試。
11. `tests/unit/ChallengerM1StressTest.java`: M1 壓力測試。
12. `tests/unit/ChallengerM3EmpiricalTest.java`: M3 經驗測試。
13. `tests/unit/ChallengerM3Challenger2StressTest.java`: M3 綜合壓力測試。
14. `tests/unit/ChallengerM4StressTest.java`: M4 GUI 視窗縮放壓力測試。
15. `tests/unit/ChallengerM5EmpiricalStressTest.java`: M5 Portals 綜合壓力測試。
16. `tests/unit/ChallengerM5Iter2EmpiricalTest.java`: M5 迭代 2 測試。
17. `tests/unit/ChallengerM5Iter2LinuxStorageProviderTest.java`: SAF 存取壓力測試。
18. `tests/unit/ChallengerM5Iter3_2LinuxStorageProviderTest.java`: SAF 迭代 3.2 測試。
19. `tests/unit/LinuxCeKeyDerivationStressTest.java`: LUKS2 金鑰衍生壓力測試。
20. `tests/unit/TouchpadVsockStressTest.java`: 觸控板 Vsock 封包壓力測試。
21. `tests/stress/AdversarialLinuxAppTrackerTest.java`: 對抗性 AppTracker 測試。
22. `tests/stress/AdversarialLinuxWindowBridgeServiceTest.java`: 對抗性 WindowBridge 測試。
23. `tests/stress/AdversarialWaylandBufferSharingTest.cpp`: 對抗性 Wayland 共享測試。
24. `tests/stress/InotifyBurstTest.rs`: Inotify 爆量事件 Rust 測試。
25. `tests/stress/test_desktop_parser_adversarial.py`: 對抗性 .desktop 解析測試。

### (3) Python E2E 測試用例與測試框架 (`tests/e2e/`)
- **框架核心**: `e2e/runner.py`, `e2e/framework/base_test.py`, `command_runner.py`, `vsock_helper.py`, `mock_env.py`, `report_formatter.py`
- **Tier 1 (Feature Coverage)**: `test_m1_tier1.py` ~ `test_m5_tier1.py`
- **Tier 2 (Boundary & Corner)**: `test_m1_tier2.py` ~ `test_m5_tier2.py`
- **Tier 3 (Cross-Feature Pairwise)**: `test_pairwise_matrix.py` (40 個用例)
- **Tier 4 (Real-World Scenarios)**: `test_scenarios.py` (20 個完整場景用例)

---

## 5. 驗證指令集與驗證條件 (Verification Commands & Criteria)

為滿足專案 Acceptance Criteria，以下為獨立環境下驗證整體系統 integrity 所需的**精確指令集**：

### 1. Java 語法與編譯完備性驗證 (Java Compile Closure)
```bash
cd /Users/iml1s/Documents/mine/aosp-linux
mkdir -p build_out/classes

# 收集所有 Java 源碼檔案 (包含 Framework、Services、Packages 與 Tests)
find frameworks/base/core/java frameworks/base/services/core/java packages/apps/LinuxTerminal/src packages/apps/Launcher3/src tests/unit -name "*.java" > build_out/all_sources.txt

# 執行 Javac 檢查
javac -d build_out/classes @build_out/all_sources.txt
```
*(驗證條件：`javac` 回傳 exit code 0 且零語法錯誤。當前已知 `LinuxAppProxyActivity.java:270` 需修復二重宣告缺陷。)*

### 2. Rust Guest Agent 交叉編譯與檢查 (`cargo check --target aarch64-unknown-linux-gnu`)
```bash
# 驗證 android-bridge-agent
cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent
~/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu

# 驗證 portal-agent
cd /Users/iml1s/Documents/mine/aosp-linux/guest/portal-agent
~/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu
```
*(驗證條件：Exit code 0 且零 Warning 零 Error。當前已能完成 ARM64 check，需清理 `unused` 警告。)*

### 3. Rust 客戶端單元測試與 Empirical 測試
```bash
cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent
~/.cargo/bin/cargo test

cd /Users/iml1s/Documents/mine/aosp-linux/guest/portal-agent
~/.cargo/bin/cargo test
```
*(驗證條件：所有測試通過，`test result: ok. 34 passed; 0 failed`)*

### 4. Native C++ 測試組件編譯與執行
```bash
cd /Users/iml1s/Documents/mine/aosp-linux
mkdir -p build_out/bin

# 編譯 C++ Native 測試二進制檔
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test

clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_framing_test.cpp -o build_out/bin/challenger_m2_framing_test

clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_hmac_test.cpp -o build_out/bin/challenger_m2_hmac_test

clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/challenger_m2_empirical_test.cpp -o build_out/bin/challenger_m2_empirical_test

clang++ -std=c++17 -Wall -Wextra -I. system/linux_bridge/wayland_buffer_sharing.cpp tests/unit/VirtioGpuDmabufTest.cpp -o build_out/bin/VirtioGpuDmabufTest

clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/guest_ota_rollback_watchdog.cpp tests/unit/guest_ota_rollback_watchdog_test.cpp -o build_out/bin/guest_ota_rollback_watchdog_test

clang++ -std=c++20 -Wall -Wextra -pthread -I. -I/opt/homebrew/opt/openssl@3/include system/vold/AvbVerifier.cpp tests/unit/avb_verifier_test.cpp -L/opt/homebrew/opt/openssl@3/lib -lcrypto -o build_out/bin/avb_verifier_test

# 執行 C++ Native 測試
./build_out/bin/linux_bridge_test
./build_out/bin/challenger_m2_framing_test
./build_out/bin/challenger_m2_hmac_test
./build_out/bin/challenger_m2_empirical_test
./build_out/bin/VirtioGpuDmabufTest
./build_out/bin/guest_ota_rollback_watchdog_test
./build_out/bin/avb_verifier_test
```
*(驗證條件：所有二進制檔執行均輸出 PASS，Exit Code 0)*

### 5. 完整 Python 4-Tier E2E 測試套件執行
```bash
cd /Users/iml1s/Documents/mine/aosp-linux
./tests/e2e/run_tests.sh --output-json tests/e2e/e2e_report.json
```
*(驗證條件：430 / 430 PASS，Pass Rate 100.0%，Exit Code 0)*

---
*報告完成，無修改任何專案原始碼，僅記錄客觀調查數據。*
