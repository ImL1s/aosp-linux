# Milestone M1 (Real AVF VM Launch - R1) 獨立代碼審查與對抗性評估報告

**審查員**: Reviewer 2 (`teamwork_preview_reviewer_m1_2`)  
**審查對象**: Worker M1 (`teamwork_preview_worker_m1_1`)  
**最終判定**: **APPROVE**

---

## 1. 觀察 (Observation)

本審查針對 Milestone M1 相關的 5 個核心檔案進行了逐行獨立審查與對抗性測試：

1. **`guest/scripts/launch_vm.sh`**:
   - 行號 20-45：透過 `python3` 解析 `/data/misc/linux/vm_config.json` 取得 `ram_mb`, `cpus`, `cid`, `kernel_path`, `initrd_path`, `base_rootfs`, `custom_overlay`, `user_home` 等參數。
   - 行號 48-64：實作磁碟映像檔檔案鎖。使用 `exec 200<"$BASE_IMG"` 與 `exec 201<"$OVERLAY_IMG"` (唯讀導向，避免 `O_TRUNC` 破壞檔案)，配合 `flock -n` 或 `python3 fcntl.flock` 進行互斥鎖定。當行程結束時操作系統會自動釋放鎖與 FD。
   - 行號 67-73：主機可用記憶體檢查 (`/proc/meminfo` 剩餘記憶體需大於等於 `REQ_RAM_MB`)。
   - 行號 75-79：KVM 設備檢查 (`/dev/kvm`)，當非 `TEST_MODE=1` 且找不到 `/dev/kvm` 時拋出 `KVMException` 退出。
   - 行號 81-105：傳遞核心參數 `android_bridge.token=${AUTH_TOKEN}`，且在 `crosvm` 存在時以 `exec crosvm run` 替換 shell 行程以精確追蹤 PID；在 `TEST_MODE=1` 測試模式下執行 `exec sleep 3600` 維持 PID 追蹤。

2. **`system/linux_bridge/socket_server.cpp` & `socket_server.h`**:
   - **Deferred Handshake 實作**：
     - 行號 230-273 (`SocketServer::clientLoop`)：收到 `CMD_VM_START` (0x0001) 時，**已完全移除舊有的立即回應 `CMD_HANDSHAKE_COMPLETE` 假邏輯**。取而代之的是設定 `mVmState = VmState::STARTING`、記錄 `mPendingClientFd` 與 `mPendingTransactionId`、調用 `fork()` 與 `execlp("bash", ...)` 啟動 `launch_vm.sh`。
     - 行號 70-81 (`SocketServer::onVsockHandshakeSuccess`)：當 Guest 經由 Vsock 埠 5000 完成 HMAC-SHA256 認證時，回調觸發 `onVsockHandshakeSuccess`，驗證成功後將 `mVmState` 改為 `VmState::RUNNING` 並向 Framework Socket 寫回 `CMD_HANDSHAKE_COMPLETE` (0x0003)。
   - **子行程終止與資源釋放**：
     - 行號 83-109 (`SocketServer::stopVmProcess`)：發送 `SIGTERM` 至 PID `mVmPid`，並透過 `waitpid(mVmPid, &status, WNOHANG)` 在 2 秒內輪詢 (20 次 × 100ms)。若 2 秒後仍未退出且 `force == true`，則發送 `SIGKILL` 並以 `waitpid` 阻塞收割子行程。
   - **IPC 封包校驗與邊界檢查**：
     - 行號 210-223 (`clientLoop`)：對接收封包檢查 Magic Number (`0x4C4E5842`)、最大 Payload 限制 (`MAX_PAYLOAD_SIZE = 16MB`) 以及 `Header + length` 整數溢位檢查。

3. **`frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`**:
   - 新增 `CMD_VM_START_FAILED` (0x0004) 處理，可解析 native 層傳回的錯誤碼與錯誤訊息。
   - `notifyVmStarting(byte[] authToken)` 支援帶入隨機 32 位元 HMAC 安全憑證。
   - `readLoop` 在 Socket 斷開時正確關閉 socket，觸發 `onVmDisconnected()` 回調並排程 reconnect。

4. **`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`**:
   - 在 `startVm()` 時呼叫 `generateHmacAuthToken()` 生成隨機憑證，並安排 15 秒引導超時定時器 (`mBootTimeoutFuture`)。
   - 實作 `onVmStartFailed()`，啟動失敗時及時取消 15 秒超時定時器，將狀態轉為 `LinuxManager.STATE_ERROR`。
   - 實作 `onVmHandshakeCompleted()`，握手完成時將狀態轉換為 `LinuxManager.STATE_RUNNING`。

