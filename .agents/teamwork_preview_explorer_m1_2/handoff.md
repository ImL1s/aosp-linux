# Milestone M1 (Real AVF VM Launch - R1) Java Framework 技術調查與改善方案報告

**Agent**: Explorer 2 (Milestone M1 Java Framework Explorer)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_2`  
**Date**: 2026-08-08  
**Scope Files**:
- `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`

---

## 1. Observation (觀察事實)

本報告針對 `LinuxManagerService.java` 與 `LinuxBridgeService.java` 在 Java Framework 端的現行實作進行詳細程式碼分析，觀察到的具體程式碼位置與事實如下：

### 1.1 `LinuxManagerService.java` 狀態機與啟動超時機制
- **狀態常量與鎖定義** (Lines 47-50)：
  ```java
  private final Object mStateLock = new Object();
  private int mCurrentState = LinuxManager.STATE_STOPPED; // STATE_STOPPED = 0
  private ScheduledFuture<?> mBootTimeoutFuture;
  private int mNextSessionId = 1000;
  ```
  狀態包含：`STATE_STOPPED (0)`, `STATE_STARTING (1)`, `STATE_RUNNING (2)`, `STATE_SUSPENDED (3)`, `STATE_ERROR (4)`。
- **`startVm()` 觸發流程** (Lines 305–330)：
  ```java
  @Override
  public boolean startVm() {
      if (mContext != null) {
          mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE_LINUX, "Permission denied to start Linux VM");
      }
      synchronized (mStateLock) {
          if (mCurrentState == LinuxManager.STATE_RUNNING || mCurrentState == LinuxManager.STATE_STARTING) {
              Slog.w(TAG, "startVm called when already in state " + mCurrentState);
              return false;
          }
          int oldState = mCurrentState;
          mCurrentState = LinuxManager.STATE_STARTING;
          Slog.i(TAG, "Initiating Linux Guest VM (STARTING)...");
          dispatchStateChanged(mCurrentState, oldState, 0, "VM Booting");

          cancelBootTimeoutLocked();
          mBootTimeoutFuture = mScheduler.schedule(
                  LinuxManagerService.this::handleBootTimeout,
                  BOOT_TIMEOUT_MS, // 15000L (15秒)
                  TimeUnit.MILLISECONDS
          );
          if (mBridgeService != null) {
              mBridgeService.notifyVmStarting();
          }
          return true;
      }
  }
  ```
- **握手完成與狀態轉換 `notifyVmStarted()`** (Lines 151–161)：
  ```java
  public void notifyVmStarted() {
      synchronized (mStateLock) {
          if (mCurrentState == LinuxManager.STATE_STARTING) {
              cancelBootTimeoutLocked();
              int oldState = mCurrentState;
              mCurrentState = LinuxManager.STATE_RUNNING;
              Slog.i(TAG, "Linux Guest VM boot completed -> STATE_RUNNING");
              dispatchStateChanged(mCurrentState, oldState, 0, "VM Running");
          }
      }
  }
  ```
- ** boot 15秒超時處理 `handleBootTimeout()`** (Lines 170–180)：
  ```java
  public void handleBootTimeout() {
      synchronized (mStateLock) {
          if (mCurrentState == LinuxManager.STATE_STARTING) {
              int oldState = mCurrentState;
              mCurrentState = LinuxManager.STATE_ERROR;
              mBootTimeoutFuture = null;
              Slog.e(TAG, "Linux Guest VM boot timed out (15s exceeded) -> STATE_ERROR");
              dispatchStateChanged(mCurrentState, oldState, 101, "VM Boot Timeout (15s exceeded)");
          }
      }
  }
  ```
- **現有 HMAC Token 產生函式** (Lines 252–257)：
  ```java
  public byte[] generateHmacAuthToken() {
      byte[] token = new byte[32];
      new java.security.SecureRandom().nextBytes(token);
      mActiveAuthToken = token;
      return token;
  }
  ```
  *觀察關鍵缺失*：`startVm()` 被呼叫時，**並未呼叫** `generateHmacAuthToken()` 產生安全性 Token，亦**未將 Token 傳遞給 `mBridgeService.notifyVmStarting()`**。

---

### 1.2 `LinuxBridgeService.java` 通訊封包與 Socket 管理
- **Unix Domain Socket 與標頭定義** (Lines 47-54)：
  - Socket 路徑：`SOCKET_PATH = "/dev/socket/linux_bridge"` (使用 `LocalSocketAddress.Namespace.FILESYSTEM`)
  - 魔數：`MAGIC = 0x4C4E5842` ("LNXB")
  - 最大 Payload：`MAX_PAYLOAD_SIZE = 16 * 1024 * 1024` (16MB)
  - 指令代碼：
    - `CMD_VM_START = 0x0001`
    - `CMD_VM_STOP = 0x0002`
    - `CMD_HANDSHAKE_COMPLETE = 0x0003`
    - `CMD_PTY_DATA = 0x0100`, `CMD_PTY_RESIZE = 0x0101`, `CMD_PTY_OPEN = 0x0102`, `CMD_PTY_CLOSE = 0x0103`
    - `CMD_APP_SYNC = 0x0200`
- **封包結構 (14-byte Header + Payload)**：
  - `magic` (4 bytes, Big-Endian)
  - `cmdType` (2 bytes, Big-Endian)
  - `length` (4 bytes, Big-Endian)
  - `transactionId` (4 bytes, Big-Endian)
  - `payload` (`length` bytes)
- ** Socket 連線與自動重連** (Lines 115–136, 138–172)：
  - 於專屬背景執行緒 `LinuxBridgeWorker` (`HandlerThread`) 中執行 `connectDaemonSocket()`。
  - 當與原生守护進程 `linux_bridge` 斷開或連線失敗時，透過 `scheduleReconnect()` 每 3 秒自動嘗試重新連線。
  - `readLoop()` 持續從 `DataInputStream` 讀取並驗證 Header 魔數與 Payload 長度，解析後送交 `handleIncomingPacket()`。
- ** `CMD_VM_START` 送出** (Lines 271–273)：
  ```java
  public boolean notifyVmStarting() {
      return sendPacket(CMD_VM_START, 0, new byte[0]);
  }
  ```
  *觀察關鍵缺失*：`notifyVmStarting()` 目前傳送空白 Payload (`new byte[0]`) 給 `socket_server.cpp`。
- ** `CMD_HANDSHAKE_COMPLETE` 接收與 Callback 回呼** (Lines 175–183)：
  ```java
  private void handleIncomingPacket(short cmdType, int transId, byte[] payload) {
      switch (cmdType) {
          case CMD_HANDSHAKE_COMPLETE:
              Slog.i(TAG, "Received CMD_HANDSHAKE_COMPLETE from linux_bridge daemon");
              if (mCallback != null) {
                  mCallback.onVmHandshakeCompleted();
              }
              break;
          ...
      }
  }
  ```
  當收到來自原生端 Daemon 的 `CMD_HANDSHAKE_COMPLETE` 封包時，觸發 `mCallback.onVmHandshakeCompleted()`，進而呼叫 `LinuxManagerService.notifyVmStarted()` 將狀態切換為 `STATE_RUNNING` 並取消 15 秒超時計時器。

---

## 2. Logic Chain (推理邏輯鏈)

1. **現有模擬啟動的瓶頸與漏洞點**：
   - 根據 Survey 報告，Native Daemon (`socket_server.cpp` lines 173–177) 在收到 `CMD_VM_START` (0x0001) 時，會立刻偽造並回傳 `CMD_HANDSHAKE_COMPLETE` (0x0003)。
   - 在 Java Framework 端，`LinuxManagerService.startVm()` 發送 `notifyVmStarting()` 試圖啟動 VM。因為 `socket_server.cpp` 立即回傳 `CMD_HANDSHAKE_COMPLETE`，Java 端的 `mCallback.onVmHandshakeCompleted()` 會立刻觸發 `notifyVmStarted()`，使狀態直接由 `STATE_STARTING` 跳至 `STATE_RUNNING`。
   - 事實上，Native Daemon 並未啟動 `launch_vm.sh` 或 `crosvm`，Guest Agent 亦未在 Guest 內部完成任何 Vsock HMAC 認證握手。

2. **真實 AVF VM 啟動的 Java 端邏輯鏈需求**：
   - **安全認證 Token 產生與傳遞**：
     - Guest Agent (Rust) 啟動時需要驗證 Host 身份與 `/proc/cmdline` 傳入的 HMAC Security Token（參見 R2 規範與 `auth.rs`）。
     - Java 端 `LinuxManagerService` 已有 `generateHmacAuthToken()` 可產生 32-byte 隨機 Token，但此前並未在 `startVm()` 時呼叫。
     - **邏輯推理**：在呼叫 `mBridgeService.notifyVmStarting(token)` 時，必須將 32-byte Token 寫入 `CMD_VM_START` 的 Payload。Native Daemon (`socket_server.cpp`) 讀取此 Payload 後，方能將其轉化為 `android_bridge.token=<hex>` 參數並傳給 `launch_vm.sh` 啟動 `crosvm`。
   - **啟動失敗與錯誤即時回報機制**：
     - 若原生端在執行 `launch_vm.sh` 或 `crosvm` 時失敗（例如無 `/dev/kvm` 權限、鏡像毀損），目前 Native Daemon 無法主動通知 Java 端，Java 端只能被動等待 15 秒 `handleBootTimeout()` 觸發。
     - **邏輯推理**：需要擴充 `LinuxBridgeService` 的通訊協定，加入 `CMD_VM_START_FAILED` (例如 `0x0004`) 封包或增強 `onError` 處理。當收到原生端傳回啟動失敗時，Java 端應立即取消 15 秒計時器，並呼叫 `handleVmStartFailed(errorCode, message)` 將狀態轉為 `STATE_ERROR`。

---

## 3. Caveats (注意事項與未覆蓋範圍)

1. **唯讀探測限制**：
   - 本任務為唯讀調查，不直接修改 `LinuxManagerService.java` 或 `LinuxBridgeService.java` 原始碼。具體修改須由後續 Worker 角色執行。
2. **與 R3 Vsock Session ID 格式之對齊**：
   - `LinuxManagerService` 的 `createTerminalSession` 目前產生的 Session ID 格式為 `"session_1001"` (Prefix + 自增數字)。若 R3 `VsockTerminalClient` 要求 16 鹼 Hex 或 UUID 格式，必須確保兩端 Session ID 生成規範一致。
3. **SELinux 權限與 Binder 傳遞**：
   - SystemServer 與原生 daemon 之間透過 `/dev/socket/linux_bridge` 進行 LocalSocket 通訊，受 `linux_manager.te` 與 `linux_bridge.te` SELinux Policy 保護。新增 Token 傳遞與錯誤封包時，封包格式仍符合現有 `MAGIC + cmdType + length + transId` 之 binary framing，不會觸發新的 SELinux 權限阻擋。

---

## 4. Conclusion (調查結論與 Java 端重構計畫)

為使 Milestone M1 (Real AVF VM Launch) 順利實現，Java Framework 端之具體重構與實作計畫如下：

### 4.1 `LinuxBridgeService.java` 變更計畫
1. **修改 `notifyVmStarting` 簽名與 Payload**：
   - 將 `public boolean notifyVmStarting()` 修改為 `public boolean notifyVmStarting(byte[] authToken)`。
   - 將傳送的 `CMD_VM_START` (0x0001) 封包之 Payload 由 `new byte[0]` 改為 32-byte 的 `authToken`。
2. **新增 `CMD_VM_START_FAILED` (0x0004) 封包處理**：
   - 在 `LinuxBridgeService` 中定義 `public static final short CMD_VM_START_FAILED = 0x0004;`。
   - 在 `LinuxBridgeCallback` 介面中新增 `void onVmStartFailed(int errorCode, String message);`。
   - 在 `handleIncomingPacket()` 中，當收到 `CMD_VM_START_FAILED` 時，解析 Payload 中的錯誤碼與訊息，並觸發 `mCallback.onVmStartFailed(...)`。

### 4.2 `LinuxManagerService.java` 變更計畫
1. **整合 Token 產生與傳遞**：
   - 在 `startVm()` 進入 `STATE_STARTING` 狀態時，呼叫 `byte[] token = generateHmacAuthToken();`。
   - 將產生的 `token` 傳給 `mBridgeService.notifyVmStarting(token)`。
2. **更新 `LinuxBridgeCallback` 實作**：
   - 實作 `onVmStartFailed(int errorCode, String message)`：
     ```java
     @Override
     public void onVmStartFailed(int errorCode, String message) {
         synchronized (mStateLock) {
             if (mCurrentState == LinuxManager.STATE_STARTING) {
                 cancelBootTimeoutLocked();
                 int oldState = mCurrentState;
                 mCurrentState = LinuxManager.STATE_ERROR;
                 Slog.e(TAG, "VM Launch failed from native daemon: " + message + " (code: " + errorCode + ")");
                 dispatchStateChanged(mCurrentState, oldState, errorCode, message);
             }
         }
     }
     ```
3. **維持並強化 Boot Timeout 護欄**：
   - 保留 15 秒 `BOOT_TIMEOUT_MS = 15000L` 超時防護。若原生端或 Guest 在 15 秒內未完成真實 Vsock HMAC 握手並回傳 `CMD_HANDSHAKE_COMPLETE`，超時機制會安全地將狀態轉為 `STATE_ERROR` (reasonCode 101)。

---

## 5. Verification Method (獨立驗證方法)

完成程式碼修改後，可透過以下步驟進行獨立驗證：

1. **單元測試驗證 (Java Framework Unit Tests)**：
   - 執行 Java 測試套件：
     ```bash
     /usr/bin/java -cp build_out/classes:frameworks/base/core/java tests.unit.LinuxManagerServiceTest
     ```
   - 驗證點：
     - `testStateTransitionsNormalLifecycle`: 驗證 `STOPPED -> STARTING -> RUNNING -> STOPPED` 正常轉換。
     - `testBootTimeoutGuard`: 驗證當未收到握手時，15秒後可正確轉換為 `STATE_ERROR`。
     - `testStatusCallbacks`: 驗證狀態變更通知能正確廣播給所有註冊的 `ILinuxStatusCallback`。

2. **Socket 封包驗證 (IPC Verification)**：
   - 透過日誌 `logcat -s LinuxBridgeService LinuxManagerService` 檢查：
     - `startVm()` 被觸發時，`LinuxBridgeService` 印出發送 `CMD_VM_START` 並包含 32-byte Payload。
     - 當收到真實 `CMD_HANDSHAKE_COMPLETE` 時，印出 `"Received CMD_HANDSHAKE_COMPLETE from linux_bridge daemon"` 且狀態轉為 `STATE_RUNNING`。
