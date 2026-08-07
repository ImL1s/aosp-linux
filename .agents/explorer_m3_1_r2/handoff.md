# Milestone M3 (Iteration 2 Remediation) Technical Handoff Report

**Author**: Explorer 1 (`explorer_m3_1_r2`)  
**Date**: 2026-08-06  
**Status**: COMPLETE (Hard Handoff — Detailed Strategy Formulation)  
**Target Agent**: `worker_m3_gen2` / Orchestrator  

---

## 1. Observation (觀察事實)

1. **F-R3-001 (Native Surface Canvas Renderer) 假外殼與無效繪製**：
   - 檔名與行號：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalSurfaceView.java` (Lines 103-106)
   - 內容：
     ```java
     canvas.drawColor(Color.BLACK);
     canvas.drawText("Terminal Surface Canvas (60 FPS Budget)", 20, 50, mPaint);
     ```
   - 檔名與行號：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` (Lines 97-98)
   - 內容：
     ```java
     canvas.drawText("AOSP Linux Terminal Engine", 20, 80, mTextPaint);
     canvas.drawText("user@debian:~$ ", 20, 130, mTextPaint);
     ```
   - 檔名與行號：`packages/apps/LinuxTerminal/jni/terminal_renderer.cpp` (Lines 102-117)
   - 內容：`rasterizeGlyph` 填寫 `(x+y)%2 == 0` 棋盤格 alpha 地圖，完全未導出 JNI 方法供 Java 呼叫。

