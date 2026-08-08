# Handoff Report: Milestone M1 Build System, Test Infrastructure & VM Launch Strategy Analysis

**Agent**: Explorer 3  
**Milestone**: M1 — Real AVF VM Launch (R1)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_3`  
**Date**: 2026-08-08  

---

## Executive Summary (執行摘要)

本報告針對 **Milestone M1 (Real AVF VM Launch - R1)** 之構建系統、測試基礎設施與 VM 啟動腳本進行全方位唯讀調查。報告詳細列出 `socket_server` / `linux_bridge` 原生單元測試的編譯與執行指令、`launch_vm.sh` 在 `TEST_MODE` 與非測試模式下的測試指令，並提出徹底消除偽造 `CMD_HANDSHAKE_COMPLETE` 響應、實作真實進程派生與 Vsock 握手驗證的具體單元與整合測試策略。

---

## 1. Observation (直接觀察事實與程式碼證據)

### 1.1 Scope 檔案原始碼觀察

1. **`system/linux_bridge/socket_server.cpp`**
   - **Line 173–177**: 存在偽造握手回應邏輯：
     ```cpp
     if (header.cmdType == 0x0001) { // CMD_VM_START
         // Respond with CMD_HANDSHAKE_COMPLETE
         std::vector<uint8_t> response = serializePacket(0x0003, header.transactionId, {});
         write(clientFd, response.data(), response.size());
     }
     ```
     *觀察點*: 收到 `CMD_VM_START` (0x0001) 時，`socket_server` 立即同步回寫 `CMD_HANDSHAKE_COMPLETE` (0x0003)，未派生 `launch_vm.sh` 或啟動 `crosvm`，亦未等待 Guest 經由 Vsock 完成 HMAC 握手。

2. **`guest/scripts/launch_vm.sh`**
   - **Line 5–6**: 接收設定檔與 token 參數：`CONFIG_FILE="${1:-/data/misc/linux/vm_config.json}"`, `AUTH_TOKEN="${2:-...}"`
   - **Line 76–79**: `/dev/kvm` 節點檢查與 `TEST_MODE` 開關：
     ```bash
     if [ ! -c /dev/kvm ] && [ "${TEST_MODE:-0}" != "1" ]; then
         echo "ERROR: KVMException: /dev/kvm not found or insufficient permission" >&2
         exit 1
     fi
     ```
   - **Line 82**: 組合 kernel cmdline：`CMDLINE="... android_bridge.token=${AUTH_TOKEN} ..."`
   - **Line 88–102**: 檢測 `crosvm` 命令並執行或輸出模擬模式訊息。

3. **`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`**
   - **Line 305–329**: `startVm()` 將狀態切換為 `STATE_STARTING` (1)，啟動 15 秒超時定時器 `BOOT_TIMEOUT_MS = 15000L` (`handleBootTimeout`)，並呼叫 `mBridgeService.notifyVmStarting()`。
   - **Line 151–161**: `notifyVmStarted()` 在收到 `onVmHandshakeCompleted()` 回呼時被觸發，將狀態轉換至 `STATE_RUNNING` (2) 並取消超時定時器。

4. **`frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`**
   - **Line 47**: 定義原生 Domain Socket 路徑 `SOCKET_PATH = "/dev/socket/linux_bridge"`。
   - **Line 176–181**: 處理接收到的 `CMD_HANDSHAKE_COMPLETE` 並觸發 `mCallback.onVmHandshakeCompleted()`。
   - **Line 271–273**: `notifyVmStarting()` 透過 socket 傳送 `CMD_VM_START` (0x0001)。

### 1.2 編譯與測試環境觀察

- 原生單元測試檔案位於 `system/linux_bridge/tests/linux_bridge_test.cpp`。
- 原生標頭檔與實作檔位於 `system/linux_bridge/` (`socket_server.cpp`, `vsock_server.cpp`, `vsock_framing.cpp`, `hmac_auth.cpp`)。
- 經由 `clang++` 編譯，生成的測試執行檔放置於 `build_out/bin/linux_bridge_test`。
- 端到端測試框架位於 `tests/e2e/runner.py` 與 `tests/e2e/run_tests.sh`。

---

## 2. Logic Chain (邏輯推理鏈)

1. **偽造回應問題推理**:
   - *前提 (Obs 1.1.1)*: `socket_server.cpp` 在接收到 `CMD_VM_START` 時，直接回傳 `CMD_HANDSHAKE_COMPLETE`。
   - *前提 (Obs 1.1.3 & 1.1.4)*: `LinuxBridgeService` 收到此 packet 後立即宣告握手完成，使 `LinuxManagerService` 將狀態改為 `STATE_RUNNING`。
   - *推論*: 即使沒有任何底層 VM 被啟動、也沒有任何腳本執行，系統也會假裝 VM 已順利運行。必須移除 `socket_server.cpp` 中立即寫回 `CMD_HANDSHAKE_COMPLETE` 的邏輯，改為觸發 `launch_vm.sh` 並由 `VsockServer` 在認證成功後異步通知。

2. **`launch_vm.sh` 行為推理**:
   - *前提 (Obs 1.1.2)*: `launch_vm.sh` 依賴 `/dev/kvm` 字符裝置。非 Linux/KVM 環境（如 macOS 開發環境）中 `/dev/kvm` 不存在。
   - *推論*: 在無 KVM 模擬或單元測試環境中，必須設定 `TEST_MODE=1` 才能繞過 `/dev/kvm` 檢查，使腳本回傳 0；而在非測試模式 (`TEST_MODE=0`) 下，若無 `/dev/kvm`，腳本必須正確拋出 `ERROR: KVMException` 並以 exit code 1 退出。

3. **測試與驗證鏈推理**:
   - *推論*: 為確保修正不破壞現有測試且能精確驗證 R1 Remediation，測試必須包含三層：
     1. **原生 C++ 單元測試**：驗證 `socket_server` 封包序列化、Vsock HMAC 認證與埠口綁定。
     2. **Shell 腳本測試**：驗證 `launch_vm.sh` 參數解析、`flock` 檔案鎖、記憶體檢查與 `TEST_MODE` 開關。
     3. **E2E / 整合測試**：驗證跨進程 Unix Domain Socket IPC、超時機制 (15s) 與真正握手後的狀態轉換。

---

## 3. Detail Requirements Analysis & Command Reference (詳細需求分析與指令說明)

### 3.1 原生單元測試 (`socket_server` / `linux_bridge`) 編譯與執行指令

#### A. 單獨編譯原生 C++ 單元測試 (Build Command)
在專案根目錄 (`/Users/iml1s/Documents/mine/aosp-linux`) 執行：

```bash
mkdir -p build_out/bin
clang++ -std=c++20 -Wall -Wextra -pthread -I. \
  system/linux_bridge/vsock_server.cpp \
  system/linux_bridge/hmac_auth.cpp \
  system/linux_bridge/vsock_framing.cpp \
  system/linux_bridge/socket_server.cpp \
  system/linux_bridge/tests/linux_bridge_test.cpp \
  -o build_out/bin/linux_bridge_test
