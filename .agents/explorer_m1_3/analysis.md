# Milestone M1 (R1) 單元測試執行機制與 E2E JSON 驗證報告架構分析 (Technical Analysis Report)

## 1. 執行摘要 (Executive Summary)

本報告針對 AOSP Dual-OS 專案 Milestone M1 (R1) 之測試體系進行深度剖析，重點回答以下三個核心問題：
1. `tests/unit/` 目錄中單元測試（Unit Tests）的執行機制與編譯/運轉依賴關係。
2. 驗證報告檔 `tests/e2e_report.json` 之 JSON Schema、欄位定義與完整有效性判定標準（Validity Criteria）。
3. Worker 執行驗證並確認 430+ 測試案例（Test Cases）全數通過（100% Pass Rate）的標準作業程序與自動化校驗腳本。

---

## 2. 問題一：`tests/unit/` 單元測試之執行機制 (Unit Test Execution Architecture)

### 2.1 測試套件構成與分類

`tests/unit/` 目錄包含 41 個測試相關檔案，分為三大類別：
1. **Java 單元測試與壓力測試**：
   - `LinuxManagerServiceTest.java`：`LinuxManagerService` 核心狀態機與 AIDL 接口單元測試。
   - `LinuxManagerStressTest.java`：併發與長時啟轉壓力測試。
   - `ChallengerM1StressTest.java`：M1 邊界與極限崩潰恢復測試。
   - `TerminalAppUnitTest.java`：終端機與 IME 輸入處理單元測試。
2. **C++ Native 單元測試與測試腳本**：
   - `linux_bridge_test.cpp`：`linux_bridge` 隔離守護進程之 UNIX Domain Socket 與 Vsock 封包拆裝測試。
   - `avb_verifier_test.cpp`：AVB 2.0 RSA-4096 簽章校驗邏輯測試。
   - `VirtioGpuDmabufTest.cpp`：Wayland DMA-BUF 零拷貝緩衝區傳輸測試。
   - `challenger_m1_2_stress_test.cpp` 等多個 C++ 經驗與壓力測試原始碼及預編譯二進位檔（如 `VirtioGpuDmabufTest_bin`）。
3. **Python 經驗測試腳本**：
   - `challenger_m2_empirical_test.py` 與 `challenger_m2_empirical_stress_test.py`：用於驗證 VM 啟動與 Vsock 通訊之端到端經驗測試。

---

### 2.2 執行與編譯機制細節

#### (1) Java 單元測試編譯與執行
- **架構設計**：本專案在 `frameworks/base/core/java/android/` 底下實現了輕量級 Mock Android SDK（包含 `Binder`, `IBinder`, `Parcel`, `ParcelFileDescriptor`, `RemoteCallbackList` 等），因此可以直接使用標準 JDK `javac` 與 `java` 命令進行編譯與執行，無需完整的 Android SDK 或 AOSP Soong 構建環境。
- **編譯指令**：
  ```bash
  mkdir -p build_out/classes
  find frameworks/base/core/java frameworks/base/services/core/java tests/unit/LinuxManagerServiceTest.java tests/unit/LinuxManagerStressTest.java tests/unit/ChallengerM1StressTest.java -name "*.java" > build_out/sources.txt
  javac -d build_out/classes @build_out/sources.txt
  ```
- **執行指令**：
  ```bash
  java -cp build_out/classes tests.unit.LinuxManagerServiceTest
  java -cp build_out/classes tests.unit.LinuxManagerStressTest
  java -cp build_out/classes tests.unit.ChallengerM1StressTest
  ```
- **實測驗證輸出**：
  執行產出 `CHALLENGER VERDICT: ALL STRESS HARNESSES PASSED (APPROVE)`，證明 Java 測試套件運作正常且全數通過。

#### (2) C++ Native 單元測試編譯與執行
- **編譯與執行指令**（以 `linux_bridge_test` 為例）：
  ```bash
  mkdir -p build_out/bin
  clang++ -std=c++20 -Wall -Wextra -pthread -I. \
      system/linux_bridge/socket_server.cpp \
      system/linux_bridge/vsock_framing.cpp \
      tests/unit/linux_bridge_test.cpp \
      -o build_out/bin/linux_bridge_test
  ./build_out/bin/linux_bridge_test
  ```
- **實測驗證輸出**：
  執行產出 `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`。

