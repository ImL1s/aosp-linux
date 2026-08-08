# Reviewer 1 Handoff Report — Milestone M1 (Real AVF VM Launch - R1)

## 1. 觀察 (Observation)
- **代碼變更與架構合規審查**：
  - **`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`**：
    - 在 `startVm()` 方法中，透過 `generateHmacAuthToken()` 生成 32 位元 `SecureRandom` 安全憑證，並經由 `mBridgeService.notifyVmStarting(authToken)` 將憑證傳遞至原生守護進程。
    - 設定 15 秒啟動超時定時器 `mBootTimeoutFuture`；當 VM 引導成功或失敗時正確取消定時器。
    - 於 `LinuxBridgeCallback` 實作 `onVmStartFailed(errorCode, message)`，當原生層回報啟動失敗時將狀態轉為 `LinuxManager.STATE_ERROR` 並向外部廣播。
    - 狀態變更鎖 `mStateLock` 正確保護 `mCurrentState` 與定時器生命週期，無併發線程競爭問題。
  - **`frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`**：
    - 新增 `CMD_VM_START_FAILED` (0x0004) 指令碼與解析邏輯。
    - 擴充 `notifyVmStarting(byte[] authToken)` 將 32 位元安全憑證封裝於 `CMD_VM_START` (0x0001) 封包 Payload 中發送。
  - **`system/linux_bridge/socket_server.h` / `socket_server.cpp`**：
    - 徹底移除舊有收到 `CMD_VM_START` 立即回應 `CMD_HANDSHAKE_COMPLETE` (0x0003) 的虛假邏輯。
    - 於 `SocketServer` 引入 `VmState` 狀態機 (`STOPPED`, `STARTING`, `RUNNING`)、`mVmPid` 行程識別碼與 `mPendingClientFd` 懸掛 Socket 記錄。
    - 收到 `CMD_VM_START` 後提取 32 位元憑證並註冊至 `VsockServer`，透過 `fork()` 與 `execlp("bash", ...)` 啟動 `guest/scripts/launch_vm.sh`，傳入 Hex 格式之安全憑證。
    - 延遲 Handshake 回應：僅當 Guest 端經由 AF_VSOCK 埠 5000 完成 HMAC-SHA256 驗證並觸發 `onVsockHandshakeSuccess(uint32_t cid)` 時，方將 `CMD_HANDSHAKE_COMPLETE` 寫回 `mPendingClientFd`，並將狀態轉為 `VmState::RUNNING`。
    - 實作 `stopVmProcess(bool force)`：向 `mVmPid` 發送 `SIGTERM`，經 2 秒 `waitpid` 輪詢；若逾時且 `force=true` 則發送 `SIGKILL` 強制終止，並重置 vsock 會話與 VM 狀態。
  - **`guest/scripts/launch_vm.sh`**：
    - 接受腳本參數 `$2` 安全憑證，並將其帶入 `CMDLINE` 核心參數 `android_bridge.token=${AUTH_TOKEN}`。
    - 使用唯讀重定向 `exec 200<"$BASE_IMG"` 搭配 `flock -n 200` 防止鏡像檔截斷與資源競爭。
    - 執行 `exec crosvm run ...` 替代子 Shell，確保 PID 追蹤之精確性。

- **誠實度與完整性檢驗 (Integrity Audit)**：
  - 未發現任何硬編碼測試結果、偽造日誌或 Bypass 邏輯。
  - 原生 C++ 單元測試與 Python E2E 測試皆為真實 Socket/IPC 與行程調用。

- **測試執行結果**：
  1. **原生 C++ 單元測試**：
     - 命令：`mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test`
     - 結果：`NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`（5/5 測試通過）。
  2. **Python E2E 測試套件**：
     - 命令：`python3 tests/e2e/runner.py --filter F-R1`
     - 結果：`TOTAL TESTS: 61, PASSED: 61, PASS RATE: 100.0%`（61/61 測試通過）。

## 2. 推論鏈 (Logic Chain)
1. **問題根因驗證**：原 Defect R1 之核心在於原生層收到 `CMD_VM_START` 後立即回傳 `CMD_HANDSHAKE_COMPLETE`，導致未真實啟動 VM 與進行安全認證。
2. **重構邏輯驗證**：
   - 經檢查 `socket_server.cpp`，收到 `0x0001` 後不再立即傳送 `0x0003`，而是紀錄 `mPendingClientFd` 並 `fork()` 執行 `launch_vm.sh`。
   - 憑證經由核心參數傳遞至 Guest，Guest Bridge Agent 與 Host VsockServer 進行 HMAC-SHA256 挑戰應答，成功後觸發 `onVsockHandshakeSuccess` 回調，方將 `CMD_HANDSHAKE_COMPLETE` (0x0003) 傳回 Framework。
3. **安全與線程安全性驗證**：
   - `LinuxManagerService` 與 `SocketServer` 皆採用嚴格鎖保護 (`mStateLock`, `mVmMutex`)，無 Lock inversion 或死鎖風險。
   - 資源釋放與子行程清理 (SIGTERM -> SIGKILL) 具備完整的 Waitpid 異常處理機制。
4. **測試覆蓋驗證**：單元測試與端到端測試均 100% 通過，符合項目設計規範。

## 3. 注意事項 (Caveats)
- 在 macOS 宿主測試環境中，`TEST_MODE=1` 模式模擬 `/dev/kvm` 與 `AF_VSOCK` 驅動，真實 Android/Linux 裝置上將直接綁定硬體 KVM 與原生 kernel AF_VSOCK 模組。
- 無其他未調查之潛在風險。

## 4. 結論 (Conclusion)
**審查判定 verdict: APPROVE**

Milestone M1 (Real AVF VM Launch - R1) 之代碼修正完全滿足正確性、完整性、穩健性、線程安全性與介面合約規範。虛假 Handshake 邏輯已徹底替換為真實 HMAC 權鑑與延遲 Handshake 觸發機制，子行程生命週期與超時處理完善，測試 100% 通過。

## 5. 獨立驗證方法 (Verification Method)
執行以下 Terminal 指令進行獨立驗證：
1. 原生 C++ 單元測試：
   `mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test`
2. Python E2E 測試：
   `python3 tests/e2e/runner.py --filter F-R1`
