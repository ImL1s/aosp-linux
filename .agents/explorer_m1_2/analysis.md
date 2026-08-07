# Milestone M1 (R1) Analysis Report: E2E Test Runner & Empirical Stress Test Suites Analysis

## 執行摘要 (Executive Summary)

本報告對 AOSP Dual-OS 專案中的驗證腳本 `scripts/run_m1_verification.sh`、E2E 測試執行器 `tests/e2e/runner.py` 以及各類實證壓力測試套件 (`tests/stress/` 與 `tests/e2e/test_m3_challenger2_stress.py`) 進行了深入分析與實證檢驗。

---

## 1. 實證壓力測試套件的執行腳本與命令 (Empirical Stress Test Suites & Commands)

經調查，本專案包含以下四大類實證壓力測試套件：

### A. Python E2E 獨立實證壓力測試套件
- **檔案路徑**: `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/test_m3_challenger2_stress.py`
- **測試重點**: Touch Mode 狀態機並行轉換 (5,000+ 狀態切換)、SGR 滑鼠座標限制與邊界 (1-based 轉譯與 4K 邊界)、手勢中途模式切換狀態脫節、滾輪增量累加與量化損失、Vsock Port 5001 負數 payload 繞過漏洞、無效 Header 類別 byte 解碼脫節。
- **執行命令**:
  ```bash
  python3 tests/e2e/test_m3_challenger2_stress.py
  ```
- **實證結果**: 6/6 個測試案例全數 PASS。
- **備註**: 該檔案位於 `tests/e2e/` 根目錄下，未包含於 `runner.py` 的 Tier 目錄 (`tier1_feature_coverage` ~ `tier4_real_world`) 掃描範圍內，需獨立調用。

### B. `tests/stress/` 目錄下之獨立壓力測試套件
`tests/stress/` 目錄包含 5 個跨語言壓力測試檔案：

1. **`tests/stress/test_desktop_parser_adversarial.py`** (Python)
   - **測試重點**: Linux 桌面入口檔 (`.desktop`) 解析器之對抗性壓力測試 (空檔案、無 Section Header、NoDisplay 過濾、大小寫不敏感過濾、不規則空格、XML 注入字元處理、100 次高頻連續寫入覆蓋)。
   - **執行命令**:
     ```bash
     python3 tests/stress/test_desktop_parser_adversarial.py
     ```
   - **實證結果**: 7/7 個測試步驟全數 PASS。

2. **`tests/stress/InotifyBurstTest.rs`** (Rust)
   - **測試重點**: Guest `portal-agent` 之 inotify 檔案監測器爆發性事件處理 (10 個檔名快速建立、單一檔案 10 次 1ms 爆發寫入去抖動驗證)。
   - **編譯與執行命令**:
     ```bash
     ~/.cargo/bin/rustc tests/stress/InotifyBurstTest.rs -o build_out/bin/InotifyBurstTest && ./build_out/bin/InotifyBurstTest
     ```
   - **實證結果**: 測試步驟 1 與步驟 2 全數 PASS。

3. **`tests/stress/AdversarialWaylandBufferSharingTest.cpp`** (C++)
   - **測試重點**: Native `WaylandBufferSharingManager` 之對抗性 GPU dma-buf 緩衝區共享測試 (負數 FD、零寬高邊界拒絕、FD 匯出、GPU 研磨/重置、SyncFence 等待超時、像素格式協商、Null/雙重釋放安全性)。
   - **編譯與執行命令**:
     ```bash
     clang++ -std=c++20 -Wall -Wextra -pthread -Isystem/linux_bridge -I. \
         system/linux_bridge/wayland_buffer_sharing.cpp \
         tests/stress/AdversarialWaylandBufferSharingTest.cpp \
         -o build_out/bin/AdversarialWaylandBufferSharingTest && \
     ./build_out/bin/AdversarialWaylandBufferSharingTest
     ```
   - **實證結果**: 所有對抗測試全數 PASS。

4. **`tests/stress/AdversarialLinuxAppTrackerTest.java`** (Java)
   - **測試重點**: `LinuxAppTracker` 之 1,000 次 Inotify 爆發更新死鎖/競態測試、惡意 XML 注入字元轉義、5,000 次重複應用程式去重、多使用者 Profile 隔離與動態清理。
   - **編譯與執行命令**: 參見下文 Java 整合編譯命令。