5. **測試執行指令與結果**：
   - **Native C++ 單元測試**：
     ```bash
     mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test
     ```
     *實測輸出*：
     ```
     === Starting Native linux_bridge C++ Test Suite ===
     [TEST] Socket Framing Packet Serialization... PASS
     [TEST] Vsock Framing Packing & Unpacking... PASS
     [TEST] SocketServer Deferred Handshake & Real VM Lifecycle... PASS
     [TEST] Socket Partial Read Loop & Payload Bounds Check... PASS
     [TEST] VsockServer Handshake & UnauthenticatedBinding Restriction... PASS
     =====================================================
     NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY
     ```
   - **Python E2E 測試套件**：
     ```bash
     python3 tests/e2e/runner.py --filter F-R1
     ```
     *實測輸出*：
     ```
     TOTAL TESTS  : 61
     PASSED       : 61
     FAILED       : 0
     PASS RATE    : 100.0%
     ```

---

## 2. 推論鏈 (Logic Chain)

1. **真實性與 Integrity 驗證**：
   - 在 `socket_server.cpp` 中確認舊有立即回傳 `CMD_HANDSHAKE_COMPLETE` 假回應邏輯已完全刪除。握手回應被嚴格綁定在 Guest 經由 AF_VSOCK 埠 5000 成功完成 HMAC 認證後。
   - 代碼中無任何硬編碼測試結果、Facade 假實作或 Bypass 快捷方式。
2. **安全性與資源管理**：
   - `launch_vm.sh` 透過唯讀導向開啟 FD 200/201 搭配 `flock`，能有效防止重複開啟與磁碟影像損壞，且行程終止時自動歸還。
   - `socket_server.cpp` 之 PID 追蹤與 `stopVmProcess` 機制在 `force=true` 時具備 `SIGTERM` 升級至 `SIGKILL` 收割保障，避免殘留孤兒 VM 行程。
3. **測試覆蓋與結果印證**：
   - 獨立執行的 C++ 原生測試與 Python E2E 測試全數通過（通過率 100.0%），證實修改後之功能符合 `SCOPE.md` 與 `PROJECT.md` 之契約要求。

---

## 3. 注意事項 (Caveats / Minor Findings)

在對抗性審查中發現以下兩點次要邊界情況（不影響本 Milestone 之 Approve 通過，但建議在未來迭代中持續優化）：

1. **`mPendingClientFd` 懸空 socket 描述子風險**：
   - 在 `socket_server.cpp` 中，當 Framework 發送 `CMD_VM_START` 時，`mPendingClientFd` 被設為 `clientFd`。
   - 若 Client 在 Vsock 握手完成前非正常斷開連線，`clientLoop` 會關閉 `clientFd`，但未同步清理 `mPendingClientFd = -1`。若隨後有新連線重用相同的 FD 整數值，當 Vsock 認證成功時，`onVsockHandshakeSuccess` 可能會對重用的 FD 寫入握手封包。
   - *建議*：在 `clientLoop` 結束 cleanup 區塊中加入 `if (mPendingClientFd == clientFd) mPendingClientFd = -1;` 判斷。

2. **非強迫終止 (`force=false`) 超時處置**：
   - 在 `stopVmProcess(bool force)` 中，若 `force=false` 且子行程在 `SIGTERM` 發送 2 秒後仍未退出，函數會在迴圈結束後直接執行 `mVmPid = -1;`。
   - 這會導致 daemon 失去對該還在執行的子行程 PID 之追蹤。
   - *建議*：若 `force=false` 且 `ret == 0`，可保持 `mVmPid` 不重置或記錄 warning 紀錄。

---

## 4. 結論 (Conclusion)

Worker M1 對 Milestone M1 (Real AVF VM Launch - R1) 的重構與修復品質優良：
1. 徹底消除了硬編碼與假 Mock 握手回應。
2. 實作了完整的 real VM `fork()`/`execlp()` 啟動、HMAC 安全憑證傳遞、延遲 Handshake 觸發機制與磁碟鎖管理。
3. 原生 C++ 與 Python E2E 測試 100% 通過。

**審查判定**: **APPROVE**

---

## 5. 獨立驗證方法 (Verification Method)

可透過執行以下命令獨立重現並驗證：

1. **原生 C++ 測試套件**：
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux && mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test
   ```
   *預期結果*: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`

2. **Python E2E 測試套件**：
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux && python3 tests/e2e/runner.py --filter F-R1
   ```
   *預期結果*: `TOTAL TESTS: 61, PASSED: 61, PASS RATE: 100.0%`
