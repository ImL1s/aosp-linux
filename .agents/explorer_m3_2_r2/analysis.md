# Milestone M3 (Iteration 2 Remediation) Technical Analysis Report

**專案**: AOSP Dual-OS System (Native Touch Terminal & IME - M3)  
**分析人員**: Explorer 2 (`explorer_m3_2_r2`)  
**日期**: 2026-08-06  
**目標範疇**: 
1. **F-R3-003 (TerminalInputConnection) & Java 語法**: 修復 `TerminalKeyEncoder.java` 與相關檔案中 Java 字串逃逸序列錯誤（將 `"\x1b"` 替換為 `"\u001b"` / `"\033"` 以修復 130 個 `javac` 語法錯誤）、統一套件結構 (`com.android.virtualization.terminal.*`)，並修復單元測試 (`TerminalAppUnitTest.java`)。
2. **F-R3-004 (Multi-stage CJK IME Commit)**: 修復 `CjkComposingTextManager.java` 中 `deleteBeforeCursor` 之 `StringIndexOutOfBoundsException` 邊界檢查漏洞與游標計算邏輯。

---

## 1. 現狀問題診斷與觀察 (Observation)

### 1.1 問題 1：Java 字串逃逸序列語法錯誤 (`"\x1b"`)
* **現象**: 執行 `javac` 編譯 `packages/apps/LinuxTerminal/src` 時拋出 130 個 `illegal escape character` 錯誤。
* **精確代碼位址**:
  1. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/TerminalKeyEncoder.java`: Lines 46, 52-94 (`"\x1b[Z"`, `"\x1b[A"`, `"\x1b[B"`, `"\x1b[C"`, `"\x1b[D"`, `"\x1b[H"`, `"\x1b[F"`, `"\x1b[2~"`, `"\x1b[3~"`, `"\x1b[5~"`, `"\x1b[6~"`, `"\x1bOP"`, `"\x1bOQ"`, `"\x1bOR"`, `"\x1bOS"`, `"\x1b[15~"`, `"\x1b[17~"`, `"\x1b[18~"`, `"\x1b[19~"`, `"\x1b[20~"`, `"\x1b[21~"`, `"\x1b[23~"`, `"\x1b[24~"`)
  2. `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/touch/SgrMouseProtocolGenerator.java`: Lines 60, 68, 77, 87, 100 (`"\x1b[<0;%d;%d;M"`, `"\x1b[<32;%d;%d;M"`, `"\x1b[<%d;%d;%d;M"`, `"\x1b[<0;%d;%d;m"`, `"\x1b[<%d;%d;%d;%s"`)
  3. `tests/unit/TerminalAppUnitTest.java`: Lines 152, 158 (`"\x1b[<0;10;20;M"`, `"\x1b[<64;15;30;M"`)
* **根本原因**: `\x` 是 C/C++ 與 Python 中的 16 進位逃逸字元，但在 Java 字串常數中為**非法語法**。Java 規範中 16 進位 Unicode 逃逸字元必須寫為 `\u001b` (4 位 16 進位)，八進位則為 `\033`。

### 1.2 問題 2：套件結構重複與類別遮蔽 (Package Duplication & Shadowing)
* **現象**: `packages/apps/LinuxTerminal/src/` 中存在兩套重複的 Java 原始碼：
  1. 根目錄扁平結構: `com.android.virtualization.terminal.*` (`CJKImeHandler.java`, `CjkComposingTextManager.java`, `CjkComposingWindow.java`, `ComposingTextSpan.java`, `PtySender.java`, `SgrMouseProtocolGenerator.java`, `TerminalCell.java`, `TerminalInputConnection.java`, `TerminalKeyEncoder.java`, `TerminalSurfaceView.java`, `TouchModeManager.java`, `TouchModeStateMachine.java`, `VTermParser.java`, `VsockPtyFramer.java`)
  2. 模組化子套件結構: `com.android.virtualization.terminal.{ime, net, parser, renderer, touch}.*`
* **根源分析**: 根目錄下的 duplicate 類別遮蔽了子套件中的正統實作，且導致 JNI 連結失敗（例：`libvterm_jni.cpp` 中匯出的 JNI 符號為 `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit`，指向 `.parser.VTermParser`；若根目錄的 `VTermParser.java` 在 `com.android.virtualization.terminal` 載入，將引發 `UnsatisfiedLinkError` 導致 JNI 無法連結）。

### 1.3 問題 3：CJK IME 組字刪除邊界異常 (`deleteBeforeCursor`)
* **現象**: 在 CJK IME（注音/倉頡/拼音）內聯組字視窗中進行退格（Backspace）刪除時，`CjkComposingTextManager.java` 之 `deleteBeforeCursor(int length)` 會觸發 `StringIndexOutOfBoundsException`。
* **根源分析**:
  1. **舊實作游標計算不符 Android IME 規範**: Android `InputConnection.setComposingText(text, newCursorPosition)` 規範規定：
     - 若 `newCursorPosition > 0`，游標位置相對於組字字串**末尾**：`targetCursor = text.length() + (newCursorPosition - 1)`。
     - 若 `newCursorPosition <= 0`，游標位置相對於組字字串**開頭**：`targetCursor = newCursorPosition`。
     舊程式碼僅作 `Math.min(text.length(), newCursorPosition)`，當 IME 傳入 `setComposingText("ㄘㄨㄛ", 1)` 時，游標被誤設為 **1** 而非 **3**。
  2. **缺少防禦性邊界保護**: 當呼叫 `deleteBeforeCursor` 時，若 `mCursorPosition` 未受限在 `[0, mComposingBuffer.length()]` 區間內，`mComposingBuffer.delete(start, mCursorPosition)` 計算出的 `start` 或 `end` 會超越 `StringBuilder` 長度或變為負數，拋出 `StringIndexOutOfBoundsException`。

---

## 2. 完整技術修復策略 (Remediation Strategy)

### 策略 1：統一套件結構與刪除影子重複檔案
1. **刪除根目錄陰影重複檔案** (位於 `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/`):
   - 刪除：`CJKImeHandler.java`, `CjkComposingTextManager.java`, `CjkComposingWindow.java`, `ComposingTextSpan.java`, `PtySender.java`, `SgrMouseProtocolGenerator.java`, `TerminalCell.java`, `TerminalInputConnection.java`, `TerminalKeyEncoder.java`, `TerminalSurfaceView.java`, `TouchModeManager.java`, `TouchModeStateMachine.java`, `VTermParser.java`, `VsockPtyFramer.java`
2. **維護標準模組化套件結構**:
   - `com.android.virtualization.terminal` (主 entry points): `TerminalActivity.java`, `TerminalView.java`
   - `com.android.virtualization.terminal.ime`: `TerminalInputConnection.java`, `TerminalKeyEncoder.java`, `CjkComposingTextManager.java`, `CjkComposingWindow.java`, `CJKImeHandler.java`, `ComposingTextSpan.java`
   - `com.android.virtualization.terminal.net`: `PtySender.java`, `VsockPtyFramer.java`
   - `com.android.virtualization.terminal.parser`: `VTermParser.java`
   - `com.android.virtualization.terminal.renderer`: `NativeSurfaceCanvasRenderer.java`, `TerminalSurfaceView.java`, `TerminalScreenMatrix.java`, `TerminalCell.java`, `GlyphCache.java`, `ColorPalette.java`
   - `com.android.virtualization.terminal.touch`: `TouchModeStateMachine.java`, `SgrMouseProtocolGenerator.java`, `TouchModeManager.java`
3. **更新 `TerminalActivity.java` 與 `TerminalView.java` 之 import**:
   明確匯入各子套件，解除類別歧義。

---

### 策略 2：修正 Java 逃逸序列 (F-R3-003)
將所有 `.java` 原始碼中的 `"\x1b"` 全數替換為 Java 標準 ANSI ESC 逃逸序列 `"\u001b"`：
* 在 `TerminalKeyEncoder.java`:
  ```java
  case KeyEvent.KEYCODE_TAB:
      return isShift ? "\u001b[Z".getBytes(StandardCharsets.US_ASCII) : new byte[]{'\t'};
  case KeyEvent.KEYCODE_DPAD_UP:
      return "\u001b[A".getBytes(StandardCharsets.US_ASCII);
  // ... (F1-F12, Insert, Delete, PageUp, PageDown 全數修正)
  ```
* 在 `SgrMouseProtocolGenerator.java`:
  ```java
  sb.append(String.format("\u001b[<0;%d;%d;M", col, row));
  ```
* 在 `TerminalAppUnitTest.java`:
  ```java
  if (!"\u001b[<0;10;20;M".equals(packet)) ...
  ```

---

### 策略 3：修復 CJK IME `deleteBeforeCursor` 邊界保護與游標計算 (F-R3-004)

修改 `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/CjkComposingTextManager.java`：

```java
package com.android.virtualization.terminal.ime;

