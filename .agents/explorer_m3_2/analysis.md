# F-R3-003 與 F-R3-004 技術分析與架構設計報告 (Technical Strategy & Architecture Report)

## 摘要 (Executive Summary)
本報告針對 **Milestone M3 (Native Touch Terminal & IME)** 中兩個核心輸入模組提供完整技術設計規範：
1. **F-R3-003: TerminalInputConnection Key Code & Control Sequence Engine** — 自訂 `TerminalInputConnection extends BaseInputConnection`，精確處理解析硬體與軟體鍵盤事件 (KeyEvent)、按鍵碼 (KeyCodes)、退格 (Backspace)、換行 (Enter)、方向鍵 (Arrow Keys)、功能鍵 (F1-F12) 及 ANSI/VT100 控制序列 (Ctrl/Alt 組合鍵與修飾鍵)。
2. **F-R3-004: Multi-stage CJK IME Commit Pipeline** — 支援繁體中文（注音/倉頡）、簡體中文（拼音）等多階段組字 (Composing Text) 視窗與 UTF-8 批次 Commit 管道。實現 IME 組字狀態緩衝區隔離、游標畫布組字視窗 (Inline Composing Window) 渲染、`CursorAnchorInfo` 游標定位更新，以及 PTY Vsock Channel (Port 5001) 的 UTF-8 位元流發送。

---

## 1. 現有程式碼調查與問題診斷 (Existing Codebase Investigation)

### 1.1 現有 `TerminalInputConnection.java` 觀察
在 `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalInputConnection.java` 中，現有實現為簡單骨架（如下所示）：

```java
// File: packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalInputConnection.java
// Lines 18-32
@Override
public boolean commitText(CharSequence text, int newCursorPosition) {
    Log.d(TAG, "Committed IME Text: " + text);
    byte[] utf8Bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return super.commitText(text, newCursorPosition);
}

@Override
public boolean setComposingText(CharSequence text, int newCursorPosition) {
    Log.d(TAG, "IME Composing Preview: " + text);
    return super.setComposingText(text, newCursorPosition);
}
```

### 1.2 發現之重大缺陷 (Identified Architectural Deficiencies)
1. **缺乏 PTY 輸出管道連接**：`utf8Bytes` 僅轉為 byte 陣列，未寫入 Vsock 5001 Stream (`PtySender`)。
2. **缺乏 KeyEvent 控制序列轉換**：未處理 `sendKeyEvent()`、`KEYCODE_DEL` (Backspace)、`KEYCODE_ENTER`、`KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT` 等控制碼與 ESC 轉義序列（如 `\x1b[A`）。
3. **缺乏 CJK IME 多階段組字狀態隔離**：`setComposingText()` 僅印出 Log，未建立組字緩衝區。在 IME 組字過程中（例如輸入注音 `ㄘㄨㄛ` 或拼音 `nihao`），若 IME 或系統傳送變更，未隔離組字文字會直接被當作普通字元或丟失。
4. **缺乏 IME 文字查詢 API 覆寫**：未覆寫 `getTextBeforeCursor()`、`getTextAfterCursor()`、`getSelectedText()`、`getExtractedText()`、`deleteSurroundingText()` 等 API，導致 Gboard / 注音輸入法 / 倉頡輸入法在查詢游標周圍文字時會返回空值或引發 IME 崩潰。
5. **缺乏組字 UI 視覺呈現與錨點更新**：未呼叫 `InputMethodManager.updateCursorAnchorInfo()`，導致 IME 候選字視窗無法準確定位在 Terminal 游標下方；同時未在 Terminal 畫布上呈現底線 (Underline) 的 Inline 預覽文字。
6. **缺乏外部中斷重置機制**：當 Terminal 收到 Ctrl+C 或 Shell 清屏/重置時，未重置 IME 組字狀態，導致組字狀態殘留。

---

## 2. 系統元件交互與架構設計 (System Architecture & Component Interaction)

### 2.1 元件層級圖 (Component Interaction Diagram)

