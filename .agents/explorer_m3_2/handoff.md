# Handoff Report: Milestone M3 Explorer 2 (F-R3-003 & F-R3-004 Architecture Strategy)

## 1. Observation
- **目標組件檔案**: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalInputConnection.java`
- **現狀觀察**:
  - `TerminalInputConnection` (34 行) 僅為極簡骨架，`commitText` 轉 byte 後未寫入 Vsock 5001 PTY Stream。
  - `setComposingText` 僅輸出 Log 訊息，缺乏 CJK IME (注音/倉頡/拼音) 的多階段組字緩衝區。
  - 缺乏 `sendKeyEvent()` 按鍵事件處理，無法將 Backspace (`\x7f`)、Enter (`\r`)、方向鍵 (`\x1b[A/B/C/D`) 及 Ctrl/Alt 組合鍵編碼為 ANSI / VT100 轉義碼。
  - 缺乏 `getTextBeforeCursor` / `getExtractedText` / `deleteSurroundingText` 覆寫，導致第三方輸入法 (如 Gboard) 在查詢文字時恐遭遇 NullPointerException 或行為異常。
  - 未對接 `InputMethodManager.updateCursorAnchorInfo` 游標定位與 Canvas Inline 組字預覽視窗。

## 2. Logic Chain
1. **問題起因**: 傳統 Terminal 輸入僅支援即時 byte 傳送，無法適應 CJK IME 的多階段「注音符號/拼音字母鍵入 -> 候選字挑選 -> 最終確定」流程。若中間狀態直接送入 PTY，會導致 Shell command 污染與亂碼。
2. **推導結論**:
   - 必須設計 **雙階段 (Two-Stage) IME 輸入管道**：Stage 1 阻斷並在 Host `CjkComposingTextManager` 緩衝；Stage 2 (`commitText`) 再轉 UTF-8 位元組批次送入 Vsock 5001。
   - 必須設計 **ANSI/VT100 轉義編碼器 (`TerminalKeyEncoder`)**：負責將 `KeyEvent` 轉碼為 `\x7f` (DEL)、`\r` (CR)、`\x1b[A/B/C/D` (Arrow Keys) 及 `(keycode - KEYCODE_A + 1)` (Ctrl 組合鍵)。
   - 必須設計 **Inline 組字畫布與 CursorAnchorInfo**：提供 Terminal 游標位置上的灰底黃字底線預覽，並引導 Gboard 選字視窗浮動於正確螢幕座標。

## 3. Caveats
- 假設 Implementer 將建立對應的 `CjkComposingTextManager.java` 與 `TerminalKeyEncoder.java` 獨立輔助類別，並將 `PtySender` 介面導向 `VsockPtyFramer`。
- 本次調查為純 Read-only 規劃設計，並未直接對 `packages/apps/LinuxTerminal/` 進行程式碼寫入。

## 4. Conclusion
F-R3-003 與 F-R3-004 的技術策略已完整確立，詳細類別架構、API 覆寫規範、對照表、UI 繪製流程與單元測試設計已寫入 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_2/analysis.md`。

## 5. Verification Method
- **檔案檢查**:
  - 檢查 `analysis.md` 是否完整涵蓋 F-R3-003 與 F-R3-004 的設計規範、類別結構、API 簽名與對照表。
- **單元測試驗證指引**:
  - `TerminalKeyEncoderTest`: 驗證 KeyCode 到 ANSI 轉義碼 (Backspace `\x7f`, Enter `\r`, Up `\x1b[A`, Ctrl+C `\x03`) 轉換正確。
  - `CjkComposingTextManagerTest`: 驗證 `setComposingText` 隔離、`commitText` UTF-8 轉碼以及 `deleteSurroundingText` 邏輯。
