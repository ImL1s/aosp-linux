# Handoff Report — Explorer 1 (explorer_m6_ci_runner)

## 1. Observation (觀察事實)

1. **`.github/workflows/ci.yml` 靜態斷言斷層 (Line 31-33)**:
   - 檔案路徑: `/Users/iml1s/Documents/mine/aosp-linux/.github/workflows/ci.yml`
   - 第 31-33 行內容:
     ```yaml
     - name: Verify Architecture Blueprints & Test Specs
       run: |
         python3 -c "import json; data=json.load(open('tests/e2e_report.json')); print('E2E Verification Total:', data['summary']['total']); assert data['summary']['failed'] == 0"
     ```
   - 觀察結果: CI 工作流完全沒有執行 E2E 測試 Runner 或任何真實測試程式碼，而是直接讀取專案中靜態提交的 `tests/e2e_report.json` 檔案並斷言 `failed == 0`。

2. **`tests/e2e/runner.py` CLI 參數解析與層級過濾缺陷**:
   - 檔案路徑: `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py`
   - 第 102 行內容:
     ```python
     parser.add_argument("--tier", type=int, choices=[1, 2, 3, 4], help="Run tests for specific tier (1, 2, 3, 4)")
     ```
   - 觀察結果: `argparse` 將 `--tier` 定義為單一 `type=int` 參數。當在 CLI 傳遞 `python3 tests/e2e/runner.py --tier 1 --tier 2` 時，後者的 `--tier 2` 會覆蓋前者的 `--tier 1`，導致僅執行 Tier 2 測試（僅 185 項測試被執行，而非 Tier 1 + Tier 2 共 370 項測試）。
   - 函數 `discover_test_classes(tier_filter)` 僅接受單一整數或 `None`，無法處理整數列表/集合。

3. **`tests/e2e/runner.py` 硬編碼預設報告路徑**:
   - 檔案路徑: `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py`
   - 第 29 行內容:
     ```python
     DEFAULT_REPORT_PATH = "/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json"
     ```
   - 觀察結果: 寫死開發者本機絕對路徑，在 CI 容器（如 GitHub Actions `ubuntu-latest`）或其他環境下執行時會遭遇路徑不符問題。

4. **`tests/e2e_report.json` 靜態偽造報告**:
   - 檔案路徑: `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json`
   - 原始內容包含 4,744 行預先生成的靜態 JSON，宣告 430/430 測試通過。
   - 當以 `python3 tests/e2e/runner.py --tier 1 --tier 2` 實際運行 `runner.py` 時，該檔會被動態覆寫，回報真實執行結果 (例如 TOTAL: 185, PASSED: 145, FAILED: 5, ERRORS: 35)，退出碼為 1。

---

## 2. Logic Chain (推理邏輯鏈)

1. **從 Observation 1 到 CI 改善結論**:
   - *推論*: 由於 `ci.yml` 僅檢查靜態 JSON，即便 codebase 原始碼有重大缺陷或測試程式庫報錯，CI 亦會全數顯示綠燈 Passing。
   - *結論*: 必須刪除第 31-33 行的靜態 `json.load()` 斷言，替換為動態執行 `python3 tests/e2e/runner.py --tier 1 --tier 2` 的真實指令。

2. **從 Observation 2 到 runner.py CLI 重構結論**:
   - *推論*: CI 需求為執行 `python3 tests/e2e/runner.py --tier 1 --tier 2`，若 `runner.py` 無法解析多重 `--tier` 參數，將會遺漏 Tier 1 的 185 個測試案例。
   - *結論*:
     1. 修改 `argparse` 中 `--tier` 的解析規則（改用 `action="append"` 或自訂正規化處理），支援多重標記 (`--tier 1 --tier 2`)、多值輸入 (`--tier 1 2`) 與逗號分隔 (`--tier 1,2`)。
     2. 重構 `discover_test_classes(tier_filter)`，使其能接收 `Union[int, List[int], Set[int], None]`，並按指定的層級集合搜尋與彙整測試類別。

3. **從 Observation 3 到相對路徑相容結論**:
   - *推論*: 硬編碼絕對路徑 `/Users/iml1s/...` 在 GitHub Actions Runner 無法寫入該位置。
   - *結論*: 將 `DEFAULT_REPORT_PATH` 改為相對於 `BASE_DIR` 的動態解析路徑 `os.path.abspath(os.path.join(BASE_DIR, "..", "e2e_report.json"))`。

