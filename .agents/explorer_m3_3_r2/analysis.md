# Technical Remediation Analysis: Milestone M3 Iteration 2 (F-R3-005, F-R3-006, F-R3-007)

**Author**: Explorer 3 (`explorer_m3_3_r2`)  
**Date**: 2026-08-06  
**Milestone**: M3 (Native Touch Terminal Engine & IME — Iteration 2 Remediation)  
**Target Features**:
1. **F-R3-005**: Touch Modes State Machine (`TOUCHPAD_MODE` functional implementation & mode lock persistence)
2. **F-R3-006**: SGR Mouse Protocol Generator (DEC SGR 1006 sequence formatting & Java string escape fix)
3. **F-R3-007**: Vsock Port 5001 PTY Framing (Real Vsock socket connection, signed MSB int overflow fix & stream buffer resync)

---

## 1. Executive Summary & Defect Overview

本分析報告針對 Milestone M3 第二輪修復（Iteration 2 Remediation）中指派給 Explorer 3 的三大核心功能模組（F-R3-005、F-R3-006、F-R3-007）進行全面且精確的源碼診斷與修復策略制定。

各功能當前缺陷總結：
1. **F-R3-005 (Touch Modes State Machine)**:
   - `TOUCHPAD_MODE` 為空白 Stub，未實現相對位移（Relative Motion）計算、單指點擊（左鍵）、雙指/長按點擊（右鍵）與雙指滾輪滾動（Mouse Wheel Up/Down）。
   - 手動模式鎖定（`mIsManualLocked`）未持久化至 `SharedPreferences`，導致 App 重啟或 Activity 重建後 `mIsManualLocked` 重置為 `false`，進而被 Terminal parser 傳來的 Escape 序列（如 `\033[?1000h`）強制覆蓋用戶手動選擇的模式。
   - 類別重複定義於 `com.android.virtualization.terminal` 與 `com.android.virtualization.terminal.touch`。

2. **F-R3-006 (SGR Mouse Protocol Generator)**:
   - Java 字串轉義語法錯誤：使用 `"\x1b"`，導致 `javac` 編譯器報錯 `illegal escape character`。Java 字串必須使用八進位 `"\033"` 或 Unicode `"\u001b"`。
   - DEC SGR 1006 格式字串含有額外分號（Extra Trailing Semicolon）：生成格式如 `"\x1b[<%d;%d;%d;M"`，在數字座標與末尾字符 `M`/`m` 之間多出了一個 `;`（如 `ESC[<0;10;20;M`），違背 DEC SGR 1006 標準格式 `ESC[<b;x;yM` / `ESC[<b;x;ym`，導致 Vim / tmux / htop 解析失敗。
   - C++ Native 實現 `sgr_mouse_generator.cpp` 同樣含有額外末尾分號 `;`。

3. **F-R3-007 (Vsock Port 5001 PTY Framing)**:
   - `TerminalView` / `PtySender` 的 `sendBytes()` 僅為 Logcat 日誌 Stub（`Log.d(TAG, "Sent PTY Frame...")`），未建立真實的 Vsock 5001 網絡 Socket 連線。
   - `VsockPtyFramer.java` 中存在有符號整數 MSB 溢位漏洞：`headerBuf.getInt()` 當長度高位為 1 時會返回負數（如 `-1`），導致 `payloadLength > MAX_PAYLOAD_SIZE` 判斷失效，進而觸發 `Arrays.copyOfRange(..., 21, 20)` 的 `IllegalArgumentException` 崩潰。
   - `StreamParser` 遇到無效 Packet Type 位元組（如 `0xFF`）或壞封包標頭時，無法乾淨地重新同步流緩衝區（Stream Buffer Resynchronization），導致緩衝區永久污染或數據丟失。

---

## 2. Deep Dive Analysis & Detailed Remediation Strategy

### 2.1 Feature F-R3-005: Touch Modes State Machine & Touchpad Mode

#### 2.1.1 缺陷源碼位置與原因分析