2. **F-R3-002 (libvterm Parser Integration) JNI/類別不符與編譯失敗**：
   - 檔名與行號：`packages/apps/LinuxTerminal/jni/libvterm_jni.cpp` (Line 92) 導出 `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit` (3 個參數)。
   - 檔名與行號：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/VTermParser.java` (Line 29) 宣告 `nativeInit(rows, cols)` (2 個參數)，且 static 方塊捕捉 `UnsatisfiedLinkError` (Lines 28-32)。
   - 檔名與行號：`packages/apps/LinuxTerminal/jni/vterm_parser.cpp` (Lines 25-118) 自創 C `struct VTerm` 與 `vterm_input_write` 虛擬迴圈，遇 `\n` 重置游標，忽略 ANSI escape codes。
   - 檔名與行號：`packages/apps/LinuxTerminal/jni/Android.bp` 未包含 `libvterm/src/*.c` 原始碼。
   - 檔名與行號：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/TerminalKeyEncoder.java` (Line 46, 52-94) 包含 `"\x1b"`，導致 `javac` 報錯 `illegal escape character` 達 130 處。
   - 重複類別：根套件 `com.android.virtualization.terminal` 與子套件 (`parser`, `renderer`, `ime`, `touch`, `net`) 存有 11 個同名重複 Java 檔案。

3. **測試套件缺乏真實性與編譯失敗**：
   - 檔名與行號：`tests/e2e/tier1_feature_coverage/test_m3_tier1.py` (Lines 21-24, 90-92) 斷言局部 Python dict：
     ```python
     surface_config = {"type": "SURFACE_TYPE_HARDWARE", "width": 1024, "height": 768, "valid": True}
     CustomAssertions.assert_equal(surface_config["type"], "SURFACE_TYPE_HARDWARE")
     ```
   - 檔名與行號：`tests/unit/TerminalAppUnitTest.java` (Line 152) 含有 `"\x1b"`。
   - 檔名與行號：`tests/unit/m3_native_terminal_test.cpp` 包含 `jni/third_party/libvterm/vterm.h` (Line 62 `boolean` 型別錯誤)，致 `g++` 編譯失敗。

---

## 2. Logic Chain (推導邏輯鏈)

1. **從觀察事實 1 推導 F-R3-001 補救**：
   - `TerminalSurfaceView.java`（根套件）與 `TerminalView.java` 僅輸出硬編碼文字，未讀取 `TerminalScreenMatrix` 格狀單元。
   - 因此必須刪除根套件假外殼，統一選用 `com.android.virtualization.terminal.renderer.TerminalSurfaceView` 綁定 `NativeSurfaceCanvasRenderer`。
   - `NativeSurfaceCanvasRenderer` 檢查 `dirtyGridRect` 後呼叫 `lockCanvas(pixelDirty)` 寫入背景色矩形與文字 Character，並依需求導出 `ANativeWindow_fromSurface` / `ANativeWindow_lock` JNI 原生渲染，徹底擺脫靜態文字。

2. **從觀察事實 2 推導 F-R3-002 補救**：
   - JNI 套件名稱與參數不符源於 M3 Iteration 1 在根套件殘留的假外殼檔案。刪除根套件下 11 個重複檔案可消除歧義。
   - `libvterm_jni.cpp` 已具備正確之 JNI 簽名與 `libvterm` 呼叫邏輯。將 `jni/libvterm/src/*.c` 加入 `Android.bp` 即可成功編譯並載入 `vterm_jni.so`。
   - 在 `libvterm_jni.cpp` 中加入 `AttachCurrentThread` / `DetachCurrentThread` 與 `DeleteLocalRef(cbClass)`，解決背景 Thread 回呼崩潰與 Local Ref 溢出。
   - 將所有 Java 檔案中的 `"\x1b"` 替換為 `"\u001b"`，全面消除 `javac` 編譯阻塞。

3. **從觀察事實 3 推導測試真實性補救**：
   - E2E 測試直接比對 Python 字典屬於極嚴重誠信違規 (INTEGRITY VIOLATION)。
   - 透過修復 `m3_native_terminal_test.cpp` 與 `TerminalAppUnitTest.java` 的語法錯誤，將其編譯為可執行二進位檔 `/tmp/m3_native_terminal_test` 與 Java `.class`。
   - 在 `test_m3_tier1.py` 與 `test_m3_tier2.py` 中經由 `CommandRunner.run()` 調用上述編譯檔案及 `ctypes` 載入 `.so`，實現 100% 真實程式碼執行。

---

## 3. Caveats (注意事項與未檢驗範疇)

- 本次分析係讀取全套原始碼、編譯日誌、審查與稽核報告所做之技術制定，未在本次探索中對 Android 設備直接部署 APK（因環境為靜態分析與單元/E2E 測試模式）。
- 無其他隱藏假設。相關編譯與語法錯誤均已於指令比對中精準證實。

---

## 4. Conclusion (結論)

1. **技術策略可行且完整**：補救策略徹底覆蓋 F-R3-001 (Canvas Surface Renderer)、F-R3-002 (libvterm JNI & Build) 與 Test Suite Authenticity。
2. **嚴格符合 DEAD_ENDS.md**：本策略拒絕所有外殼化、靜默捕捉與 Python 字典假測試行為。
3. **產出文件**：完整技術細節已寫入 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_1_r2/analysis.md`。

---

## 5. Verification Method (獨立驗證方法)

實作完畢後，執行以下指令以驗證修正成果：

1. **Java 語法與編譯驗證**：
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:packages/apps/LinuxTerminal/src \
       -d /tmp/m3_classes \
       $(find packages/apps/LinuxTerminal/src -name "*.java")
   ```
2. **C++ 原生 libvterm 單元測試編譯與執行**：
   ```bash
   g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include \
       tests/unit/m3_native_terminal_test.cpp \
       packages/apps/LinuxTerminal/jni/libvterm/src/*.c \
       -o /tmp/m3_native_terminal_test && /tmp/m3_native_terminal_test
   ```
3. **Java 終端機單元測試編譯與執行**：
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:packages/apps/LinuxTerminal/src \
       -d /tmp/m3_test_classes \
       $(find packages/apps/LinuxTerminal/src -name "*.java") \
       tests/unit/TerminalAppUnitTest.java && \
   java -cp /tmp/m3_test_classes tests.unit.TerminalAppUnitTest
   ```
4. **E2E 測試套件真實性執行**：
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
