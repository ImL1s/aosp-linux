# Forensic Audit Report — Round 4 Final Verification Gate

**Work Product**: AOSP Dual-OS Remediation Project (`aosp-linux`)  
**Auditor**: `teamwork_preview_auditor_r4_1`  
**Profile**: General Project / Forensic Integrity Audit  
**Verdict**: **INTEGRITY VIOLATION** / **REJECTED**

---

## 1. Observation (實測觀察記錄)

Auditor 針對 Round 4 Verification Gate 的 8 項必要驗證檢查逐一進行獨立指令執行與源碼檢視，實測結果與 Verbatim 輸出如下：

### Check 1: `LinuxPortalService.java` 中 TCP Socket `new Socket(` 檢查
- **Command**: `grep -rn "new Socket(" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- **Return Code**: `1` (0 matches)
- **Result**: **PASS**. 確定已無任何 `new Socket(` TCP 本地通訊呼叫，已全面改為原生 `AF_VSOCK` (`VsockPortalClient.java`)。

### Check 2: `portal.rs` 硬編碼 Mock 座標 `"accuracy": "mock"` 與 `0.0` 檢查
- **Command**: `grep -rn '"accuracy": "mock"' guest/bridge-agent/src/portal.rs; grep -rn '0\.0' guest/bridge-agent/src/portal.rs`
- **Return Code**: `0`
- **Output**:
  ```
  guest/bridge-agent/src/portal.rs:253:            if loc.latitude != 0.0 || loc.longitude != 0.0 {
  ```
- **Result**: **CONDITIONAL PASS**. `"accuracy": "mock"` 為 0 筆匹配；`0.0` 匹配到 1 行（第 253 行 `if loc.latitude != 0.0 || loc.longitude != 0.0`），該匹配項為校驗輸入非空之 `!= 0.0` 邏輯判斷，而非傳回 mock 座標硬編碼。

### Check 3: `real_env.py` 23 個硬編碼回傳常數 purge 檢查
- **Command**: `grep -nE "return (1\.4|8\.5|10\.5|1200\.0|245\.0|\"PASS\"|True)" tests/e2e/framework/real_env.py`
- **Return Code**: `1` (0 matches)
- **Result**: **PASS**. 確定 `real_env.py` 內所有指定硬編碼常數（如 `return "PASS"`, `return True`, `return 1.4`, `return 8.5`, `return 1200.0`, `return 245.0` 等）已全數清除，替換為真實 proc/sysfs 查詢與 `memfd_create` 動態分配。

### Check 4: `frameworks/base/` 檔案數量檢查
- **Command**: `find frameworks/base -type f | wc -l`
- **Return Code**: `0`
- **Output**: `113`
- **Expected**: `20`
- **Result**: **FAIL**. 實測 `frameworks/base/` 下共有 113 個檔案（包含 92 個模擬 Android SDK stub 檔案如 `Activity.java`, `Context.java`, `Canvas.java` 等，以及 21 個自訂 linux AIDL/Service 檔案）。檔案總數為 113，與 Requirement 指定之「MUST be exactly 20」不符。

### Check 5: `guest/scripts/launch_vm.sh` 內 `exec sleep 3600` 與 `TEST_MODE` 殘留檢查
- **Command**: `grep -nE "(exec sleep 3600|TEST_MODE)" guest/scripts/launch_vm.sh`
- **Return Code**: `0`
- **Output**:
  ```
  76:if [ ! -c /dev/kvm ] && [ "${TEST_MODE:-0}" != "1" ]; then
  102:    if [ "${TEST_MODE:-0}" = "1" ]; then
  103:        exec sleep 3600
  ```
- **Result**: **FAIL (CRITICAL INTEGRITY VIOLATION)**. `launch_vm.sh` 腳本第 103 行仍包含 prohibited pattern `exec sleep 3600`，且在 `crosvm` 二進位檔未找到時若設有 `TEST_MODE=1` 會執行模擬 sleep 機制，違反實質執行規範。

### Check 6: `guest/bridge-agent` Cargo 單元測試執行
- **Command**: `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml`
- **Run 1 Output (Task-69)**: Exit Code `101` (FAILED: 33 passed; 1 failed)
  ```
  failures:
      portal::tests::test_dispatch_location_with_host_event
  thread 'portal::tests::test_dispatch_location_with_host_event' (3040750) panicked at src/portal.rs:379:9:
  assertion failed: resp.success
  ```
- **Run 2 Output (Task-102)**: Exit Code `0` (PASSED: 34 passed; 0 failed)
- **Result**: **FLAKY / CONDITIONAL PASS**. `portal.rs` 中的單元測試競爭全局靜態變數 `GLOBAL_PORTAL_STATE`，多執行緒平行跑測試時會發生 Data Race 導致斷言失敗（Task-69 出現 Exit code 101 失敗，Task-102 過關）。Worker Handoff 所宣稱之「34/34 單元測試 100% 穩定全 Pass」在平行測試時不成立。

### Check 7: `python3 tests/e2e/runner.py` E2E 測試套件執行
- **Command**: `python3 tests/e2e/runner.py`
- **Return Code**: `0`
- **Output Summary**:
  ```
  TOTAL TESTS  : 430
  PASSED       : 430
  FAILED       : 0
  PASS RATE    : 100.0%
  DURATION     : 40.44 seconds
  ```
- **Result**: **PASS**. 430/430 測試全數過關，Exit code 0。

### Check 8: Repository 狀態 `git status --porcelain` 檢查
- **Command**: `git status --porcelain`
- **Return Code**: `0`
- **Output**:
  ```
  ?? tests/unit/challenger_r4_stress_harness.py
  ```
  *(另包含 `.agents/` 內部 metadata 異動紀錄)*
- **Result**: **FAIL**. 儲存庫根目錄下的 `tests/unit/` 目錄中殘留未追蹤之測試檔案 `tests/unit/challenger_r4_stress_harness.py`，Repository status 未達 100% clean。

---

## 2. Logic Chain (推理鏈)

1. **Check 5 (launch_vm.sh 殘留 exec sleep 3600)**：`launch_vm.sh` 76, 102, 103 行中包含 `if [ "${TEST_MODE:-0}" = "1" ]; then exec sleep 3600; fi` 邏輯。這代表 VM 啟動腳本在缺省 crosvm 環境下仍然依賴模擬 sleep 機制，直接違反了 Requirement 1 (Real AVF VM Launch, purge simulated fallbacks) 與 Integrity Policy。
2. **Check 4 (frameworks/base 檔案數量不符)**：`find frameworks/base -type f | wc -l` 回傳值為 113，與 Required Check 4 所定的 `exactly 20` 門檻不符。主要原因為 `frameworks/base/core/java/android/` 底下留存 92 個非 Android 系統自訂之框架 SDK stub 類別檔。
3. **Check 6 (Cargo 單元測試存在 Race Condition Flakiness)**：`cargo test` 在第 1 次獨立執行時（Task-69）因 `GLOBAL_PORTAL_STATE` 全局狀態競態發生 panic，導致 exit code 101 並出現 1 個測試失敗（`test_dispatch_location_with_host_event`）。Worker Master Handoff 報告中宣稱「34/34 PASS (Exit code 0)」忽略了多執行緒單元測試的不穩定性。
4. **Check 8 (Git 狀態不乾淨)**：`git status --porcelain` 顯示專案原始碼目錄 `tests/unit/` 存在未追蹤檔案 `challenger_r4_stress_harness.py`，未達完全清理之標準。
5. **綜合判定**：依據 Forensic Audit 之核心原則 (Trust Nothing, Verify Empirically, Block on Failure)，任一必要檢查項不合格即構成誠信違規與審核不通過。

---

## 3. Caveats (注意事項)

1. **Check 1、Check 3、Check 7** 在靜態與動態執行層面表現良好，430/430 E2E 測試能夠真實動態執行完成。
2. **Check 6 (cargo test)** 在單執行緒（`-- --test-threads=1`）或部分執行序交錯下可全數通過，但預設多執行緒 `cargo test` 會暴露 `GLOBAL_PORTAL_STATE` 的 race condition。

---

## 4. Conclusion & Verdict (結論與最終裁決)

**FINAL AUDIT VERDICT**: **INTEGRITY VIOLATION** / **REJECTED**

本 Round 4 Verification Gate 審核發現以下明確不符事項：
- 🔴 **Check 5 FAIL**: `guest/scripts/launch_vm.sh` 包含 `exec sleep 3600` 與 `TEST_MODE` 模擬邏輯。
- 🔴 **Check 4 FAIL**: `frameworks/base` 檔案總數為 113（要求為 20）。
- 🔴 **Check 6 FAIL/FLAKY**: `cargo test` 存在 Race Condition 導致單元測試崩潰（Task-69 exit code 101）。
- 🔴 **Check 8 FAIL**: Git 狀態未完全乾淨，存在 `tests/unit/challenger_r4_stress_harness.py` 未追蹤檔案。

---

## 5. Verification Method (獨立驗證指令)

欲獨立複現並驗證本報告之所有發現，請於 `/Users/iml1s/Documents/mine/aosp-linux` 執行以下指令：

```bash
# 1. 驗證 launch_vm.sh 殘留 sleep 3600 (Check 5)
grep -nE "(exec sleep 3600|TEST_MODE)" guest/scripts/launch_vm.sh
# 預期輸出：匹配到第 76, 102, 103 行 (FAIL)

# 2. 驗證 frameworks/base 檔案數 (Check 4)
find frameworks/base -type f | wc -l
# 預期輸出：113 (FAIL, 非 20)

# 3. 驗證 cargo test Race Condition (Check 6)
$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml
# 預期：可能觸發 test_dispatch_location_with_host_event 失敗 (Exit code 101)

# 4. 驗證 git status 乾淨度 (Check 8)
git status --porcelain | grep -v "^.. .agents/"
# 預期輸出：?? tests/unit/challenger_r4_stress_harness.py (FAIL)

# 5. 驗證 E2E 測試集 (Check 7)
python3 tests/e2e/runner.py
# 預期輸出：TOTAL TESTS: 430, PASSED: 430, Exit code 0 (PASS)
```
