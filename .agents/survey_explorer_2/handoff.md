# Handoff Report — Auth Protocol & Handshake Explorer

## 1. Observation (觀察結果)

### 1.1 Host Java 系統服務層
- **檔案**: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - 第 274–279 行：`generateHmacAuthToken()` 使用 `SecureRandom` 生成 32-byte 陣列 `token`。
  - 第 421–424 行：`startVm()` 產生 `byte[] authToken = generateHmacAuthToken();`，並呼叫 `mBridgeService.notifyVmStarting(authToken);`。
  - 第 73–75 行、164–174 行：`onVmHandshakeCompleted()` 呼叫 `notifyVmStarted()`，將 `mCurrentState` 從 `STATE_STARTING` 改為 `STATE_RUNNING`，並取消 15 秒啟動逾時。
- **檔案**: `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`
  - 第 290–292 行：`notifyVmStarting(byte[] authToken)` 呼叫 `sendPacket(CMD_VM_START, 0, authToken)`，透過 Unix Domain Socket `/dev/socket/linux_bridge` 傳送 32-byte payload。
  - 第 195–200 行：收到 `CMD_HANDSHAKE_COMPLETE` (`0x0003`) 封包時，呼叫 `mCallback.onVmHandshakeCompleted()`。

### 1.2 Host C++ 原生守護行程層
- **檔案**: `system/linux_bridge/main.cpp`
  - 第 50–60 行：建立 `VsockServer` 實例，綁定 Control Port 5000 (`vsockServer.bindPort(5000)`)。
- **檔案**: `system/linux_bridge/socket_server.cpp`
  - 第 239–256 行：
    ```cpp
    std::vector<uint8_t> token;
    if (payload.size() >= 32) {
        token.assign(payload.begin(), payload.begin() + 32);
    } else {
        token = HmacAuth::generateRandomToken();
    }
    std::vector<uint8_t> secret;
    if (payload.size() >= 64) {
        secret.assign(payload.begin() + 32, payload.begin() + 64);
    } else {
        secret = HmacAuth::generateRandomToken();
    }
    std::string tokenHex = HmacAuth::hexEncode(token);
    ```
  - 第 254–256 行：呼叫 `mVsockServer->setAuthToken(token, secret);`。
  - 第 273 行：呼叫 `execlp("bash", "bash", scriptPath, configPath, tokenHex.c_str(), nullptr);` 將 `tokenHex` 傳給 launch 腳本。
  - 第 70–81 行：`onVsockHandshakeSuccess(uint32_t cid)` 將 `mVmState` 改為 `VmState::RUNNING` 並對 Java 端寫入 `CMD_HANDSHAKE_COMPLETE` (`0x0003`)。
- **檔案**: `system/linux_bridge/vsock_server.cpp`
  - 第 144–158 行：AF_VSOCK Port 5000 `listenLoop` 接收連線，讀取 64-byte `AuthHandshakePayload`。
  - 第 204–228 行：`processHandshake()` 檢查 `cid == 3` 並呼叫 `HmacAuth::verifyHandshake`。
- **檔案**: `system/linux_bridge/hmac_auth.cpp`
  - 第 236–270 行：`verifyHandshake()` 進行 5 秒逾時檢查、Token 匹配 (Constant-time)、防重放標記 (`markTokenUsed`) 以及 RFC 2104 HMAC-SHA256 簽章驗證。

### 1.3 Guest Agent & VM 啟動腳本
- **檔案**: `guest/scripts/launch_vm.sh`
  - 第 6 行 & 第 81 行：接收 `$2` 作為 `AUTH_TOKEN`，建構 `CMDLINE="... android_bridge.token=${AUTH_TOKEN} ..."`，並傳給 `crosvm --params "${CMDLINE}"`。
- **檔案**: `guest/bridge-agent/src/main.rs`
  - 第 18–24 行：呼叫 `auth::extract_auth_secret()`。
  - 第 28–52 行：`VsockListener::bind(VMADDR_CID_ANY, PORT_PORTAL)` (Port 5000)、5001、5002，在 Guest 端監聽（作為 Server）。
- **檔案**: `guest/bridge-agent/src/auth.rs`
  - 第 55–75 行：`parse_secret_from_cmdline` 從 `/proc/cmdline` 尋找 `android_bridge.token=`。
  - 第 37–52 行：`decode_hex_or_raw` 將 64-char Hex 字串轉為 32-byte 二進位向量。
- **檔案**: `guest/bridge-agent/Cargo.toml`
  - 套件名稱 `bridge-agent`。

