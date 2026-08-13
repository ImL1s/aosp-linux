# AOSP Dual-OS Auth Protocol & VSOCK Handshake 調查報告

## 1. 執行摘要 (Executive Summary)

本報告針對 AOSP Dual-OS 系統中的 **Auth 協定與 VSOCK Handshake 機制** 進行全面且唯讀的源碼調查。調查範圍涵蓋 Host Java 系統服務層 (`LinuxManagerService` / `LinuxBridgeService`)、Host C++ 原生守護行程 (`linux_bridge`)、Guest 腳本 (`launch_vm.sh`) 以及 Guest Agent 語言組件 (`bridge-agent` Rust crate)。

經由精確的代碼追蹤與靜態分析，已確定各組件的原始碼位置、Token/Secret 生成與傳遞流程、VSOCK Port 5000 監聽與 HMAC 驗證演算法、以及 Host VM 狀態轉換鏈。同時，本報告亦發現了三個關乎啟動握手成功的核心結構性缺陷（Token/Secret 長度不匹配、Secret 獨立隨機生成斷層、以及 Guest Agent 角色倒置問題），並驗證了 Rust `aarch64-unknown-linux-gnu` 目標編譯狀態。

---

## 2. 系統架構與檔案對照表 (System Architecture & File Mapping)

| 層級 (Layer) | 組件名稱 (Component) | 核心檔案路徑 (File Path) | 職責說明 (Responsibilities) |
| :--- | :--- | :--- | :--- |
| **Host Java** | LinuxManagerService | `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` | SystemServer 服務，管理 VM 生命週期 (`startVm`, `stopVm`)、產生 32-byte 隨機 Auth Token、維護 `STATE_STARTING` / `STATE_RUNNING` 狀態與 15 秒啟動逾時。 |
| **Host Java** | LinuxBridgeService | `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java` | Unix Domain Socket (UDS) 客戶端，連接 `/dev/socket/linux_bridge`，序列化/反序列化 `LNXB` 封包 (`CMD_VM_START`, `CMD_HANDSHAKE_COMPLETE`)。 |
| **Host Java** | AIDL 介面 | `frameworks/base/core/java/android/system/linux/ILinuxManager.aidl`<br>`frameworks/base/core/java/android/system/linux/ILinuxBridge.aidl` | Binder IPC 定義。 |
| **Host C++** | Main Entry | `system/linux_bridge/main.cpp` | 原生守護行程入口，初始化 `SocketServer` 與 `VsockServer`，綁定 AF_VSOCK Control Port 5000。 |
| **Host C++** | SocketServer | `system/linux_bridge/socket_server.h`<br>`system/linux_bridge/socket_server.cpp` | 監聽 `/dev/socket/linux_bridge`，接收 `CMD_VM_START`，提取 Token/Secret，fork/exec `launch_vm.sh`，接收握手成功回呼並回傳 `CMD_HANDSHAKE_COMPLETE`。 |
| **Host C++** | VsockServer | `system/linux_bridge/vsock_server.h`<br>`system/linux_bridge/vsock_server.cpp` | 監聽 `AF_VSOCK` Port 5000 (Control)、5001 (PTY)、5002 (Wayland)；對 Port 5000 讀取 64-byte `AuthHandshakePayload`；呼叫 `verifyHandshake` 驗證 HMAC。 |
| **Host C++** | HmacAuth | `system/linux_bridge/hmac_auth.h`<br>`system/linux_bridge/hmac_auth.cpp` | 實現 RFC 2104 HMAC-SHA256、常數時間位元組比較 (Constant-time Comparison)、單次使用 Token 防重放防護 (Replay Protection)、5 秒握手逾時控制。 |
| **Host C++** | VsockFraming | `system/linux_bridge/vsock_framing.h`<br>`system/linux_bridge/vsock_framing.cpp` | 定義 `AuthHandshakePayload` (32B Token + 32B Signature) 與 `VsockFrameHeader` 結構體與 Framing 打包解包。 |
| **Host Script**| launch_vm.sh | `guest/scripts/launch_vm.sh` | VM 啟動腳本，檢查 RAM/Lock/KVM，組合 Kernel Cmdline `android_bridge.token=${AUTH_TOKEN}`，透過 `crosvm run` 啟動 Guest。 |
| **Guest Rust**| bridge-agent main | `guest/bridge-agent/src/main.rs` | Guest 端代理程式主入口，讀取 `/proc/cmdline` 提取 Auth Secret。 |
| **Guest Rust**| bridge-agent auth | `guest/bridge-agent/src/auth.rs` | 從 `/proc/cmdline` (解析 `android_bridge.token=`) 提取 32-byte 密鑰，計算 HMAC-SHA256 簽章，執行 Stream 握手。 |
| **Guest Rust**| bridge-agent vsock| `guest/bridge-agent/src/vsock.rs` | 封裝 AF_VSOCK Socket (`VMADDR_CID_HOST = 2`, Ports 5000/5001/5002)，支援 Linux 原生與非 Linux (TCP) 抽象。 |
| **Guest Rust**| bridge-agent cargo| `guest/bridge-agent/Cargo.toml` | Guest Agent Cargo 專案設定檔。 |