```
*(備註：亦可使用 `g++ -std=c++20` 代替 `clang++`)*

#### B. 執行原生單元測試 (Execution Command)
```bash
./build_out/bin/linux_bridge_test
```

#### C. 編譯並執行的單行連動指令 (Combined Command)
```bash
mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test
```
*實測輸出結果*:
```text
=== Starting Native linux_bridge C++ Test Suite ===
[TEST] Socket Framing Packet Serialization... PASS
[TEST] Vsock Framing Packing & Unpacking... PASS
[TEST] SocketServer Lifecycle & Client Request Handling... PASS
[TEST] Socket Partial Read Loop & Payload Bounds Check... PASS
[TEST] VsockServer Handshake & Unauthenticated Binding Restriction... PASS
===================================================
NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY
```

#### D. Python E2E 測試框架執行指令 (E2E Test Runner Commands)
- **執行全套 E2E 測試**:
  ```bash
  python3 tests/e2e/runner.py
  ```
- **僅執行 M1 相關測試 (F-R1-001 ~ F-R1-005)**:
  ```bash
  python3 tests/e2e/runner.py --filter F-R1
  ```
- **執行測試包裝腳本並產生 JSON 報告**:
  ```bash
  ./tests/e2e/run_tests.sh --output-json tests/e2e/e2e_report.json
  ```

---

### 3.2 `launch_vm.sh` 測試指令與行為對比

#### A. 腳本語法檢查 (Syntax Check)
```bash
bash -n guest/scripts/launch_vm.sh
```

#### B. TEST_MODE 模式 (`TEST_MODE=1`)
```bash
TEST_MODE=1 bash guest/scripts/launch_vm.sh /data/misc/linux/vm_config.json testtoken123
```
- **預期行為/輸出**:
  - 繞過 `/dev/kvm` 設備節點存在性檢查。
  - 解析 `/data/misc/linux/vm_config.json`（若不存在則使用預設值：RAM=4096MB, CPUs=4, CID=3）。
  - 檢查 `/proc/meminfo` 的可用記憶體。
  - 組合 Kernel 命令列 `android_bridge.token=testtoken123`。
  - Exit Code 為 `0`。
- **實測輸出**:
  ```text
  [Launch Script] Starting VM launch procedure...
  [Launch Script] Launching crosvm Non-Protected VM (CID: 3, CPUs: 4, RAM: 4096MB)...
  [Launch Script] Kernel Params: console=ttyS0 root=/dev/vda ro init=/sbin/init android_bridge.token=testtoken123 panic=1 quiet
  [Launch Script] crosvm binary not in PATH (Simulated execution mode)
  [Launch Script] VM launch script completed successfully.
  ```

#### C. 非測試模式 (`TEST_MODE=0` 或未設定)
```bash
TEST_MODE=0 bash guest/scripts/launch_vm.sh /data/misc/linux/vm_config.json testtoken123
```
- **預期行為/輸出**:
  - 執行第 76 行的 `/dev/kvm` 檢查。在無 `/dev/kvm` 節點的環境（如 macOS 或未暴露 KVM 的 Docker 容器）中，輸出錯誤訊息並以 Exit Code `1` 終止。
- **實測輸出**:
  ```text
  [Launch Script] Starting VM launch procedure...
  ERROR: KVMException: /dev/kvm not found or insufficient permission
  ```

---

### 3.3 具體單元與整合測試策略 (Eliminating Fake Handshake & Real VM Launch Verification)

#### 3.3.1 問題核心 (Defect R1 Analysis)
在目前的 `socket_server.cpp` 中，收到 `CMD_VM_START` (0x0001) 時會立即寫回 `CMD_HANDSHAKE_COMPLETE` (0x0003)。這造成了假成功的漏洞。

#### 3.3.2 原生單元測試策略 (`system/linux_bridge/tests/linux_bridge_test.cpp`)

1. **修改 `testSocketServerLifecycle()` 斷言**:
   - **舊斷言**: 寫入 `CMD_VM_START` 後，預期立即 read 到 `CMD_HANDSHAKE_COMPLETE` (0x0003)。
   - **新斷言**: 寫入 `CMD_VM_START` 後，`socket_server` **不得**立即回傳 `CMD_HANDSHAKE_COMPLETE`；若在無 Vsock 握手狀況下立刻讀到 `CMD_HANDSHAKE_COMPLETE`，測試應宣告失敗（`assert(false)`）。

2. **新增進程派生驗證 (Process Spawning Unit Test)**:
   - 測試 `socket_server` 收到 `CMD_VM_START` 後，是否正確調用 `fork()` + `execve()` 執行 `launch_vm.sh` 並傳入生成之 Security Token。
   - 記錄並追蹤子進程 PID (`mGuestVmPid`)，並驗證當 `launch_vm.sh` 異常退出時（如 return code != 0），`socket_server` 能捕捉 `SIGCHLD` 並向 Host 回報錯誤。

3. **新增 Vsock HMAC 握手觸發 Handshake Complete 測試 (Deferred Handshake Test)**:
   - 模擬完整的啟動流程：
     1. Client 傳送 `CMD_VM_START` (0x0001)。
     2. `socket_server` 啟動 `launch_vm.sh` 並將狀態標記為 `WAITING_FOR_VSOCK_HANDSHAKE`。
     3. 測試 Harness 模擬 Guest (CID 3) 連線至 `VsockServer` Port 5000 傳送合法 HMAC-SHA256 Token 簽名。
     4. `VsockServer::processHandshake()` 驗證成功後，通知 `SocketServer`。
     5. `SocketServer` 此時才將 `CMD_HANDSHAKE_COMPLETE` (0x0003) 寫回 Unix Domain Socket Client。

4. **超時情境測試 (Boot Timeout Test)**:
   - 傳送 `CMD_VM_START` 後，模擬 Guest 死機或未傳送 Vsock 握手。
   - 驗證 `SocketServer` 在超時時間內絕不發送 `CMD_HANDSHAKE_COMPLETE`，從而允許 `LinuxManagerService` 的 15 秒定時器觸發 `STATE_ERROR`。

#### 3.3.3 Framework 整合測試策略 (`LinuxManagerService` & `LinuxBridgeService`)

1. **狀態轉移邏輯測試 (State Machine Verification)**:
   - 調用 `ILinuxManager.startVm()` -> 驗證狀態切換為 `STATE_STARTING`。
   - 驗證 `mBootTimeoutFuture` (15,000ms) 已被排程。
   - 在未收到 `onVmHandshakeCompleted()` 前，狀態必須保持為 `STATE_STARTING`，不得 premature 切換至 `STATE_RUNNING`。
   - 模擬 `onVmHandshakeCompleted()` 回傳 -> 驗證 `mBootTimeoutFuture` 被成功取消，狀態正式切換為 `STATE_RUNNING`。

2. **失敗與超時整合測試 (Boot Timeout Integration Test)**:
   - 調用 `startVm()` 後，不觸發握手回呼。
   - 推進時間（模擬 15 秒超時），驗證 `handleBootTimeout()` 被執行，狀態由 `STATE_STARTING` 切換至 `STATE_ERROR` (reason code 101)，並廣播狀態變更。

---

## 4. Caveats (注意事項與未覆蓋範圍)

1. **Host 系統 KVM 權限**: 在非 Linux/ARM64 硬體（例如 macOS 主機）上執行 `launch_vm.sh` 時，非測試模式 (`TEST_MODE=0`) 會因缺少 `/dev/kvm` 拋出錯誤 exit 1。在本地 CI / macOS 單元測試中，必須帶入 `TEST_MODE=1` 進行腳本路徑驗證。
2. **SELinux 權限標籤**: `socket_server.cpp` 在真實 AOSP 系統中派生 `launch_vm.sh` 或執行 `crosvm` 時，需確認 `linux_bridge.te` 具有 `exec` 與 `fork` 權限，避免在 Enforcing 模式下被 SELinux 攔截。

---

## 5. Conclusion (結論)

1. `socket_server` 與 `linux_bridge` 原生單元測試可經由 `clang++ -std=c++20` 指令乾淨編譯並執行於 `build_out/bin/linux_bridge_test`。
2. `launch_vm.sh` 具備完善的 `TEST_MODE` 開關與 `/dev/kvm` 檢查機制，可分別以 `TEST_MODE=1`（通過測試）與 `TEST_MODE=0`（回報 KVM 缺失）進行驗證。
3. 消除偽造握手回應的關鍵在於：移除 `socket_server.cpp` 對 `CMD_VM_START` 的立即回傳，改為派生 `launch_vm.sh` 並在 `VsockServer` 完成真實 HMAC 認證後異步發送 `CMD_HANDSHAKE_COMPLETE`。

---

## 6. Verification Method (獨立驗證方法)

1. **編譯與執行原生單元測試**:
   ```bash
   mkdir -p build_out/bin
   clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test
   ./build_out/bin/linux_bridge_test
   ```
   *通過條件*: 終端機顯示 `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` 且回傳碼為 0。

2. **驗證 `launch_vm.sh` 腳本測試**:
   - 測試模式：
     `TEST_MODE=1 bash guest/scripts/launch_vm.sh /data/misc/linux/vm_config.json testtoken123`
     *通過條件*: 終端機顯示 `VM launch script completed successfully.` 且回傳碼為 0。
   - 非測試模式（在無 `/dev/kvm` 的 Mac 環境）：
     `TEST_MODE=0 bash guest/scripts/launch_vm.sh /data/misc/linux/vm_config.json testtoken123`
     *通過條件*: 終端機顯示 `ERROR: KVMException: /dev/kvm not found or insufficient permission` 且回傳碼為 1。

3. **執行 Python E2E 測試框架**:
   ```bash
   python3 tests/e2e/runner.py --filter F-R1
   ```
   *通過條件*: 所有 Tier 1 / Tier 2 測試案例全數 PASS。
