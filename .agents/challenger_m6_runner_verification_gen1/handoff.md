# Handoff Report — Challenger 2 (challenger_m6_runner_verification_gen1)

## 1. Observation (觀察事實)

對 Milestone M6 (Clean & Honest E2E Test Suite) 的 Test Runner 與測試隔離及資源清理進行實測驗證，觀察到以下結果：

1. **全量測試執行 (Full E2E Suite Run)**：
   - 執行命令：`python3 tests/e2e/runner.py --tier 1 --tier 2 --tier 3 --tier 4`
   - 觀察結果：控制台輸出 `TOTAL TESTS: 430 | PASSED: 430 | FAILED: 0 | ERRORS: 0 | PASS RATE: 100.0%`。
   - 測試報告已成功寫入 `tests/e2e_report.json`。

2. **Tier 隔離測試 (Tier Isolation Verification)**：
   - 個別執行命令：
     - `python3 tests/e2e/runner.py --tier 1` -> 185/185 Passed (100.0%)
     - `python3 tests/e2e/runner.py --tier 2` -> 185/185 Passed (100.0%)
     - `python3 tests/e2e/runner.py --tier 3` -> 40/40 Passed (100.0%)
     - `python3 tests/e2e/runner.py --tier 4` -> 20/20 Passed (100.0%)
   - 總計測試數：185 + 185 + 40 + 20 = 430 個測試，各 Tier 隔離執行均無干擾。

3. **背景進程與 Socket 殘留檢查 (Background Process & Socket Teardown Check - 致命缺陷)**：
   - **觀察到的缺陷**：在 `runner.py` 執行完成並印出 `PASS RATE: 100.0%` 呼叫 `sys.exit(0)` 後，Python 進程**無法正常退出**，持續作為背景遺留進程掛起在 `S` (Sleeping) 狀態（例如 PID 43086, 43221, 43256）。
   - **Socket 埠口佔用**：`lsof -i :5000 -i :5001 -i :5002` 顯示遺留的 Python 進程持續監聽 TCP `127.0.0.1:5000` (Control)、`5001` (PTY) 與 `5002` (Wayland)。
   - **UNIX Socket 檔案殘留**：/tmp/dev_socket/linux_bridge 套接字檔案在進程結束後仍殘留在磁碟上未清空。

---

## 2. Logic Chain (邏輯推理鏈)

1. **進程無法退出的根因分析**：
   - 在 `tests/e2e/framework/socket_harness.py` Line 185 中，`SocketHarnessServer.start()` 使用了 `ThreadPoolExecutor(max_workers=128, thread_name_prefix="HarnessWorker")` 來處理連線。
   - 在 Python 標準庫 `concurrent.futures.thread` 中，`ThreadPoolExecutor` 建立的工作線程預設為**非守護線程** (`t.daemon = False`)。
   - 當 `runner.py` 執行結束呼叫 `env.stop_harness()` 時，`SocketHarnessServer.stop()` 呼叫了 `self.executor.shutdown(wait=False, cancel_futures=True)`。
   - `shutdown(wait=False)` 並不會強制終止已經建立的 `HarnessWorker` 非守護線程。
   - 當 `runner.py` 執行 `sys.exit(0)` 時，Python 解譯器在退出前會執行 `threading._shutdown()`，主動 `join()` 所有非守護線程。
   - 由於 `HarnessWorker` 線程處於等待 socket 或 queue 的阻塞狀態，`join()` 無限期等待，導致整個 Python 進程卡死掛起，無法結束。

2. **資源洩漏影響**：
   - 每次執行 `runner.py` 都會在背景遺留一個卡死掛起的 Python 進程。
   - 該遺留進程持續佔用 TCP 埠口 5000, 5001, 5002，導致後續若重新執行測試或在 CI 環境中執行時發生埠口綁定衝突或進程洩漏。

---

## 3. Caveats (注意事項與例外說明)

- 測試邏輯本體與 430 個測試案例之 IPC、Socket 及邏輯斷言均 100% 正確且能完全通過。
- 唯一阻塞通過的原因為 `socket_harness.py` 之中 `ThreadPoolExecutor` 線程未設定為 daemon 或未在 `stop()` 時被正確強制清理，導致進程退出死鎖。

---

## 4. Conclusion & Explicit Verdict (結論與明確裁決)

**OVERALL VERDICT: REJECT (退回)**

- 430/430 測試案例與 Tier 隔離驗證：PASS
- 測試結束後之背景進程與 Socket 清理驗證：**FAIL** (Python runner 進程掛起無法退出，殘留背景進程與 5000/5001/5002 埠口監聽)。

### 建議修復方案 (Mitigation for Worker)：
在 `tests/e2e/framework/socket_harness.py` 中：
1. 避免使用預設非守護線程的 `ThreadPoolExecutor`；或者為 `ThreadPoolExecutor` 指定客製化的 `thread_name_prefix` 並且手動將產生的 thread 設為 `daemon = True`。
2. 或者在 `SocketHarnessServer.stop()` 中更徹底地關閉所有 worker connection socket 並斷開阻塞，或在 `runner.py` 結束處使用 `os._exit(code)` 代替 `sys.exit(code)` 確保釋放所有背景線程。

---

## 5. Verification Method (獨立驗證方法)

從工作區根目錄 (`/Users/iml1s/Documents/mine/aosp-linux`) 執行以下命令：

1. 執行 Test Runner：
   ```bash
   python3 tests/e2e/runner.py --tier 1
   ```
2. 檢查進程是否遺留：
   ```bash
   ps aux | grep runner.py | grep -v grep
   ```
   **當前失敗現象**：會看到 `Python tests/e2e/runner.py --tier 1` 進程依然留在進程列表中 (狀態為 `S`)。
3. 檢查埠口是否殘留：
   ```bash
   lsof -i :5000 -i :5001 -i :5002
   ```
   **當前失敗現象**：會看到 Python 進程持續佔用並監聽 5000, 5001, 5002 埠口。
