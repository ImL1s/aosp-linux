# Handoff Report — Milestone M3 Iteration 3 Remediation Strategy (`handoff.md`)

**Agent**: Explorer 3 (`explorer_m3_3_r3`)  
**Date**: 2026-08-06  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3_r3`  
**Handoff Type**: Hard Handoff (Task Complete)

---

## 1. Observation (觀察)

1. **Reviewer 2 INTEGRITY VIOLATION 報告**：
   - 參照 `.agents/reviewer_m3_2_r2/review.md` (Line 14-41, Line 64-66):
     - Finding 1: `TerminalView.java:166` 中的 `TOUCHPAD_MODE` 為空 Stub (`case TOUCHPAD_MODE: return true;`)，未實現相對位移追蹤與 SGR 1006 封包生成。
     - Finding 2: `TerminalView.java:95-111` 中的 `sendBytes()`、`sendFrame()`、`sendResize()` 僅印出 `Log.d` 日誌，未將封包傳遞給 `mVsockClient.sendFrame()` 進行 AF_VSOCK Socket 傳輸。
2. **Dead Ends Log**：
   - 參照 `.agents/sub_orch_m3/DEAD_ENDS.md` (Line 6):
     - Iteration 2 死路記錄：`TOUCHPAD_MODE` 返回 true 卻無相對位移追蹤；`sendBytes`/`sendFrame` 僅記錄 `Log.d` 卻未調用 `mVsockClient.sendFrame`。
3. **現有單元測試與 E2E 測試現狀**：
   - 參照 `tests/unit/TerminalAppUnitTest.java` (Line 15-257): 包含 `testVsockPtyFramer()`, `testTouchModeStateMachine()`, `testSgrMouseProtocolGenerator()`, `testTerminalKeyEncoder()`, `testCjkComposingTextManager()`, `testColorPaletteAndScreenMatrix()`，尚未涵蓋 `TOUCHPAD_MODE` 相對游標運動斷言及 `VsockTerminalClient` Socket 發送斷言。
   - 參照 `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` 與 `tests/e2e/tier2_boundary_corner/test_m3_tier2.py`: `ensure_binaries_built()` 透過 `javac` 及 `g++` 編譯 Java `.class` 與 Native C++ 測試執行檔，並經由 `CommandRunner.run()` 執行。

---

## 2. Logic Chain (邏輯推理鏈)

1. **從 Observation 1 (Finding 1 & Finding 2) 到修復需求**：
   - Reviewer 2 明確指出 Iteration 2 REQUEST_CHANGES 乃因 `TOUCHPAD_MODE` 未實現相對運動/手勢生成，以及 `TerminalView` 未串接 `VsockTerminalClient.sendFrame()`。
   - 因此，Iteration 3 的核心修復必須補齊 `TOUCHPAD_MODE` 相對位移與 SGR 手勢轉換邏輯，並將 `TerminalView` / `PtySender` 的發送方法連結至 `mVsockClient.sendFrame()`。
2. **從 Observation 2 到死路避開 (Dead Ends Avoidance)**：
   - `DEAD_ENDS.md` 已列明 Facade/unwired touch mode 與 vsock `Log.d` 僅記錄不發送為無效作法。
   - 因此，測試驗證策略必須設計真實的 Socket Transmission 單元測試與真實相對位移計算測試，杜絕任何 Facade 或 Dummy 回傳。
3. **從 Observation 3 到測試斷言與二進位執行保障**：
   - 在 `TerminalAppUnitTest.java` 中新增 `testTouchpadModeEventGeneration()` 驗證：初始網格 (40,12)、相對位移 deltaCalculations (dx=+40, dy=-80 -> (42,10))、Tap 發送 `\033[<0;42;10M\033[<0;42;10m`、LongPress 發送 `\033[<2;42;10M\033[<2;42;10m`、Two-finger Scroll 發送 `\033[<65;42;10M`、邊界 Clamping (80,24)。
   - 在 `TerminalAppUnitTest.java` 中新增 `testVsockTerminalClientSocketTransmission()` 驗證：經由本地 ServerSocket 建立實體 Socket 連接，驗證 `VsockTerminalClient` 寫入 Header 與 Payload 位元組數與內容無誤。
   - 在 `test_m3_tier1.py` 與 `test_m3_tier2.py` 中，保持 `ensure_binaries_built()` 實體 `javac` 與 `g++` 編譯流程，確保 Python 測試套件繼續執行真實 compiled Java 與 C++ binaries。

---

## 3. Caveats (注意事項與未探討領域)

- **環境限制**：macOS 本地開發環境不具備 Linux kernel 原生 `AF_VSOCK` 裝置驅動，因此單元測試在非 Android/QEMU 環境下使用 `java.net.ServerSocket` (TCP/loopback socket) 驗證 `VsockTerminalClient` / `VsockPtyFramer` 之 Socket 串流發送與 Header 解析邏輯；在 AOSP/crosvm 環境下則自動轉為原生 `AF_VSOCK` socket。
- **UI 觸控與 MotionEvent 模擬**：單元測試中使用手勢處理器 (`TouchpadGestureHandler`) 進行純邏輯斷言，避免無 UI 畫面的 Headless CI 環境發生 Android Input Window 相關例外。

---

## 4. Conclusion (結論)

已完成 Milestone M3 Iteration 3 Remediation 的技術測試驗證策略制定。
詳細分析文件已寫入 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3_r3/analysis.md`。

關鍵策略結論：
1. **`TerminalAppUnitTest.java` 新增斷言**：
   - `testTouchpadModeEventGeneration()`: 驗證相對位移 $(\Delta x, \Delta y)$ 游標網格計算、Tap (SGR Button 0)、LongPress (SGR Button 2)、Two-finger Scroll (SGR Buttons 64/65) 封包生成與座標 Clamping。
   - `testVsockTerminalClientSocketTransmission()`: 驗證實體 Socket 寫入、`VsockPtyFramer` Header (16B SessionID + 1B Type + 4B Length) 與 Payload 位元組驗證。
2. **`test_m3_tier1.py` & `test_m3_tier2.py` 二進位執行**：
   - 維持 `ensure_binaries_built()` 與 `CommandRunner.run()` 調用 compiled Java `.class` (`TerminalAppUnitTest`) 及 Native C++ binaries，全數 80 項測試維持 100% 真實二進位檔執行。

---

## 5. Verification Method (驗證方法)

1. **Java 單元測試編譯與執行**：
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') tests/unit/TerminalAppUnitTest.java
   java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```
   **預期輸出**：`JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY` 且包含 `TOUCHPAD_MODE` 與 `VsockTerminalClient Real Socket Transmission` 測試結果。

2. **C++ Native 壓力與單元測試編譯執行**：
   ```bash
   g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_terminal_test.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_terminal_test && /tmp/m3_native_terminal_test
   g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni tests/unit/m3_native_challenger2_stress.cpp packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp -o /tmp/m3_native_challenger2_stress && /tmp/m3_native_challenger2_stress
   ```

3. **Python E2E 測試套件全量驗證**：
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
   **預期輸出**：80 tests executed, 80 PASSED, 0 FAILED, Pass Rate 100.0%.
