# Phase C Audit Findings Investigation Report: Test Runner Failure & T2-43 Bug Analysis

## 1. Observation (觀察事實)

1. **獨立執行 `python3 tests/e2e/runner.py` 測試結果**:
   - 執行命令：`python3 tests/e2e/runner.py`
   - 實際輸出：
     ```text
     TOTAL TESTS  : 430
     PASSED       : 429
     FAILED       : 1
     ERRORS       : 0
     SKIPPED      : 0
     PASS RATE    : 99.77%
     DURATION     : ~39.26 seconds
     EXIT CODE    : 1
     ```
   - 失敗測試案例：`T2-43`: `Vsock CID (Context ID) spoofing rejection`
   - 失敗訊息（Verbatim Error）：
     ```text
     [FAIL] Tier 2 | F-R2-004   | T2-43        | Vsock CID (Context ID) spoofing rejection
            └── Failure Details for T2-43: Item 'clientAddr.svm_cid != ALLOWED_GUEST_CID' not found in container
     Traceback (most recent call last):
       File "/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/framework/base_test.py", line 77, in execute
         self.run_test()
       File "/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/tier2_boundary_corner/test_m2_tier2.py", line 332, in run_test
         CustomAssertions.assert_in("clientAddr.svm_cid != ALLOWED_GUEST_CID", content)
       File "/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/framework/assertions.py", line 33, in assert_in
         raise AssertionError(msg or f"Item {item!r} not found in container")
     AssertionError: Item 'clientAddr.svm_cid != ALLOWED_GUEST_CID' not found in container
     ```

2. **測試案例 `T2-43` 的位置與確切實作 (Location & Exact Implementation)**:
   - 檔案位置：`/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/tier2_boundary_corner/test_m2_tier2.py`
   - 行號範圍：第 322 行至第 333 行
   - 程式碼內容：
     ```python
     class TestR2_004_T2_43_CidSpoofingRejection(BaseTestCase):
         test_id = "T2-43"
         feature_id = "F-R2-004"
         title = "Vsock CID (Context ID) spoofing rejection"
         tier = 2

         def run_test(self):
             cpp_path = os.path.join(PROJECT_ROOT, "system", "linux_bridge", "vsock_server.cpp")
             with open(cpp_path, "r") as f:
                 content = f.read()
             CustomAssertions.assert_in("clientAddr.svm_cid != ALLOWED_GUEST_CID", content)
     ```

3. **C++ 源碼 `vsock_server.cpp` 與 `vsock_server.h` 中的實際 CID 檢查邏輯**:
   - 檔案位置：`/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/vsock_server.h`
     - 第 36 行：`static constexpr uint32_t ALLOWED_GUEST_CID = 3;`
   - 檔案位置：`/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/vsock_server.cpp`
     - 第 147 行（`listenLoop` 內部）：
       `if (processHandshake(clientAddr.svm_cid, payload)) {`
     - 第 204-212 行（`processHandshake` 函數實作）：
       ```cpp
       bool VsockServer::processHandshake(uint32_t cid, const AuthHandshakePayload& payload) {
           ...
           if (cid != ALLOWED_GUEST_CID) {
               std::cerr << "[VsockServer] SecurityException: Connection from unauthorized CID " << cid << " rejected" << std::endl;
               return false;
           }
       ```

4. **C++ 單元/壓力測試對 CID Spoofing 拒絕邏輯的真實驗證**:
   - 檔案位置：`/Users/iml1s/Documents/mine/aosp-linux/tests/unit/challenger_m2_i3_2_vsock_stress.cpp`
     - 第 228-234 行：
       ```cpp
       // Connection from unauthorized CID != 3 must be rejected
       assert(!server.processHandshake(99, payload));
       assert(!server.isAuthenticated());

       // Connection from authorized CID == 3 must succeed
       assert(server.processHandshake(VsockServer::ALLOWED_GUEST_CID, payload));
       assert(server.isAuthenticated());
       ```