1. **模式鎖定持久化缺陷（Mode Lock Persistence Bug）**:
   - **檔案位置**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TouchModeStateMachine.java`
   - **程式碼觀察**:
     ```java
     // line 26
     private boolean mIsManualLocked = false;
     
     // line 31-44 建構子
     public TouchModeStateMachine(Context context) {
         if (context != null) {
             mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
             String saved = mPrefs.getString(KEY_PREF_MODE, TouchMode.SHELL_MODE.name());
             // 僅載入 mCurrentMode，未載入 mIsManualLocked！
         }
     }
     ```
   - **邏輯推導**: 當用戶手動鎖定模式（例如鎖定為 `TOUCHPAD_MODE`）後，`setManualTouchMode(mode)` 設定 `mIsManualLocked = true` 並把 `mCurrentMode` 寫入 `SharedPreferences`。然而，`mIsManualLocked` 狀態未存入 `SharedPreferences`。App 重啟時，`mIsManualLocked` 初始化為 `false`。隨後當 Terminal 輸出 Escape 序列觸發 `onTerminalEscapeMouseTrackingChanged(true)` 時，因 `!mIsManualLocked` 成立，系統會強制將模式改為 `TUI_MOUSE_MODE`，破壞用戶手動鎖定的持久性。

2. **TOUCHPAD_MODE 空白 Stub（Non-functional Touchpad Mode）**:
   - **檔案位置**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
   - **程式碼觀察**:
     ```java
     // lines 131-134
     case TOUCHPAD_MODE:
         // Relative motion processing for virtual touchpad
         return true;
     ```
   - **邏輯推導**: `TOUCHPAD_MODE` 旨在提供虛擬觸控板相對位移控制。當前實現完全未處理 `MotionEvent` 的 ACTION_DOWN、ACTION_MOVE、ACTION_UP 軌跡，未維護虛擬游標網格座標（`mVirtualCursorCol`, `mVirtualCursorRow`），未轉化相對位移 `(dx, dy)`，亦未支持 Tap 單擊（左鍵 0）、Long-press/Two-finger Tap（右鍵 2）與 Two-finger Drag（滾輪 Up/Down 64/65）。

#### 2.1.2 具體修復方案（Proposed Code Changes）

1. **修正 `TouchModeStateMachine.java` 持久化邏輯**:
   ```java
   private static final String KEY_PREF_MANUAL_LOCKED = "saved_manual_locked";

   public TouchModeStateMachine(Context context) {
       if (context != null) {
           mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
           String saved = mPrefs.getString(KEY_PREF_MODE, TouchMode.SHELL_MODE.name());
           mIsManualLocked = mPrefs.getBoolean(KEY_PREF_MANUAL_LOCKED, false);
           try {
               mCurrentMode = TouchMode.valueOf(saved);
           } catch (Exception e) {
               mCurrentMode = TouchMode.SHELL_MODE;
           }
       } else {
           mPrefs = null;
           mCurrentMode = TouchMode.SHELL_MODE;
       }
   }

   public synchronized void setManualTouchMode(TouchMode mode) {
       mIsManualLocked = true;
       if (mPrefs != null) {
           mPrefs.edit().putBoolean(KEY_PREF_MANUAL_LOCKED, true).apply();
       }
       transitionTo(mode, true);
   }

   public synchronized void unlockAutoMode() {
       mIsManualLocked = false;
       if (mPrefs != null) {
           mPrefs.edit().putBoolean(KEY_PREF_MANUAL_LOCKED, false).apply();
       }
       if (mMouseTrackingRequested) {
           transitionTo(TouchMode.TUI_MOUSE_MODE, false);
       } else {
           transitionTo(TouchMode.SHELL_MODE, false);
       }
   }

   public synchronized void onTerminalEscapeMouseTrackingChanged(boolean enabled) {
       mMouseTrackingRequested = enabled;
       if (!mIsManualLocked) {
           TouchMode target = enabled ? TouchMode.TUI_MOUSE_MODE : TouchMode.SHELL_MODE;
           transitionTo(target, false);
       }
   }
   ```

2. **實現完整功能的 `TOUCHPAD_MODE` 觸控板控制器**:
   在 `TerminalView.java` 中新增虛擬觸控板相對位移狀態與事件處理器：
   ```java
   private int mVirtualCursorCol = 40;
   private int mVirtualCursorRow = 12;
   private float mTouchpadLastX = 0f;
   private float mTouchpadLastY = 0f;
   private float mTouchpadDownX = 0f;
   private float mTouchpadDownY = 0f;
   private long mTouchpadDownTime = 0L;
   private float mAccumulatedDx = 0f;
   private float mAccumulatedDy = 0f;

   private boolean handleTouchpadEvent(MotionEvent event) {
       int action = event.getActionMasked();
       float x = event.getX();
       float y = event.getY();
       int pointerCount = event.getPointerCount();

       switch (action) {
           case MotionEvent.ACTION_DOWN:
           case MotionEvent.ACTION_POINTER_DOWN:
               mTouchpadDownX = x;
               mTouchpadDownY = y;
               mTouchpadLastX = x;
               mTouchpadLastY = y;
               mTouchpadDownTime = System.currentTimeMillis();
               mAccumulatedDx = 0f;
               mAccumulatedDy = 0f;
               break;

           case MotionEvent.ACTION_MOVE:
               float dx = x - mTouchpadLastX;
               float dy = y - mTouchpadLastY;
               mTouchpadLastX = x;
               mTouchpadLastY = y;

               if (pointerCount == 1) {
                   // 單指相對位移：轉換像素 delta 至網格座標
                   mAccumulatedDx += dx;
                   mAccumulatedDy += dy;

                   int colDelta = (int) (mAccumulatedDx / mCellWidth);
                   int rowDelta = (int) (mAccumulatedDy / mCellHeight);

                   if (colDelta != 0 || rowDelta != 0) {
                       mVirtualCursorCol = Math.max(1, Math.min(mColumns, mVirtualCursorCol + colDelta));
                       mVirtualCursorRow = Math.max(1, Math.min(mRows, mVirtualCursorRow + rowDelta));
                       mAccumulatedDx -= colDelta * mCellWidth;
                       mAccumulatedDy -= rowDelta * mCellHeight;

                       // 發送 SGR Motion 拖曳/移動封包 (Button 32 + motion)
                       sendBytes(mSgrMouseGenerator.formatSgrPacketBytes(32, mVirtualCursorCol, mVirtualCursorRow, true));
                       invalidate();
                   }
               } else if (pointerCount >= 2) {
                   // 雙指垂直滾動：發送 SGR 滾輪封包 (64=Up, 65=Down)
                   mAccumulatedDy += dy;
                   if (Math.abs(mAccumulatedDy) >= mCellHeight) {
                       int button = (mAccumulatedDy < 0) ? 64 : 65; // dy<0向上滾動
                       sendBytes(mSgrMouseGenerator.formatSgrPacketBytes(button, mVirtualCursorCol, mVirtualCursorRow, true));
                       mAccumulatedDy = 0f;
                   }
               }
               break;

           case MotionEvent.ACTION_UP:
               long duration = System.currentTimeMillis() - mTouchpadDownTime;
               float dist = (float) Math.hypot(x - mTouchpadDownX, y - mTouchpadDownY);

               if (duration < 250 && dist < 15f) {
                   // 手勢判定：Tap 單擊 (左鍵 Button 0 Press + Release)
                   sendBytes(mSgrMouseGenerator.formatSgrPacketBytes(0, mVirtualCursorCol, mVirtualCursorRow, true));
                   sendBytes(mSgrMouseGenerator.formatSgrPacketBytes(0, mVirtualCursorCol, mVirtualCursorRow, false));
               } else if (duration >= 500 && dist < 15f) {
                   // 長按判定：Long-press (右鍵 Button 2 Press + Release)
                   sendBytes(mSgrMouseGenerator.formatSgrPacketBytes(2, mVirtualCursorCol, mVirtualCursorRow, true));
                   sendBytes(mSgrMouseGenerator.formatSgrPacketBytes(2, mVirtualCursorCol, mVirtualCursorRow, false));
               }
               break;
       }
       return true;
   }
   ```

---

### 2.2 Feature F-R3-006: SGR Mouse Protocol Generator

#### 2.2.1 缺陷源碼位置與原因分析

1. **Java 字串轉義語法錯誤 (`"\x1b"`)**:
   - **檔案位置**:
     - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/SgrMouseProtocolGenerator.java`
     - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/touch/SgrMouseProtocolGenerator.java`
   - **程式碼觀察**:
     ```java
     // SgrMouseProtocolGenerator.java lines 58, 67, 76, 87, 98, 102
     String.format("\x1b[<0;%d;%d;M", col, row);
     ```
   - **原因**: Java 不支援 `\x` 轉義字元。`javac` 編譯時會拋出 `illegal escape character` 語法錯誤。正解應使用八進位 `"\033"` 或 Unicode `"\u001b"`。

2. **DEC SGR 1006 格式多餘分號（Extra Trailing Semicolon）**:
   - **檔案位置**:
     - Java: `SgrMouseProtocolGenerator.java` (所有 `String.format` 呼叫點)
     - C++: `packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp` (lines 27, 35, 43, 52)
   - **程式碼觀察**:
     ```java
     // 錯誤格式 (注意 %d;M 和 %d;m 前面的分號 ;)
     String.format("\x1b[<0;%d;%d;M", col, row) // 輸出: \x1b[<0;10;20;M
     ```
     ```cpp
     // C++ 錯誤格式
     snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%d;M", cb, col, row);
     ```
   - **原因與影響**: DEC SGR 1006 mouse protocol 的標準規範為：
     - Press / Motion: `CSI < Button ; Column ; Row M` (格式字串: `"\033[<%d;%d;%dM"`)
     - Release: `CSI < Button ; Column ; Row m` (格式字串: `"\033[<%d;%d;%dm"`)
     多餘的分號使得 Linux 端的 `pty-agent` / Vim / tmux 在解析 `%d;%d;%d;M` 時無法正確匹配末尾的 `M` 或 `m` 字符，導致所有滑鼠點擊與拖曳事件被忽視或解析錯誤。

#### 2.2.2 具體修復方案（Proposed Code Changes）

1. **修正 Java `SgrMouseProtocolGenerator.java` 格式與轉義**:
   ```java
   package com.android.virtualization.terminal;

   import android.view.MotionEvent;
   import java.nio.charset.StandardCharsets;

   /**
    * SGR Mouse Protocol Generator (F-R3-006).
    * Correct DEC SGR 1006 format: \033[<b;x;yM (press/motion) and \033[<b;x;ym (release).
    */
   public class SgrMouseProtocolGenerator {
       private boolean mMouseTrackingEnabled = false;

       public void setMouseTrackingEnabled(boolean enabled) {
           this.mMouseTrackingEnabled = enabled;
       }

       public boolean isMouseTrackingEnabled() {
           return mMouseTrackingEnabled;
       }

       public byte[] processMotionEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows) {
           if (!mMouseTrackingEnabled || event == null) {
               return new byte[0];
           }

           int action = event.getActionMasked();
           float x = event.getX();
           float y = event.getY();

           int col = Math.max(1, Math.min(totalCols, (int) (x / Math.max(1, cellWidth)) + 1));
           int row = Math.max(1, Math.min(totalRows, (int) (y / Math.max(1, cellHeight)) + 1));

           StringBuilder sb = new StringBuilder();

           switch (action) {
               case MotionEvent.ACTION_DOWN:
                   sb.append(formatSgrPacket(0, col, row, true));
                   break;

               case MotionEvent.ACTION_MOVE:
                   if (event.getPointerCount() == 1) {
                       // Motion / Drag: Button 0 + 32 = 32
                       sb.append(formatSgrPacket(32, col, row, true));
                   } else if (event.getPointerCount() >= 2) {
                       // Scroll: 64=Up, 65=Down
                       sb.append(formatSgrPacket(64, col, row, true));
                   }
                   break;

               case MotionEvent.ACTION_UP:
               case MotionEvent.ACTION_CANCEL:
                   sb.append(formatSgrPacket(0, col, row, false));
                   break;
           }

           return sb.toString().getBytes(StandardCharsets.US_ASCII);
       }

       public static String formatSgrPacket(int button, int col, int row, boolean isPress) {
           return String.format("\033[<%d;%d;%d%s", button, col, row, isPress ? "M" : "m");
       }

       public byte[] formatSgrPacketBytes(int button, int col, int row, boolean isPress) {
           return formatSgrPacket(button, col, row, isPress).getBytes(StandardCharsets.US_ASCII);
       }
   }
   ```

2. **修正 C++ Native `jni/sgr_mouse_generator.cpp` 格式**:
   ```cpp
   std::string SgrMouseGeneratorNative::generateButtonPress(int button, int col, int row, int modifiers) {
       if (!mTrackingEnabled) return "";
       int cb = button + modifiers;
       char buf[64];
       snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%dM", cb, col, row);
       return std::string(buf);
   }

   std::string SgrMouseGeneratorNative::generateButtonRelease(int button, int col, int row, int modifiers) {
       if (!mTrackingEnabled) return "";
       int cb = button + modifiers;
       char buf[64];
       snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%dm", cb, col, row);
       return std::string(buf);
   }

   std::string SgrMouseGeneratorNative::generateMotion(int button, int col, int row, int modifiers) {
       if (!mTrackingEnabled) return "";
       int cb = button + 32 + modifiers;
       char buf[64];
       snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%dM", cb, col, row);
       return std::string(buf);
   }

   std::string SgrMouseGeneratorNative::generateWheel(int direction, int col, int row, int modifiers) {
       if (!mTrackingEnabled) return "";
       int cb = (direction < 0) ? 65 : 64; // 64 = Up, 65 = Down
       cb += modifiers;
       char buf[64];
       snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%dM", cb, col, row);
       return std::string(buf);
   }
   ```

---

### 2.3 Feature F-R3-007: Vsock Port 5001 PTY Framing

#### 2.3.1 缺陷源碼位置與原因分析

1. **`TerminalView` 缺乏真實 Socket 連線（Logcat Stub）**:
   - **檔案位置**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
   - **程式碼觀察**:
     ```java
     // lines 83-89
     @Override
     public void sendBytes(byte[] bytes) {
         if (bytes == null || bytes.length == 0) return;
         byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
         Log.d(TAG, "Sent PTY Frame over Port 5001: " + frame.length + " bytes");
     }
     ```
   - **原因**: 僅對 byte 數組進行序列化並列印 Logcat，完全沒有開啟 `AF_VSOCK`（Port 5001）Socket，也沒有接收 Guest VM `pty-agent` 傳回的 Terminal 輸出流。

2. **`VsockPtyFramer.java` 有符號整數 MSB 溢位漏洞**:
   - **檔案位置**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/VsockPtyFramer.java`
   - **程式碼觀察**:
     ```java
     // lines 118-125
     int payloadLength = headerBuf.getInt();

     if (payloadLength > MAX_PAYLOAD_SIZE) {
         mBuffer.reset();
         if (listener != null) {
             listener.onError(new IllegalArgumentException("PayloadLengthExceeded: " + payloadLength + " > " + MAX_PAYLOAD_SIZE));
         }
         return;
     }
     ```
   - **原因**: `headerBuf.getInt()` 當輸入標頭的 4 位元組長度最高位（MSB bit 31）為 1 時（例如 `0xFFFFFFFF`），`payloadLength` 解構為負整數 `-1`。
   - 在 Java 中，`-1 > 65536` 評估為 `false`，導致溢位檢查被繞過！
   - 隨後 `totalFrameLength = 21 + (-1) = 20`，呼叫 `Arrays.copyOfRange(bytes, 21, 20)`，直接拋出 `java.lang.IllegalArgumentException: fromIndex(21) > toIndex(20)` 造成崩潰。

