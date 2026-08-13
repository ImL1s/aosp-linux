# Handoff Report — Milestone 3 (R3 Single-Secret HMAC Agreement & Handshake Initiator Challenger 2)

## 1. Observation (觀察事實)

### 1.1 `LinuxManagerService` 狀態轉換與握手邏輯觀察
- 在 `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` 中：
  - `startVm()`（第 409–435 行）：發起 VM 啟動，將狀態由 `STATE_STOPPED` 轉為 `STATE_STARTING`，並透過 `mScheduler.schedule` 排程 15 秒 boot timeout 定時器，同時調用 `generateHmacAuthToken()` 產生 64 位元組包含 32 位元組 token 與 32 位元組 secret 之 authPayload 傳送給 `LinuxBridgeService`。
  - `onVmHandshakeCompleted()` 回調（第 72–75 行）：當 Native Daemon 完成 Vsock 握手並發送 `CMD_HANDSHAKE_COMPLETE` (0x0003) 時，`LinuxBridgeService` 調用 `onVmHandshakeCompleted()`，觸發 `notifyVmStarted()`。
  - `notifyVmStarted()`（第 164–174 行）：檢查 `if (mCurrentState == LinuxManager.STATE_STARTING)`。若為 `STATE_STARTING`，調用 `cancelBootTimeoutLocked()` 取消 15 秒定時器，將狀態轉換為 `STATE_RUNNING`，並廣播 `dispatchStateChanged`。
  - **狀態轉換實證測試 (`LinuxManagerServiceStateTest.java`)**：
    - 成功驗證正常的狀態轉換：`STATE_STOPPED` -> `STATE_STARTING` -> `STATE_RUNNING` -> `STATE_STOPPED`。
    - 驗證重複觸發 `notifyVmStarted()` 具備冪等性（Idempotent），狀態保持在 `STATE_RUNNING` 且不產生重複廣播。
    - 驗證在 `STATE_STOPPED` 或 `STATE_ERROR` 狀態下收到遲到（late）或偽造的 handshake 通知均被安全忽略。
    - 驗證 15 秒超時觸發時狀態正確轉為 `STATE_ERROR`，且後續握手通知不再修改 `STATE_ERROR` 狀態。

### 1.2 C++ Native Daemon 與 HMAC 加密實作觀察
- 在 `system/linux_bridge/socket_server.cpp`（第 230–286 行）：
  - 接收到 `CMD_VM_START` (0x0001) 時，若 `payload.size() >= 64`，正確解析前 32 位元組為 `token`、後 32 位元組為 `secret`，並透過 `mVsockServer->setAuthToken(token, secret)` 設定金鑰，將 `secret` 轉為 64 位元 Hex 字串傳遞給 `launch_vm.sh`（kernel cmdline `android_bridge.token=<secretHex>`）。
  - `mVmState` 設為 `VmState::STARTING`，並保持 `mPendingClientFd`，等待 Vsock 握手完成。
- 在 `system/linux_bridge/vsock_server.cpp`（第 204–228 行）：
  - 當 Guest CID 3 連接至 Host Port 5000 並傳送 64 位元組 `AuthHandshakePayload` 時，`processHandshake` 驗證 CID（必須為 `ALLOWED_GUEST_CID = 3`）及 `HmacAuth::verifyHandshake`。
  - 握手成功後，調用 `onVsockHandshakeSuccess`，將 `mVmState` 轉為 `VmState::RUNNING` 並向 Framework Socket 回傳 `CMD_HANDSHAKE_COMPLETE` (0x0003)。
- **實證發現：`system/linux_bridge/hmac_auth.cpp` 第 87 行 SHA-256 常數缺陷**：
  - 在 `system/linux_bridge/hmac_auth.cpp` 第 87 行之 `sha256_internal::K` 常數陣列中：
    - 現有程式碼為：`0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef4a3f7, 0xc67178f2`
    - 其中第 62 個常數（K[62]）被誤寫為 `0xbef4a3f7`。
    - 標準 FIPS 180-4 SHA-256 及 Rust 實作 (`guest/bridge-agent/src/auth.rs` 第 117 行) 中之正確常數為 `0xbef9a3f7`。
  - **實證測試結果 (`challenger_m3_2_empirical_test.cpp`)**：
    - 執行 RFC 4231 Test Case 2 國際標準 Golden Vector 測試 (`Key="Jefe"`, `Data="what do ya want for nothing?"`)：
      - 預期標準 HMAC-SHA256: `5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843`
      - C++ `hmac_auth.cpp` (fallback 模式) 算出的結果: `5b2bbba31425a1de386e4538bd66a8d52ddb3c9372b7a741792bd5568a79f30f`
      - **測試結果：FAILED**。當 OpenSSL 庫未連結或在 fallback 模式下，C++ Daemon 計算之 HMAC-SHA256 將無法與符合標準的 Guest Rust Agent (`guest/bridge-agent/src/auth.rs`) 匹配，導致握手失敗被拒絕 (`SECURITY_ALERT: HMAC signature mismatch during guest handshake`)。

### 1.3 建置與測試執行觀察
- **ARM64 Rust Agent 編譯檢查**：
  - 指令：`(cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)` 與 `(cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)`
  - 結果：Exit code 0, **0 warnings, 0 errors**。
- **Java 測試套件**：
  - 指令：`TerminalAppUnitTest` 與 `LinuxManagerServiceStateTest`
  - 結果：`JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`，`LinuxManagerService` 狀態轉換 11 項測試全部通過。
- **Native C++ Daemon 測試**：
  - 指令：`./build_out/bin/linux_bridge_test`
  - 結果：`PASS (50/50 succeeded)`，`NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`（50 個高併發 Client 請求全部成功）。