5. **`tests/stress/AdversarialLinuxWindowBridgeServiceTest.java`** (Java)
   - **測試重點**: `LinuxWindowBridgeService` 之最大並行 Task 限制 (20 max) 與溢出拒絕 (-1)、重新啟動 Task ID 重用、16ms/60 FPS 幀率調步 (Frame Pacing Rate Limiting)、多執行緒並行 Surface 操作與 VM 崩潰 Flush。
   - **Java 壓力測試編譯與執行命令**:
     ```bash
     javac -d build_out/classes \
         frameworks/base/core/java/android/annotation/*.java \
         frameworks/base/core/java/android/system/linux/*.java \
         frameworks/base/core/java/android/os/*.java \
         frameworks/base/core/java/android/util/*.java \
         frameworks/base/core/java/android/net/Uri.java \
         frameworks/base/core/java/android/content/Context.java \
         frameworks/base/core/java/android/content/Intent.java \
         frameworks/base/core/java/android/content/IntentFilter.java \
         frameworks/base/core/java/android/content/BroadcastReceiver.java \
         frameworks/base/core/java/android/content/SharedPreferences.java \
         frameworks/base/core/java/android/content/ContentResolver.java \
         frameworks/base/core/java/android/graphics/Bitmap.java \
         frameworks/base/core/java/android/graphics/BitmapFactory.java \
         frameworks/base/core/java/android/net/LocalSocket.java \
         frameworks/base/core/java/android/net/LocalSocketAddress.java \
         frameworks/base/core/java/android/app/ActivityManager.java \
         frameworks/base/core/java/android/hardware/HardwareBuffer.java \
         frameworks/base/core/java/android/view/SurfaceControl.java \
         frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java \
         packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java \
         tests/stress/AdversarialLinuxAppTrackerTest.java \
         tests/stress/AdversarialLinuxWindowBridgeServiceTest.java && \
     java -cp build_out/classes tests.stress.AdversarialLinuxAppTrackerTest && \
     java -cp build_out/classes tests.stress.AdversarialLinuxWindowBridgeServiceTest
     ```
   - **實證結果**: 兩個 Java 壓力測試全數 PASS。

### C. Python E2E 測試執行器 (`tests/e2e/runner.py`)
- **執行命令**:
  ```bash
  python3 tests/e2e/runner.py
  # 或使用 Shell launcher:
  ./tests/e2e/run_tests.sh
  ```
- **測試涵蓋**: 自動掃描並執行 Tier 1 至 Tier 4 總計 430 個 Python E2E 測試案例。

---

## 2. `scripts/run_m1_verification.sh` 調用情況分析

針對指示第二點「`scripts/run_m1_verification.sh` 是否會調用 `runner.py` 與壓力測試套件」的分析結果如下：

1. **是否調用 `runner.py`？**
   - **否 (NO)**。對 `scripts/run_m1_verification.sh` 全文進行行號審查（共 73 行），無任何一行調用 `runner.py` 或 `tests/e2e/run_tests.sh`。

2. **是否調用獨立實證壓力測試？**
   - **否 (NO)**。`scripts/run_m1_verification.sh` 未調用 `tests/e2e/test_m3_challenger2_stress.py`，亦未調用 `tests/stress/` 中的任何 5 個跨語言獨立壓力測試檔案。

3. **`scripts/run_m1_verification.sh` 實際執行的內容**:
   - `[1/4]`: 檢查 21 個 M1 必要的 AIDL、Java 與 C++ 檔案是否存在。
   - `[2/4]`: 編譯 `frameworks/base/core/java`、`frameworks/base/services/core/java` 及 `tests/unit` 下的所有 `.java` 檔案至 `build_out/classes`。
   - `[3/4]`: 執行三個 Java 單元與單元級壓力測試：
     - `tests.unit.LinuxManagerServiceTest`
     - `tests.unit.LinuxManagerStressTest`
     - `tests.unit.ChallengerM1StressTest`
   - `[4/4]`: 編譯並執行 Native C++ 測試 `tests/unit/linux_bridge_test.cpp`。

