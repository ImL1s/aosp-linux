# Milestone M3 (Iteration 2 Remediation) 技術補救策略分析報告

**專案名稱**: AOSP Dual-OS System Blueprint — Native Touch Terminal Engine & IME (M3)  
**報告作者**: Explorer 1 (`explorer_m3_1_r2`)  
**日期**: 2026-08-06  
**目標**: 針對 M3 Iteration 1 經由 Forensic Auditor、Code Reviewer 1 與 Challenger 1 判定退件之三大核心缺陷（F-R3-001 Canvas 渲染外殼化、F-R3-002 libvterm 解析器符號與編譯不符、測試套件自證偽造），制定完整、可執行且徹底消除虛假外殼（Facade）的技術補救方案。

---

## 1. 執行摘要 (Executive Summary)

在 M3 Iteration 1 審查中，Forensic Audit 與 Code Review 發現 `packages/apps/LinuxTerminal/` 存在多項嚴重誠信與技術缺陷：
1. **F-R3-001 Canvas 渲染器假外殼**：`TerminalSurfaceView.java`（根套件）僅使用 Java `Canvas.drawText()` 繪製固定字串 `"Terminal Surface Canvas (60 FPS Budget)"`，未調用 `TerminalScreenMatrix` 格狀矩陣與 C++ `terminal_renderer.cpp` 的 `ANativeWindow` 雙重緩衝渲染。
2. **F-R3-002 libvterm 解析器斷層**：
   - 根套件 `VTermParser.java` 與 `libvterm_jni.cpp` JNI 符號/套件名稱（`com.android.virtualization.terminal` vs `.parser`）與方法簽名不符，且靜態捕捉 `UnsatisfiedLinkError` 靜默歸零。
   - `vterm_parser.cpp` 撰寫了虛構的 C `struct VTerm` 及 `vterm_input_write` 虛擬迴圈，忽視 ANSI 逸出碼、CSI 控制碼與 SGR 色彩。
   - `Android.bp` 未編譯 `jni/libvterm/src/*.c` 真正的 C 語言函式庫原始碼。
   - 套件結構混亂，根套件與 `parser`, `renderer`, `ime`, `touch`, `net` 子套件間存在 11 個同名重複類別。
   - `TerminalKeyEncoder.java` 與 `SgrMouseProtocolGenerator.java` 包含非法的 Java 字串逸出字元 `"\x1b"`，導致 `javac` 報錯 130 處。
3. **測試套件缺乏真實性**：
   - `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` 與 `test_m3_tier2.py` 中的 80 個測試案例直接在 Python 內部比對局部 Python 字典/字串，未載入或執行任何 Java 或 C++ 產品二進位檔。
   - `TerminalAppUnitTest.java` 與 `m3_native_terminal_test.cpp` 存在編譯阻塞語法錯誤（`"\x1b"` 及 `vterm.h` 中的 `boolean` 型別錯誤）。

本報告提出了完整的修復架構與工程步驟，完全排除 `DEAD_ENDS.md` 中禁止的假外殼行為。

---

## 2. 缺失一：F-R3-001 (Native Surface Canvas Renderer) 補救策略

### 2.1 問題根因與 Verbatim 證據
- **Java 繪製外殼** (`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalSurfaceView.java` 第 103-106 行)：
  ```java
  if (canvas != null) {
      canvas.drawColor(Color.BLACK);
      canvas.drawText("Terminal Surface Canvas (60 FPS Budget)", 20, 50, mPaint);
  }
  ```
  `TerminalView.java` 第 97-98 行亦僅繪製固定字串 `"AOSP Linux Terminal Engine"`，完全未讀取終端機單元矩陣。
- **C++ 渲染器未連結** (`packages/apps/LinuxTerminal/jni/terminal_renderer.cpp`)：
  `terminal_renderer.cpp` 未導出任何 JNI 函數，且其 `rasterizeGlyph`（第 102-117 行）採用 `(x+y)%2 == 0` 棋盤格繪製虛構字型。

### 2.2 具體架構修復方案

