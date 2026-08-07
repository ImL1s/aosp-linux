# 技術分析報告 — VsockTerminalClient 於 TerminalView.java 之完整串接策略 (M3 Iteration 3 Remediation)

## 1. 問題背景與現狀診斷 (Context & Background)

在 Milestone M3 (Native Touch Terminal & IME) 的 Iteration 2 Gate Review 中，審查員 `reviewer_m3_2_r2` 指出了一處 **門面實作 (Facade Implementation / Integrity Violation)** 違規：

- **當前問題**：`TerminalView.java` (Line 52) 雖然宣告並實例化了 `mVsockClient = new VsockTerminalClient()`，但在 `sendBytes()`、`sendFrame()` 以及 `sendResize()` 方法中（Line 95–111），僅利用 `VsockPtyFramer.serializeFrame(...)` 序列化封包並經由 `Log.d` 印出日誌後即拋棄封包，完全未呼叫 `mVsockClient.sendFrame(frame)` 透過真實 AF_VSOCK Socket 進行網路傳送。此外，`mVsockClient.connect(...)` 在 `TerminalView` 的生命週期中完全未被調用。
- **改進目標**：
  1. 在 `TerminalView.java` 的初始化/ View 附加生命週期（如 `onAttachedToWindow()` 或 View Init）中正確調用 `mVsockClient.connect(HOST_CID, VSOCK_PORT_5001)` (或相應的 `(GUEST_CID, mSessionId, listener)`)。
  2. 在 `sendBytes()`、`sendFrame()` 與 `sendResize()` 中，將序列化完成的二進制 Framing 封包真正遞交給 `mVsockClient.sendFrame(frame)` 傳送，徹底消除「僅 Log 不傳送」的門面行為。
  3. 設置從 AF_VSOCK Port 5001 接收到的 PTY 數據監聽器 (`TerminalStreamListener`)，自動將接收到的 byte 串流寫入 `mVTermParser.writeInput(data)` 並觸發 `postInvalidate()` 重繪 UI，形成閉環二進制雙向傳輸。

---

## 2. 源碼觀察與精確定位 (Code Inspection & Evidence)

### 2.1 `TerminalView.java` (傳送端現狀)
- **檔案路徑**：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
- **關鍵程式碼觀察**：
  ```java
  // Line 52:
  mVsockClient = new VsockTerminalClient();

  // Line 95-111 (僅 Log，未 Send):
  @Override
  public void sendBytes(byte[] bytes) {
      if (bytes == null || bytes.length == 0) return;
      byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
      Log.d(TAG, "Sent PTY Frame over Port 5001: " + frame.length + " bytes");
  }

  @Override
  public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {
      byte[] frame = VsockPtyFramer.serializeFrame(sessionId, type, payload);
      Log.d(TAG, "Sent PTY Frame over Port 5001: " + frame.length + " bytes");
  }

  @Override
  public void sendResize(byte[] sessionId, int cols, int rows) {
      byte[] frame = VsockPtyFramer.serializeResizeFrame(sessionId, cols, rows);
      Log.d(TAG, "Sent Resize Frame over Port 5001: " + frame.length + " bytes");
  }
  ```
- **診斷**：`sendBytes`、`sendFrame`、`sendResize` 將二進制封包序列化後直接留在區域變數 `frame` 中，並未調用 `mVsockClient.sendFrame(frame)`。`connect(...)` 與 `close()` 方法亦未與 View 生命週期連結。

### 2.2 `VsockTerminalClient.java` ( Socket 連線類別現狀)
- **檔案路徑**：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
- **關鍵程式碼觀察**：
  ```java
  // Line 17-18:
  private static final int AF_VSOCK = 40;
  private static final int VPORT_PTY = 5001;

  // Line 31-69:
  public synchronized void connect(int guestCid, byte[] sessionId, TerminalStreamListener listener) throws IOException {
      try {
          mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
          mInputStream = new FileInputStream(mSocketFd);
          mOutputStream = new FileOutputStream(mSocketFd);
          mRunning = true;
          ...
      } catch (ErrnoException e) {
          throw new IOException("Failed to open AF_VSOCK socket to CID " + guestCid + ":" + VPORT_PTY, e);
      }
  }

  // Line 71-76:
  public synchronized void sendFrame(byte[] frameBytes) throws IOException {
      if (mOutputStream != null) {
          mOutputStream.write(frameBytes);
          mOutputStream.flush();
      }
  }

  // Line 78-85:
  public synchronized void close() { ... }
  ```
- **診斷**：`VsockTerminalClient` 提供了 `connect(guestCid, sessionId, listener)`、`sendFrame(frameBytes)` 以及 `close()`。但在 `TerminalView.java` 中這些方法皆未被對接。

---

## 3. 完整串接策略與架構設計 (Technical Remediation Strategy)

### 3.1 Vsock 連線與 View 生命週期繫結 (Lifecycle Integration)

在 `TerminalView.java` 中引入 AF_VSOCK 埠與 CID 定義，並實作 View 生命週期連線管理：

1. **常數定義**：
   ```java
   public static final int HOST_CID = 2; // 或 GUEST_CID = 3
   public static final int VSOCK_PORT_5001 = 5001;
   ```
2. **連線重載與相容性支援**：
   在 `VsockTerminalClient.java` 中可新增多載方法（Overload），以同時支援 `connect(int hostCid, int port)` 及原有之 `connect(int guestCid, byte[] sessionId, TerminalStreamListener listener)`：
   ```java
   public synchronized void connect(int cid, int port) throws IOException {
       connect(cid, mSessionId, null);
   }
   ```
3. **`TerminalView.java` 生命週期鉤子**：
   - 於 `onAttachedToWindow()` 觸發 `connectVsock()`。
   - 於 `onDetachedFromWindow()` 觸發 `disconnectVsock()` (調用 `mVsockClient.close()`)。
