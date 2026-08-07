# Handoff Report — Explorer 5 (Milestone M3 Iteration 3 Remediation)

## 1. Observation (觀察事實)

經過對 `packages/apps/TerminalApp/` 原始碼及 Iteration 2 Gate 審查報告的深入稽核，直接觀察到以下具體事實與程式碼行號：

1. **`TOUCHPAD_MODE` 實作缺口 (Issue 1)**：
   - 在 `packages/apps/TerminalApp/src/com/android/virtualization/terminal/TerminalView.java` 第 166-168 行：
     ```java
     case TOUCHPAD_MODE:
         return true;
     ```
   - 在 `packages/apps/TerminalApp/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java` 第 115-118 行：
     ```java
     case TOUCHPAD_MODE:
         // Relative touch cursor motion tracking
         return true;
     ```
   - 兩處均為空檔（Empty Stub），僅回傳 `true`，未進行觸控相對位移（delta X, delta Y）累加計算、速度縮放、游標網格模擬、單擊轉左鍵點擊、拖曳移動或雙指 SGR 滾輪封包轉換。

2. **`VsockTerminalClient` 輸出端點未掛接 (Issue 2)**：
   - 在 `packages/apps/TerminalApp/src/com/android/virtualization/terminal/TerminalView.java` 第 94-111 行：
     ```java
     @Override
     public void sendBytes(byte[] bytes) {
         if (bytes == null || bytes.length == 0) return;
         byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
         Log.d(TAG, "Sent PTY Frame over Port 5001: " + frame.length + " bytes");
     }
     ```
   - `TerminalView` 實例化了 `mVsockClient`（第 52 行），但 `sendBytes()`、`sendFrame()`、`sendResize()` 在序列化封包後僅執行 `Log.d`，未調用 `mVsockClient.sendFrame(frame)`，導致資料無法送出至 AF_VSOCK Socket。

---

## 2. Logic Chain (推導邏輯鏈)

1. **從觀察 1 到 TOUCHPAD_MODE 補救方案**：
   - 由於 `TerminalView` 與 `TerminalSurfaceView` 均需支援 `TOUCHPAD_MODE` 且功能必須完全對齊，在 `SgrMouseProtocolGenerator.java` 中集中實現 `processTouchpadEvent(...)` 是最優架構選擇。
   - `processTouchpadEvent(...)` 透過管理 `mTouchpadAccumX`/`mTouchpadAccumY` 累加器與 `mTouchpadVelocityScale`，實現滑鼠相對平滑移動；同時維護 `(mTouchpadCol, mTouchpadRow)` 模擬游標位置。
   - 單指輕觸（<250ms & <20px 移動）轉換為 SGR 1006 左鍵 Press+Release (`\033[<0;col;rowM\033[<0;col;rowm`)；雙指上下滑動累積超過單一儲存格高度時轉換為滾輪 Up/Down (`\033[<64;col;rowM` / `\033[<65;col;rowM`)。
   - `TerminalView` 與 `TerminalSurfaceView` 在 `TOUCHPAD_MODE` 分支中調用此方法並將產出的位元組陣列送入 PTY 傳送佇列，徹底解決 Issue 1。

2. **從觀察 2 到 VsockClient 掛接補救方案**：
   - `TerminalView` 實作 `PtySender` 介面，當 IME 輸入、鍵盤按鍵或觸控手勢觸發 `sendBytes()` / `sendFrame()` / `sendResize()` 時，直接調用 `mVsockClient.sendFrame(frame)`。
   - 加上 `try-catch (IOException)` 捕獲網路傳輸例外，並補全 `connectVsock()` 使接收端的位元組流能透過 `mVTermParser.processOutput(data)` 解析並引發重繪，徹底解決 Issue 2。

---

## 3. Caveats (注意事項與未覆蓋範圍)

1. **未修改原始碼聲明**：本 Agent 嚴格遵循 Read-Only 規範，未直接修改任何 Java/C++ 原始碼檔案，所有變更與設計方案均紀錄於 `analysis.md` 中供 Worker 3 執行。
2. **硬體驗證條件**：AF_VSOCK 實際 Socket 傳輸依賴 Android 虛擬化架構（AVF / crosvm）環境，單元測試環境下建議使用 Mock/Loopback 驗證 `sendFrame` 調用頻率與 Header 結構。

---

## 4. Conclusion (最終結論)

已成功為 Worker 3 制定完整的 Iteration 3 補救技術計劃，詳細程式碼對照與逐步實作說明位於：
`/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_5/analysis.md`

主要補救點：
1. 在 `SgrMouseProtocolGenerator.java` 中補全 `processTouchpadEvent` 相對運動追蹤與 SGR 1006 編碼，並於 `TerminalView` 及 `TerminalSurfaceView` 完成掛接。
2. 在 `TerminalView.java` 中將 `sendBytes`、`sendFrame` 與 `sendResize` 補全對接至 `mVsockClient.sendFrame(frame)`。

---

## 5. Verification Method (獨立驗證方法)

1. **程式碼檢查**：
   - 檢視 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_5/analysis.md` 獲取完整實作範例。
2. **邏輯驗證與編譯步驟**：
   - Worker 3 依據 `analysis.md` 套用變更後，執行：
     `mm` 或 `m LinuxTerminal` 進行編譯。
   - 執行單元測試驗證 `SgrMouseProtocolGeneratorTest` 針對單擊、相對運動、雙指滾動產出的 SGR 1006 位元組流。
3. **失效條件 (Invalidation Conditions)**：
   - 若 `TerminalView.onTouchEvent` 在 `TOUCHPAD_MODE` 下仍回傳固定 `true` 且未調用 `processTouchpadEvent`，則驗證失敗。
   - 若 `TerminalView.sendBytes()` 執行時未觸發 `mVsockClient.sendFrame(frame)`，則驗證失敗。