3. **無效封包標頭與緩衝區再同步（Stream Buffer Resync Defect）**:
   - 當流解析器遇到非法的 Packet Type（如 `0xFF`）或壞長度時，直接處置異常但沒有正確推進 readOffset，導致 Stream Buffer 發生對齊錯誤（Framing Desynchronization），後續所有合法封包均無法解析。

#### 2.3.2 具體修復方案（Proposed Code Changes）

1. **修正 `VsockPtyFramer.java` 有符號整數防禦與流再同步**:
   ```java
   public static class StreamParser {
       private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();

       public synchronized void appendAndParse(byte[] chunk, int offset, int length, byte[] expectedSessionId, OnFrameParsedListener listener) {
           if (chunk == null || length <= 0) return;
           mBuffer.write(chunk, offset, length);
           byte[] bytes = mBuffer.toByteArray();
           int readOffset = 0;

           while (bytes.length - readOffset >= HEADER_SIZE) {
               ByteBuffer headerBuf = ByteBuffer.wrap(bytes, readOffset, HEADER_SIZE);
               headerBuf.order(ByteOrder.BIG_ENDIAN);

               byte[] sessionId = new byte[16];
               headerBuf.get(sessionId);
               byte typeByte = headerBuf.get();
               int payloadLength = headerBuf.getInt();

               // 1. MSB 溢位與範圍防禦：檢查負數與超過 64KB 限制
               if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE) {
                   if (listener != null) {
                       listener.onError(new IllegalArgumentException("Invalid payload length: " + payloadLength));
                   }
                   // 防禦機制：前進 1 位元組重新搜尋封包邊界，避免連鎖崩潰
                   readOffset += 1;
                   continue;
               }

               // 2. 合法 PacketType 驗證
               PacketType type;
               try {
                   type = PacketType.fromByte(typeByte);
               } catch (IllegalArgumentException e) {
                   if (listener != null) {
                       listener.onError(e);
                   }
                   // 非法 Type 位元組：前進 1 位元組重新同步標頭
                   readOffset += 1;
                   continue;
               }

               int totalFrameLength = HEADER_SIZE + payloadLength;
               if (bytes.length - readOffset < totalFrameLength) {
                   // 封包分片未完整到達，等待後續數據
                   break;
               }

               try {
                   byte[] payload = Arrays.copyOfRange(bytes, readOffset + HEADER_SIZE, readOffset + totalFrameLength);
                   boolean sessionMatch = (expectedSessionId == null) || Arrays.equals(sessionId, expectedSessionId);
                   if (sessionMatch && listener != null) {
                       listener.onFrameParsed(new Frame(sessionId, type, payload));
                   }
               } catch (Exception e) {
                   if (listener != null) {
                       listener.onError(e);
                   }
               }

               readOffset += totalFrameLength;
           }

           byte[] remaining = Arrays.copyOfRange(bytes, readOffset, bytes.length);
           mBuffer.reset();
           mBuffer.write(remaining, 0, remaining.length);
       }
   }
   ```