```
+-----------------------------------------------------------------------------------+
| Android System Framework / InputMethodManager (IME Service, e.g. Gboard, 注音, 拼音)|
+-----------------------------------------------------------------------------------+
                                         |
                       InputConnection API Calls 
     (setComposingText, commitText, deleteSurroundingText, sendKeyEvent, etc.)
                                         v
+-----------------------------------------------------------------------------------+
| TerminalInputConnection (extends BaseInputConnection)                             |
|                                                                                   |
|  +-----------------------------------+   +-------------------------------------+  |
|  | CjkComposingTextManager           |   | TerminalKeyEncoder                  |  |
|  | - Composing State Buffer          |   | - KeyCode -> ANSI Escape Codes      |  |
|  | - Selection / Cursor Range        |   | - Ctrl / Alt / Shift Modifiers      |  |
|  +-----------------------------------+   +-------------------------------------+  |
+-----------------------------------------------------------------------------------+
      | (Update Composing State)                   | (Encode Key / Commit Bytes)
      v                                            v
+-----------------------------------+     +-----------------------------------------+
| CjkComposingWindow / Overlay      |     | PtySender / VsockPtyFramer              |
| - Render inline text at cursor    |     | - PTY Framing Header [SessionID|Type...] |
| - Underline styling               |     | - Binary write to Vsock Port 5001       |
+-----------------------------------+     +-----------------------------------------+
      |                                            |
      v                                            v
+-----------------------------------+     +-----------------------------------------+
| TerminalView (Canvas Rendering)   |     | Guest Linux PTY Stream (pty-agent)     |
+-----------------------------------+     +-----------------------------------------+
```

---

## 3. F-R3-003: TerminalInputConnection 按鍵與控制序列引擎設計 (Detailed Spec)

### 3.1 關鍵 API 覆寫與控制邏輯

`TerminalInputConnection` 繼承自 `BaseInputConnection`，負責攔截軟硬體按鍵事件與文字輸入：

#### A. 按鍵事件攔截 (`sendKeyEvent(KeyEvent event)`)
- 僅在 `event.getAction() == KeyEvent.ACTION_DOWN` 時進行編碼與處理（防止 ACTION_UP 重複觸發，除非處理選字/長按重複 `event.getRepeatCount() > 0`）。
- 優先檢測修飾鍵狀態 (`event.getMetaState()`) 以及 `TerminalView` 虛擬按鍵列的黏性修飾鍵 (Latch Modifiers: `mCtrlLatched`, `mAltLatched`)。
- 呼叫 `TerminalKeyEncoder.encodeKeyEvent(event, metaState)` 將按鍵轉為 ANSI / VT100 位元組序列，並寫入 `PtySender`。

#### B. 特殊按鍵編碼映射對照表 (Keycode Mapping Table)

| 按鍵類別 | Android KeyCode | 觸發條件 / 模式 | ANSI / VT100 轉義序列 (Hex / Escape) |
| :--- | :--- | :--- | :--- |
| **Backspace** | `KEYCODE_DEL` | 無組字狀態時 | `\x7f` (ASCII 127 DEL) 或 `\x08` (BS) |
| **Enter** | `KEYCODE_ENTER`, `KEYCODE_NUMPAD_ENTER` | 無組字狀態時 | `\r` (`0x0D` CR) 或 `\n` (`0x0A` LF) |
| **Tab** | `KEYCODE_TAB` | Normal / Shift | Normal: `\t` (`0x09`) / Shift+Tab: `\x1b[Z` |
| **Escape** | `KEYCODE_ESCAPE` | - | `\x1b` (`0x1B`) |
| **Arrow Up** | `KEYCODE_DPAD_UP` | Normal / Application | Normal: `\x1b[A` / App Mode: `\x1bOA` |
| **Arrow Down** | `KEYCODE_DPAD_DOWN` | Normal / Application | Normal: `\x1b[B` / App Mode: `\x1bOB` |
| **Arrow Right** | `KEYCODE_DPAD_RIGHT` | Normal / Application | Normal: `\x1b[C` / App Mode: `\x1bOC` |
| **Arrow Left** | `KEYCODE_DPAD_LEFT` | Normal / Application | Normal: `\x1b[D` / App Mode: `\x1bOD` |
| **Home** | `KEYCODE_MOVE_HOME` | - | `\x1b[H` (或 `\x1b[1~`) |
| **End** | `KEYCODE_MOVE_END` | - | `\x1b[F` (或 `\x1b[4~`) |
| **Insert** | `KEYCODE_INSERT` | - | `\x1b[2~` |
| **Delete** | `KEYCODE_FORWARD_DEL` | - | `\x1b[3~` |
| **Page Up** | `KEYCODE_PAGE_UP` | - | `\x1b[5~` |
| **Page Down** | `KEYCODE_PAGE_DOWN` | - | `\x1b[6~` |
| **F1 - F4** | `KEYCODE_F1` .. `KEYCODE_F4` | - | F1: `\x1bOP`, F2: `\x1bOQ`, F3: `\x1bOR`, F4: `\x1bOS` |
| **F5 - F12** | `KEYCODE_F5` .. `KEYCODE_F12` | - | F5: `\x1b[15~`, F6: `\x1b[17~`, F7: `\x1b[18~`, F8: `\x1b[19~`, F9: `\x1b[20~`, F10: `\x1b[21~`, F11: `\x1b[23~`, F12: `\x1b[24~` |

