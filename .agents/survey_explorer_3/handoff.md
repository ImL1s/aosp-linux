# Handoff Report — Build Infra & Verification Explorer (`survey_explorer_3`)

## 1. Observation (觀察事實)

- **專案結構與模組組件**:
  - 全域共 15 個子目錄，包括 `frameworks/base/core/java/android/system/linux/` (AIDL 介面及 API 樁), `frameworks/base/services/core/java/com/android/server/linux/` (SystemServer 服務), `guest/bridge-agent` (Rust Host-Guest 中繼 agent), `guest/portal-agent` (Rust .desktop/inotify agent), `packages/apps/LinuxTerminal` (Touch Terminal App & JNI), `system/linux_bridge` (Native C++ daemon), `tests/e2e` (Python 4-Tier 430 個 E2E 測試用例)。
- **構建設定檔 (Build System Files)**:
  - AOSP Soong: 包含 `Android.bp`, `packages/apps/LinuxTerminal/Android.bp`, `packages/apps/LinuxTerminal/jni/Android.bp`, `system/linux_bridge/Android.bp`。
  - Rust Cargo: 包含 `guest/bridge-agent/Cargo.toml`, `guest/portal-agent/Cargo.toml`。
  - 不存在任何 Gradle (`build.gradle`), CMake (`CMakeLists.txt`) 或 `Makefile` 檔案。
- **Java 語法與編譯現況**:
  - 執行 `find frameworks/base/core/java frameworks/base/services/core/java packages/apps/LinuxTerminal/src packages/apps/Launcher3/src tests/unit -name "*.java" > build_out/all_sources.txt && javac -d build_out/classes @build_out/all_sources.txt` 獲得以下錯誤：
    ```
    packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java:270: error: illegal start of expression
        private void attachSurfaceControlToBridge(int surfaceId, SurfaceControl surfaceControl) {
        ^
    1 error
    ```
  - `LinuxAppProxyActivity.java` 第 264 行與 270 行存在重複且未關閉的 `attachSurfaceControlToBridge` 方法宣告。
  - `LinuxAppProxyActivity.java` 第 277 行仍使用反射 `Class.forName("com.android.server.linux.LinuxWindowBridgeService")`。
- **Rust ARM64 交叉編譯與單元測試**:
  - 於 `guest/bridge-agent` 執行 `~/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`：Exit Code 0，產生 2 個未使用代碼警告 (`reset_portal_state` 與 `Tcp` enum variant)。
  - 於 `guest/portal-agent` 執行 `~/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`：Exit Code 0，產生 2 個未使用 import 警告 (`Path` 與 `Sender`)。
  - 於 `guest/bridge-agent` 執行 `~/.cargo/bin/cargo test`：`test result: ok. 34 passed; 0 failed` (100% Pass)。
  - 於 `guest/portal-agent` 執行 `~/.cargo/bin/cargo test`：`test result: ok. 0 passed; 0 failed` (0 個測試)。
- **C++ Native 測試組件編譯與執行**:
  - 使用 `clang++ -std=c++20` (與 `-std=c++17`) 編譯 7 個 C++ 測試檔至 `build_out/bin/` 均可成功編譯並全部執行 PASS (包含 `linux_bridge_test`, `challenger_m2_framing_test`, `challenger_m2_hmac_test`, `challenger_m2_empirical_test`, `VirtioGpuDmabufTest`, `guest_ota_rollback_watchdog_test`, `avb_verifier_test`)。
- **Python E2E 測試執行率**:
  - 執行 `./tests/e2e/run_tests.sh --output-json tests/e2e/e2e_report.json`：
    `TOTAL TESTS: 430 | PASSED: 386 | FAILED: 44 | PASS RATE: 89.8%`。
  - 相較於 `TEST_READY.md` 聲稱的 100% 通過率，實測顯示 44 個測試失敗，主因包含 `build_out/bin` 與 `/tmp/m3_classes` 之前未編譯備齊，以及部分單元測試對 Android SDK classpath / 執行環境有特化依賴。
- **Milestone 驗證腳本缺漏**:
  - `scripts/run_m1_verification.sh:21`: 指向不存在的 `frameworks/base/core/java/android/system/linux/ILinuxBridgeDaemon.aidl` (實際位於 `system/linux_bridge/ILinuxBridgeDaemon.aidl`)。
  - `scripts/run_m2_verification.sh:32`: 指向不存在的 `LinuxCeKeyManager.java`。
  - `scripts/run_m5_verification.sh:15`: 指向不存在的 `LinuxAudioPolicyHandler.java`。

---

## 2. Logic Chain (推理論證鏈)