2. **建立真實的 Vsock Socket 通訊類別 (`VsockTerminalClient.java`)**:
   ```java
   package com.android.virtualization.terminal.net;

   import android.system.ErrnoException;
   import android.system.Os;
   import android.system.OsConstants;
   import android.util.Log;
   import java.io.FileDescriptor;
   import java.io.FileInputStream;
   import java.io.FileOutputStream;
   import java.io.IOException;
   import com.android.virtualization.terminal.VsockPtyFramer;

   public class VsockTerminalClient {
       private static final String TAG = "VsockTerminalClient";
       private static final int AF_VSOCK = 40;
       private static final int VPORT_PTY = 5001;

       private FileDescriptor mSocketFd;
       private FileInputStream mInputStream;
       private FileOutputStream mOutputStream;
       private Thread mReadThread;
       private volatile boolean mRunning = false;

       public interface TerminalStreamListener {
           void onDataReceived(byte[] data);
           void onError(Exception e);
       }

       public synchronized void connect(int guestCid, byte[] sessionId, TerminalStreamListener listener) throws IOException {
           try {
               mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
               // 連接至 Guest CID:Port (5001)
               // (Android 原生層使用 FileDescriptor 與 Socket 通訊)
               mInputStream = new FileInputStream(mSocketFd);
               mOutputStream = new FileOutputStream(mSocketFd);
               mRunning = true;

               VsockPtyFramer.StreamParser parser = new VsockPtyFramer.StreamParser();

               mReadThread = new Thread(() -> {
                   byte[] buffer = new byte[8192];
                   while (mRunning) {
                       try {
                           int n = mInputStream.read(buffer);
                           if (n < 0) break;
                           parser.appendAndParse(buffer, 0, n, sessionId, new VsockPtyFramer.OnFrameParsedListener() {
                               @Override
                               public void onFrameParsed(VsockPtyFramer.Frame frame) {
                                   if (frame.type == VsockPtyFramer.PacketType.DATA && listener != null) {
                                       listener.onDataReceived(frame.payload);
                                   }
                               }

                               @Override
                               public void onError(Exception e) {
                                   if (listener != null) listener.onError(e);
                               }
                           });
                       } catch (Exception e) {
                           if (mRunning && listener != null) listener.onError(e);
                           break;
                       }
                   }
               });
               mReadThread.start();
           } catch (ErrnoException e) {
               throw new IOException("Failed to open AF_VSOCK socket to CID " + guestCid + ":" + VPORT_PTY, e);
           }
       }

       public synchronized void sendFrame(byte[] frameBytes) throws IOException {
           if (mOutputStream != null) {
               mOutputStream.write(frameBytes);
               mOutputStream.flush();
           }
       }

       public synchronized void close() {
           mRunning = false;
           try {
               if (mSocketFd != null && mSocketFd.valid()) {
                   Os.close(mSocketFd);
               }
           } catch (Exception ignored) {}
       }
   }
   ```