---

## 2. Logic Chain (邏輯推理鏈)

1. **`LinuxManagerService` 握手完成狀態轉換邏輯**：
   - 觀察：`LinuxManagerService` 中的 `notifyVmStarted()` 被 `LinuxBridgeCallback.onVmHandshakeCompleted()` 調用。
   - 推理：當 Guest Boot 完成並透過 Vsock 傳送合法 64 位元組 `AuthHandshakePayload` 至 Host Port 5000 時，Host C++ `SocketServer` 會向 Host Java `LinuxBridgeService` 送出 `CMD_HANDSHAKE_COMPLETE` (0x0003)。
   - 結論：`LinuxManagerService` 收到後執行 `notifyVmStarted()`，若當前狀態為 `STATE_STARTING`，會成功取消 15 秒 boot timeout 定時器，將 VM 狀態更新為 `STATE_RUNNING`，並廣播狀態更新。若當前狀態非 `STATE_STARTING`（例如已超時轉為 `STATE_ERROR` 或已停止），則該通知會被安全忽略。實證測試 `LinuxManagerServiceStateTest` 驗證全數通過。

2. **C++ `hmac_auth.cpp` SHA-256 K[62] 常數缺陷推理**：
   - 觀察：`system/linux_bridge/hmac_auth.cpp` 第 87 行中的 K[62] 為 `0xbef4a3f7`，而 `guest/bridge-agent/src/auth.rs` 第 117 行與 FIPS 180-4 標準為 `0xbef9a3f7`。
   - 推理：SHA-256 算法中，64 個 32 位元常數 K[0..63] 來自前 64 個質數的立方根小數部分。第 63 個質數 311 的立方根小數部分正確 Hex 應為 `0xBEF9A3F7`。`0xbef4a3f7` 在第 4 個 Hex 數字上有 1 個 Bit 錯誤（`4` vs `9`）。
   - 結論：在無 OpenSSL 動態庫環境下，C++ Daemon 使用內部 fallback `sha256_internal` 時，計算出來的 HMAC-SHA256 簽名與 Guest Rust Agent（採用標準 RFC 4231 演算法）不一致。因此 Guest 送出的握手簽名會被 Host C++ 誤判為簽名不匹配而拒絕，導致 VM 握手無法完成。實證測試 `challenger_m3_2_empirical_test` 重現了此 RFC 4231 Golden Vector 失敗。

---

## 3. Caveats (注意事項與限制)

- **OpenSSL 依賴環境差異**：當 Host C++ Daemon 編譯並成功動態連結系統 OpenSSL 庫時，`HmacAuth::computeHmacSha256` 會使用 `HMAC()` API，此時可避開 `sha256_internal` 的 K[62] 錯誤；但在無 OpenSSL 庫或獨立單元測試/嵌入式系統中，此 Bug 會被觸發並導致握手失敗。因此必須修正 `hmac_auth.cpp` 中的 `K[62]` 常數。

---

## 4. Conclusion (評估結論與處置建議)

- ** verdict: REQUEST_CHANGES **
- **原因**：
  1. `LinuxManagerService` 的狀態轉換邏輯（`STATE_STARTING` -> `STATE_RUNNING`、超時定時器取消、重複握手冪等性、超時與中斷處理）表現完全正確且符合規格。
  2. 然而在 Native C++ 密碼學模組中發現**重大缺陷**：`system/linux_bridge/hmac_auth.cpp` 第 87 行之 SHA-256 常數 `K[62]` 被誤寫為 `0xbef4a3f7`（應為 `0xbef9a3f7`），導致 fallback 模式下的 HMAC-SHA256 計算違反 RFC 4231 國際標準，無法與 Guest Rust Agent 達成密碼學金鑰協議。
- **修復要求**：
  - 將 `system/linux_bridge/hmac_auth.cpp` 第 87 行之 `0xbef4a3f7` 修正為 `0xbef9a3f7`。

---

## 5. Verification Method (獨立驗證步驟)

請在 `/Users/iml1s/Documents/mine/aosp-linux` 目錄下執行以下指令進行獨立驗證：

1. **Rust ARM64 Agent 編譯檢查**：
   ```bash
   (cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   (cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   ```
   *預期結果*：Exit code 0, 0 warnings, 0 errors.

2. **Java 狀態轉換單元測試**：
   ```bash
   mkdir -p build_out/classes
   find frameworks/base/core/java frameworks/base/services/core/java packages/apps/LinuxTerminal/src -name "*.java" > build_out/sources.txt
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d build_out/classes @build_out/sources.txt
   javac -sourcepath frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src -classpath build_out/classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d /tmp/m3_classes tests/unit/TerminalAppUnitTest.java tests/unit/LinuxManagerServiceStateTest.java
   java -cp /tmp/m3_classes:build_out/classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.LinuxManagerServiceStateTest
   ```
   *預期結果*：`JAVA EMPIRICAL TEST RESULT: ALL STATE TRANSITION TESTS PASSED`.

3. **C++ 實證測試與 K[62] 缺陷重現**：
   ```bash
   mkdir -p build_out/bin
   clang++ -std=c++20 -DHAS_OPENSSL=0 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/challenger_m3_2_empirical_test.cpp -o build_out/bin/challenger_m3_2_empirical_test
   ./build_out/bin/challenger_m3_2_empirical_test
   ```
   *預期結果*：Test 1 重現 RFC 4231 Golden Vector 失敗（`Expected: 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843, Got: 5b2bbba31425a1de386e4538bd66a8d52ddb3c9372b7a741792bd5568a79f30f`），證明 `K[62]` 常數缺陷存在。
