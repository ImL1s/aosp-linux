# Handoff Report — Milestone M3 Reviewer 2 (Real Vsock Socket Connect & Session ID - R3)

**Author**: Reviewer 2 (`reviewer_m3_2`)  
**Milestone**: M3 (Real Vsock Socket Connect & Session ID - R3)  
**Date**: 2026-08-08  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m3_2`  
**Verdict**: **APPROVE**

---

## 1. Observation (觀察事實)

1. **`VsockTerminalClient.java`**:
   - 經程式碼查驗，`connect(int guestCid, byte[] sessionId, listener)` 方法中包含：
     - `if (sessionId == null || sessionId.length != 16) throw new IllegalArgumentException(...)`
     - `mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);`
     - `address = new VmSocketAddress(VPORT_PTY, guestCid);`（含反射備援）
     - `Os.connect(mSocketFd, address);`
     - 異常處理中 `catch (ErrnoException e)` 與 `catch (Exception e)` 均呼叫 `close()` 確實進行資源回收。
   - `close()` 方法同步清除 `mRunning`、中斷 `mReadThread`、關閉 Stream 與觸發 `Os.close(mSocketFd)`。

2. **`TerminalView.java`**:
   - 於 `onAttachedToWindow()` 觸發 `initDynamicSessionAndConnect()`。
   - 透過 `ServiceManager.getService("linux_service")` 取得 `ILinuxManager`，呼叫 `createTerminalSession(mColumns, mRows, null)` 取得動態 Session ID。
   - 驗證字串長度為 16 螢幕字元後轉譯為 ASCII bytes 傳給 `connectVsock()`。

3. **`LinuxManagerService.java`**:
   - `createTerminalSession` 方法調整為 `String sessionId = String.format(java.util.Locale.US, "session_%08d", ++mNextSessionId);`。
   - 產出長度固定為精確 16 位元組（例如 `"session_00001001"`）。

4. **測試執行結果**:
   - Java 測試 `TerminalAppUnitTest`: 100% 通過 (8/8 測試單元 PASS)。
   - Java 測試 `LinuxManagerServiceTest`: 100% 通過 (7/7 測試單元 PASS)。
   - Python E2E Tier 1 (`F-R3`): 35/35 PASS, 100.0% Pass Rate.
   - Python E2E Tier 2 (`F-R3`): 35/35 PASS, 100.0% Pass Rate.

---

## 2. Logic Chain (推論鏈)

1. **真實 AF_VSOCK Socket 建立與資源洩漏防範推論**:
   - 原先 Worker M3 之前的程式碼僅呼叫 `Os.socket` 未呼叫 `Os.connect` 即包裝成 Stream。現改為呼叫 `Os.connect(mSocketFd, address)` 後始建立 `FileInputStream`/`FileOutputStream`，符合 POSIX AF_VSOCK 規範。
   - 若連線階段發送 errno 錯誤 (如 `ECONNREFUSED` 或 `ENETUNREACH`)，`catch (ErrnoException e)` 內呼叫 `close()`，由 `close()` 安全呼叫 `Os.close(mSocketFd)`，保證 Socket File Descriptor 不會有 open FD leak。
2. **Session ID 標頭對齊與防爆推論**:
   - `VsockPtyFramer` 規範 Binary 封包標頭由 `16-byte Session ID + 1-byte Type + 4-byte Length` 組成。
   - `LinuxManagerService` 原先產出 12 位元組字串，導致 `VsockPtyFramer` 拋出例外。經格式化為 `"session_%08d"` 後正好為 16 位元組。
   - `TerminalView` 增加長度 16 的檢查防護，確保取得無效 Token 時可退回 safe fallback，維護 Terminal View 穩定度。

---

## 3. Caveats (注意事項與假設)

- **Classpath 配置**: 在手動單獨執行 `TerminalAppUnitTest` 編譯時，請務必同時載入 `frameworks/base/core/java` 與 `frameworks/base/services/core/java`，因為 `LinuxAppProxyActivity.java` 引用了 `LinuxWindowBridgeService`。
- **無主機 AF_VSOCK 驅動環境測試**: 在普通 macOS / Linux 無 macOS virtio-vsock 核心模組支援環境下，單元測試以 `ServerSocket` loopback 套接字驗證 `VsockTerminalClient.connectSocket` 的 Framer 序列化與資料傳輸邏輯。

---

## 4. Conclusion (審查結論)

Worker M3 對於 Milestone M3 (Real Vsock Socket Connect & Session ID - R3) 的修復完全達到品質與介面合規要求：
- 無任何誠實性或虛假實作漏洞 (Integrity Violation Free)。
- AF_VSOCK Socket 連線與錯誤清理邏輯完善。
- 16-byte Session ID 生成與 VsockPtyFramer 完美對齊。
- 最終審查判定為 **APPROVE**。

---

## 5. Verification Method (獨立驗證方法)

如需獨立重現審查與驗證結果，請執行以下命令：

1. **TerminalApp 單元測試**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') tests/unit/TerminalAppUnitTest.java
   java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```
   *預期結果*: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`

2. **LinuxManagerService 系統服務單元測試**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java -d /tmp/m3_service_classes $(find frameworks/base/services/core/java/com/android/server/linux -name '*.java') tests/unit/LinuxManagerServiceTest.java
   java -cp /tmp/m3_service_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.LinuxManagerServiceTest
   ```
   *預期結果*: `JAVA TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`

3. **E2E 測試套件 (Tier 1 & Tier 2 for F-R3)**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --feature F-R3
   python3 tests/e2e/runner.py --tier 2 --feature F-R3
   ```
   *預期結果*: 通過率 100.0% (35/35 PASS for Tier 1, 35/35 PASS for Tier 2).