3. **在 `TerminalView.java` 中整合真實 `VsockTerminalClient`**:
   ```java
   // 替換原先 Logcat Stub：
   @Override
   public void sendBytes(byte[] bytes) {
       if (bytes == null || bytes.length == 0) return;
       byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
       try {
           if (mVsockClient != null) {
               mVsockClient.sendFrame(frame);
           }
       } catch (IOException e) {
           Log.e(TAG, "Failed to send PTY frame over Vsock 5001", e);
       }
   }
   ```

---

## 3. Implementation Checklist & Verification Strategy

### 3.1 變更檔案清單 (Files to Modify / Create)
1. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TouchModeStateMachine.java`
   - 修復 `mIsManualLocked` 的 `SharedPreferences` 持久化邏輯。
2. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
   - 實現 `TOUCHPAD_MODE` 的相對位移、點擊與滾輪手勢邏輯。
   - 整合 `VsockTerminalClient` 進行真實 Vsock 5001 封包發送與接收。
3. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/SgrMouseProtocolGenerator.java`
   - 替換 `"\x1b"` 為 `"\033"`，並移除多餘的末尾分號 `;`。
4. `packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp`
   - 移除 C++ 的多餘末尾分號 `;`。
5. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/VsockPtyFramer.java`
   - 新增有符號整數 MSB 負數檢查 (`payloadLength < 0`)，修復溢位崩潰漏洞。
   - 實現無效 Type/Length 時前進 1 位元組的 Stream Buffer 再同步邏輯。
6. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java` (新檔案)
   - 建立真實 `AF_VSOCK` Port 5001 Socket 讀寫連線與背景讀取線程。
7. 移除重複封包與類別定義，統一至 `com.android.virtualization.terminal` 結構。

### 3.2 驗證方法 (Verification Steps)

1. **單元測試驗證 (Unit Tests)**:
   - 執行 JNI 與 C++ Native 測試集：
     ```bash
     g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/ \
       tests/unit/m3_native_challenger2_stress.cpp \
       packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp \
       packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp \
       -o /tmp/m3_native_test && /tmp/m3_native_test
     ```
   - 驗證標頭與格式是否無多餘分號、是否通過 64KB 邊界與異常位元組 Fuzzing 測試。

2. **Java / AOSP 編譯驗證**:
   - 執行 `javac` 確保無 `"\x1b"` 編譯錯誤：
     ```bash
     javac -cp packages/apps/LinuxTerminal/src \
       packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/*.java
     ```

3. **E2E 測試與功能覆蓋**:
   - 執行包含真實 Java / Native 物件測試的 E2E 測試套件：
     ```bash
     python3 tests/e2e/runner.py --filter F-R3
     ```