#### 方案一：完整 Java Native Surface Canvas 雙緩衝繪製線路 (標準 Android 高效能方案)
1. **統一套件與視圖元件**：
   - 刪除根套件下未連接的 `TerminalSurfaceView.java` 外殼，統一採用 `com.android.virtualization.terminal.renderer.TerminalSurfaceView` 與 `NativeSurfaceCanvasRenderer`。
   - 在 `TerminalView` 與 `TerminalSurfaceView` 中，將 `SurfaceHolder` 與 `NativeSurfaceCanvasRenderer`（背景執行緒 `TerminalRenderThread`）正式綁定。

2. **真實 Terminal 格狀單元渲染與髒矩形 (Dirty Rect) 刷新**：
   - `NativeSurfaceCanvasRenderer.run()` 逐訊框檢查 `TerminalScreenMatrix.getAndClearDirtyRect(dirtyGridRect)`。
   - 計算像素區域：`pixelDirty = Rect(left * cellWidth, top * cellHeight, right * cellWidth, bottom * cellHeight)`。
   - 透過 `mSurfaceHolder.lockCanvas(pixelDirty)` 鎖定 Native Surface 緩衝區。
   - 逐行逐列輪詢 `TerminalScreenMatrix.getCell(r, c)`：
     - 背景矩形：`canvas.drawRect(left, top, left + cellWidth, top + cellHeight, bgPaint)`（處理解析後之 ANSI 16 / 256 / TrueColor 背景色）。
     - 前景文字：`mTextPaint.setColor(fg)`，並依據 `TerminalCell.attributes` 設定 `setFakeBoldText` (Bold)、`setTextSkewX` (Italic)、`setUnderlineText` (Underline)、`setStrikeThruText` (Strike)。
     - 字元繪製：`canvas.drawText(codepointStr, left, top + fontBaseline, mTextPaint)`。
   - 游標與 IME 組字視窗：在對應游標座標繪製綠色實心游標矩形與 CJK 行內組字視窗（`mComposingWindow.drawInlineComposing`）。
   - 提交與釋放：`mSurfaceHolder.unlockCanvasAndPost(canvas)`。

#### 方案二：C++ ANativeWindow JNI 雙重緩衝渲染介面 (備選/加強 Native 導向)
1. 在 `terminal_renderer.cpp` 中修正 `rasterizeGlyph`，使其不再繪製 `(x+y)%2 == 0` 棋盤格，改為讀取標準 ASCII 點陣/筆畫資料或精準填色。
2. 在 `libvterm_jni.cpp` 中增加 JNI 方法：
   - `Java_com_android_virtualization_terminal_renderer_NativeSurfaceCanvasRenderer_nativeSetSurface(JNIEnv* env, jobject thiz, jlong ptr, jobject surface)`：調用 `ANativeWindow_fromSurface(env, surface)` 取得原生視窗句柄。
   - `Java_com_android_virtualization_terminal_renderer_NativeSurfaceCanvasRenderer_nativeRenderGrid(...)`：調用 `ANativeWindow_lock` 鎖定緩衝區，寫入 `TerminalCellNative` 點陣色彩後調用 `ANativeWindow_unlockAndPost`。

**驗證標準**：繪製邏輯必須動態反映 `VTermParser` 解析輸入後填入 `TerminalScreenMatrix` 之字元與色彩，嚴禁出現固定字串。

---

## 3. 缺失二：F-R3-002 (libvterm Parser Integration) 補救策略

### 3.1 問題根因與 Verbatim 證據
- **JNI 簽名與套件不符**：
  - C++ `libvterm_jni.cpp` 第 92 行導出 `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit(..., rows, cols, callback)`（3 個參數）。
  - 根套件 `VTermParser.java` 第 29 行宣告 `nativeInit(rows, cols)`（2 個參數），且載入 `terminal_jni` 而非 `vterm_jni`。
- **靜默例外捕捉** (`VTermParser.java` 第 28-32 行)：
  ```java
  try {
      mNativePtr = nativeInit(rows, cols);
  } catch (UnsatisfiedLinkError ignored) {
      mNativePtr = 0;
  }
  ```
  導致 JNI 連結失敗時，所有 `isAltScreen()` 或 `getScrollbackCount()` 直接返回 `false` / `0` 的假資料。