/**
 * Manages CJK IME Composing Text state (Zhuyin / Cangjie / Pinyin) with buffering and cursor tracking.
 */
public class CjkComposingTextManager {
    public static final int MAX_COMPOSING_LENGTH = 256;

    private final StringBuilder mComposingBuffer = new StringBuilder();
    private int mCursorPosition = 0;

    public synchronized void setComposingText(CharSequence text, int newCursorPosition) {
        mComposingBuffer.setLength(0);
        if (text != null) {
            String s = text.toString();
            if (s.length() > MAX_COMPOSING_LENGTH) {
                s = s.substring(0, MAX_COMPOSING_LENGTH);
            }
            mComposingBuffer.append(s);
        }
        
        int len = mComposingBuffer.length();
        int targetCursor;
        if (newCursorPosition > 0) {
            targetCursor = len + (newCursorPosition - 1);
        } else {
            targetCursor = newCursorPosition;
        }
        mCursorPosition = Math.max(0, Math.min(len, targetCursor));
    }

    public synchronized String getComposingText() {
        return mComposingBuffer.toString();
    }

    public synchronized int getCursorPosition() {
        return mCursorPosition;
    }

    public synchronized boolean isComposing() {
        return mComposingBuffer.length() > 0;
    }

    public synchronized void deleteBeforeCursor(int length) {
        int bufferLen = mComposingBuffer.length();
        if (bufferLen == 0 || length <= 0) {
            return;
        }
        
        // 嚴格夾持 mCursorPosition 在合法範圍 [0, bufferLen]
        mCursorPosition = Math.max(0, Math.min(bufferLen, mCursorPosition));
        
        int deleteCount = Math.min(mCursorPosition, length);
        if (deleteCount <= 0) {
            return;
        }
        
        int start = Math.max(0, mCursorPosition - deleteCount);
        int end = Math.min(bufferLen, mCursorPosition);
        
        if (start < end) {
            mComposingBuffer.delete(start, end);
            mCursorPosition = start;
        }
    }

