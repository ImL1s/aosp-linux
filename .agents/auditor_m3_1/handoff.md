# Forensic Audit Report — Milestone 3 (R3 Single-Secret HMAC Agreement & Handshake Initiator)

**Work Product**: Milestone 3 (R3 Authentication Protocol & Guest Handshake Initiator)
**Profile**: General Project (Development Mode)
**Verdict**: CLEAN

---

## 1. Observation

### 1.1 Source Code Analysis Observations
1. **Host Java Key Generation (`LinuxManagerService.java` lines 275–287)**:
   - `generateHmacAuthToken()` 使用 `java.security.SecureRandom` 動態生成 32-byte `token` 與 32-byte `secret`：
     ```java
     public byte[] generateHmacAuthToken() {
         byte[] token = new byte[32];
         byte[] secret = new byte[32];
         java.security.SecureRandom random = new java.security.SecureRandom();
         random.nextBytes(token);
         random.nextBytes(secret);
         mActiveAuthToken = token;
         mActiveAuthSecret = secret;
         byte[] payload = new byte[64];
         System.arraycopy(token, 0, payload, 0, 32);
         System.arraycopy(secret, 0, payload, 32, 32);
         return payload;
     }
     ```
   - 經檢查，無任何寫死（hardcoded）密鑰或寫死 token。

2. **Host C++ Daemon (`socket_server.cpp` lines 240–275)**:
   - `SocketServer::clientLoop` 接收 Host Java 透過 `CMD_VM_START` (0x0001) 傳送之 64-byte payload。
   - 解析 `token` (前 32 位元組) 與 `secret` (後 32 位元組)，將 `secret` 轉為 64 字元的十六進位字串 `secretHex`，並透過 `execlp("bash", "bash", "guest/scripts/launch_vm.sh", configPath, secretHex.c_str(), nullptr)` 傳遞給 `launch_vm.sh`。
   - 同時將 `token` 與 `secret` 設定至 `mVsockServer->setAuthToken(token, secret)`。

3. **Guest Kernel Cmdline & Launch Script (`launch_vm.sh` line 81)**:
   - 透過 `android_bridge.token=${AUTH_TOKEN}` 將 64-char hex `secretHex` 注入內核啟動參數 `CMDLINE`。

4. **Guest Rust Agent Key Extraction & Initiator (`guest/bridge-agent/src/auth.rs`, `main.rs`, `vsock.rs`)**:
   - `auth.rs`: `parse_secret_from_cmdline` 從 `/proc/cmdline` 讀取 `android_bridge.token=` 並透過 `decode_hex_or_raw` 解碼為 32-byte 二進位密鑰。實現純 Rust HMAC-SHA256 計算 `HmacSha256::compute_hmac_response`。
   - `main.rs`: 啟動時作為 Initiator 連線 Host `CID_HOST = 2` Port `5000` (`AF_VSOCK`)，計算並發送 64-byte `AuthHandshakePayload` (32-byte token + 32-byte HMAC-SHA256 signature)。
   - `vsock.rs`: 提供原生 `VsockStream::connect(cid, port)` POSIX socket connect 系統呼叫。

5. **Host C++ Handshake Verification (`vsock_server.cpp` & `hmac_auth.cpp`)**:
   - `vsock_server.cpp`: 在 Port 5000 讀取 `AuthHandshakePayload` 後呼叫 `processHandshake(cid, payload)`。
   - `hmac_auth.cpp`: `verifyHandshake` 執行以下檢查：
     a) 5.0 秒以內握手超時檢查。
     b) `constantTimeCompare` 比較 token 與 expectedToken/secret。
     c) 防重放 (Anti-replay) 檢查 `isTokenUsed`。
     d) 計算 `expectedSig = computeHmacSha256(secret, payloadToken)` 並使用 `constantTimeCompare` 比對簽名。
   - 驗證成功後觸發 `onVsockHandshakeSuccess(cid)` 回調，`socket_server.cpp` 將 `mVmState` 轉換為 `VmState::RUNNING` 並傳送 `CMD_HANDSHAKE_COMPLETE` (0x0003) 給 Host Java。

---

### 1.2 Behavioral Verification & Independent Test Execution