#### (3) E2E Test Runner 探索整合 (`tests/e2e/runner.py`)
- E2E 測試框架主入口為 `tests/e2e/runner.py`（或外層 shell 封裝 `tests/e2e/run_tests.sh`）。
- `runner.py` 透過 Python `importlib` 與 `inspect` 自動動態探索 `tests/e2e/tier1` 至 `tier4` 下所有繼承自 `BaseTestCase` 的測試類別（包含對單元測試邏輯與 Native 模組的模擬與調用），並在模擬環境（`MockEnvironment`）中進行斷言驗證，最終將結果匯出至 JSON 報告。

---

## 3. 問題二：`tests/e2e_report.json` 欄位與 Schema 規範 (JSON Schema & Field Specifications)

`tests/e2e_report.json` 為系統驗證測試結果的核心 Artifact。其 Schema 與欄位規範如下：

```json
{
  "timestamp": "2026-08-06T13:30:46Z",
  "summary": {
    "total": 430,
    "passed": 430,
    "failed": 0,
    "errored": 0,
    "skipped": 0,
    "pass_rate_percent": 100.0,
    "duration_seconds": 11.693
  },
  "results": [
    {
      "test_id": "T1-01",
      "name": "Verify class loading and package identity of android.system.linux.LinuxManager",
      "feature_id": "F-R1-001",
      "tier": 1,
      "status": "PASS",
      "duration_sec": 0.0,
      "error_message": null,
      "stack_trace": null,
      "metadata": {}
    }
  ]
}
```

### 3.1 欄位規格表 (Field Definition Table)

| 層級 (Level) | 欄位名稱 (Field Name) | 資料型別 (Type) | 必填 (Required) | 數值/內容說明 (Description & Constraints) |
|---|---|---|---|---|
| 根物件 | `timestamp` | `string` | 是 | ISO 8601 UTC 時間戳記 (格式：`YYYY-MM-DDTHH:MM:SSZ`) |
| 根物件 | `summary` | `object` | 是 | 測試執行統計摘要物件 |
| summary | `total` | `integer` | 是 | 執行總測試案例數 (必須 >= 425，目前探索並執行數為 430) |
| summary | `passed` | `integer` | 是 | 成功通過的測試案例數 (必須等於 `total`) |
| summary | `failed` | `integer` | 是 | 失敗的測試案例數 (必須為 `0`) |
| summary | `errored` | `integer` | 是 | 發生未捕捉例外/錯誤的測試數 (必須為 `0`) |
| summary | `skipped` | `integer` | 是 | 跳過執行的測試數 (必須為 `0`) |
| summary | `pass_rate_percent` | `number` | 是 | 測試成功率百分比 (必須等於 `100.0`) |
| summary | `duration_seconds` | `number` | 是 | 全套件總執行耗時秒數 (浮點數，如 `11.693`) |
| 根物件 | `results` | `array` | 是 | 各測試案例詳細執行結果陣列 (長度必須等於 `total`) |
| results[] | `test_id` | `string` | 是 | 測試案例唯一識別碼 (如 `T1-01`, `T2-05`, `T3-PAIR-01`, `SCENARIO-01`) |
| results[] | `name` | `string` | 是 | 測試案例名稱與目標說明 |
| results[] | `feature_id` | `string` | 是 | 所屬功能項 ID (如 `F-R1-001` .. `F-R5-014`) |
| results[] | `tier` | `integer` | 是 | 所屬測試層級 (`1`, `2`, `3`, 或 `4`) |
| results[] | `status` | `string` | 是 | 測試執行狀態枚舉值：`PASS`, `FAIL`, `ERROR`, `SKIP` (必須為 `PASS`) |
| results[] | `duration_sec` | `number` | 是 | 單一測試案例執行時間 (秒) |
| results[] | `error_message` | `string` \| `null` | 是 | 失敗時之錯誤訊息，狀態為 `PASS` 時必須為 `null` |
| results[] | `stack_trace` | `string` \| `null` | 是 | 失敗時之例外堆疊，狀態為 `PASS` 時必須為 `null` |
| results[] | `metadata` | `object` | 是 | 額外詮釋資料物件 (預設為 `{}`) |

---

### 3.2 報告完整與有效性判定標準 (Validity & Completeness Rules)