- **虛構 C 語言 Stub** (`jni/vterm_parser.cpp` 第 25-118 行)：
  手繪全套 `vterm_new`, `vterm_input_write` 虛構 struct，其 `vterm_input_write` 遇 `\n` 重置列、忽略所有 ANSI Escape 色彩與控制碼。
- **編譯配置與類別重複**：
  `packages/apps/LinuxTerminal/jni/Android.bp` 未包含 `libvterm/src/*.c`，且根套件與子套件存有 11 個重複檔名。
- **Java 非法逸出字元 `"\x1b"`**：
  `TerminalKeyEncoder.java` 及 `SgrMouseProtocolGenerator.java` 含有 `"\x1b"`，引發 130 處 `javac` 語法錯誤（Java 正確語法應為 `"\u001b"` 或 `"\033"`）。

### 3.2 具體架構修復方案

#### 步驟 1：徹底清理根套件重複 Facade 檔案
刪除以下 11 個位於 `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/` 的同名重複外殼檔案：
- `VTermParser.java`
- `TerminalSurfaceView.java`
- `TerminalInputConnection.java`
- `CjkComposingTextManager.java`
- `CjkComposingWindow.java`
- `TerminalKeyEncoder.java`
- `PtySender.java`
- `VsockPtyFramer.java`
- `TerminalCell.java`
- `SgrMouseProtocolGenerator.java`
- `TouchModeStateMachine.java`

保留並修正位於正規子套件內之真實實作：
- `com.android.virtualization.terminal.parser.VTermParser`
- `com.android.virtualization.terminal.renderer.*`
- `com.android.virtualization.terminal.ime.*`
- `com.android.virtualization.terminal.touch.*`
- `com.android.virtualization.terminal.net.*`

在 `TerminalActivity.java` 與 `TerminalView.java` 中正確 `import` 上述子套件類別。

#### 步驟 2：修復 `libvterm_jni.cpp` 與 `VTermParser.java` JNI 綁定
1. **移除 UnsatisfiedLinkError 靜默捕捉**：
   在 `com.android.virtualization.terminal.parser.VTermParser` 中：
   - 載入動態庫 `System.loadLibrary("vterm_jni")`（或 `libvterm_jni`）。若失敗則不進行 catch 遮蔽，確保第一時間暴露單元測試與運行期問題。
   - 移除 `mNativePtr == 0` 的假回傳 fallback。

2. **修正 JNI 執行緒附加與記憶體洩漏 (Reviewer 1 Finding 3)**：
   在 `jni/libvterm_jni.cpp` 中：
   - 背景 Thread Callbacks (`cb_damage`, `cb_movecursor`, `cb_settermprop`)：
     ```cpp
     JNIEnv* env = nullptr;
     bool needsDetach = false;
     jint res = ctx->jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
     if (res == JNI_EDETACHED) {
         if (ctx->jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
             needsDetach = true;
         }
     }
     if (env && ctx->callbackObj) {
         // Call Java callback method
     }
     if (needsDetach) {
         ctx->jvm->DetachCurrentThread();
     }
     ```
   - 在 `Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit` 中添加：
     ```cpp
     jclass cbClass = env->GetObjectClass(callback);
     // ... GetMethodIDs ...
     env->DeleteLocalRef(cbClass); // 防止 Local Reference Table 溢出
     ```

