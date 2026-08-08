# Worker M1 (Real AVF VM Launch - R1) 任務完成報告

## 1. 觀察 (Observation)
- **硬編碼問題修正與延遲 Handshake 實作**：
  - 舊有 `system/linux_bridge/socket_server.cpp` 在收到 `CMD_VM_START` (0x0001) 時會立即回傳 `CMD_HANDSHAKE_COMPLETE` (0x0003)。
  - 經重構後，移除立即回應邏輯。在 `SocketServer::handlePacket()` 中收到 `CMD_VM_START` 後，產生/提取 32 位元 HMAC 憑證，註冊 `VsockServer` 憑證與密鑰，並透過 `fork()`/`execlp()` 啟動 `guest/scripts/launch_vm.sh`（帶入 `android_bridge.token=<HEX_TOKEN>` 核心參數）。
  - 當 Guest 端經由 AF_VSOCK 埠 5000 完成 HMAC-SHA256 驗證時，觸發 `SocketServer::onVsockHandshakeSuccess(uint32_t cid)` 回調函式，方將 `CMD_HANDSHAKE_COMPLETE` 寫回 Framework Socket (`mPendingClientFd`)。
- **子行程生命週期與終止管理**：
  - 更新 `guest/scripts/launch_vm.sh` 以 `exec crosvm run` 替代原本子 shell 執行，使原生守護進程能精確追蹤 `crosvm` 真正的 PID (`mVmPid`)。
  - 在 `socket_server.cpp` 的 `stopVmProcess(bool force)` 中，實作 `SIGTERM` 觸發與 2 秒超時未退出時發送 `SIGKILL` 的強烈終止機制，並重置 Vsock 會話。
- **Java Framework 服務層對接**：
  - 更新 `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`：
    - 新增 `public static final short CMD_VM_START_FAILED = 0x0004;` 錯誤回應指令。
    - 擴充 `notifyVmStarting(byte[] authToken)` 支援帶入 HMAC 安全憑證。
    - 於 `LinuxBridgeCallback` 介面新增 `default void onVmStartFailed(int errorCode, String message)` 回調。
  - 更新 `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`：
    - 在 `startVm()` 時呼叫 `generateHmacAuthToken()` 生成隨機憑證並傳入 `notifyVmStarting(authToken)`。
    - 實作 `onVmStartFailed` 回調，當原生層啟動失敗時，取消引導超時定時器並將狀態轉為 `LinuxManager.STATE_ERROR`。
- **單元測試與 E2E 測試驗證**：
  - 更新 `system/linux_bridge/tests/linux_bridge_test.cpp` 涵蓋延遲 Handshake 觸發、`TEST_MODE=1` 行程啟動與 `CMD_VM_STOP` 子行程終止。
  - 原生 C++ 測試套件 5 項測試全數通過：`NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`。
  - Python E2E 測試套件（`python3 tests/e2e/runner.py --filter F-R1`）共 61 項測試，通過 61 項，通過率 100.0% (`PASS RATE: 100.0%`)。

## 2. 推論鏈 (Logic Chain)
1. **確信性與規範依從性**：
   - 根據 `SCOPE.md` 與 `DISPATCH.md` 規範，R1 的核心目標為「真實引導與 HMAC 認證鎖定」，不可保留假立即完成 Handshake。
2. **端到端流程關聯**：
   - Host `LinuxManagerService` -> `LinuxBridgeService` (傳送帶有 32-byte security token 的 `CMD_VM_START`) -> `SocketServer` (記錄 `mPendingClientFd`，`fork` 執行 `launch_vm.sh`) -> `launch_vm.sh` (傳遞 `android_bridge.token` 給 Guest) -> Guest Bridge Agent (透過 vsock port 5000 發起 HMAC Handshake) -> `VsockServer` (驗證成功) -> 回調 `SocketServer::onVsockHandshakeSuccess()` -> 回傳 `CMD_HANDSHAKE_COMPLETE` 至 Framework。
3. **無鎖/鎖保護與狀態轉換**：
   - `SocketServer` 引入 `VmState` (`STOPPED`, `STARTING`, `RUNNING`)，確保非同步啟動與停止請求之線程安全與正確狀態轉移。

## 3. 注意事項 (Caveats)
- 在 macOS 宿主環境中，AF_VSOCK 由 `VsockServer` 模擬層處理，真實 Linux/Android 設備運行時將直接綁定 Linux 原生 `AF_VSOCK` socket。
- `TEST_MODE=1` 模式提供測試框架在無視 `/dev/kvm` 硬體虛擬化環境下進行完整 IPC 流程驗證。

## 4. 結論 (Conclusion)
Milestone M1 (Real AVF VM Launch - R1) 的所有原生 C++ 與 Java 層功能修改已完成，代碼符合 minimal change 原則，無任何硬編碼或假測試結果。原生測試與 Python E2E 測試全數 100% 通過，已可順利交付審查。

## 5. 獨立驗證方法 (Verification Method)
執行以下指令可重現並驗證本任務成果：

1. **編譯並執行原生 C++ 單元測試**：
   ```bash
   ssh localhost "cd /Users/iml1s/Documents/mine/aosp-linux && mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test"
   ```
   *預期結果*：顯示 `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`。

2. **驗證 Python E2E 測試套件**：
   ```bash
   ssh localhost "cd /Users/iml1s/Documents/mine/aosp-linux && python3 tests/e2e/runner.py --filter F-R1"
   ```
   *預期結果*：61 項測試全數通過（`TOTAL TESTS: 61, PASSED: 61, PASS RATE: 100.0%`）。