#### C. Control (Ctrl) 與 Alt/Meta 組合鍵計算邏輯
1. **Ctrl 組合鍵 (`META_CTRL_ON` 或 `mCtrlLatched`)**:
   - 若按鍵為字母 `[A-Z]` 或 `[a-z]`：計算 `(keycode - KEYCODE_A + 1)`，例如：
     - Ctrl+A (`KEYCODE_A`): `0x01` (SOH)
     - Ctrl+C (`KEYCODE_C`): `0x03` (ETX - SIGINT)
     - Ctrl+D (`KEYCODE_D`): `0x04` (EOT - EOF)
     - Ctrl+Z (`KEYCODE_Z`): `0x1A` (SUB - SIGTSTP)
     - Ctrl+L (`KEYCODE_L`): `0x0C` (FF - Clear Screen)
   - 若按鍵為符號：
     - Ctrl+`[`: `\x1b` (27 / ESC)
     - Ctrl+`\`: `\x1c` (28 / FS)
     - Ctrl+`]`: `\x1d` (29 / GS)
     - Ctrl+`^`: `\x1e` (30 / RS)
     - Ctrl+`_`: `\x1f` (31 / US)
     - Ctrl+Space: `\x00` (NUL)
2. **Alt / Meta 組合鍵 (`META_ALT_ON` 或 `mAltLatched`)**:
   - 發送前綴轉義字元 `\x1b` (`0x1B`)，隨後發送該字元的 ASCII 碼。例如 Alt+F 輸出 `\x1bf`，Alt+B 輸出 `\x1bb`（在 Readline / Bash 中實現 word-forward / word-back 導航）。

#### D. 虛擬快捷鍵列 (Virtual Extra Keys Toolbar Support)
在 TerminalApp 頂部或底部提供快速輸入欄，包含 Ctrl, Alt, Esc, Tab, Arrow, `|`, `/`, `-`, `~` 按鍵。
- 當使用者按下 `Ctrl` 按鈕，設定 `mCtrlLatched = true`；按下下一個字元鍵（如 `C`）時，生成 `Ctrl+C` (`0x03`)，並將 `mCtrlLatched` 解除。

---

## 4. F-R3-004: Multi-stage CJK IME Commit Pipeline 管道設計 (Detailed Spec)

### 4.1 CJK IME 多階段組字架構 (Two-Stage Pipeline)

在傳統 Terminal 中，每次輸入均會立即傳送給 PTY。然而注音（如 `5j0` -> `ㄘㄨㄛ` -> `測試`）、倉頡（如 `oid` -> `個`）、拼音（如 `nihao` -> `你好`）需要多階段鍵入與候選字選擇。

```
[使用者按下注音/拼音按鍵]
           |
           v
InputConnection.setComposingText("ㄘㄨㄛ", 1)
           |
           +---> 1. 更新 CjkComposingTextManager 緩衝區
           +---> 2. 觸發 TerminalView 在 Terminal 游標處繪製 Inline 底線字元
           +---> 3. 【阻斷】不發送任何 byte 至 PTY Vsock Port 5001！
           |