#### 步驟 3：整合真實 `libvterm` C 函式庫與修復 `Android.bp`
1. 刪除 `jni/vterm_parser.cpp` 中自製的 C 語言 `vterm_*` 虛構函數（第 25-119 行），避免符號衝突。
2. 刪除 `jni/third_party/libvterm/vterm.h` 中含 `boolean` 的錯誤頭文件，全套統一使用 `jni/libvterm/include/vterm.h`。
3. 更新 `packages/apps/LinuxTerminal/jni/Android.bp` 與 `packages/apps/LinuxTerminal/Android.bp`：
   ```bp
   cc_library_shared {
       name: "libvterm_jni",
       srcs: [
           "libvterm_jni.cpp",
           "terminal_renderer.cpp",
           "sgr_mouse_generator.cpp",
           "pty_framing_handler.cpp",
           "libvterm/src/vterm.c",
           "libvterm/src/screen.c",
           "libvterm/src/state.c",
           "libvterm/src/parser.c",
           "libvterm/src/pen.c",
           "libvterm/src/unicode.c",
           "libvterm/src/encoding.c",
       ],
       include_dirs: [
           "packages/apps/LinuxTerminal/jni",
           "packages/apps/LinuxTerminal/jni/libvterm/include",
       ],
       shared_libs: [
           "liblog",
           "libandroid",
       ],
       cflags: [
           "-Wall",
           "-Werror",
           "-Wno-unused-parameter",
           "-std=c99",
       ],
       cppflags: [
           "-std=c++20",
       ],
       sdk_version: "current",
   }
   ```

#### 步驟 4：全面替換非法 Java 逸出字元 `"\x1b"`
在 `TerminalKeyEncoder.java`、`SgrMouseProtocolGenerator.java` 及 `TerminalAppUnitTest.java` 中，將所有 `"\x1b"` 替換為 `"\u001b"`（例如 `"\u001b[Z"`、`"\u001b[<0;10;20;M"`）。

#### 步驟 5：修復 `VsockPtyFramer.java` 緩衝區累積防護 (Challenger 1 Finding 5)
在 `VsockPtyFramer.java` 的 `StreamParser.appendAndParse()` 中，當遭遇異常或封包長度超過 64KB 上限時，必須執行 `mAccumulator.reset()`，防止緩衝區污染與解析器死鎖。

---

## 4. 缺失三：測試套件真實性 (Test Suite Authenticity) 補救策略

### 4.1 問題根因與 Verbatim 證據
- **Python 假測試** (`tests/e2e/tier1_feature_coverage/test_m3_tier1.py` 第 21-24, 90-92 行)：
  ```python
  surface_config = {"type": "SURFACE_TYPE_HARDWARE", "width": 1024, "height": 768, "valid": True}
  CustomAssertions.assert_equal(surface_config["type"], "SURFACE_TYPE_HARDWARE")
  ```
  直接在 Python 內創創 dict / string 斷言，80 個 E2E 測試於 0.05 秒內完成，完全未執行產品程式碼。
- **單元測試編譯失敗**：
  - `TerminalAppUnitTest.java` 因 `"\x1b"` 及 import 路徑錯誤無法編譯。
  - `m3_native_terminal_test.cpp` 因包含 `third_party/libvterm/vterm.h`（第 62 行 `boolean` 錯誤）無法以 `g++` 編譯。

### 4.2 具體架構修復方案

#### 步驟 1：修復並編譯底層 Java 與 C++ 單元測試執行檔
1. **修復 C++ 原生測試檔** (`tests/unit/m3_native_terminal_test.cpp`)：
   - 將包含頭文件改為 `jni/libvterm/include/vterm.h`。
   - 使用 `g++` 編譯成獨立測試執行檔 `/tmp/m3_native_terminal_test`：
     ```bash
     g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include \
         tests/unit/m3_native_terminal_test.cpp \
         packages/apps/LinuxTerminal/jni/libvterm/src/*.c \
         -o /tmp/m3_native_terminal_test
     ```
2. **修復 Java 單元測試檔** (`tests/unit/TerminalAppUnitTest.java`)：
   - 替換 `"\x1b"` 為 `"\u001b"`。
   - 使用 `javac` 編譯 Java 核心類別與單元測試：
     ```bash
     javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:packages/apps/LinuxTerminal/src \
         -d /tmp/m3_test_classes \
         $(find packages/apps/LinuxTerminal/src -name "*.java") \
         tests/unit/TerminalAppUnitTest.java
     ```

#### 步驟 2：重寫 `test_m3_tier1.py` 與 `test_m3_tier2.py` 測試套件
測試案例嚴禁使用 Python dict 本地斷言，必須透過以下兩種真實執行機制之一驗證：

