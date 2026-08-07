# Milestone M3 (Iteration 3 Remediation) 技術補救計劃

**目標受眾**：Worker 3 / 開發團隊  
**模組與路徑**：`packages/apps/TerminalApp/`  
**建立日期**：2026-08-06  
**分析人員**：Explorer 5  

---

## 1. 問題背景與門控失敗分析 (Gate Iteration 2 Failure)

在 Iteration 2 Gate 審查中，發現了兩項關鍵問題導致門控測試未通過（REQUEST_CHANGES / INTEGRITY VIOLATION）：

1. **`TOUCHPAD_MODE` 游標相對追蹤與 SGR 封包編碼缺口**：
   - `TerminalView.java` (Line 166) 與 `TerminalSurfaceView.java` (Line 115) 中的 `TOUCHPAD_MODE` 僅包含回傳 `true` 的 Empty Stub，沒有實現相對位移（delta X, delta Y）累加器、速度縮放、虛擬游標位置模擬、單擊轉左鍵點擊、拖曳轉按住滑鼠移動、雙指捲動轉 SGR 滾輪滾動，亦未透過 `SgrMouseProtocolGenerator` 產生 DEC SGR 1006 格式封包。
2. **`TerminalView.java` 內 `VsockTerminalClient` 輸出數據未對接**：
   - `TerminalView.java` 雖然實例化了 `mVsockClient`，但在 `sendBytes()`、`sendFrame()` 及 `sendResize()` 中僅調用 `Log.d` 印出日誌，未將序列化後的 `frame` 位元組陣列傳送給 `mVsockClient.sendFrame(frame)`，導致所有輸入與控制訊號無法透過 AF_VSOCK Port 5001 傳送至 Linux VM。

本計劃為 Worker 3 提供無歧義、逐步且具體的程式碼補救指南。

---

## 2. 補救計劃 1：TOUCHPAD_MODE 相對觸控追蹤與 SGR 1006 協定編碼

### 2.1 修改組件 1：`SgrMouseProtocolGenerator.java` 擴充

**檔案路徑**：`packages/apps/TerminalApp/src/com/android/virtualization/terminal/touch/SgrMouseProtocolGenerator.java`

#### 2.1.1 新增 Touchpad 狀態變數與設定介面
在 `SgrMouseProtocolGenerator` 類別中新增以下狀態欄位：

```java
// Touchpad Mode 狀態追蹤變量
private int mTouchpadCol = -1;
private int mTouchpadRow = -1;
private float mTouchpadLastX = 0f;
private float mTouchpadLastY = 0f;
private float mTouchpadAccumX = 0f;
private float mTouchpadAccumY = 0f;
private long mTouchpadDownTime = 0L;
private float mTouchpadTotalMoveDist = 0f;
private boolean mTouchpadIsDragging = false;
private float mTouchpadScrollAccumY = 0f;
private float mTouchpadVelocityScale = 1.0f; // 速度與靈敏度縮放因子

public void setTouchpadVelocityScale(float scale) {
    this.mTouchpadVelocityScale = scale > 0 ? scale : 1.0f;
}

public float getTouchpadVelocityScale() {
    return mTouchpadVelocityScale;
}

public int getTouchpadCol() {
    return mTouchpadCol;
}

public int getTouchpadRow() {
    return mTouchpadRow;
}
```

#### 2.1.2 實現 `processTouchpadEvent()` 方法
在 `SgrMouseProtocolGenerator` 中實現完整的 `processTouchpadEvent` 手勢解析邏輯：