[使用者選擇候選字 "測試" 或按下 Enter/Space 確定]
           |
           v
InputConnection.commitText("測試", 1)
           |
           +---> 1. 清空 CjkComposingTextManager 緩衝區
           +---> 2. 隱藏 Inline 組字視窗
           +---> 3. 將 "測試" 轉為 UTF-8 bytes: [0xE6, 0xB8, 0xAC, 0xE8, 0xA9, 0xB6]
           +---> 4. 經由 PtySender 寫入 PTY Vsock Channel
```

### 4.2 核心 API 覆寫實現細節 (Method Override Specifications)

#### 1. `setComposingText(CharSequence text, int newCursorPosition)`
- **職責**：攔截 IME 中間拼音/符號預覽。
- **邏輯**：
  ```java
  @Override
  public boolean setComposingText(CharSequence text, int newCursorPosition) {
      if (text == null) text = "";
      mComposingManager.setComposingText(text, newCursorPosition);
      mView.updateInlineComposing(mComposingManager.getComposingText(), mComposingManager.getCursorPosition());
      notifyCursorAnchorInfo();
      return true;
  }
  ```

#### 2. `commitText(CharSequence text, int newCursorPosition)`
- **職責**：接收 IME 最終確定輸出的字串，並發送至 Linux PTY。
- **邏輯**：
  ```java
  @Override
  public boolean commitText(CharSequence text, int newCursorPosition) {
      if (text != null && text.length() > 0) {
          mComposingManager.clear();
          mView.hideInlineComposing();
          byte[] utf8Bytes = text.toString().getBytes(StandardCharsets.UTF_8);
          mPtySender.sendBytes(utf8Bytes);
      }
      return true;
  }
  ```

#### 3. `finishComposingText()`
- **職責**：當 IME 完成組字階段（如使用者點擊非輸入區域或確定輸入）時呼叫。
- **邏輯**：若緩衝區中有殘留組字文字，將其作為確定文字 commit，或清空緩衝區並隱藏組字視窗。

#### 4. `deleteSurroundingText(int beforeLength, int afterLength)`
- **職責**：處理 IME 刪除請求（如注音組字過程按 Backspace 刪除聲調或拼音字母）。
- **邏輯**：
  - **若處於組字狀態 (`mComposingManager.isComposing()`)**：在 `mComposingManager` 緩衝區內部刪除 `beforeLength` 個字元，並更新 Inline 視窗。
  - **若非組字狀態**：向 PTY 發送 `beforeLength` 個 Backspace 位元組 (`\x7f`)。

#### 5. IME 狀態查詢 API 覆寫 (避免 IME 崩潰)
Android 第三方輸入法 (Gboard, SwiftKey, 搜狗/百度輸入法) 在組字時會頻繁查詢游標前後文字：
- `getTextBeforeCursor(int n, int flags)`：返回 `mComposingManager` 中游標前的組字文字。若非組字狀態，返回 Terminal 當前列游標左側文字。
- `getTextAfterCursor(int n, int flags)`：返回 `mComposingManager` 中游標後的組字文字。
- `getSelectedText(int flags)`：返回 `mComposingManager` 中被選取的組字子字串。
- `getExtractedText(ExtractedTextRequest request, int flags)`：構建並返回 `ExtractedText` 物件，填入當前組字文字與游標位置。

#### 6. `performEditorAction(int actionCode)`
- 當 IME 發送 `IME_ACTION_DONE`、`IME_ACTION_GO` 或 `IME_ACTION_SEND` 時，轉換為發送 `\r` (Enter 鍵) 至 PTY。

---

### 4.3 CjkComposingWindow Inline 視窗與 UI 視覺設計

為了提供最直覺的輸入體驗，採用 **Inline Canvas Overlay (畫布內嵌渲染)** 結合 **`CursorAnchorInfo` 游標定位**：

```
+-------------------------------------------------------------+
| user@debian:~$ ls -la /tmp/                                 |
| user@debian:~$ echo "ㄘㄨㄛ"                                 |  <-- Terminal 游標處 (Row 2, Col 21)
|                        ~~~~~ (半透明灰底 + 黃色/白色底線)   |  <-- CjkComposingWindow Inline 渲染
+-------------------------------------------------------------+
           |
           | CursorAnchorInfo 更新 (螢幕 X, Y 座標)
           v