    public synchronized void clear() {
        mComposingBuffer.setLength(0);
        mCursorPosition = 0;
    }
}
```

---

### 策略 4：修復單元測試 Suite (`TerminalAppUnitTest.java`)

修復 `tests/unit/TerminalAppUnitTest.java`：
1. 替換逃逸序列 `"\x1b"` -> `"\u001b"`。
2. 保留對子套件 (`com.android.virtualization.terminal.ime.*`, `.net.*`, `.renderer.*`, `.touch.*`) 的正統 import。
3. 驗證所有測試函數 (`testVsockPtyFramer`, `testTouchModeStateMachine`, `testSgrMouseProtocolGenerator`, `testTerminalKeyEncoder`, `testCjkComposingTextManager`, `testColorPaletteAndScreenMatrix`) 均可通過 `javac` 編譯與 Java 虛擬機執行。

---

## 3. 擬議變更對照 (Proposed Modifications)

### 對照 1: `TerminalKeyEncoder.java` (Proposed Patch Snippet)
```java
- case KeyEvent.KEYCODE_TAB: return isShift ? "\x1b[Z".getBytes(StandardCharsets.US_ASCII) : new byte[]{'\t'};
+ case KeyEvent.KEYCODE_TAB: return isShift ? "\u001b[Z".getBytes(StandardCharsets.US_ASCII) : new byte[]{'\t'};
- case KeyEvent.KEYCODE_DPAD_UP: return "\x1b[A".getBytes(StandardCharsets.US_ASCII);
+ case KeyEvent.KEYCODE_DPAD_UP: return "\u001b[A".getBytes(StandardCharsets.US_ASCII);
```

### 對照 2: `CjkComposingTextManager.java` (Proposed Patch Snippet)
```java
  public synchronized void deleteBeforeCursor(int length) {
-     if (mComposingBuffer.length() == 0 || length <= 0) return;
-     int deleteCount = Math.min(mCursorPosition, length);
-     int start = mCursorPosition - deleteCount;
-     mComposingBuffer.delete(start, mCursorPosition);
-     mCursorPosition = start;
+     int bufferLen = mComposingBuffer.length();
+     if (bufferLen == 0 || length <= 0) return;
+     mCursorPosition = Math.max(0, Math.min(bufferLen, mCursorPosition));
+     int deleteCount = Math.min(mCursorPosition, length);
+     if (deleteCount <= 0) return;
+     int start = Math.max(0, mCursorPosition - deleteCount);
+     int end = Math.min(bufferLen, mCursorPosition);
+     if (start < end) {
+         mComposingBuffer.delete(start, end);
+         mCursorPosition = start;
+         }
  }
```

---

## 4. 驗證與驗收方式 (Verification Method)

1. **Java 編譯驗證**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d /tmp/classes $(find packages/apps/LinuxTerminal/src -name "*.java") tests/unit/TerminalAppUnitTest.java
   ```
   *預期結果*: `javac` 以 Code 0 順利完成編譯，0 個語法與符號錯誤。

2. **Java 單元測試執行**:
   ```bash
   java -cp /tmp/classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```
   *預期結果*: 印出 `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`。

3. **CJK IME 邊界條件單元測試驗證**:
   測試 `deleteBeforeCursor` 在 `mCursorPosition=0`, `length=100`, `mCursorPosition > buffer.length()`, `text=null` 等極限情況下均無例外拋出。