4. **從 Observation 4 到退出碼與誠實測試報告結論**:
   - *推論*: `runner.py` 已包含末段檢查 `has_failures = any(r.status in (TestStatus.FAIL, TestStatus.ERROR) for r in results)` 並在有失敗/錯誤時 `sys.exit(1)`。當替換 CI 命令並修正參數解析後，`runner.py` 會動態將真實結果寫入 `tests/e2e_report.json`，並以誠實退出碼 (0 表示全過，非 0 表示有失敗) 回報 CI。

---

## 3. Caveats (注意事項與假設)

1. **測試案例本身的失敗 (FAIL/ERROR)**:
   - 本任務聚焦於 CI 工作流與 `runner.py` 的調用機制與 CLI 參數解析。若 Implementer / Worker 在修正 `mock_env.py` 及 Tier 1/2 測試案例前即啟用 CI 真實執行，`runner.py` 將因尚存的 5 個 FAILED 及 35 個 ERRORS 測試而回報退出碼 1。這證明 CI 運作誠實，但需與 M6 其他任務協同推進至全數 PASS。
2. **無其他外部依賴硬性約束**:
   - CI 環境需要 Python 3.10+，`.github/workflows/ci.yml` 已有 `actions/setup-python@v5` 步驟，可順利支援 `runner.py` 之執行。

---

## 4. Conclusion (最終結論)

### 具體改造計畫與規格書:

#### 1. `.github/workflows/ci.yml` 修改方案:
- 將原本的靜態斷言步驟：
  ```yaml
      - name: Verify Architecture Blueprints & Test Specs
        run: |
          python3 -c "import json; data=json.load(open('tests/e2e_report.json')); print('E2E Verification Total:', data['summary']['total']); assert data['summary']['failed'] == 0"
  ```
- 替換為真實動態測試執行步驟：
  ```yaml
      - name: Run Real E2E Test Suite (Tier 1 & Tier 2)
        run: |
          python3 tests/e2e/runner.py --tier 1 --tier 2
  ```

#### 2. `tests/e2e/runner.py` 修改方案:
- **相對路徑修復**:
  ```python
  DEFAULT_REPORT_PATH = os.path.abspath(os.path.join(BASE_DIR, "..", "e2e_report.json"))
  ```
- **CLI 參數解析重構**:
  ```python
  parser.add_argument(
      "--tier",
      action="append",
      nargs="*",
      help="Specify tier(s) to run (e.g. --tier 1 --tier 2, or --tier 1 2, or --tier 1,2)"
  )
  ```
  在 `main()` 中加入參數正規化處理邏輯，將 raw tiers 轉成 `set[int]`：
  ```python
  selected_tiers = set()
  if args.tier:
      for group in args.tier:
          for item in group:
              if isinstance(item, list):
                  sub_items = item
              else:
                  sub_items = [item]
              for sub in sub_items:
                  for part in str(sub).split(','):
                      if part.strip().isdigit():
                          selected_tiers.add(int(part.strip()))
  tier_filter = list(selected_tiers) if selected_tiers else None
  ```
- **`discover_test_classes` 函數升級**:
  ```python
  def discover_test_classes(tier_filter=None) -> list:
      ...
      if tier_filter is None:
          target_tiers = sorted(TIER_DIRS.keys())
      elif isinstance(tier_filter, int):
          target_tiers = [tier_filter]
      else:
          target_tiers = sorted([t for t in tier_filter if t in TIER_DIRS])
      ...
  ```
- **退出碼與報告記錄保持誠實**:
  - 執行所有收集到的測試，透過 `ReportFormatter` 在終端顯示進度與結果。
  - 將最新結果動態寫入 `e2e_report.json`。
  - 若有 FAIL 或 ERROR，以 `sys.exit(1)` 退出；全數 PASS 時以 `sys.exit(0)` 退出。

---

## 5. Verification Method (驗證方法)

1. **驗證的多重 `--tier` CLI 測試**:
   在終端執行：
   `python3 tests/e2e/runner.py --tier 1 --tier 2 --list`
   - *通過條件*: 正確輸出發現 Tier 1 (185 個) 與 Tier 2 (185 個) 共 370 個測試案例。
2. **驗證動態測試執行與退出碼**:
   在終端執行：
   `python3 tests/e2e/runner.py --tier 1 --tier 2`
   - *通過條件*: 印出包含 TOTAL 370 的測試執行報告，更新 `tests/e2e_report.json`，且退出碼誠實反映測試狀況 (非 0 當存在 FAIL/ERR)。
3. **驗證 `ci.yml` 內容**:
   檢查 `.github/workflows/ci.yml` 確保無任何 `import json; assert data['summary']['failed'] == 0` 靜態字樣，並包含 `python3 tests/e2e/runner.py --tier 1 --tier 2`。