+-------------------------------------------------------------+
|  [ 測試 ] [ 挫 ] [ 錯 ] [ 措 ] [ 措手不及 ]                  |  <-- Gboard / IME 浮動候選字視窗
+-------------------------------------------------------------+
```

#### A. 畫布內嵌組字視窗 (Inline Canvas Overlay) 繪製參數
- **繪製位置**：根據 Terminal 當前游標行列座標 `(cursorCol * cellWidth, cursorRow * cellHeight)` 決定起始 X, Y。
- **文字樣式**：
  - 背景色：半透明深灰/藍色 (`0xCC223344`) 矩形框。
  - 文字顏色：亮黃色 (`0xFFFFD700`) 或純白色。
  - 輔助線：`Paint.setUnderlineText(true)` 或畫布手動繪製 2dp 厚度虛線/實線，代表正處於 IME 編輯狀態。

#### B. 游標錨點資訊更新 (`updateCursorAnchorInfo`)
為使 Gboard / 系統 IME 的選字框正確貼合 Terminal 游標：
```java
public void notifyCursorAnchorInfo() {
    CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
    int[] locationOnScreen = new int[2];
    mView.getLocationOnScreen(locationOnScreen);

    float cursorX = locationOnScreen[0] + mView.getCursorCol() * mView.getCellWidth();
    float cursorY = locationOnScreen[1] + mView.getCursorRow() * mView.getCellHeight();
    float lineHeight = mView.getCellHeight();

    builder.setInsertionMarkerLocation(cursorX, cursorY, cursorY + lineHeight, cursorY + lineHeight, 0);

    InputMethodManager imm = (InputMethodManager) mView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null) {
        imm.updateCursorAnchorInfo(mView, builder.build());
    }
}
```

---

### 4.4 邊界條件與異常恢復 (Edge Cases & Exception Handling)

1. **PTY 接收外部中斷 (Ctrl+C / SIGINT / Shell Clear)**：
   - 當 Terminal 收到 PTY 傳回的 Control-C 或清屏轉義碼時，呼叫 `TerminalInputConnection.cancelComposing()`。
   - 清空 `CjkComposingTextManager` 並隱藏 Inline 組字視窗，防止殘留無效的組字預覽。
2. **焦點切換 (Focus Loss)**：
   - 當 `TerminalView.onWindowFocusChanged(false)` 或 view 失去焦點時，自動執行 `finishComposingText()`，確保輸入法狀態清空。
3. **超長文字批次 Commit (Long Paste / Large IME Commit)**：
   - 當貼上超長文字（如 2000 個字元的 CJK 文本）或 IME 一次性 commit 大量字串時，`commitText` 將 UTF-8 位元組切分為 1KB 區塊 (Chunking)，經由 `VsockPtyFramer` 序列化輸出，防止 Socket 緩衝區溢位。
4. **組字狀態下的 Backspace vs 一般 Backspace**：
   - 當 `mComposingManager.isComposing() == true`：Backspace 僅在 Android 端修改組字字串，不傳送位元組給 Linux PTY。
   - 當 `mComposingManager.isComposing() == false`：Backspace 發送 `\x7f` 給 Linux PTY 執行 Linux Shell 的字元刪除。

---

## 5. 類別結構設計與 API 簽名 (Class Structures & API Design)

### 5.1 核心類別清單 (Packages & Files)

```
packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/
├── ime/
│   ├── TerminalInputConnection.java   # 主 InputConnection 實作
│   ├── CjkComposingTextManager.java   # CJK 組字狀態與緩衝區管理器
│   ├── TerminalKeyEncoder.java        # KeyCode & Meta 到 ANSI/VT100 轉義碼編碼器
│   └── CjkComposingWindow.java        # 畫布 Inline 組字視窗繪製器
├── net/
│   └── PtySender.java                 # PTY Byte Stream 發送介面
```

### 5.2 詳細類別 API 簽名 (Class Signatures)

#### 1. `TerminalInputConnection.java`
```java
package com.android.virtualization.terminal.ime;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import com.android.virtualization.terminal.net.PtySender;

