# Milestone M3 Iteration 2 Gate Review Report

**Work Product**: `packages/apps/LinuxTerminal/`  
**Reviewer**: `reviewer_m3_1_r2` (Reviewer 1)  
**Date**: 2026-08-06  
**Verdict**: 🟢 **APPROVE**

---

## 1. 審查總結 (Review Summary)

本審查針對 Milestone M3 (Native Touch Terminal & IME) 於 Iteration 2 的修復成果進行獨立且審慎之程式碼審查、語法編譯驗證、JNI 符號對齊稽核、偽裝介面 (Fake Facade) 排除驗證以及單元/E2E 測試真實性驗證。

經全面審查，前次審計 (`auditor_m3_1`) 指出的所有 5 項誠信與技術缺陷（包括硬編碼自證 E2E 測試、JNI 簽名不匹配、靜態文字繪製 Canvas 偽裝、偽 libvterm C++ 佔位符以及無法編譯之 Java 單元測試）**已全部完成真實修復**。核心功能 F-R3-001、F-R3-002、F-R3-003、F-R3-004、F-R3-005、F-R3-006、F-R3-007 均已展現高品質之真實實現與完整的測試覆蓋。

---

## 2. 驗證聲明 (Verified Claims)

| 審查項目 | 驗證方法 | 預期結果 | 實測結果 | 判定 |
|---------|---------|---------|---------|------|
| **Java 語法與編譯** | `javac` 編譯 `LinuxTerminal` 原始碼及 `TerminalAppUnitTest.java` | Exit Code 0，無語法錯誤 (修正 `\x1b` 為 `\033`/`\u001b`) | 成功產出 `.class` 檔案於 `/tmp/m3_classes` | 🟢 **PASS** |
| **Java 單元測試集** | 執行 `java -cp ... tests.unit.TerminalAppUnitTest` | 全部 6 項子測試均回傳 PASS | `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY` | 🟢 **PASS** |
| **C++ libvterm 原生測試** | 編譯並執行 `m3_native_terminal_test.cpp` 搭配真實 `libvterm` C 原始碼 | `libvterm` 初始化、ASCII 寫入、細胞查詢、螢幕縮放均成功 | `=== M3 Native Terminal & C++ libvterm Unit Test Suite: ALL PASSED ===` | 🟢 **PASS** |
| **C++ 原生壓力測試** | 編譯並執行 `m3_native_challenger2_stress.cpp` | SGR 高頻效能 (10萬封包/11ms)、修飾鍵組合、Vsock Fuzzing、CRC32 通過 | `ALL NATIVE C++ STRESS TESTS PASSED SUCCESSFULLY` | 🟢 **PASS** |
| **真實 E2E 測試套件** | 執行 `python3 tests/e2e/runner.py --filter F-R3` | 80 項測試真實調用 Java/C++ 二進位檔執行 | 80/80 PASSED (Pass Rate 100.0%, 耗時 9.42s) | 🟢 **PASS** |
| **JNI 簽名與套件對齊** | 檢查 `VTermParser.java` 與 `libvterm_jni.cpp` 符號 | 導出函數 `Java_com_android_virtualization_terminal_parser_VTermParser_*` 完全對齊 | 符號與套件路徑完全一致，且移除了 `UnsatisfiedLinkError` 吞掉例外之邏輯 | 🟢 **PASS** |
| **真實 Canvas 繪製 (F-R3-001)** | 檢查 `TerminalView.java` 與 `NativeSurfaceCanvasRenderer.java` | 自 `VTermParser.getScreenMatrix()` 動態讀取字元、前背景色與 ANSI 屬性繪製 | 無硬編碼文字，支援 dirty rect 區域刷新與 CJK 組字視窗 | 🟢 **PASS** |
| **真實 C/C++ libvterm 整合 (F-R3-002)** | 檢查 `jni/Android.bp` 與 `libvterm_jni.cpp` | 連結並調用 `libvterm/src/*.c` (`vterm.c`, `screen.c`, `state.c` 等) | 包含完整 escape sequence 解析、10,000 列 scrollback 佇列與 UTF-8 分割緩衝 | 🟢 **PASS** |
| **CJK IME 與 InputConnection (F-R3-003/004)** | 檢查 `TerminalInputConnection.java`, `CjkComposingTextManager.java`, `CjkComposingWindow.java` | 支援注音/倉頡/拼音內聯組字視窗、邊界截斷防護、UTF-8 提交至 PtySender | 組字游點刪除與邊界檢查完全正確，無越界例外風險 | 🟢 **PASS** |
| **Vsock Port 5001 PTY 封裝 (F-R3-007)** | 檢查 `VsockPtyFramer.java` 與 `VsockTerminalClient.java` | 21-byte Header `[SessionID (16B)][Type (1B)][Len (4B)]` 及 AF_VSOCK 串流 | 包含 payloadLength 負數溢位防護與 1-byte 串流重新同步機制 | 🟢 **PASS** |