1. **子進程真實二進位檔調用機制 (`CommandRunner.run`)**：
   - 測試案例發起 `CommandRunner.run("/tmp/m3_native_terminal_test")` 或 `CommandRunner.run("java -cp /tmp/m3_test_classes tests.unit.TerminalAppUnitTest")`。
   - 檢查 `exit_code == 0` 以及 `stdout` 輸出中包含的真實解析成果（如 `[libvterm] ASCII Stream Write & Cell Query: PASS`）。

2. **Python `ctypes` 直接載入與呼叫 `libvterm_jni.so` 原生函式庫**：
   - 在 Python E2E 測試類別中，使用 `ctypes.CDLL("/tmp/libvterm_jni.so")` 或編譯產出的 .so 檔。
   - 直接調用 `vterm_new(rows, cols)`、`vterm_input_write(vt, bytes, len)` 及 `vterm_screen_get_cell(...)`，並斷言真實解出的 ASCII/Unicode 字元與 ANSI 顏色數值。

3. **Vsock Framing 位元組串流編解碼真測試**：
   - 調用 `VsockPtyFramer` 的真實打包邏輯，驗證 16-byte Session ID、Packet Type (0x01 DATA / 0x02 RESIZE)、Length (Big-Endian int) 及 Payload 的動態位元組序列比對。

---

## 5. 嚴格迴避之 DEAD_ENDS 清單

在 Iteration 2 實作中，**絕對禁止**採用以下任何已記錄於 `DEAD_ENDS.md` 的失敗做法：

| 禁止做法 | 失敗原因與後果 | 替代正軌方案 |
|---|---|---|
| 在 `vterm_parser.cpp` 中手寫虛構 C `struct VTerm` stub | 毀滅 ANSI 轉義碼與色碼解析能力，違反專案誠信 | 編譯並連結 `jni/libvterm/src/*.c` 官方 C 原始碼 |
| 在 `TerminalSurfaceView.java` 中繪製固定字串 `"Terminal Surface Canvas..."` | 形成假外殼（Facade），未反映真實終端機畫面 | 經由 `NativeSurfaceCanvasRenderer` 繪製 `TerminalScreenMatrix` 點陣與字元 |
| `System.loadLibrary("terminal_jni")` 配合靜態捕捉 `UnsatisfiedLinkError` | 隱蔽 JNI 連結失敗，回傳假資料 | 統一調用 `System.loadLibrary("vterm_jni")`，連結失敗即報錯 |
| Java 字串中使用 `"\x1b"` 逸出字元 | `javac` 無法識別 `\x` 語法，導致 130 處編譯錯誤 | 統一採用 Java 標準 Unicode 逸出 `"\u001b"` 或八進位 `"\033"` |
| Python E2E 測試在測試方法內直接比對自建 Python 字典/字串 | 偽造 100% 通過率，未執行任何 C++/Java 產品程式碼 | 使用 `CommandRunner` 執行真實編譯之二進位檔或經由 `ctypes` 驗證 |

---

## 6. Worker 執行步驟與檔案變更清單

Worker 應依據以下順序精準執行修復：

```
+-----------------------------------------------------------------------------------+
| 步驟 1: 清理根套件重複 Facade 檔案 (刪除 11 個重複 Java 檔)                      |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| 步驟 2: 全面修正 Java 逸出字元 ("\x1b" -> "\u001b") 及修正 JNI 執行緒/記憶體洩漏  |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| 步驟 3: 修正 Android.bp 連結真實 libvterm/src/*.c 並移除非法 vterm_parser.cpp C stub |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| 步驟 4: 連接 TerminalSurfaceView 與 NativeSurfaceCanvasRenderer 進行真實格狀繪製  |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| 步驟 5: 修復並編譯 C++ / Java 單元測試執行檔 (/tmp/m3_native_terminal_test 等)    |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| 步驟 6: 重寫 test_m3_tier1.py 與 test_m3_tier2.py 確保執行真實 compiled 二進位檔  |
+-----------------------------------------------------------------------------------+
```

### 檔案變更矩陣 (File Action Matrix)