public class TerminalInputConnection extends BaseInputConnection {
    private final View mTargetView;
    private final PtySender mPtySender;
    private final CjkComposingTextManager mComposingManager;
    private final TerminalKeyEncoder mKeyEncoder;

    public TerminalInputConnection(View targetView, boolean fullEditor, PtySender ptySender);

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition);

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition);

    @Override
    public boolean finishComposingText();

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength);

    @Override
    public boolean setComposingRegion(int start, int end);

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags);

    @Override
    public CharSequence getTextAfterCursor(int n, int flags);

    @Override
    public CharSequence getSelectedText(int flags);

    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags);

    @Override
    public boolean sendKeyEvent(KeyEvent event);

    @Override
    public boolean performEditorAction(int actionCode);

    public void cancelComposing();
}
```

#### 2. `CjkComposingTextManager.java`
```java
package com.android.virtualization.terminal.ime;

public class CjkComposingTextManager {
    private final StringBuilder mComposingBuffer = new StringBuilder();
    private int mCursorPosition = 0;

    public synchronized void setComposingText(CharSequence text, int newCursorPosition);
    public synchronized String getComposingText();
    public synchronized int getCursorPosition();
    public synchronized boolean isComposing();
    public synchronized void deleteBeforeCursor(int length);
    public synchronized void clear();
}
```

#### 3. `TerminalKeyEncoder.java`
```java
package com.android.virtualization.terminal.ime;

import android.view.KeyEvent;

public class TerminalKeyEncoder {
    public static byte[] encodeKeyEvent(KeyEvent event, int metaState, boolean ctrlLatched, boolean altLatched);
    public static byte[] encodeCtrlKey(int keyCode);
    public static byte[] encodeAltKey(int keyCode);
}
```

---

## 6. 單元測試與驗證方案 (Unit Test Suite & Verification Strategy)

為確保輸入引擎與 CJK 管道無懈可擊，建立完整單元測試與模擬測試：

### 6.1 測試套件規劃 (Test Classes)

1. `TerminalKeyEncoderTest.java` (單元測試)
   - 驗證 `KEYCODE_DEL` -> `\x7f`
   - 驗證 `KEYCODE_ENTER` -> `\r`
   - 驗證 `KEYCODE_DPAD_UP` -> `\x1b[A`
   - 驗證 `Ctrl+C` (`KEYCODE_C` + `META_CTRL_ON`) -> `\x03`
   - 驗證 `Ctrl+D` (`KEYCODE_D` + `META_CTRL_ON`) -> `\x04`
   - 驗證 `Alt+F` -> `\x1bf`
   - 驗證 `F1` (`KEYCODE_F1`) -> `\x1bOP`

2. `CjkComposingTextManagerTest.java` (單元測試)
   - 注音輸入模擬：`ㄘ` -> `ㄘㄨ` -> `ㄘㄨㄛ` 組字緩衝區累積。
   - 候選字選擇：`commitText("測試")` 清空緩衝區並返回正確的 6 位元組 UTF-8 陣列 (`0xE6 0xB8 0xAC 0xE8 0xA9 0xB6`)。
   - 刪除測試：在 `ㄘㄨㄛ` 狀態下 `deleteSurroundingText(1, 0)` 變更為 `ㄘㄨ`。

3. `TerminalInputConnectionTest.java` (Android 模擬/整合測試)
   - 驗證 IME 查詢 API (`getTextBeforeCursor`, `getExtractedText`) 不返回 null。
   - 驗證中途 `cancelComposing()` 清理狀態。

---

## 7. 結論 (Conclusion)

本設計方案針對 F-R3-003（TerminalInputConnection）與 F-R3-004（Multi-stage CJK IME Commit）提供了完整且強健的架構藍圖。透過 **雙階段組字/提交隔離管道**、**完整的 ANSI/VT100 轉義編碼器**、**Inline 畫布視窗與 CursorAnchorInfo 錨點更新**，能完美相容繁體中文（注音/倉頡）、簡體中文（拼音）及實體/虛擬鍵盤，同時解決傳統 Terminal IME 輸入亂碼、組字文字洩漏與輸入法崩潰問題。
