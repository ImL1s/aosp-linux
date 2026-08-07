# AOSP Dual-OS 自動化 E2E 與實證壓力測試套件 (runner.py) 調查報告

## 1. 執行摘要 (Executive Summary)

本報告針對需求 **R1: 執行所有 430+ 自動化 E2E 及實證壓力測試套件 (runner.py) 並生成完整驗證報告** 進行全面的唯讀代碼庫調查。

主要調查發現摘要：
1. **runner.py 位置**：`/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py`，並附帶 Shell 封裝啟動腳本 `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/run_tests.sh`。
2. **測試案例數量與分佈**：`runner.py` 透過動態模組載入機制探索 4 個 Tier 目錄，共計包含 **390 個自動化 E2E 測試案例**（Tier 1: 165, Tier 2: 165, Tier 3: 40, Tier 4: 20）。包含全專案在 `tests/stress/` 與 `tests/unit/` 中的獨立實證壓力測試（C++、Java、Rust、Python），專案測試案例總數達 **430+** 個。
3. **執行方式與旗標**：使用 `python3 tests/e2e/runner.py [FLAGS]` 或 `bash tests/e2e/run_tests.sh [FLAGS]` 執行。支援 `--tier` (1-4)、`--feature`、`--filter`、`--report` / `--output-json`、`--verbose` 以及 `--list`。
4. **依賴與前置條件**：僅依賴 Python 3 標準庫（`argparse`、`importlib`、`inspect`、`json`、`dataclasses`、`typing`、`hmac`、`hashlib`、`struct` 等），無第三方 PyPI 套件依賴。
5. **報告格式與路徑**：預設輸出 JSON 格式報告至 `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json`，報告包含精確的時間戳記、總結統計（總數、通過數、失敗數、錯誤數、跳過數、通過率、總耗時）及個別測試的詳細字典紀錄。

---

## 2. runner.py 位置與啟動腳本 (Location)

- **主測試執行器 (CLI Runner)**:
  ` /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py`
- **Shell 啟動腳本 (Shell Launcher)**:
  `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/run_tests.sh`
- **測試框架核心目錄 (Framework Core)**:
  `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/framework/`
  - `base_test.py`: 定義 `BaseTestCase` 抽象類別、`TestResult` 與 `TestStatus` 枚舉 (PASS, FAIL, ERROR, SKIP)。
  - `report_formatter.py`: 主理終端機控制台摘要輸出與 JSON 報告生成。
  - `mock_env.py`: 提供完整模擬環境 `MockEnvironment` (含 MockVsockBridge, MockSystemServer, MockSommelier, MockXdgPortal)。
  - `assertions.py`: 自訂斷言函式 `CustomAssertions` (斷言對齊、SELinux audit 檢查、HMAC 驗證、Vsock header 驗證)。
  - `vsock_helper.py`: Vsock 封包幀化 (VsockFramingHelper) 與 HMAC 簽名與認證 (HmacAuthHelper)。
  - `command_runner.py`: 外部命令執行輔助工具 `CommandRunner`。

---

## 3. 測試套件/案例數量與檔案目錄結構 (Test Inventory & File Locations)

`runner.py` 透過 `discover_test_classes()` 動態搜尋指定 Tier 目錄中所有繼承自 `BaseTestCase` 的測試類別：

### 3.1 `runner.py` 動態探索的 4 大 Tier 目錄（390 個 E2E 測試案例）

| Tier 層級 | 對應檔案目錄 | 包含測試檔案 | 測試案例數 | 測試範疇說明 |
|---|---|---|---|---|
| **Tier 1** | `tests/e2e/tier1_feature_coverage/` | `test_m1_tier1.py` (25)<br>`test_m2_tier1.py` (25)<br>`test_m3_tier1.py` (35)<br>`test_m4_tier1.py` (30)<br>`test_m5_tier1.py` (50) | **165** | 核心功能基礎涵蓋測試 (T1_01 至 T1_165)，驗證 AIDL、VM 啟動、Luks/Overlayfs、Wayland/Vsock、Portals 權限等。 |
| **Tier 2** | `tests/e2e/tier2_boundary_corner/` | `test_m1_tier2.py` (25)<br>`test_m2_tier2.py` (25)<br>`test_m3_tier2.py` (35)<br>`test_m4_tier2.py` (30)<br>`test_m5_tier2.py` (50) | **165** | 邊界與極端異常狀況測試 (T2_01 至 T2_165)，包含 Null 傳參、Binder 死亡、極限長度、逾時、資源搶占與異常恢復。 |
| **Tier 3** | `tests/e2e/tier3_cross_feature/` | `test_pairwise_matrix.py` (40) | **40** | 跨模組成對組合測試 (T3Pair01 至 T3Pair40)，驗證安全解鎖與掛載、Vsock併發、IME與Libvterm、Wayland與Virtio-GPU DMA-BUF等交會場景。 |
| **Tier 4** | `tests/e2e/tier4_real_world/` | `test_scenarios.py` (20) | **20** | 完全實境整合情境測試 (TestScenario01 至 TestScenario20)，包含冷啟動、多視窗調整、相機 Portal 流串流、OTA AVB 失敗退回等端到端流程。 |
| **小計** | — | **12 個測試模組檔案** | **390** | **`runner.py` 動態發現執行的 E2E 測試案例總數** |

