# Forensic Audit Report — Milestone M1 (Real AVF VM Launch - R1)

**Work Product**: Milestone M1 (Real AVF VM Launch - R1) Implementation
**Profile**: General Project
**Integrity Mode**: Development
**Verdict**: CLEAN

---

## Executive Summary
A comprehensive forensic integrity audit was conducted on Milestone M1 (Real AVF VM Launch - R1). The audit evaluated the AOSP Framework Java services, host native `linux_bridge` C++ daemon, VM launch script, and associated unit/E2E test suites. All forensic checks confirmed that the implementation is authentic, replacing previous simulated shortcuts with genuine process execution (`fork`/`exec`), deferred vsock HMAC authentication, PID lifecycle tracking, and proper error handling. Both native C++ unit tests and Python E2E test suites pass with 100% success.

---

## Phase Results

### Phase 1: Source Code & AST Integrity Analysis
- **Check 1: Hardcoded test results / Fake response shortcuts**: **PASS**
  - In `system/linux_bridge/socket_server.cpp` (lines 230-273), receiving `CMD_VM_START` (0x0001) no longer returns an immediate fake `CMD_HANDSHAKE_COMPLETE` (0x0003). The response is deferred until `onVsockHandshakeSuccess(uint32_t cid)` (lines 70-81) is invoked upon successful guest vsock HMAC authentication.
  - In `LinuxManagerService.java` (lines 401-427), `startVm()` generates a 32-byte cryptographically secure random token (`SecureRandom`) and passes it to `notifyVmStarting(authToken)` without hardcoding token values or state transitions.

- **Check 2: Facade & Dummy Implementation Detection**: **PASS**
  - In `system/linux_bridge/socket_server.cpp` (lines 256-272), real process spawning via `fork()` and `execlp("bash", ...)` is performed to execute `guest/scripts/launch_vm.sh` with parameters `vm_config.json` and `<tokenHex>`.
  - In `system/linux_bridge/socket_server.cpp` (lines 83-109), `stopVmProcess(bool force)` manages the process lifecycle with `kill(mVmPid, SIGTERM)`, `waitpid` polling up to 2 seconds, and conditional `SIGKILL` escalation.
  - In `guest/scripts/launch_vm.sh` (lines 47-64), real file locking (`flock`) on base rootfs and overlay images is enforced, host RAM availability is checked via `/proc/meminfo` (lines 66-73), and `exec crosvm run` (lines 88-99) is called to execute the guest VM.

- **Check 3: Pre-populated Artifact & Test Bypass Detection**: **PASS**
  - No pre-populated test output logs or fake result files were found predating test execution.
  - No assertion bypasses, artificial passes, or commented-out test validations exist in `system/linux_bridge/tests/linux_bridge_test.cpp` or `tests/e2e/tier1_feature_coverage/test_m1_tier1.py`.

### Phase 2: Behavioral & Runtime Verification
- **Check 4: Native C++ Unit Test Suite Execution**: **PASS**
  - Executed command: `mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test`
  - Output: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` (5/5 tests passed).

- **Check 5: Python E2E Test Suite Execution**: **PASS**
  - Executed command: `python3 tests/e2e/runner.py --filter F-R1`
  - Output: `TOTAL TESTS: 61, PASSED: 61, FAILED: 0, PASS RATE: 100.0%`.

---

## 1. 觀察 (Observation)

1. **socket_server.cpp 延遲 Handshake 與真實 Process Forking**:
   - File: `system/linux_bridge/socket_server.cpp` lines 70-81:
     ```cpp
     void SocketServer::onVsockHandshakeSuccess(uint32_t cid) {
         (void)cid;
         std::lock_guard<std::mutex> lock(mVmMutex);
         if (mVmState == VmState::STARTING && mPendingClientFd >= 0) {
             mVmState = VmState::RUNNING;
             std::vector<uint8_t> response = serializePacket(0x0003, mPendingTransactionId, {});
             write(mPendingClientFd, response.data(), response.size());
             std::cout << "[linux_bridge] Real VM Vsock handshake complete. CMD_HANDSHAKE_COMPLETE sent to framework." << std::endl;
             mPendingClientFd = -1;
             mPendingTransactionId = 0;
         }
     }
     ```
   - File: `system/linux_bridge/socket_server.cpp` lines 256-272:
     ```cpp
     pid_t pid = fork();
     if (pid < 0) {
         ...
     } else if (pid == 0) {
         const char* scriptPath = "guest/scripts/launch_vm.sh";
         const char* configPath = "/data/misc/linux/vm_config.json";
         execlp("bash", "bash", scriptPath, configPath, tokenHex.c_str(), nullptr);
         _exit(127);
     } else {
         mVmPid = pid;
         std::cout << "[linux_bridge] Spawned VM launch script PID: " << mVmPid << std::endl;
     }
     ```

2. **LinuxManagerService.java 與 LinuxBridgeService.java 錯誤傳遞與憑證處理**:
   - File: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` lines 108-118:
     ```java
     @Override
     public void onVmStartFailed(int errorCode, String message) {
         synchronized (mStateLock) {
             if (mCurrentState == LinuxManager.STATE_STARTING) {
                 cancelBootTimeoutLocked();
                 int oldState = mCurrentState;
                 mCurrentState = LinuxManager.STATE_ERROR;
                 Slog.e(TAG, "VM Launch failed from native daemon: " + message + " (code: " + errorCode + ")");
                 dispatchStateChanged(mCurrentState, oldState, errorCode > 0 ? errorCode : 100, message != null ? message : "VM Launch Failed");
             }
         }
     }
     ```
   - File: `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java` lines 178-194:
     ```java
     case CMD_VM_START_FAILED:
         Slog.e(TAG, "Received CMD_VM_START_FAILED from linux_bridge daemon");
         int errCode = 100;
         String errMsg = "VM Launch Failed";
         if (payload != null && payload.length >= 4) {
             ByteBuffer buf = ByteBuffer.wrap(payload);
             errCode = buf.getInt();
             if (buf.remaining() > 0) {
                 byte[] msgBytes = new byte[buf.remaining()];
                 buf.get(msgBytes);
                 errMsg = new String(msgBytes, StandardCharsets.UTF_8);
             }
         }
         if (mCallback != null) {
             mCallback.onVmStartFailed(errCode, errMsg);
         }
         break;
     ```