4. **接收端數據寫回 Terminal Parser**：
   在連線成功後，將 `TerminalStreamListener` 的 `onDataReceived(byte[] data)` 事件遞交給 `mVTermParser.writeInput(data)`，並調用 `postInvalidate()` 在主執行緒更新 terminal 畫面：
   ```java
   public void connectVsock() {
       try {
           mVsockClient.connect(HOST_CID, mSessionId, new VsockTerminalClient.TerminalStreamListener() {
               @Override
               public void onDataReceived(byte[] data) {
                   if (mVTermParser != null && data != null && data.length > 0) {
                       mVTermParser.writeInput(data);
                       postInvalidate();
                   }
               }

               @Override
               public void onError(Exception e) {
                   Log.e(TAG, "Vsock PTY stream error", e);
               }
           });
           Log.i(TAG, "VsockTerminalClient connected to CID " + HOST_CID + ":" + VSOCK_PORT_5001);
       } catch (Exception e) {
           Log.e(TAG, "Failed to connect VsockTerminalClient to CID " + HOST_CID + ":" + VSOCK_PORT_5001, e);
       }
   }
   ```

### 3.2 真正 Socket 封包傳送實作 (`sendBytes`, `sendFrame`, `sendResize`)

移除僅作 `Log.d` 紀錄後丟棄封包的邏輯，改為將序列化封包經由 `mVsockClient.sendFrame(frame)` 寫入 Socket：

1. **`sendBytes(byte[] bytes)`**：
   ```java
   @Override
   public void sendBytes(byte[] bytes) {
       if (bytes == null || bytes.length == 0) return;
       byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
       try {
           mVsockClient.sendFrame(frame);
           Log.d(TAG, "Transmitted DATA frame (" + frame.length + " bytes) over AF_VSOCK 5001");
       } catch (IOException e) {
           Log.e(TAG, "Error transmitting DATA frame over AF_VSOCK 5001", e);
       }
   }
   ```
2. **`sendFrame(byte[] sessionId, PacketType type, byte[] payload)`**：
   ```java
   @Override
   public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {
       byte[] frame = VsockPtyFramer.serializeFrame(sessionId, type, payload);
       try {
           mVsockClient.sendFrame(frame);
           Log.d(TAG, "Transmitted frame type " + type + " (" + frame.length + " bytes) over AF_VSOCK 5001");
       } catch (IOException e) {
           Log.e(TAG, "Error transmitting frame type " + type + " over AF_VSOCK 5001", e);
       }
   }
   ```
3. **`sendResize(byte[] sessionId, int cols, int rows)`**：
   ```java
   @Override
   public void sendResize(byte[] sessionId, int cols, int rows) {
       byte[] frame = VsockPtyFramer.serializeResizeFrame(sessionId, cols, rows);
       try {
           mVsockClient.sendFrame(frame);
           Log.d(TAG, "Transmitted RESIZE frame (" + cols + "x" + rows + ") over AF_VSOCK 5001");
       } catch (IOException e) {
           Log.e(TAG, "Error transmitting RESIZE frame over AF_VSOCK 5001", e);
       }
   }
   ```

---

## 4. 端到端數據資料流 (End-to-End Data Pipeline Flow)

完成修復後的完整數據通道如下：

```
[使用者 IME 輸入 / 觸控手勢]
       │
       ▼
[TerminalInputConnection / SgrMouseProtocolGenerator]
       │
       ▼
[TerminalView.sendBytes(bytes)]
       │
       ▼
[VsockPtyFramer.serializeFrame(mSessionId, DATA, bytes)]
       │
       ▼
[mVsockClient.sendFrame(frame)]  <─── (取代僅 Log.d 舊門面)
       │
       ▼
[AF_VSOCK Socket (Port 5001)]
       │
       ▼
[Guest Linux VM / pty-agent]
       │
       ▼
[Guest PTY Output]
       │
       ▼
[AF_VSOCK Socket (Port 5001)]
       │
       ▼
[VsockTerminalClient.VsockReadThread]
       │
       ▼
[VsockPtyFramer.StreamParser -> onDataReceived()]
       │
       ▼
[mVTermParser.writeInput(data)] -> [TerminalView.postInvalidate()]
       │
       ▼
[TerminalView.onDraw(canvas)] -> 渲染畫面至 Android 螢幕
```

---

## 5. 避免之錯誤嘗試 (Dead Ends Compliance)

依據 `DEAD_ENDS.md`，以下方案嚴格禁止採用：
- **禁止條款 1**：在 `sendBytes()` / `sendFrame()` / `sendResize()` 中僅保留 `Log.d` 日誌而不執行 Socket `write()` / `sendFrame()`。
- **禁止條款 2**：在連線發生 `IOException` 時以空 `try-catch` 靜默吞掉錯誤並欺騙呼叫者。
- **禁止條款 3**：忽略 `onDetachedFromWindow()` 的 Socket 關閉資源清理，導致 Socket 描述符洩漏。

---

## 6. 獨立驗證方法 (Verification Plan)

修復完成後，可透過以下步驟進行獨立驗證：

1. **Java 源碼編譯驗證**：
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_remediation_classes $(find packages/apps/LinuxTerminal/src -name "*.java") tests/unit/TerminalAppUnitTest.java
   ```
   *期望結果*：`Exit Code 0`，無語法錯誤或符號未找到錯誤。

2. **單元測試套件執行**：
   ```bash
   java -cp /tmp/m3_remediation_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```
   *期望結果*：`JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`。

3. **E2E 測試套件驗證**：
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
   *期望結果*：80/80 測試通過，無任何門面或 Socket 發送失敗宣告。