### 3.2 專案全體實證壓力測試 (Empirical Stress Tests)

除了 `runner.py` 的 390 個 E2E 測試外，專案在其他目錄包含額外的壓力與實證測試，使總計達到 **430+** 個測試：
1. `tests/e2e/test_m3_challenger2_stress.py`: Milestone M3 實證壓力測試 (6 個測試案例，測試 Touch Mode 狀態機極限與 Vsock 解碼錯位)。
2. `tests/stress/`: C++、Java、Rust、Python 壓力測試套件 (5 個檔案，包含 `InotifyBurstTest.rs`、`AdversarialWaylandBufferSharingTest.cpp` 等)。
3. `tests/unit/`: C++、Java、Python 單元與實證壓力測試 (41 個檔案，包含 `LinuxManagerServiceStressTest.java`、`virtiofs_stress_test.cpp` 等)。

---

## 4. runner.py 執行方式與命令列參數 (Execution & CLI Flags)

### 4.1 執行語法

1. **直接 Python 執行**:
   ```bash
   python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py [FLAGS]
   ```
2. **透過 Shell 腳本執行**:
   ```bash
   bash /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/run_tests.sh [FLAGS]
   ```

### 4.2 支援的命令列參數與旗標

- `--tier {1,2,3,4}` *(int)*: 僅執行指定 Tier 的測試（選擇 1, 2, 3, 4）。
- `--feature FEATURE_ID` *(str)*: 依 Feature ID 進行過濾（不區分大小寫匹配），如 `--feature F-R1-001` 或 `--feature F-R3-005`。
- `--filter FILTER_PATTERN` *(str)*: 依關鍵字進行過濾（可匹配 `test_id`、`feature_id` 或 `title` 的子字串）。
- `--report REPORT_PATH` *(str)*: 指定產出 JSON 驗證報告的路徑（預設值為 `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json`）。
- `--output-json PATH` *(str)*: `--report` 的別名旗標。若提供則覆蓋 `--report`。
- `--verbose` *(action="store_true")*: 當測試失敗或發生 Error 時，輸出詳細的失敗訊息與 Traceback 堆疊追蹤。
- `--list` *(action="store_true")*: 僅列出所有探索到的測試清單與詳細資訊表格，不執行測試即離開（離開碼 0）。

### 4.3 離開碼 (Exit Codes)
- `0`: 所有執行的測試皆通過 (`PASS`)，或指定 `--list` 列出測試。
- `1`: 有任何測試失敗 (`FAIL`) 或發生異常錯誤 (`ERROR`)。

---

## 5. 依賴與前置條件 (Dependencies & Prerequisites)

1. **執行環境 (Runtime Environment)**:
   - macOS / Linux 系統環境。
   - `python3` (Python 3.7+ 常用標準庫)。
2. **零第三方 PyPI 套件依賴**:
   - `runner.py` 及其測試框架完全基於 Python 標準庫開發，無須 `pip install` 任何外置套件。
   - 所使用的標準庫模組包括：`os`, `sys`, `time`, `argparse`, `inspect`, `importlib`, `json`, `subprocess`, `dataclasses`, `typing`, `enum`, `hmac`, `hashlib`, `struct`, `traceback`。
3. **檔案系統存取權限**:
   - 對 `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/` 具備讀取權限。
   - 對輸出報告路徑（預設 `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json`）具備寫入權限。

---

## 6. 驗證報告的產出路徑與格式 (Verification Report Output)

### 6.1 報告產出路徑
- **預設路徑**: `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json`
- 可使用 `--report <path>` 或 `--output-json <path>` 進行客製化指定。

### 6.2 JSON 報告結構範例

```json
{
  "timestamp": "2026-08-06T13:25:31Z",
  "summary": {
    "total": 390,
    "passed": 390,
    "failed": 0,
    "errored": 0,
    "skipped": 0,
    "pass_rate_percent": 100.0,
    "duration_seconds": 1.2345
  },
  "results": [
    {
      "test_id": "T1_01",
      "name": "ApiClassPresence",
      "feature_id": "F-R1-001",
      "tier": 1,
      "status": "PASS",
      "duration_sec": 0.0012,
      "error_message": null,
      "stack_trace": null,
      "metadata": {}
    }
  ]
}
```

### 6.3 終端機控制台輸出格式 (Console Output Format)
在執行過程中，`runner.py` 會逐行顯示測試狀態標記：
`[PASS] Tier 1 | F-R1-001   | T1_01        | ApiClassPresence`
並在執行結束後列出綜合統計面板：
```
================================================================================
                AOSP DUAL-OS E2E TEST EXECUTION REPORT                 
================================================================================
...
--------------------------------------------------------------------------------
TOTAL TESTS  : 390
PASSED       : 390
FAILED       : 0
ERRORS       : 0
SKIPPED      : 0
PASS RATE    : 100.0%
DURATION     : 1.23 seconds
================================================================================
```