| 檔案路徑 | 操作類型 | 核心變更內容 |
|---|---|---|
| `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/*.java` (11 個重複外殼檔) | **DELETE** | 移除與子套件重複之 Facade 檔案。 |
| `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalActivity.java` | **MODIFY** | 更新 import 指向 `renderer.*`, `touch.*`, `parser.*`。 |
| `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` | **MODIFY** | 更新 import，移除固定字串 `onDraw` 繪製，連接 `NativeSurfaceCanvasRenderer`。 |
| `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java` | **MODIFY** | 確保綁定 `NativeSurfaceCanvasRenderer` 繪製 `TerminalScreenMatrix` 格狀矩陣。 |
| `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/TerminalKeyEncoder.java` | **MODIFY** | 替換 `"\x1b"` 為 `"\u001b"`。 |
| `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/touch/SgrMouseProtocolGenerator.java` | **MODIFY** | 替換 `"\x1b"` 為 `"\u001b"`。 |
| `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/parser/VTermParser.java` | **MODIFY** | 移除 UnsatisfiedLinkError 靜默捕捉，載入 `vterm_jni`。 |
| `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockPtyFramer.java` | **MODIFY** | 在例外或超長封包時執行 `mAccumulator.reset()`。 |
| `packages/apps/LinuxTerminal/jni/libvterm_jni.cpp` | **MODIFY** | 修正 JNI Env 執行緒附加/解附邏輯，加入 `DeleteLocalRef(cbClass)`。 |
| `packages/apps/LinuxTerminal/jni/vterm_parser.cpp` | **MODIFY** | 刪除虛構的 C 語言 `vterm_*` 函數。 |
| `packages/apps/LinuxTerminal/jni/third_party/libvterm/vterm.h` | **DELETE** | 刪除含 `boolean` 錯誤之舊頭文件。 |
| `packages/apps/LinuxTerminal/jni/Android.bp` | **MODIFY** | 包含 `libvterm/src/*.c` 原始碼。 |
| `packages/apps/LinuxTerminal/Android.bp` | **MODIFY** | 確保 `libvterm_jni` 包含 `libvterm/src/*.c` 原始碼。 |
| `tests/unit/TerminalAppUnitTest.java` | **MODIFY** | 替換 `"\x1b"` 為 `"\u001b"`。 |
| `tests/unit/m3_native_terminal_test.cpp` | **MODIFY** | 引用 `jni/libvterm/include/vterm.h`。 |
| `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` | **MODIFY** | 重寫測試案例，經由 `CommandRunner` 執行真實編譯二進位檔。 |
| `tests/e2e/tier2_boundary_corner/test_m3_tier2.py` | **MODIFY** | 重寫測試案例，執行真實位元組串流與二進位檔測試。 |

---

## 7. 獨立驗證指令集 (Verification Method)

在完成上述修復後，透過以下指令序列獨立驗證：

1. **驗證 Java 原始碼編譯無語法錯誤**：
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:packages/apps/LinuxTerminal/src \
       -d /tmp/m3_classes \
       $(find packages/apps/LinuxTerminal/src -name "*.java")
   ```
   *預期結果*：`javac` 成功編譯，零錯誤退出 (Exit code 0)。

2. **編譯與執行 C++ 核心 libvterm 原生單元測試**：
   ```bash
   g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include \
       tests/unit/m3_native_terminal_test.cpp \
       packages/apps/LinuxTerminal/jni/libvterm/src/*.c \
       -o /tmp/m3_native_terminal_test && /tmp/m3_native_terminal_test
   ```
   *預期結果*：`g++` 成功編譯，執行輸出 `=== M3 Native Terminal & C++ libvterm Unit Test Suite: ALL PASSED ===`。

3. **編譯與執行 Java 終端機引擎單元測試**：
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:packages/apps/LinuxTerminal/src \
       -d /tmp/m3_test_classes \
       $(find packages/apps/LinuxTerminal/src -name "*.java") \
       tests/unit/TerminalAppUnitTest.java && \
   java -cp /tmp/m3_test_classes tests.unit.TerminalAppUnitTest
   ```
   *預期結果*：執行輸出 `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`。

4. **執行真實化 E2E 測試套件**：
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
   *預期結果*：所有 Tier 1 與 Tier 2 測試案例全數調用真實二進位檔並獲得 [PASS]。