3. **launch_vm.sh Exec crosvm Run 與 Disk Locking**:
   - File: `guest/scripts/launch_vm.sh` lines 48-55:
     ```bash
     if [ -f "$BASE_IMG" ]; then
         exec 200<"$BASE_IMG"
         if command -v flock >/dev/null 2>&1; then
             flock -n 200 || { echo "ERROR: ResourceBusy: base_rootfs.img is locked by another process" >&2; exit 3; }
         else
             python3 -c 'import fcntl; fcntl.flock(200, fcntl.LOCK_EX | fcntl.LOCK_NB)' 2>/dev/null || { echo "ERROR: ResourceBusy: base_rootfs.img is locked by another process" >&2; exit 3; }
         fi
     fi
     ```
   - File: `guest/scripts/launch_vm.sh` lines 88-100:
     ```bash
     if command -v crosvm >/dev/null 2>&1; then
         exec crosvm run \
           --cid "$CID" \
           --cpus "$CPUS" \
           --mem "$REQ_RAM_MB" \
           --kernel "$KERNEL_PATH" \
           --initrd "$INITRD_PATH" \
           --params "${CMDLINE}" \
           --shared-dir "/data/media/0/LinuxShared:linux_shared:type=fs:cache=always:timeout=1" \
           --rodisk "$BASE_IMG" \
           --rwdisk "$OVERLAY_IMG" \
           --rwdisk "$HOME_MAPPER"
     ```

4. **測試執行結果 Verification Command Tool Output**:
   - Native C++ test: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`
   - Python E2E test: `TOTAL TESTS: 61, PASSED: 61, FAILED: 0, PASS RATE: 100.0%`

---

## 2. 推論鏈 (Logic Chain)

1. **依據 1（靜態代碼檢查）**: 舊有 `socket_server.cpp` 在收到 `CMD_VM_START` (0x0001) 時直接寫回 `CMD_HANDSHAKE_COMPLETE` (0x0003) 的硬編碼造假行為已被完整移除。
2. **依據 2（IPC 與認證邏輯鏈條）**: 新邏輯改為將 Framework 送入的 32-byte HMAC 憑證註冊至 `VsockServer`，並透過 `fork()`/`execlp()` 啟動 `launch_vm.sh`。當 Guest 端經由 AF_VSOCK 埠 5000 完成 HMAC 驗證後，才會觸發 `onVsockHandshakeSuccess` 並回傳 `CMD_HANDSHAKE_COMPLETE` 给 Framework。
3. **依據 3（錯誤傳播與超播處理）**: 若原生進程 fork 失敗或 VM State 不符，`socket_server.cpp` 會回傳 `CMD_VM_START_FAILED` (0x0004)，`LinuxBridgeService` 解析後回調 `LinuxManagerService.onVmStartFailed()`，取消 15 秒啟動超時並將狀態設定為 `STATE_ERROR`。
4. **依據 4（動態執行與測試驗證）**: 執行原生 C++ 測試與 Python E2E 測試全數通過（61/61），未發現任何硬編碼結果、跳過斷言或模擬迴避行為。
5. **結論推導**: 符合 Development Mode 下的真實實作要求，確認 Milestone M1 (Real AVF VM Launch - R1) 實作真實、完整且無誠信違規事項。

---

## 3. 注意事項 (Caveats)

- **環境差異說明**: 在無真實 ARM64 AVF / crosvm 硬體與 `/dev/kvm` 節點的 macOS/CI 宿主環境中，`launch_vm.sh` 透過 `TEST_MODE=1` 模式執行 `exec sleep 3600` 進行行程 PID 生命週期追蹤與 Vsock 模擬對接，此為跨平台測試標準做法，非誠信違規行為。
- No other caveats.

---

## 4. 結論 (Conclusion)

Milestone M1 (Real AVF VM Launch - R1) 的實作符合所有設計規範與安全要求，無硬編碼測試結果、無 Dummy Facade、無測試繞過。

**Verdict**: **CLEAN**

---

## 5. 獨立驗證方法 (Verification Method)

可透過執行以下命令獨立驗證本審計結果：

1. **原生 C++ 單元測試**：
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux
   mkdir -p build_out/bin
   clang++ -std=c++20 -Wall -Wextra -pthread -I. \
     system/linux_bridge/vsock_server.cpp \
     system/linux_bridge/hmac_auth.cpp \
     system/linux_bridge/vsock_framing.cpp \
     system/linux_bridge/socket_server.cpp \
     system/linux_bridge/tests/linux_bridge_test.cpp \
     -o build_out/bin/linux_bridge_test
   ./build_out/bin/linux_bridge_test
   ```
   *預期結果*: 輸出 `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`。

2. **Python E2E 測試套件**：
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux
   python3 tests/e2e/runner.py --filter F-R1
   ```
   *預期結果*: 輸出 `TOTAL TESTS: 61, PASSED: 61, FAILED: 0, PASS RATE: 100.0%`。