```java
/**
 * 處理 TOUCHPAD_MODE 的相對觸控事件並生成 SGR 1006 協定封包位元組
 */
public byte[] processTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows) {
    if (!mMouseTrackingEnabled || event == null) {
        return new byte[0];
    }

    int safeCellW = Math.max(1, cellWidth);
    int safeCellH = Math.max(1, cellHeight);
    int safeCols = Math.max(1, totalCols);
    int safeRows = Math.max(1, totalRows);

    // 初始化模擬游標位置至終端螢幕中央
    if (mTouchpadCol < 1 || mTouchpadCol > safeCols || mTouchpadRow < 1 || mTouchpadRow > safeRows) {
        mTouchpadCol = safeCols / 2;
        mTouchpadRow = safeRows / 2;
    }

    int action = event.getActionMasked();
    int pointerCount = event.getPointerCount();
    StringBuilder sb = new StringBuilder();

    // 1. 雙指捲動 (Two-finger Scroll -> SGR Scroll Wheel 64/65)
    if (pointerCount >= 2) {
        float avgY = (event.getY(0) + event.getY(1)) / 2f;
        if (action == MotionEvent.ACTION_MOVE) {
            float dy = avgY - mTouchpadLastY;
            mTouchpadScrollAccumY += dy;
            float threshold = safeCellH * 0.8f;
            if (Math.abs(mTouchpadScrollAccumY) >= threshold) {
                // 64 = Wheel Up (向上滾動), 65 = Wheel Down (向下滾動)
                int button = (mTouchpadScrollAccumY < 0) ? 65 : 64;
                sb.append(formatSgrPacket(button, mTouchpadCol, mTouchpadRow, true));
                mTouchpadScrollAccumY = 0f;
            }
        }
        mTouchpadLastY = avgY;
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    // 2. 單指相對運動與點擊 (Single-finger relative motion & Single Tap / Drag)
    float x = event.getX();
    float y = event.getY();

    switch (action) {
        case MotionEvent.ACTION_DOWN:
            mTouchpadLastX = x;
            mTouchpadLastY = y;
            mTouchpadDownTime = System.currentTimeMillis();
            mTouchpadTotalMoveDist = 0f;
            mTouchpadAccumX = 0f;
            mTouchpadAccumY = 0f;
            mTouchpadScrollAccumY = 0f;
            break;

        case MotionEvent.ACTION_MOVE:
            float dx = (x - mTouchpadLastX) * mTouchpadVelocityScale;
            float dy = (y - mTouchpadLastY) * mTouchpadVelocityScale;
            mTouchpadLastX = x;
            mTouchpadLastY = y;
            mTouchpadTotalMoveDist += (float) Math.hypot(dx, dy);

            mTouchpadAccumX += dx;
            mTouchpadAccumY += dy;

            int colShift = (int) (mTouchpadAccumX / safeCellW);
            int rowShift = (int) (mTouchpadAccumY / safeCellH);

            if (colShift != 0) {
                mTouchpadCol = Math.max(1, Math.min(safeCols, mTouchpadCol + colShift));
                mTouchpadAccumX -= colShift * safeCellW;
            }
            if (rowShift != 0) {
                mTouchpadRow = Math.max(1, Math.min(safeRows, mTouchpadRow + rowShift));
                mTouchpadAccumY -= rowShift * safeCellH;
            }

            // 若游標網格位置有變更，發送 SGR 移動/拖曳封包
            if (colShift != 0 || rowShift != 0) {
                if (mTouchpadIsDragging) {
                    // 按住左鍵拖曳 (SGR Button 32: Button 0 + Motion 32)
                    sb.append(formatSgrPacket(32, mTouchpadCol, mTouchpadRow, true));
                } else {
                    // 純游標懸停移動 (SGR Button 35: Motion with no buttons pressed)
                    sb.append(formatSgrPacket(35, mTouchpadCol, mTouchpadRow, true));
                }
            }
            break;

        case MotionEvent.ACTION_UP:
            long duration = System.currentTimeMillis() - mTouchpadDownTime;
            // 判斷是否為單擊 (Tap: 時間 < 250ms 且 移動距離 < 20px)
            if (duration < 250 && mTouchpadTotalMoveDist < 20f) {
                // 單擊 -> 左鍵 Down 隨即 Up (\033[<0;col;rowM\033[<0;col;rowm)
                sb.append(formatSgrPacket(0, mTouchpadCol, mTouchpadRow, true));
                sb.append(formatSgrPacket(0, mTouchpadCol, mTouchpadRow, false));
            } else if (mTouchpadIsDragging) {
                // 拖曳結束 -> 發送左鍵 Up
                sb.append(formatSgrPacket(0, mTouchpadCol, mTouchpadRow, false));
                mTouchpadIsDragging = false;
            }
            break;

        case MotionEvent.ACTION_CANCEL:
            if (mTouchpadIsDragging) {
                sb.append(formatSgrPacket(0, mTouchpadCol, mTouchpadRow, false));
                mTouchpadIsDragging = false;
            }
            break;
    }

    return sb.toString().getBytes(StandardCharsets.US_ASCII);
}
```

