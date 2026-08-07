# Milestone M1 (R1) 單元測試與 JSON 驗證報告交接報告 (Handoff Report)

## 1. Observation (直接觀察事實)

1. **單元測試目錄與實體檔案** (`tests/unit/`)：
   - 目錄下包含 41 個檔案，包含 Java 單元/壓力測試（`LinuxManagerServiceTest.java`, `LinuxManagerStressTest.java`, `ChallengerM1StressTest.java`, `TerminalAppUnitTest.java` 等）、C++ Native 測試與二進位檔（`linux_bridge_test.cpp`, `avb_verifier_test.cpp`, `VirtioGpuDmabufTest_bin` 等）、以及 Python 經驗腳本（`challenger_m2_empirical_test.py` 等）。
   - 經實測編譯與執行 Java 測試：`javac -d build_out/classes ...` 及 `java -cp build_out/classes tests.unit.LinuxManagerServiceTest` 等指令成功產出 `CHALLENGER VERDICT: ALL STRESS HARNESSES PASSED (APPROVE)`（退出碼 0）。
   - 經實測編譯與執行 Native C++ 測試：`clang++ -std=c++20 ... system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_framing.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test` 及 `./build_out/bin/linux_bridge_test` 成功產出 `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`（退出碼 0）。

2. **JSON 驗證報告格式** (`tests/e2e_report.json`)：
   - 由 `tests/e2e/runner.py` 透過 `ReportFormatter.generate_json_report()` 產出。
   - 根物件包含三項核心欄位：`timestamp` (ISO 8601 UTC 格式字串), `summary` (統計數據物件), `results` (測試案例詳細結果陣列)。
   - `summary` 包含：`total` (430), `passed` (430), `failed` (0), `errored` (0), `skipped` (0), `pass_rate_percent` (100.0), `duration_seconds` (11.693)。
   - `results` 陣列包含 430 個測試物件，每個物件包含 `test_id`, `name`, `feature_id`, `tier`, `status`, `duration_sec`, `error_message`, `stack_trace`, `metadata` 共 9 個子欄位。

3. **二進位檔依賴現象**：
   - 若未編譯 `build_out/bin/` 二進位檔即直接執行 `python3 tests/e2e/runner.py`，其中 13 項 Native 端到端封裝測試會因缺少執行檔（Exit Code 127）而失敗（通過率下降至 97%）。

---

## 2. Logic Chain (推導邏輯鏈)

1. **單元測試執行與運作推導**：
   - 觀察：`frameworks/base/core/java/android/` 實現了 Mock Android SDK 類別。
   - 推導： Java 單元測試脫離了對外部完整 Android SDK / AOSP 重型環境的依賴，可用純 JDK `javac` / `java` 獨立快速運行。
   - 觀察：Native C++ 測試包含獨立 `main()` 與 Socket/Vsock 模擬測試邏輯。
   - 推導：可以使用 `clang++ -std=c++20` 直接編譯成獨立執行檔放置於 `build_out/bin/` 運行。
   - 觀察：`tests/e2e/runner.py` 動態探索 Tier 1-4 測試並封裝執行。
   - 推導：全量測試套件可透過 Python Runner 一鍵排程與產出報告。

2. **報告有效性與驗證邏輯推導**：
   - 觀察：`TEST_INFRA.md` 要求最少 425 項測試（185 T1 + 185 T2 + 37 T3 + 18 T4），當前實際探索出 430 項測試。
   - 推導：合格的 `e2e_report.json` 必須滿足 `summary.total >= 425` 且 `summary.passed == summary.total`（100% 通過）。
   - 觀察：`results` 陣列每筆資料之 `status` 代表個別測試結果。
   - 推導：必須遍歷 `results` 陣列確保無任何 `FAIL`, `ERROR`, `SKIP` 存在，且 `error_message` 及 `stack_trace` 為 `null`。

3. **Worker 驗證 SOP 推導**：
   - 觀察：缺少 `build_out/bin/` 二進位檔時 13 項測試會回傳 127 錯誤。
   - 推導：Worker 必須採取「先構建二進位檔 Artifacts」➜「執行 `runner.py` 產出 JSON」➜「執行 Python 斷言腳本進行 100% 通過校驗」的標準 3 步驟。

---

## 3. Caveats (注意事項與例外說明)

1. **環境編譯依賴**：執行 `tests/e2e/runner.py` 測試前，必須確保 `build_out/bin/` 已正確編譯 Native 執行檔，否則會出現二進位檔缺失的 127 失敗。
2. **Java 探索與選取**：若直接調用全目錄 `javac` 編譯 `tests/unit/*.java`，因部分 M3/M4 測試引用了 `packages/apps/` 下的模組，必須一併引入 `packages/apps/` 原始碼或僅編譯特定 Milestone Java 測試。

---

## 4. Conclusion (最終結論)

1. **單元測試執行**：`tests/unit/` 之單元測試包含 Java Mock-SDK 單元測試、C++ Native 測試與 Python 經驗測試。Java 透過 `javac`/`java` 執行；Native 透過 `clang++` 編譯為執行檔執行；E2E Runner 透過 `python3 tests/e2e/runner.py` 進行動態探索與集體測試。
2. **報告格式規格**：`tests/e2e_report.json` 符合完整定義之 `timestamp`, `summary` (total>=425, passed==total, failed==0, errored==0, skipped==0, pass_rate_percent==100.0), 及 `results` 陣列 (430 項 status=="PASS") 規格。
3. **Worker 驗證 SOP**：Worker 應遵照二進位構建 ➜ `runner.py` 執行 ➜ Python 單行斷言校驗三步驟，確認全數 430+ 測試案例皆為 100% PASS。

---

## 5. Verification Method (驗證方法與獨立複現指令)

接收此交接報告之 Agent 或 Worker 可使用以下指令進行獨立複現與驗證：

```bash
# 1. 進入工作目錄
cd /Users/iml1s/Documents/mine/aosp-linux

# 2. 構建 Native 測試二進位檔與 M1 Java 單元測試
./scripts/run_m1_verification.sh

# 3. 執行全量 E2E 測試套件並產出驗證報告
python3 tests/e2e/runner.py --output-json tests/e2e_report.json

# 4. 執行 Python 自動斷言報告校驗
python3 -c '
import json, sys
with open("tests/e2e_report.json", "r", encoding="utf-8") as f:
    data = json.load(f)
s = data.get("summary", {})
assert s.get("total", 0) >= 425
assert s.get("passed", 0) == s.get("total", 0)
assert s.get("failed", -1) == 0
assert s.get("errored", -1) == 0
assert s.get("skipped", -1) == 0
assert s.get("pass_rate_percent", 0.0) == 100.0
assert len(data.get("results", [])) == s.get("total", 0)
for r in data.get("results", []):
    assert r.get("status") == "PASS"
print("[INDEPENDENT VERIFICATION SUCCESS] e2e_report.json verified 100% PASS.")
'
```