### 1.4 Cargo 交叉編譯結果
- **命令**: `$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`
- **結果**:
  - `guest/bridge-agent`: Exit Code 0 (0 errors, 2 warnings: unused `reset_portal_state` & `VsockListener::Tcp`).
  - `guest/portal-agent`: Exit Code 0 (0 errors, 2 warnings: unused imports).

---

## 2. Logic Chain (邏輯推理鏈)

1. **Premise 1 (Host Java Token 產生長度與 C++ 期待不一致)**:
   - 由 Observation 1.1，Java `LinuxManagerService` 只生成 32 碼二進位 token 傳送給 C++。
   - 由 Observation 1.2，Host C++ `socket_server.cpp` 在 `payload.size() < 64` 時，自己再次執行 `HmacAuth::generateRandomToken()` 生成另一組隨機 `secret`。
   - 推理：這導致 Host `VsockServer` 內部保留的 `mSharedSecret` 是 C++ 自行生成的亂數，而注入 Kernel cmdline `android_bridge.token=` 的卻是 Java 的 `tokenHex`。Guest 讀取 cmdline 得到的密鑰與 Host `VsockServer` 的 `mSharedSecret` 不匹配，HMAC 簽章比對必定失敗。

2. **Premise 2 (Guest Agent 控制通道角色倒置)**:
   - 由 Observation 1.2，Host C++ `main.cpp` 與 `vsock_server.cpp` 正確在 Host 端 `AF_VSOCK` Port 5000 上執行 `bind` 與 `listenLoop`，等待 Guest 連接並傳送 64-byte `AuthHandshakePayload`。
   - 由 Observation 1.3，Guest `bridge-agent/src/main.rs` 目前也是在 Guest 端執行 `VsockListener::bind(VMADDR_CID_ANY, 5000)`，等待連線進入。
   - 推理：Guest 沒有實作開機時向 Host (`CID_HOST = 2`) Port 5000 發起連線並送出 `AuthHandshakePayload` 的 Initiator 邏輯。這導致兩端都在監聽而無人發起連線，引發 15 秒啟動逾時（`LinuxManagerService.handleBootTimeout`）。

3. **Premise 3 (握手成功觸發狀態流轉)**:
   - 由 Observation 1.1 及 1.2，當 Host `VsockServer` 收到合法的 64-byte 封包並通過 `verifyHandshake` 後，會觸發 `onVsockHandshakeSuccess`，向 Java 端發送 `CMD_HANDSHAKE_COMPLETE` (`0x0003`)，進而使 Java 端的 VM 狀態成功轉換為 `STATE_RUNNING`。
   - 推理：只要修復 Premise 1 (Secret 一致性) 與 Premise 2 (Guest Initiator 連線發起)，整個 Handshake 與 VM 狀態轉換鏈即可完整閉合。

---

## 4. Caveats (注意事項與未覆蓋範圍)

- **無寫入操作**: 本調查嚴格遵守唯讀約束，未修改任何源碼。
- **環境差異**: 測試環境中使用 `$HOME/.cargo/bin/cargo` 執行 `cargo check`；live 部署環境需確保 `cargo` 已在系統 PATH 中。
- **網頁/其他通道**: 本次調查僅針對 Auth Protocol 與 VSOCK Port 5000 Handshake，未深入涉及 Port 5001 (PTY) / 5002 (Wayland) 之資料傳輸內容。

---

## 5. Conclusion (調查結論)

1. **代碼位置已精確定位**：Java 系統服務、Host C++ Daemon、Guest Agent Rust Crate 及啟動腳本檔案與行號均已詳細標註於 `survey_report.md`。
2. **核心缺陷已明確查明**：
   - 缺陷 A：Host Java 只傳 32B，導致 Host C++ 獨立生成另一組 random `secret`，使 Host 期望的 Secret 與 Guest Cmdline 拿到的 Secret 斷層。
   - 缺陷 B：Guest Agent 在 Port 5000 上角色倒置（監聽而非發起連線），未向 Host `CID_HOST=2` Port 5000 主動發起 HMAC 握手。
3. **編譯驗證通過**：Rust `bridge-agent` 與 `portal-agent` 在 `aarch64-unknown-linux-gnu` 目標下 `cargo check` 均成功通過 (Exit Code 0)。

---

## 6. Verification Method (獨立驗證方法)

1. **查看調查報告**：
   - 檢查 `/Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/survey_report.md`
2. **驗證 Rust ARM64 交叉編譯**：
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent
   $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu
   ```
   *預期結果*: Exit code 0。