---

### 2.2 修改組件 2：`TerminalView.java` 中的 TOUCHPAD_MODE 整合

**檔案路徑**：`packages/apps/TerminalApp/src/com/android/virtualization/terminal/TerminalView.java`

#### 2.2.1 更新 `initView()` 中的 Touch Mode 監聽器
在 `initView()` 方法中，確保 `TOUCHPAD_MODE` 與 `TUI_MOUSE_MODE` 皆能啟動 SGR 追蹤：

```java
mTouchModeManager.getStateMachine().addListener((oldMode, newMode, isManual) -> {
    boolean isMouseMode = (newMode == TouchModeStateMachine.TouchMode.TUI_MOUSE_MODE 
                        || newMode == TouchModeStateMachine.TouchMode.TOUCHPAD_MODE);
    mSgrMouseGenerator.setMouseTrackingEnabled(isMouseMode);
    invalidate();
});
```

#### 2.2.2 替代 `onTouchEvent()` 中的 Stub 實現
替換 Line 166 處的 `case TOUCHPAD_MODE: return true;`：

```java
// [BEFORE]
case TOUCHPAD_MODE:
    return true;

// [AFTER]
case TOUCHPAD_MODE:
    byte[] touchpadSgr = mSgrMouseGenerator.processTouchpadEvent(
        event, mCellWidth, mCellHeight, mColumns, mRows
    );
    if (touchpadSgr != null && touchpadSgr.length > 0) {
        sendBytes(touchpadSgr);
    }
    return true;
```

---

### 2.3 修改組件 3：`TerminalSurfaceView.java` 中的 TOUCHPAD_MODE 整合

**檔案路徑**：`packages/apps/TerminalApp/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java`

替換 Line 115 處的 `case TOUCHPAD_MODE:`：

```java
// [BEFORE]
case TOUCHPAD_MODE:
    // Relative touch cursor motion tracking
    return true;

// [AFTER]
case TOUCHPAD_MODE:
    if (mPtySender != null) {
        byte[] touchpadSgr = mSgrGenerator.processTouchpadEvent(
            event,
            (int) mRenderer.getCellWidth(),
            (int) mRenderer.getCellHeight(),
            mScreenMatrix.getCols(),
            mScreenMatrix.getRows()
        );
        if (touchpadSgr != null && touchpadSgr.length > 0) {
            mPtySender.sendBytes(touchpadSgr);
        }
    }
    return true;
```

---

## 3. 補救計劃 2：`TerminalView.java` 連接 `VsockTerminalClient` 數據輸出

### 3.1 修改組件：`TerminalView.java`

**檔案路徑**：`packages/apps/TerminalApp/src/com/android/virtualization/terminal/TerminalView.java`

#### 3.1.1 修正 `sendBytes()`, `sendFrame()`, `sendResize()`
在 `sendBytes()`, `sendFrame()`, `sendResize()` 方法中，直接調用 `mVsockClient.sendFrame(frame)` 並加入異常處理：

