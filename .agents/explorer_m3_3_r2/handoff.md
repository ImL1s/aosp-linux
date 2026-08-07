# Milestone M3 Iteration 2 Explorer 3 Handoff Report

**Author**: Explorer 3 (`explorer_m3_3_r2`)  
**Date**: 2026-08-06  
**Milestone**: M3 (Native Touch Terminal & IME — Iteration 2 Remediation)  
**Status**: COMPLETE (Hard Handoff)  
**Target Features**: F-R3-005, F-R3-006, F-R3-007  

---

## 1. Observation (觀察)

1. **F-R3-005 (Touch Modes State Machine)**:
   - **程式碼位置**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TouchModeStateMachine.java` (第 26-44 行) 與 `TerminalView.java` (第 131-134 行)。
   - **具體觀察**:
     - `TouchModeStateMachine.java` 中 `mIsManualLocked` 初始化為 `false` 且未持久化至 `SharedPreferences`。在 Activity/State Machine 重建後，`mIsManualLocked` 被重置為 `false`。
     - `TerminalView.java` 中 `onTouchEvent` 的 `case TOUCHPAD_MODE:` 僅包含註解 `// Relative motion processing for virtual touchpad; return true;`，無實際相對位移、點擊或滾輪手勢邏輯。

2. **F-R3-006 (SGR Mouse Protocol Generator)**:
   - **程式碼位置**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/SgrMouseProtocolGenerator.java` (第 58, 67, 76, 87, 98, 102 行) 及 `jni/sgr_mouse_generator.cpp` (第 27, 35, 43, 52 行)。
   - **具體觀察**:
     - Java 字串中包含無效轉義字元 `"\x1b"`，導致 `javac` 編譯報錯 `illegal escape character`。
     - Java 與 C++ 中的格式字串均含有多餘分號：`"\x1b[<%d;%d;%d;M"` 與 `"\x1b[<%d;%d;%d;m"`，輸出的 DEC SGR 1006 序列為 `ESC[<b;x;y;M`，座標與字符之間多出一個 `;`，與 DEC SGR 規範（`ESC[<b;x;yM`）不符。

3. **F-R3-007 (Vsock Port 5001 PTY Framing)**:
   - **程式碼位置**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` (第 83-89 行) 與 `VsockPtyFramer.java` (第 118-125 行)。
   - **具體觀察**:
     - `TerminalView.sendBytes()` 僅使用 `Log.d(TAG, "Sent PTY Frame over Port 5001...")` 記錄日誌，未開啟 `AF_VSOCK`（Port 5001）Socket 連線。
     - `VsockPtyFramer.java` 中 `int payloadLength = headerBuf.getInt()` 解構帶有 MSB 位元為 1 的長度時返回負整數（如 `-1`）。`payloadLength > MAX_PAYLOAD_SIZE`（`-1 > 65536`）評估為 `false`，繞過了邊界檢查，隨後觸發 `Arrays.copyOfRange(bytes, 21, 20)` 造成 `IllegalArgumentException` 崩潰。

---

## 2. Logic Chain (推導邏輯鏈)

1. **F-R3-005 推導邏輯**:
   - 觀察 1 顯示 `mIsManualLocked` 未持久化。當 Terminal Parser 解析到 Escape 序列（如 Vim 啟動發送 `\033[?1000h`）呼叫 `onTerminalEscapeMouseTrackingChanged(true)` 時，因為 `!mIsManualLocked` 成立，系統會強制覆蓋用戶手動設定的模式。因此，必須將 `KEY_PREF_MANUAL_LOCKED` 寫入 `SharedPreferences`。
   - 觀察 1 顯示 `TOUCHPAD_MODE` 為空白 Stub。實現虛擬觸控板必須捕捉 `ACTION_DOWN`、`ACTION_MOVE` 與 `ACTION_UP`，計算相對位移像素 `(dx, dy)` 並轉換為網格游標移動；同時根據按壓時間與指數量發送 SGR 按鍵封包（單擊=Button 0，長按=Button 2，雙指滾動=Button 64/65）。

2. **F-R3-006 推導邏輯**:
   - 觀察 2 顯示 Java 源碼使用 `"\x1b"`，這違背 Java 語言規範（Java 不支援 `\x` 轉義），必須全數替換為 `"\033"` 或 `"\u001b"`。
   - 觀察 2 顯示格式字串為 `"\x1b[<%d;%d;%d;M"`。DEC SGR 1006mouse protocol 規範明確規定格式為 `CSI < Button ; Column ; Row M`（Press/Motion）與 `CSI < Button ; Column ; Row m`（Release）。移除多餘的分號即可將格式修正為 `"\033[<%d;%d;%dM"` 與 `"\033[<%d;%d;%dm"`。

3. **F-R3-007 推導邏輯**:
   - 觀察 3 顯示 `TerminalView.sendBytes()` 僅有 Logcat 日誌。必須新增 `VsockTerminalClient` 類別，透過 Android `FileDescriptor` 建立與 Guest VM（AF_VSOCK, Port 5001）的 Socket 讀寫連線。
   - 觀察 3 顯示 `headerBuf.getInt()` 的結果可為負數。由於 Java 語言中 `int` 為有符號整數，當 4 位元組長度的最高位為 1 時，`getInt()` 回傳負數。防禦邊界條件必須同時檢查 `payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE`。遇到異常標頭時，`readOffset` 應前進 1 位元組以重新同步流邊界，防止緩衝區對齊失效。

---

## 3. Caveats (注意事項與未檢驗範疇)

- 本報告為 **Read-Only Investigation** 階段之策略規劃，未直接修改專案原始碼。修復方案需由後續 Implementer/Worker Agent 執行修改。
- 專案根目錄中存在部分重複定義之 Package 類別（`com.android.virtualization.terminal` 與 `.touch` / `.net` 子包），建議在 Worker 階段進行統一整理與重構。

---

## 4. Conclusion (結論)

F-R3-005、F-R3-006 與 F-R3-007 的缺陷根因清晰且完全被觀察與邏輯鏈所證實。修復策略具體、可行且具備明確的操作步驟：
1. **F-R3-005**: 於 `TouchModeStateMachine` 增加 `KEY_PREF_MANUAL_LOCKED` 之 `SharedPreferences` 讀寫，並於 `TerminalView` 完成 `TOUCHPAD_MODE` 之相對位移手勢發送邏輯。
2. **F-R3-006**: 於 `SgrMouseProtocolGenerator.java` 與 `sgr_mouse_generator.cpp` 中替換 `"\x1b"` 為 `"\033"` 並移除 `%d;M` 前的多餘分號 `;`。
3. **F-R3-007**: 在 `VsockPtyFramer.java` 增加 `payloadLength < 0` 負數防禦與流再同步邏輯，並建立 `VsockTerminalClient` 實現真實 AF_VSOCK 5001 Socket 傳輸。

---

## 5. Verification Method (獨立驗證方法)

1. **編譯檢查 (Java Compilation)**:
   ```bash
   javac -cp packages/apps/LinuxTerminal/src \
     packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/*.java
   ```
   *期望結果*: 編譯成功，無 `illegal escape character` 錯誤。

2. **Native C++ 應力測試 (Native Benchmark & Fuzzing)**:
   ```bash
   g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/ \
     tests/unit/m3_native_challenger2_stress.cpp \
     packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp \
     packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp \
     -o /tmp/m3_native_test && /tmp/m3_native_test
   ```
   *期望結果*: 所有 SGR 格式與 PTY Framing 測試全數通過（PASS）。

3. **E2E 測試覆蓋 (E2E Suite)**:
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
   *期望結果*: 測試真實調用產品二進位檔案，無硬編碼斷言。