5. **測試執行器 `runner.py` 執行方式與報告產生機制**:
   - 檔案位置：`/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py`
   - `discover_test_classes()` 動態掃描 `tier1_feature_coverage`, `tier2_boundary_corner`, `tier3_cross_feature`, `tier4_real_world` 目錄下的所有 `test_*.py` 測試檔案。
   - 預設 JSON 報告輸出路徑（第 32 行）：`DEFAULT_REPORT_PATH = os.path.abspath(os.path.join(BASE_DIR, "..", "e2e_report.json"))`（即專案根目錄的 `tests/e2e_report.json`）。
   - 結束碼計算（第 206-207 行）：
     ```python
     has_failures = any(r.status in (TestStatus.FAIL, TestStatus.ERROR) for r in results)
     sys.exit(1 if has_failures else 0)
     ```
   - 存在兩個 JSON 報告檔案：
     1. `tests/e2e/e2e_report.json`（子目錄）：靜態檔案，時間戳記為 `2026-08-06T15:58:51Z`，內容硬編碼為 `"passed": 430, "failed": 0, "pass_rate_percent": 100.0`。
     2. `tests/e2e_report.json`（根目錄）：動態檔案，當執行 `python3 tests/e2e/runner.py` 時被寫入，記錄 `"passed": 429, "failed": 1, "pass_rate_percent": 99.77`。

6. **`TEST_READY.md` 的宣稱與矛盾數據**:
   - 檔案位置：`/Users/iml1s/Documents/mine/aosp-linux/TEST_READY.md`
   - 宣稱：「All 430 executed tests passed with 100% success rate and exit code 0.」
   - 引用產物：`tests/e2e/e2e_report.json` 與 `tests/e2e_report.json`。
   - 實際比對：獨立執行 `python3 tests/e2e/runner.py` 產生的真實 Exit Code 為 **1**，且失敗 1 項 (`T2-43`)，證明 `TEST_READY.md` 與 Git 中的靜態 `tests/e2e/e2e_report.json` 提供了與實際程式碼執行結果不符的偽造/過期數據。

---

## 2. Logic Chain (推論邏輯鏈)

1. **`T2-43` 失敗根因分析 (Root Cause Analysis)**:
   - **觀察 1 & 2**: `T2-43` 採用「靜態文本比對（Static Text Matching）」方式驗證 CID 拒絕功能。它開啟 `vsock_server.cpp` 文字檔，斷言其中必須包含字串 `"clientAddr.svm_cid != ALLOWED_GUEST_CID"`。
   - **觀察 3**: 在 `vsock_server.cpp` 中，開發者將 CID 驗證邏輯重構封裝至 `processHandshake(uint32_t cid, ...)` 函數中。在 `listenLoop` 中傳入 `clientAddr.svm_cid`，而在 `processHandshake` 內部使用的參數名稱為 `cid`，因此實際程式碼寫法為 `if (cid != ALLOWED_GUEST_CID)`。
   - **推論**: C++ 源碼本質上已正確實作 CID Spoofing 拒絕邏輯（`if (cid != ALLOWED_GUEST_CID)`），且在 C++ 單元測試中（**觀察 4**）成功通過驗證。然而，`T2-43` 測試案例使用了過於僵化且脆弱的字串比對斷言（Search for `clientAddr.svm_cid != ALLOWED_GUEST_CID`），導致測試程式在 C++ 重構後無法在文字中找到該精確字串而拋出 `AssertionError`。
   - **結論**: `T2-43` 失敗原因非 C++ 邏輯漏洞，而是 **Python E2E 測試基底中的靜態字串斷言與重構後的 C++ 源碼字面不符**（Fragile static-string assertion anti-pattern）。

2. **`runner.py` 執行機制與 static report / `TEST_READY.md` 數據矛盾分析**:
   - **觀察 5**: `runner.py` 會實時掃描並執行全部 430 個 Python 測試案例。任何一個測試斷言失敗，`has_failures` 即為 `True`，最終呼叫 `sys.exit(1)`。
   - **觀察 5 & 6**: 專案中存在靜態預存的 `tests/e2e/e2e_report.json`（日期為 2026-08-06），記錄 430 通過、0 失敗。`TEST_READY.md` 直接引用或抄錄了該靜態 report 的 100% 數據與 Exit Code 0，而未在最新的源碼基底上重新執行動態測試。
   - **推論**: 當後續程式碼變更（`vsock_server.cpp` 重構）導致 `T2-43` 靜態斷言失敗後，前人團隊未重新執行測試 runner 進行驗證，或故意提交預先填寫好的靜態 JSON 報告與 `TEST_READY.md` 以掩蓋測試失敗事實，違反了項目的誠信測試規範（Rule 4: "No static JSON report... may count toward production verification"）。

---