```java
@Override
public void sendBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0) return;
    byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
    try {
        mVsockClient.sendFrame(frame);
    } catch (IOException e) {
        Log.e(TAG, "Failed to send PTY data frame over Vsock Port 5001", e);
    }
    Log.d(TAG, "Sent PTY Frame over Port 5001: " + frame.length + " bytes");
}

@Override
public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {
    byte[] frame = VsockPtyFramer.serializeFrame(sessionId, type, payload);
    try {
        mVsockClient.sendFrame(frame);
    } catch (IOException e) {
        Log.e(TAG, "Failed to send PTY frame type " + type + " over Vsock Port 5001", e);
    }
    Log.d(TAG, "Sent PTY Frame over Port 5001: " + frame.length + " bytes");
}

@Override
public void sendResize(byte[] sessionId, int cols, int rows) {
    byte[] frame = VsockPtyFramer.serializeResizeFrame(sessionId, cols, rows);
    try {
        mVsockClient.sendFrame(frame);
    } catch (IOException e) {
        Log.e(TAG, "Failed to send PTY resize frame over Vsock Port 5001", e);
    }
    Log.d(TAG, "Sent Resize Frame over Port 5001: " + frame.length + " bytes");
}
```

#### 3.1.2 補全 Vsock 連接與數據傳輸 API
新增 `connectVsock()` 與 `getVsockTerminalClient()` 方法，使 `TerminalView` 能夠建立 Vsock 數據接收通道，並將 VM 回傳的 terminal 位元組寫入 `mVTermParser` 並引發重繪：

```java
public VsockTerminalClient getVsockTerminalClient() {
    return mVsockClient;
}

public void connectVsock(int guestCid, byte[] sessionId) {
    if (sessionId != null && sessionId.length == 16) {
        this.mSessionId = sessionId;
    }
    try {
        mVsockClient.connect(guestCid, mSessionId, new VsockTerminalClient.TerminalStreamListener() {
            @Override
            public void onDataReceived(byte[] data) {
                if (mVTermParser != null && data != null && data.length > 0) {
                    mVTermParser.processOutput(data);
                    postInvalidate();
                }
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Vsock terminal stream error", e);
            }
        });
    } catch (IOException e) {
        Log.e(TAG, "Failed to connect Vsock terminal client to CID " + guestCid, e);
    }
}
```

---

## 4. 變更對照與影響範圍矩陣

| 檔案 | 變更說明 | 修正問題點 | 測試驗證方式 |
|------|----------|------------|--------------|
| `SgrMouseProtocolGenerator.java` | 新增 Touchpad 狀態變量及 `processTouchpadEvent()` 方法 | Issue 1 (TOUCHPAD_MODE SGR 生成) | 單元測試傳入 1-finger down/move/up, 2-finger scroll, 驗證輸出之 ASCII 封包 |
| `TerminalView.java` | 1. 在 `onTouchEvent` TOUCHPAD_MODE 中調用 `processTouchpadEvent`<br>2. 在 `sendBytes/sendFrame/sendResize` 中對接 `mVsockClient.sendFrame` | Issue 1 & Issue 2 | 1. 手勢測試<br>2. 驗證 VsockClient.sendFrame 被成功執行 |
| `TerminalSurfaceView.java` | 在 `onTouchEvent` TOUCHPAD_MODE 中調用 `processTouchpadEvent` 並透過 `mPtySender.sendBytes` 送出 | Issue 1 (SurfaceView parity) | 手勢與繪圖整合測試 |

---

## 5. Worker 3 驗證步驟指南 (Verification Steps)

1. **單元測試 (Unit Tests)**：
   - 撰寫測試驗證 `SgrMouseProtocolGenerator.processTouchpadEvent`：
     - 單擊事件產出 `\033[<0;<col>;<row>M\033[<0;<col>;<row>m`。
     - 相對移動產出 `\033[<35;<col>;<row>M` 且游標座標符合網格計算。
     - 雙指向上/向下滾動分別產出 `\033[<65;<col>;<row>M` 與 `\033[<64;<col>;<row>M`。
2. **Vsock 端點驗證**：
   - 在 Mock/Real socket 環境下調用 `TerminalView.sendBytes("test".getBytes())`，驗證 `mVsockClient.sendFrame(...)` 確實寫出長度 25 位元組（21B Header + 4B Payload）之 PTY DATA 封包。
3. **編譯構建檢查**：
   - 執行 Android 模組編譯命令，確認無 Syntax Error、Uncaught Exception 或 Import 遺漏。