1. **從觀察到 Java 編譯斷點**:
   - `javac` 直接對全部源碼進行編譯時在 `LinuxAppProxyActivity.java:270` 爆出 `illegal start of expression` 錯誤。
   - 檢視 `LinuxAppProxyActivity.java` 第 264-270 行發現第 264 行宣告方法開了 `{` 卻沒有 `}` 即在第 270 行重新宣告同名方法，導致語法解析崩潰。
   - 因此，R1 階段必須修復 `LinuxAppProxyActivity.java` 語法錯誤，並以 Binder IPC 替換第 277 行的反射呼叫。
2. **從觀察到 Milestone 腳本失效原因**:
   - `run_m1_verification.sh`, `run_m2_verification.sh`, `run_m5_verification.sh` 執行即中斷並回傳 file missing error。
   - 原因為腳本內的 `required_files` 陣列包含舊檔名或移動過位置的 AIDL/Java 檔案名稱 (如 `ILinuxBridgeDaemon.aidl`, `LinuxCeKeyManager.java`, `LinuxAudioPolicyHandler.java`)。
3. **從觀察到 E2E 通過率 89.8% 與修正途徑**:
   - 初始執行 `runner.py` 時，因 `build_out/bin/` 缺少 `linux_bridge_test` 等 C++ 測試產物，導致 Python runner 呼叫外部二進制時回傳 code 127 失敗。
   - 在先手手動使用 `clang++` 編譯全部 7 個 C++ 測試產物後，E2E 通過測試數由 377 提升至 386 (通過率由 87.7% 提升至 89.8%)。
   - 其餘 44 個失敗用例多為 Java `javac` 編譯斷點或 `/tmp/m3_classes` 樁代碼缺乏所致。

---

## 3. Caveats (保留事項與限制)

- 本次調查嚴格遵守 **Read-Only** 原則，未修改任何項目源碼或驗證腳本。
- `cargo check --target aarch64-unknown-linux-gnu` 需確保本機已安裝 `aarch64-unknown-linux-gnu` target (`rustup target add aarch64-unknown-linux-gnu`) 且使用 `~/.cargo/bin/cargo` 執行。
- 在無完整 AOSP 環境的 macOS 宿主機上，`javac` 需要依賴專案內建立的 Android Stub 類別 (`frameworks/base/core/java/android/...`) 才能順利編譯無 SDK 依賴的 Java 檔案。

---

## 4. Conclusion (最終結論)

1. 專案具備完整且設計精良的雙重構建（AOSP Soong + Standalone Verification Scripts）與 4-Tier E2E 測試框架（430 個測試用例）。
2. 目前阻礙 100% 驗證通過的核心瓶頸已明確定位：
   - **Java 語法與 Binder IPC**: `LinuxAppProxyActivity.java:270` 語法錯位與反射解耦。
   - **Rust Warning**: `bridge-agent` 與 `portal-agent` 各有 2 個 unused warnings。
   - **Verification Shell Scripts**: `run_m1/m2/m5_verification.sh` 的 `required_files` 路徑需要修正更新。
   - **Test Binary Build Order**: E2E 測試前必須依序完成 C++ Native 測試二進制檔與 Java class 樁檔的 pre-build。

---

## 5. Verification Method (獨立驗證方法)

執行以下 4 個步驟以獨立驗證上述調查結果與專案編譯/測試狀態：

### 步驟 1: 驗證 Java 語法與編譯錯誤
```bash
cd /Users/iml1s/Documents/mine/aosp-linux
mkdir -p build_out/classes
find frameworks/base/core/java frameworks/base/services/core/java packages/apps/LinuxTerminal/src packages/apps/Launcher3/src tests/unit -name "*.java" > build_out/all_sources.txt
javac -d build_out/classes @build_out/all_sources.txt
```
*預期結果*: 於 `LinuxAppProxyActivity.java:270` 報錯 `illegal start of expression` (Exit Code 1)。

### 步驟 2: 驗證 Rust ARM64 交叉編譯與單元測試
```bash
cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent
~/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu
~/.cargo/bin/cargo test

cd /Users/iml1s/Documents/mine/aosp-linux/guest/portal-agent
~/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu
~/.cargo/bin/cargo test
```
*預期結果*: 兩者 `cargo check` Exit Code 0 且皆帶有 2 個 warnings；`bridge-agent` `cargo test` 34/34 PASS。

### 步驟 3: 驗證 C++ Native 測試編譯與執行
```bash
cd /Users/iml1s/Documents/mine/aosp-linux
mkdir -p build_out/bin
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test
./build_out/bin/linux_bridge_test
```
*預期結果*: 編譯成功並輸出 `PASS: All linux_bridge_test passed.`。

### 步驟 4: 驗證 Python E2E 測試套件
```bash
cd /Users/iml1s/Documents/mine/aosp-linux
./tests/e2e/run_tests.sh --output-json tests/e2e/e2e_report.json
```
*預期結果*: 執行 430 個測試用例，目前真實通過率為 89.8% (386 PASS, 44 FAIL)。