4. **關鍵發現與缺失 (Caveat / Issue)**:
   - `scripts/run_m1_verification.sh` 第 48 行使用 `find ... "${WORKSPACE_ROOT}/tests/unit" -name "*.java"`，嘗試編譯 `tests/unit/` 下所有的 Java 檔案。
   - 由於後續 Milestone (M3/M4) 在 `tests/unit/` 下新增了 `ChallengerM3RepEmpiricalTest.java` 與 `LinuxAppTrackerTest.java` 等檔案，這些新增的測試依賴 `packages/apps/` 中的類別（例如 `VsockPtyFramer` 與 `LinuxAppTracker`），但 `run_m1_verification.sh` 的編譯路徑並未包含 `packages/apps/`，導致直接執行 `./scripts/run_m1_verification.sh` 會觸發 100+ 個 javac 符號找不到錯誤 (Symbol Not Found Error)。

---

## 3. Worker 應執行的完整驗證命令 (Command for Worker Verification)

若 Worker 需要同時驗證 **E2E 測試執行器 (E2E Test Runner)** 與 **全套實證壓力測試 (Empirical Stress Tests)**（包含 M1 基礎驗證），應執行以下組合驗證管道 (Compound Verification Pipeline)：

```bash
# 1. 執行 Python E2E 測試套件執行器 (430 個 E2E 測試案例)
python3 tests/e2e/runner.py

# 2. 執行 M3 Python 實證壓力測試套件 (6/6 壓力測試)
python3 tests/e2e/test_m3_challenger2_stress.py

# 3. 執行 Python 桌面入口檔解析對抗壓力測試 (7/7 步驟)
python3 tests/stress/test_desktop_parser_adversarial.py

# 4. 編譯並執行 C++ Wayland 緩衝區共享對抗壓力測試
clang++ -std=c++20 -Wall -Wextra -pthread -Isystem/linux_bridge -I. \
    system/linux_bridge/wayland_buffer_sharing.cpp \
    tests/stress/AdversarialWaylandBufferSharingTest.cpp \
    -o build_out/bin/AdversarialWaylandBufferSharingTest && \
./build_out/bin/AdversarialWaylandBufferSharingTest

# 5. 編譯並執行 Rust Inotify 爆發壓力測試
~/.cargo/bin/rustc tests/stress/InotifyBurstTest.rs -o build_out/bin/InotifyBurstTest && \
./build_out/bin/InotifyBurstTest

# 6. 編譯並執行 Java 對抗壓力測試 (Adversarial LinuxAppTracker & LinuxWindowBridgeService)
javac -d build_out/classes \
    frameworks/base/core/java/android/annotation/*.java \
    frameworks/base/core/java/android/system/linux/*.java \
    frameworks/base/core/java/android/os/*.java \
    frameworks/base/core/java/android/util/*.java \
    frameworks/base/core/java/android/net/Uri.java \
    frameworks/base/core/java/android/content/Context.java \
    frameworks/base/core/java/android/content/Intent.java \
    frameworks/base/core/java/android/content/IntentFilter.java \
    frameworks/base/core/java/android/content/BroadcastReceiver.java \
    frameworks/base/core/java/android/content/SharedPreferences.java \
    frameworks/base/core/java/android/content/ContentResolver.java \
    frameworks/base/core/java/android/graphics/Bitmap.java \
    frameworks/base/core/java/android/graphics/BitmapFactory.java \
    frameworks/base/core/java/android/net/LocalSocket.java \
    frameworks/base/core/java/android/net/LocalSocketAddress.java \
    frameworks/base/core/java/android/app/ActivityManager.java \
    frameworks/base/core/java/android/hardware/HardwareBuffer.java \
    frameworks/base/core/java/android/view/SurfaceControl.java \
    frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java \
    packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java \
    tests/stress/AdversarialLinuxAppTrackerTest.java \
    tests/stress/AdversarialLinuxWindowBridgeServiceTest.java && \
java -cp build_out/classes tests.stress.AdversarialLinuxAppTrackerTest && \
java -cp build_out/classes tests.stress.AdversarialLinuxWindowBridgeServiceTest

# 7. 執行 M1 標的修正版 Java 與 C++ 單元驗證
find frameworks/base/core/java frameworks/base/services/core/java tests/unit/LinuxManager*.java tests/unit/ChallengerM1*.java -name "*.java" > build_out/m1_sources.txt && \
javac -d build_out/classes @build_out/m1_sources.txt && \
java -cp build_out/classes tests.unit.LinuxManagerServiceTest && \
java -cp build_out/classes tests.unit.LinuxManagerStressTest && \
java -cp build_out/classes tests.unit.ChallengerM1StressTest && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_framing.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && \
./build_out/bin/linux_bridge_test
```
