# Quality & Adversarial Review Report — Milestone M3 (Real Vsock Socket Connect & Session ID - R3)

**審查員**: Reviewer 2 (`reviewer_m3_2`)  
**里程碑**: M3 (Real Vsock Socket Connect & Session ID - R3)  
**日期**: 2026-08-08  
**工作目錄**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m3_2`  
**最終判定 (Verdict)**: **APPROVE** (核准)

---

## 1. Review Summary (審查摘要)

針對 Worker M3 (`worker_m3_1`) 於 Milestone M3 中針對 Vsock Socket 連線、動態 Session ID 生成以及 VsockPtyFramer 16 位元組標頭長度斷言之修復進行獨立審查與對抗性測試。

經詳細程式碼查驗與獨立測試驗證：
1. **AF_VSOCK Socket 連線與資源釋放**: `VsockTerminalClient.java` 成功落實真實 `Os.socket(AF_VSOCK, SOCK_STREAM, 0)` 與 `Os.connect(mSocketFd, address)` 系統呼叫（ targeting Guest CID 3, Port 5001）；在連線異常或 Stream 關閉時，均透過 `close()` 確實清理 Socket 檔案描述符 (`mSocketFd`) 與 I/O Stream，無 Socket/FD 洩漏隱患。
2. **動態 Session ID 標頭對齊 (Exact 16-byte)**: `LinuxManagerService.java` 中 `createTerminalSession` 產生格式調整為 `String.format(Locale.US, "session_%08d", ++mNextSessionId)`，產出精確 16 位元組 ASCII 字串 (`session_00001001`)，完全符台 `VsockPtyFramer` 的 `HEADER_SIZE` (16 bytes Session ID) 強制斷言。
3. **TerminalView 動態 Token 獲取**: `TerminalView.java` 於 `onAttachedToWindow()` 透過 Binder 服務 `ILinuxManager` 查詢並取得動態 16 位元組 Session ID，擺脫原本寫死的 `"0123456789abcdef"` 靜態 ID。
4. **誠實性與完整性 (Integrity Check)**: 未發現任何硬編碼測試結果、偽裝實作 (Facade/Dummy)、繞過邏輯或自證偽造情事。所有單元測試與 E2E 測試皆為真實執行且 100% 通過。

---

## 2. Findings & Verification (發現與驗證)

### 2.1 發現等級 (Findings)
- **Critical (嚴重)**: 無
- **Major (主要)**: 無
- **Minor (次要)**:
  - *單元測試編譯指令 Classpath 補充*: `handoff.md` 中的 `TerminalAppUnitTest` 手動編譯指令缺少 `frameworks/base/services/core/java` 模組路徑，若直接執行會因 `LinuxAppProxyActivity` 找不到 `LinuxWindowBridgeService` 而報錯。補上 Classpath 後即可順利編譯通過（此為文件說明微調，不影響產品程式碼品質）。

### 2.2 Verified Claims (已驗證主張)

| 驗證項目 | 說明 / 方法 | 驗證結果 |
|---|---|---|
| AF_VSOCK Connect Syscall | 檢視 `VsockTerminalClient.java` line 39-54 確有 `Os.socket` 與 `Os.connect` | PASS (已確認) |
| Socket Teardown & Clean Cleanup | 測試連線失敗 (ErrnoException) 與 `onDetachedFromWindow` 呼叫 `close()` 釋放 `mSocketFd` | PASS (已確認) |
| 16-byte Session ID Format | `LinuxManagerService.java` 產出 `session_%08d` (8+8=16 bytes)，與 `VsockPtyFramer` 斷言完全相符 | PASS (已確認) |
| Dynamic Session ID Query | `TerminalView.java` 於 `onAttachedToWindow()` 呼叫 `ILinuxManager.createTerminalSession` | PASS (已確認) |
| Java Unit Test - `TerminalAppUnitTest` | 執行修復後全 Classpath 單元測試 | PASS (100% 通過) |
| Java Unit Test - `LinuxManagerServiceTest` | 執行 SystemServer 服務單元測試 | PASS (100% 通過) |
| Python E2E Tier 1 (`F-R3`) | 執行 `python3 tests/e2e/runner.py --tier 1 --feature F-R3` (35/35) | PASS (100% 通過) |
| Python E2E Tier 2 (`F-R3`) | 執行 `python3 tests/e2e/runner.py --tier 2 --feature F-R3` (35/35) | PASS (100% 通過) |

---

## 3. Adversarial & Stress Testing (對抗性與極限測試)

### 3.1 Session ID 長度溢位邊界測試
- **測試情境**: 當 `mNextSessionId` 超過 8 位數 (例如 > 99,999,999) 時之表現。
- **對抗評估**: `TerminalView.java` (line 103) 在設定 `mSessionId` 時增加了 `sessionIdStr.length() == 16` 的防護檢查。若長度不合即退回預設 16-byte 安全安全 Session ID，防止 `VsockPtyFramer` 拋出 `IllegalArgumentException` 導致 Crash。

### 3.2 多執行緒與讀取線程生命週期 (Thread Safety & Concurrency)
- **測試情境**: 頻繁建立/關閉 TerminalView，或連線中途突然觸發 View Detach。
- **對抗評估**: `VsockTerminalClient` 的 `connect()`、`sendFrame()`、`close()` 方法皆加有 `synchronized` 關鍵字，`mRunning` 為 `volatile` 標誌。`close()` 會發送 `mReadThread.interrupt()` 並關閉 Stream 與 Socket FD，確保 Read Loop 正常中斷 exit，無死鎖或懸空線程。

---

## 4. Final Verdict & Conclusion (最終判定與結論)

- **Verdict**: **APPROVE**
- **結論**: Milestone M3 (Real Vsock Socket Connect & Session ID - R3) 之程式碼變更品質符合專案架構規範與安全要求，測試全數通過，予以核准併入。