1. **Rust ARM64 Compilation Check**:
   - 指令: `(cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu) && (cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)`
   - 結果: Exit Code 0, Finished dev profile, **0 Warnings, 0 Errors**.

2. **Java Framework & Service Compilation**:
   - 指令: `mkdir -p build_out/classes && find frameworks/base/core/java frameworks/base/services/core/java packages/apps/LinuxTerminal/src -name "*.java" > build_out/sources.txt && javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d build_out/classes @build_out/sources.txt`
   - 結果: Exit Code 0, **0 Compilation Errors**.

3. **Java Unit Test Suite**:
   - 指令: `javac -sourcepath frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d /tmp/m3_classes tests/unit/TerminalAppUnitTest.java && java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest`
   - 結果: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`.

4. **Host C++ Native Unit Test Suite**:
   - 指令: `mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test`
   - 結果: `PASS (50/50 succeeded)`, `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`.

5. **Python E2E Test Suite (Tier 1 & Tier 2 for Milestone 3)**:
   - 指令: `python3 tests/e2e/runner.py --tier 1 --filter F-R3` 與 `python3 tests/e2e/runner.py --tier 2 --filter F-R3`
   - 結果: Tier 1: 35/35 PASSED (100.0%); Tier 2: 35/35 PASSED (100.0%).

---

## 2. Logic Chain

1. **單一主鑰與 HMAC 驗證完整性 (Single-Secret HMAC Agreement Integrity)**:
   - 經源碼審查，Host Java 透過 `SecureRandom` 產生 32-byte token 與 32-byte secret 打包為 64-byte payload。
   - Host C++ `socket_server.cpp` 正確解析將 secret 以 64-char hex 送入 kernel cmdline (`android_bridge.token=`)，Guest Rust agent 動態解析此 32-byte 二進位密鑰。
   - 雙方均採用真正的 RFC 2104 HMAC-SHA256 演算法與 `constantTimeCompare` 比對簽名，無任何硬編碼密鑰或繞過簽名檢查的程式碼。

2. **真實 Handshake Initiator 與狀態轉移 (Authentic Initiator & State Transition)**:
   - Guest Agent 啟動時發起真正 `AF_VSOCK` socket 連線至 Host CID 2 Port 5000，送出 64-byte HMAC 簽名認證包。
   - Host C++ 收到並驗證成功後，始透過 `onVsockHandshakeSuccess` 將 VM 狀態改為 `VmState::RUNNING` 並通知 Host Java。不存在偽造或模擬之狀態轉移。

3. **合規性與禁置模式審查 (Compliance with Prohibited Patterns)**:
   - **Hardcoded test results**: 無任何測試結果或寫死回應。
   - **Facade implementations**: 所有介面與函式皆包含真實運算邏輯與系統呼叫。
   - **Fabricated verification outputs**: 測試產出皆為現場執行測試產出，無預先放置之日誌檔。
   - **Self-certifying tests**: 測試對象與測試邏輯分離，真實比對驗證。

---

## 3. Caveats

**無注意事項 (No caveats)**：Milestone 3 的程式碼修改、密鑰協商、HMAC 簽名生成與驗證、Guest Agent Handshake Initiator 均經獨立審查與實測，確認無任何誠信違規（Integrity Violation）。

---

## 4. Conclusion

Milestone 3 (R3 Single-Secret HMAC Agreement & Handshake Initiator) 通過法醫級誠信審計。
- 最終裁定：**CLEAN**

---

## 5. Verification Method

可執行以下指令獨立重現驗證：

1. **Rust ARM64 審查**:
   ```bash
   (cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   (cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   ```

2. **Java 編譯與單元測試**:
   ```bash
   mkdir -p build_out/classes
   find frameworks/base/core/java frameworks/base/services/core/java packages/apps/LinuxTerminal/src -name "*.java" > build_out/sources.txt
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d build_out/classes @build_out/sources.txt
   javac -sourcepath frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d /tmp/m3_classes tests/unit/TerminalAppUnitTest.java
   java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```

3. **C++ 原生單元測試**:
   ```bash
   mkdir -p build_out/bin
   clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test
   ./build_out/bin/linux_bridge_test
   ```

4. **E2E 測試**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --filter F-R3
   python3 tests/e2e/runner.py --tier 2 --filter F-R3
   ```