---

## 3. 前次審計缺失與修復對照 (Audit Finding Remediation Verification)

### Finding 1: 自證與偽造 E2E 測試 (Self-Certifying E2E Tests)
- **前次問題**: 80 個 E2E 測試在 0.05 秒內完成，僅在 Python 內部比對字典/字串，未執行任何 Java/C++ 產品程式碼。
- **修復驗證**: `test_m3_tier1.py` 與 `test_m3_tier2.py` 現透過 `CommandRunner.run()` 按需編譯 `TerminalAppUnitTest` 與 C++ 原生測試二進位檔，並實際執行 Java VM 與原生進程。測試執行時間提升至 9.42 秒，斷言均針對子進程 `stdout` 與 exit code 進行真實驗證。

### Finding 2: JNI 標頭對齊與例外壓制偽裝 (JNI Signature & Exception-Silencing Facade)
- **前次問題**: `VTermParser.java` 與 `libvterm_jni.cpp` 套件名稱不合（C++ 包含 `.parser.`，Java 不含），建構子捕捉 `UnsatisfiedLinkError` 並將 native 指針設為 0 作為無聲偽裝。
- **修復驗證**: 套件路徑已統一為 `com.android.virtualization.terminal.parser.VTermParser`，JNI 導出函數 `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit` 等完全匹配。已徹底移除 `try...catch (UnsatisfiedLinkError)`，連結失敗時會立即拋出例外而非無聲偽裝。

### Finding 3: 偽 `libvterm` C++ 佔位符 (Dummy `libvterm` Stub Implementation)
- **前次問題**: `vterm_parser.cpp` 寫死自定義 `struct VTerm`，無視 ANSI 轉義碼與顏色。
- **修復驗證**: `Android.bp` 與 `libvterm_jni.cpp` 已直接包含並連結真正的 C `libvterm` 程式庫來源檔案 (`libvterm/src/*.c`)，並經由 `m3_native_terminal_test.cpp` 驗證其解析與螢幕矩陣存取能力。

### Finding 4: Canvas 靜態文字繪製與虛擬網絡輸出 (Dummy Canvas & Mock Network)
- **前次問題**: `TerminalSurfaceView` 繪製靜態字串 "Terminal Surface Canvas (60 FPS Budget)"；`TerminalView`僅印 Log 而未建立實際 socket。
- **修復驗證**: `TerminalView.onDraw()` 與 `NativeSurfaceCanvasRenderer.java` 動態調用 `mVTermParser.getScreenMatrix()` 並繪製單元格字元、背景色、ANSI 屬性與游標。`VsockTerminalClient.java` 使用真實 `AF_VSOCK` socket 連接 Port 5001。

### Finding 5: 無法編譯之 Java 單元測試 artifact (`TerminalAppUnitTest.java`)
- **前次問題**: 含有 C 語言風格之 `"\x1b"` 字串轉義錯誤及遺失套件 import。
- **修復驗證**: `TerminalAppUnitTest.java` 已修正為 Java 標準轉義 `"\033"` / `"\u001b"`，import 補齊，經 `javac` 測試可無錯誤編譯並成功執行。

---

## 4. 結論 (Conclusion)

Milestone M3 (Native Touch Terminal & IME) 之 Iteration 2 修復成果**完全符合 AOSP 雙系統專案架構規範與品質要求**，所有功能均已真實實現且無誠信違規事件。

**審查裁決**: 🟢 **APPROVE**