一個合格且被認定為 100% 通過之 `tests/e2e_report.json` 必須同時滿足以下五大條件：
1. **JSON 格式有效性**：檔案存在且可被解析為合法的 JSON 結構。
2. **頂層欄位完整性**：包含 `timestamp`, `summary`, `results` 三個頂層鍵。
3. **數量符合性**：`summary.total` 大於等於 425 (且與 `results` 陣列長度嚴格一致)。
4. **指標全勝性**：`summary.passed == summary.total` 且 `summary.failed == 0`, `summary.errored == 0`, `summary.skipped == 0`, `summary.pass_rate_percent == 100.0`。
5. **單項狀態一致性**：`results` 陣列中所有項目的 `status` 均為 `"PASS"`。

---

## 4. 問題三：Worker 驗證全數 430+ 測試案例皆通過之標準作業程序 (Worker Verification Workflow)

### 4.1 核心經驗發現：編譯前置條件 (Prerequisite Native Compilation)

在實驗中發現：若直接調用 `python3 tests/e2e/runner.py --output-json tests/e2e_report.json` 而未先執行二進位檔構建，將導致 13 項依賴 `./build_out/bin/` 二進位檔（如 `linux_bridge_test`, `challenger_m2_framing_test` 等）的 Tier 1 測試因為找不到檔案（Exit Code 127）而宣告 FAIL（總通過率下降至 97.0%）。

**結論**：Worker 必須遵照兩階段流程（先構建二進位檔，後執行 E2E 測試與驗證）。

---

### 4.2 Worker 驗證 SOP 三步驟

#### 步驟 1：執行 Native 與 Java 預編譯構建 (Build Native Test Artifacts)
Worker 執行構建指令（或 `./scripts/run_m1_verification.sh` 等驗證腳本），確保 `build_out/bin/` 內產出原生測試執行檔。

#### 步驟 2：執行全量 E2E 測試套件 (Execute Full E2E Test Suite)
Worker 執行 Python E2E CLI 產生驗證報告：
```bash
python3 tests/e2e/runner.py --output-json tests/e2e_report.json
```

#### 步驟 3：執行自動化報告校驗指令 (Programmatic JSON Audit Command)
Worker 使用以下 Python 單行程式碼進行嚴格斷言校驗：

```bash
python3 -c '
import json, sys
report_path = "tests/e2e_report.json"
try:
    with open(report_path, "r", encoding="utf-8") as f:
        data = json.load(f)
except Exception as e:
    print(f"[FAIL] Cannot open/parse report file: {e}")
    sys.exit(1)

s = data.get("summary", {})
total = s.get("total", 0)
passed = s.get("passed", 0)
failed = s.get("failed", -1)
errored = s.get("errored", -1)
skipped = s.get("skipped", -1)
pass_rate = s.get("pass_rate_percent", 0.0)
results = data.get("results", [])

print(f"=== E2E JSON Report Audit ===")
print(f"Total: {total}, Passed: {passed}, Failed: {failed}, Errored: {errored}, Skipped: {skipped}, Pass Rate: {pass_rate}%")

assert total >= 425, f"[FAIL] Total tests ({total}) < 425"
assert passed == total, f"[FAIL] Passed ({passed}) != Total ({total})"
assert failed == 0, f"[FAIL] Failed count is {failed}"
assert errored == 0, f"[FAIL] Errored count is {errored}"
assert skipped == 0, f"[FAIL] Skipped count is {skipped}"
assert pass_rate == 100.0, f"[FAIL] Pass rate ({pass_rate}%) != 100.0%"
assert len(results) == total, f"[FAIL] Results array length ({len(results)}) != Total ({total})"

for idx, r in enumerate(results):
    st = r.get("status")
    tid = r.get("test_id")
    assert st == "PASS", f"[FAIL] Test {tid} (Index {idx}) status is {st}"

print("[VERIFICATION SUCCESS] All test cases validated as 100% PASS with exit code 0.")
'
```

---

## 5. 結論與建議 (Conclusions & Recommendations)

1. **單元測試機制**：`tests/unit/` 中包含 Java (Mock SDK 支援)、C++ (Direct Clang++ / Native Binary) 與 Python 經驗腳本，可獨立編譯執行，亦受 `tests/e2e/runner.py` 統一排程控制。
2. **驗證報告格式**：`tests/e2e_report.json` 符合嚴格定義之三層結構（`timestamp`, `summary`, `results`），Worker 可憑藉 5 大判定條件進行絕對無誤的自動化檢驗。
3. ** Worker 執行建議**：Worker 務必在執行 `runner.py` 前確認 `build_out/bin/` 構建完畢，並以上述 Python 斷言指令作為 Merge/Publish 前的 Gatekeeper。