---

## 3. Token 與 Secret 生成與傳遞流程分析 (Token & Secret Generation & Propagation)

### 3.1 Host Java 端的生成與傳送
1. 在 `LinuxManagerService.java` 中，當呼叫 `startVm()` 時：
   - 呼叫 `generateHmacAuthToken()`（第 274–279 行）：
     ```java
     byte[] token = new byte[32];
     new java.security.SecureRandom().nextBytes(token);
     mActiveAuthToken = token;
     return token;
     ```
   - 隨後在第 421–424 行呼叫 `mBridgeService.notifyVmStarting(authToken)`。
2. `LinuxBridgeService.java`（第 290–292 行）將 `authToken` 封裝為 UDS 封包：
   - Magic: `0x4C4E5842` (`LNXB`)
   - CmdType: `CMD_VM_START` (`0x0001`)
   - Payload: 32 位元組的 `authToken`

### 3.2 Host C++ Daemon 端的接收與處理
1. 在 `socket_server.cpp`（第 230–279 行）處理 `CMD_VM_START` 時：
   - 解析來自 Java 的 `payload`：
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
     ```
   - 轉為 64 位元 Hex 字串：`std::string tokenHex = HmacAuth::hexEncode(token);`
   - 存入 `mVsockServer`：`mVsockServer->setAuthToken(token, secret);`
   - `fork()` 並 `exec()` 呼叫 launch 腳本：
     ```cpp
     execlp("bash", "bash", "guest/scripts/launch_vm.sh", configPath, tokenHex.c_str(), nullptr);
     ```

### 3.3 Kernel Cmdline 傳遞
1. `launch_vm.sh`（第 6 行及 81 行）接收第二個參數作為 `${AUTH_TOKEN}`：
   ```bash
   CMDLINE="console=ttyS0 root=/dev/vda ro init=/sbin/init android_bridge.token=${AUTH_TOKEN} panic=1 quiet"
   ```
2. 透過 `crosvm run --params "${CMDLINE}"` 傳給 Guest Kernel。

### 🔍 發現之關鍵缺陷 1 (Defect #1: Java/C++ Token & Secret 獨立生成斷層)
- Java 僅生成並傳送 **32 位元組** 的 `authToken` 給 C++ 守護行程。
- C++ 守護行程 `socket_server.cpp` 期待長度為 **64 位元組**（前 32B 為 token，後 32B 為 secret）。由於 `payload.size() == 32 < 64`，C++ 守護行程在第 250 行自主呼叫 `HmacAuth::generateRandomToken()` **重新生了一組隨機的 `secret`**！
- 傳給 Kernel cmdline `android_bridge.token=` 的是 `tokenHex`（前 32B），而 Host VsockServer 驗證 HMAC 時用的卻是重新生出的 `secret`（後 32B），導致 Guest agent 拿到的密鑰與 Host VsockServer 期待的密鑰不一致！
- **修復要求**：需依照 R3 規範，Host Java 生成 32B token + 32B secret（或單一共享 32B secret 機制），確保傳給 Host C++ 的 64B 與注入 Kernel cmdline 的 `android_bridge.token=<hex_secret>` 完全匹配。

---

## 4. Guest Agent 啟動與 Cmdline 解析分析 (Guest Agent Startup & Cmdline Parsing)

### 4.1 Cmdline 讀取與解析
1. 在 `guest/bridge-agent/src/main.rs` 第 18 行呼叫 `auth::extract_auth_secret()`。
2. `guest/bridge-agent/src/auth.rs`（第 10–35 行）按優先順序提取密鑰：
   1. 環境變數 `LINUX_AUTH_SECRET`
   2. 檔案 `/etc/linux_auth_secret`
   3. 核心參數 `/proc/cmdline`
3. 在 `parse_secret_from_cmdline()`（第 55–75 行）中，掃描 `/proc/cmdline` 欄位：
   - 尋找 `android_bridge.token=`、`linux_auth_secret=` 或 `auth_secret=` 前綴。
   - 呼叫 `decode_hex_or_raw(val)`（第 37–52 行）：
     - 若字串長度為 64 且全為 Hex 字符，則解碼為 **32 位元組二進位 Data (`Vec<u8>`)**。
     - 否則回傳原始 ASCII 字串位元組。

---

## 5. Host C++ Daemon AF_VSOCK 監聽伺服器分析 (Host AF_VSOCK Listening Server)

### 5.1 服務初始化與 Port 綁定
1. 在 `main.cpp`（第 50–60 行）：
   - 建立 `VsockServer` 實例。
   - 呼叫 `vsockServer.bindPort(5000)` (VSOCK_PORT_CONTROL)。
2. 在 `vsock_server.cpp`（第 87–130 行）：
   - `bindPort(5000)` 建立 `AF_VSOCK` Socket (`SOCK_STREAM`)。
   - 綁定到 `VMADDR_CID_ANY` (0xFFFFFFFF)，Port 5000。
   - 開啟 `listenLoop` 執行緒等待連線。

### 5.2 握手驗證處理 (`processHandshake`)
1. 當有 Client 連接到 Host Port 5000 時，`listenLoop` 讀取 64 位元組 `AuthHandshakePayload`：
   - `token` (32 bytes)
   - `signature` (32 bytes)
2. `vsock_server.cpp`（第 204–228 行）呼叫 `processHandshake()`：
   - **CID 檢查**：驗證 `clientAddr.svm_cid == ALLOWED_GUEST_CID` (3)，非授權 CID 直接拒絕。
   - 呼叫 `HmacAuth::verifyHandshake(mSharedSecret, mActiveToken, payload, mTokenCreatedAt)`。
3. `HmacAuth::verifyHandshake`（`hmac_auth.cpp` 第 236–270 行）實作嚴格的安全驗證：
   - **5 秒逾時檢查**：`HANDSHAKE_TIMEOUT_SEC = 5.0`，若 Token 建立超過 5 秒，拒絕握手。
   - **Token 匹配**：使用 `constantTimeCompare` 比較 `payload.token` 與 `mActiveToken`。
   - **防重放防護 (Replay Protection)**：檢查 `isTokenUsed(payloadToken)`，已使用過的 Token 立即拒絕，驗證通過後呼叫 `markTokenUsed()`。
   - **HMAC-SHA256 簽章驗證**：呼叫 `computeHmacSha256(secret, payloadToken)` 計算預期簽章，並使用常數時間演算法比對 `payload.signature`。

---

## 6. Handshake 協定與 VM 狀態轉換鏈 (Handshake & VM State Transitions)

### 6.1 握手成功後的狀態轉換鏈
1. 當 Host `VsockServer::processHandshake` 驗證成功後：
   - 設定 `mAuthenticated = true`。
   - 觸發 `mOnHandshakeSuccessCb(cid)` 回呼。
2. 回呼轉致 `SocketServer::onVsockHandshakeSuccess(cid)`（`socket_server.cpp` 第 70–81 行）：
   - 更新內部 `mVmState = VmState::RUNNING`。
   - 透過 Unix Domain Socket 發送 `CMD_HANDSHAKE_COMPLETE` (`0x0003`) 封包給 Host Java `LinuxBridgeService`。
3. Host Java `LinuxBridgeService`（`LinuxBridgeService.java` 第 195–200 行）收到 `CMD_HANDSHAKE_COMPLETE`：
   - 觸發 `mCallback.onVmHandshakeCompleted()`。
4. `LinuxManagerService`（`LinuxManagerService.java` 第 73–75 行、164–174 行）：
   - 呼叫 `notifyVmStarted()`。
   - 取消 15 秒啟動逾時定時器 (`cancelBootTimeoutLocked()`)。
   - 狀態從 `STATE_STARTING` 轉換為 `STATE_RUNNING`。
   - 向全系統廣播 VM 狀態變更訊息。

### 🔍 發現之關鍵缺陷 2 (Defect #2: Guest Agent 角色倒置問題)
- **R3 規範要求**："Control Channel roles: Host C++ listens on `AF_VSOCK` port 5000; Guest agent acts as initiator upon boot by connecting to Host `CID_HOST=2` port 5000, sending 32-byte token + 32-byte HMAC signature."
- **現有 Guest 代碼現狀**：在 `guest/bridge-agent/src/main.rs` 中：
  - Guest agent 目前是在 Guest 內部 **綁定並監聽 Port 5000, 5001, 5002** (做為 Server)：
    ```rust
    let listener_portal = VsockListener::bind(VMADDR_CID_ANY, PORT_PORTAL);
    ```
  - 當有連線進入時，Guest agent 期待 incoming connection 發送 64B 封包給 Guest 進行 `perform_handshake` 驗證。
- **架構不匹配**：Host C++ Daemon 也在監聽 Host 端的 Port 5000，等待 Guest 主動發起連線。如果 Guest Agent 也在監聽 5000 而不主動發起連線，兩端將形成雙向等待 (Deadlock / Timeout)，導致 15 秒 VM 啟動逾時失敗！
- **修復要求**：Guest agent 啟動時應作為 **Initiator**，主動向 Host `CID_HOST=2` Port 5000 發起 AF_VSOCK 連線，發送 `AuthHandshakePayload` (32B Token + 32B HMAC Signature)，完成 Host 端的驗證並觸發 `STATE_RUNNING` 轉置。

---

## 7. Rust Crates 與 Cargo 工作區驗證 (Cargo Workspace Verification)

### 7.1 Crate 結構
- 專案目錄下包含兩個獨立的 Rust Package：
  1. `guest/bridge-agent/Cargo.toml` (`package.name = "bridge-agent"`)
  2. `guest/portal-agent/Cargo.toml` (`package.name = "portal-agent"`)
- 根目錄無 `Cargo.toml` workspace 配置，各自獨立管理。

### 7.2 交叉編譯檢查 (`cargo check --target aarch64-unknown-linux-gnu`)
在目標 `aarch64-unknown-linux-gnu` 下執行檢查：
- **`guest/bridge-agent` 執行結果**：
  - 指令：`$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`
  - 結果：**Exit Code 0 (Success)**
  - 警告訊息：2 個 `dead_code` / `unused` 警告（`reset_portal_state` 與 `VsockListener::Tcp` 未使用）。無任何語法或類型錯誤。
- **`guest/portal-agent` 執行結果**：
  - 指令：`$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`
  - 結果：**Exit Code 0 (Success)**
  - 警告訊息：2 個 `unused_imports` 警告。無任何編譯錯誤。

---

## 8. 修復與重構建議清單 (Remediation Checklist for Subsequent Agents)

1. **Host Java & C++ Daemon 32B Single-Secret / 64B Pair 傳遞對齊**：
   - 修正 `LinuxManagerService.java` 生成並傳送包含 token + secret 的完整資料，或統一定義單一 32B shared secret 協議。
   - 修正 `socket_server.cpp`，確保傳給 `VsockServer::setAuthToken(token, secret)` 的 secret 與注入 Cmdline 的 `android_bridge.token=<hex_secret>` 一致。
2. **Guest Agent 連線發起者 (Initiator) 角色修正**：
   - 在 `guest/bridge-agent` 啟動主流程中，新增發起者邏輯：引導 Guest Agent 開機時建立到 `VMADDR_CID_HOST = 2`, `PORT = 5000` 的 AF_VSOCK 連線。
   - 讀取 `/proc/cmdline` 的 `android_bridge.token=<hex_secret>` 作為 secret，計算 HMAC-SHA256 簽章，並送出 64-byte `AuthHandshakePayload` (Token + Signature)。
3. **VM 狀態轉換驗證**：
   - 確保完成 VSOCK 5000 握手後，Host C++ 成功觸發 `CMD_HANDSHAKE_COMPLETE`，使 `LinuxManagerService` 成功進階至 `STATE_RUNNING`。