## 3. Caveats (注意事項與未檢驗範圍)

1. 本報告為 Read-Only 調查報告，未對 `test_m2_tier2.py` 或 `vsock_server.cpp` 進行任何直接修改。
2. 雖然 C++ 單元測試 `challenger_m2_i3_2_vsock_stress.cpp` 證明 `processHandshake(cid, payload)` 能拒絕 `cid != 3` 的呼叫，但這是在 Mock/Unit 環境下的調用；在真實 Linux Kernel AF_VSOCK 環境中，sockaddr_vm 的 CID 綁定仍須仰賴 Kernel socket API 的層級隔離。

---

## 4. Conclusion (最終結論)

1. **`T2-43` 確切位置與實作**:
   位於 `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` (Line 322-333)，屬於脆弱的靜態 C++ 原始碼字串比對斷言。
2. **`T2-43` 失敗根因**:
   `vsock_server.cpp` 內將 CID 檢查參數命名為 `cid`（寫為 `if (cid != ALLOWED_GUEST_CID)`），而 `T2-43` 仍強制要求字串 `"clientAddr.svm_cid != ALLOWED_GUEST_CID"`，導致 Python `assert_in` 拋出 `AssertionError`。
3. **`runner.py` 與 `TEST_READY.md` 欺詐矛盾**:
   `runner.py` 實時執行會因為 `T2-43` 失敗而回傳 **Exit Code 1** (429 PASS, 1 FAIL)。`TEST_READY.md` 宣稱 100% PASS (Exit Code 0) 是基於預先提交於 Git 儲存庫的靜態歷史產物 `tests/e2e/e2e_report.json`，存在嚴重的報告不實與測試誠信違規。
4. **具體修復策略與測試執行器誠信驗證方案**:
   - **修復策略 (T2-43 Fix Strategy)**:
     - *近程修正*: 將 `test_m2_tier2.py` 中 `T2-43` 的字串斷言修改為匹配實際重構後的 C++ 程式碼模式：
       `CustomAssertions.assert_in("cid != ALLOWED_GUEST_CID", content)`
     - *長遠修正 (Production-Grade)*: 廢除對源碼檔進行 `open().read()` 的靜態字串搜尋，改為執行編譯後的 C++ 測試二進位檔（例如 `build_out/bin/challenger_m2_vsock_test`），透過傳入非法的 CID (例如 CID=99) 並斷言 Handshake 回傳 `false` 來實現真實功能測試。
   - **測試執行器誠信驗證方案 (Runner Integrity Verification)**:
     - 清除 Git 內預存的靜態 `tests/e2e/e2e_report.json` 與 `tests/e2e_report.json` 產物，將其加入 `.gitignore`。
     - 統一 `runner.py` 與 `run_tests.sh` 的 JSON 輸出路徑，確保每次執行皆動態覆寫。
     - 在 CI 自動化流程中強制檢驗 `python3 tests/e2e/runner.py` 的結束碼必須為 `0`。

---

## 5. Verification Method (獨立驗證方法)

1. **重現 `T2-43` 失敗與 Exit Code 1**:
   ```bash
   python3 tests/e2e/runner.py --filter T2-43 --verbose
   ```
   - *預期結果*: 顯示 `AssertionError: Item 'clientAddr.svm_cid != ALLOWED_GUEST_CID' not found in container`，結束碼為 1。

2. **檢查 `vsock_server.cpp` 的真實實作**:
   ```bash
   grep -n -C 5 "ALLOWED_GUEST_CID" system/linux_bridge/vsock_server.cpp
   ```
   - *預期結果*: 顯示 Line 209 為 `if (cid != ALLOWED_GUEST_CID)`。

3. **驗證 C++ 單元測試對 CID 拒絕的真實測試邏輯**:
   ```bash
   grep -n -C 5 "processHandshake(99" tests/unit/challenger_m2_i3_2_vsock_stress.cpp
   ```
   - *預期結果*: 顯示 `assert(!server.processHandshake(99, payload));`。

4. **比對靜態報告與動態報告的數據不符**:
   ```bash
   grep -E '"passed"|"failed"|"pass_rate_percent"' tests/e2e/e2e_report.json tests/e2e_report.json
   ```
   - *預期結果*: `tests/e2e/e2e_report.json` 顯示 passed 430 / failed 0；`tests/e2e_report.json` 顯示 passed 429 / failed 1。
