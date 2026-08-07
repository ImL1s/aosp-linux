# M3 第四次迭代修復技術計畫書 (Remediation Technical Plan for Worker 4)

## 1. 概述 (Overview)

在 Milestone M3 (Native Touch Terminal & IME) 第 3 次迭代的 Gate 評估中，審查小組 (Reviewers & Challengers) 發現了 4 個關鍵技術缺陷：
1. **`TouchpadController.java` 與 `TerminalView.java` 串接缺失**：`TouchpadController` 未正確調用 `sgrGenerator.processTouchpadEvent(event, cols, rows)`，導致觸控板相對移動（Relative Touch Motion）未產生 Hover (`\033[<35;col;rowM`)、Drag (`\033[<32;col;rowM`) 與 Scroll (`\033[<64/65;col;rowM`) 的 SGR 1006 封包。
2. **`vterm_parser.cpp` UTF-8 重組與緩衝區重置缺陷**：`VTermParserBridge::feedBytes` 多位元組 UTF-8 重組邏輯忽略了 `vterm_input_write` 實際消耗位元組數（`consumed`），導致中文字元（如 `'繁'` 0x7B41）截斷為空格 (0x20) 或在極端高併發壓力測試（`m3_native_challenger2_stress_bin`）中引發 SIGABRT 崩潰。
3. **`libvterm/src/parser.c` SGR 顏色解析缺陷**：`parse_sgr()` 僅使用 `atoi(param)` 解析單一整數，無法處理複合樣式 (`\e[1;31;42m`)、256 色 (`\e[38;5;Nm`) 與 24-bit TrueColor (`\e[38;2;R;G;Bm`)。
4. **`TerminalInputConnection.java` 向前刪除 (Forward Delete) 缺陷**：`deleteSurroundingText(beforeLength, afterLength)` 未處理 `afterLength`，導致向後/向前刪除（如 `deleteSurroundingText(0, 1)`）無法發送 ANSI Delete (`\033[3~`) 轉義序列。

本計畫書為 **Worker 4 (`worker_m3_gen4`)** 提供精確且步步可驗證的代碼修復方案。

---

## 2. 缺陷 1：TouchpadController 與 TerminalView 串接修復

### 2.1 根因分析 (Root Cause)
- 在 `TouchpadController.java` 中，`handleTouchpadEvent(...)` 雖接收了 `sgrGenerator` 參數，但未調用 `sgrGenerator.processTouchpadEvent(event, cellWidth, cellHeight, totalCols, totalRows)`，且自身產生的位元組未包含 Hover (Button 35) 移動封包。
- `TerminalView.java` 的 `onTouchEvent` 在 `TOUCHPAD_MODE` 下調用 `mTouchpadController.handleTouchpadEvent(...)` 時，未確保 `mSgrMouseGenerator` 正確開啟 Mouse Tracking。

### 2.2 修復步驟與代碼改動 (Remediation Plan)

#### 檔案 A：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/touch/TouchpadController.java`
修改 `handleTouchpadEvent` 方法，使其統一委派（Delegate）給 `sgrGenerator.processTouchpadEvent(...)` 生成標準 SGR 1006 封包，並透過 `ptySender.sendBytes(...)` 發送：

```java
// BEFORE:
    public boolean handleTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows,
                                       PtySender ptySender, SgrMouseProtocolGenerator sgrGenerator) {
        if (event == null || ptySender == null) {
            return false;
        }
        ...
        // 舊邏輯手動處理相對移動，但遺漏了 hover (button 35) 封包，也沒有使用 sgrGenerator.processTouchpadEvent
```

```java
// AFTER:
    public boolean handleTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows,
                                       PtySender ptySender, SgrMouseProtocolGenerator sgrGenerator) {
        if (event == null || ptySender == null) {
            return false;
        }

        this.mCellWidth = Math.max(1, cellWidth);
        this.mCellHeight = Math.max(1, cellHeight);
        this.mTotalCols = Math.max(1, totalCols);
        this.mTotalRows = Math.max(1, totalRows);

        if (sgrGenerator != null) {
            sgrGenerator.setMouseTrackingEnabled(true);
            byte[] sgrBytes = sgrGenerator.processTouchpadEvent(event, mCellWidth, mCellHeight, mTotalCols, mTotalRows);
            if (sgrBytes != null && sgrBytes.length > 0) {
                ptySender.sendBytes(sgrBytes);
            }
        }
        return true;
    }
```

同時確保 `processTouchpadEvent` 方便外部測試調用：
```java
    public byte[] processTouchpadEvent(MotionEvent event, int cellWidth, int cellHeight, int totalCols, int totalRows) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PtySender dummySender = new PtySender() {
            @Override
            public void sendBytes(byte[] data) {
                if (data != null) baos.write(data, 0, data.length);
            }
            @Override public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {}
            @Override public void sendResize(byte[] sessionId, int cols, int rows) {}
        };
        SgrMouseProtocolGenerator sgrGen = new SgrMouseProtocolGenerator();
        sgrGen.setMouseTrackingEnabled(true);
        byte[] sgrBytes = sgrGen.processTouchpadEvent(event, cellWidth, cellHeight, totalCols, totalRows);
        return sgrBytes;
    }
```

#### 檔案 B：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
在 `onTouchEvent` 中處理 `TOUCHPAD_MODE` 時，確保啟用 `mSgrMouseGenerator` 追蹤並調用：

```java
// BEFORE:
            case TOUCHPAD_MODE:
                return mTouchpadController.handleTouchpadEvent(
                    event, mCellWidth, mCellHeight, mColumns, mRows, this, mSgrMouseGenerator
                );
```

```java
// AFTER:
            case TOUCHPAD_MODE:
                mSgrMouseGenerator.setMouseTrackingEnabled(true);
                return mTouchpadController.handleTouchpadEvent(
                    event, mCellWidth, mCellHeight, mColumns, mRows, this, mSgrMouseGenerator
                );
```

---

## 3. 缺陷 2：vterm_parser.cpp 多位元組 UTF-8 重組與緩衝區重置修復

### 3.1 根因分析 (Root Cause)
- 在 `packages/apps/LinuxTerminal/jni/vterm_parser.cpp` 的 `feedBytes` 中，原算法僅從後向前搜尋 UTF-8 lead byte 並計算 `validLen`，但忽視了 `vterm_input_write(mVterm, ..., validLen)` 的回傳值（即 `vterm_input_write` 實際寫入/解析的位元組數 `consumed`）。
- 當分流傳入的不完整位元組流（例如 CJK 3 位元組 `'繁'` 0xE7 0xB9 0x81 被拆開）寫入時，若 `validLen` 與 `vterm_input_write` 消耗的位元組數不一致，`mUtf8PartialBuffer` 的切片起點便會錯位，導致後續讀入的位元組變成無效 UTF-8，被替換為空格 `0x20`，或在斷言檢查中觸發 SIGABRT。

### 3.2 修復步驟與代碼改動 (Remediation Plan)

#### 檔案：`packages/apps/LinuxTerminal/jni/vterm_parser.cpp`
重構 `VTermParserBridge::feedBytes`，結合 UTF-8 lead byte 安全計算與 `vterm_input_write` 的實時回傳值 `consumed`：

```cpp
// BEFORE:
void VTermParserBridge::feedBytes(const uint8_t* data, size_t length) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (!mVterm || length == 0) return;

    std::vector<uint8_t> buffer = mUtf8PartialBuffer;
    buffer.insert(buffer.end(), data, data + length);
    mUtf8PartialBuffer.clear();

    size_t validLen = buffer.size();
    size_t searchLimit = (buffer.size() > 4) ? buffer.size() - 4 : 0;
    size_t i = buffer.size();

    while (i > searchLimit) {
        i--;
        uint8_t b = buffer[i];
        if ((b & 0x80) == 0) break;
        if ((b & 0xE0) == 0xC0) { if (buffer.size() - i < 2) validLen = i; break; }
        if ((b & 0xF0) == 0xE0) { if (buffer.size() - i < 3) validLen = i; break; }
        if ((b & 0xF8) == 0xF0) { if (buffer.size() - i < 4) validLen = i; break; }
    }

    if (validLen < buffer.size()) {
        mUtf8PartialBuffer.assign(buffer.begin() + validLen, buffer.end());
    }

    if (validLen > 0) {
        vterm_input_write(mVterm, reinterpret_cast<const char*>(buffer.data()), validLen);
    }
}
```

```cpp
// AFTER:
void VTermParserBridge::feedBytes(const uint8_t* data, size_t length) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (!mVterm || length == 0) return;

    // 1. 合併之前殘留的 partial buffer 與新接收的 data
    std::vector<uint8_t> buffer = mUtf8PartialBuffer;
    buffer.insert(buffer.end(), data, data + length);
    mUtf8PartialBuffer.clear();

    // 2. 檢查尾部是否有末端不完整的 UTF-8 多位元組序列
    size_t validLen = buffer.size();
    if (buffer.size() > 0) {
        size_t i = buffer.size();
        size_t searchLimit = (buffer.size() > 4) ? buffer.size() - 4 : 0;
        while (i > searchLimit) {
            i--;
            uint8_t b = buffer[i];
            if ((b & 0x80) == 0) {
                // 遇到 ASCII 字元，代表前面的 UTF-8 序列已完結
                break;
            }
            if ((b & 0xE0) == 0xC0) { // 2-byte lead
                if (buffer.size() - i < 2) validLen = i;
                break;
            }
            if ((b & 0xF0) == 0xE0) { // 3-byte lead (CJK)
                if (buffer.size() - i < 3) validLen = i;
                break;
            }
            if ((b & 0xF8) == 0xF0) { // 4-byte lead (Emoji)
                if (buffer.size() - i < 4) validLen = i;
                break;
            }
        }
    }

    // 3. 將完整的 validLen 位元組寫入 vterm，並獲取實際消耗的位元組數 consumed
    size_t consumed = 0;
    if (validLen > 0) {
        consumed = vterm_input_write(mVterm, reinterpret_cast<const char*>(buffer.data()), validLen);
    }

    // 4. 將未被 vterm 消耗的位元組（包含未完成的 partial UTF-8）精確保留至下一次重組
    if (consumed < buffer.size()) {
        mUtf8PartialBuffer.assign(buffer.begin() + consumed, buffer.end());
    } else {
        mUtf8PartialBuffer.clear();
    }
}
```

此變更確保了 `vterm_input_write` 消耗多少，`mUtf8PartialBuffer` 就留存多少，完全杜絕位元組丟失或重覆寫入。

---

## 4. 缺陷 3：libvterm/src/parser.c SGR 顏色與樣式解析修復

### 4.1 根因分析 (Root Cause)
- 在 `packages/apps/LinuxTerminal/jni/libvterm/src/parser.c` 中，原 `parse_sgr` 實作僅為 `int code = atoi(param)`，只能讀取字串中的第一個數字！
- 對於複合轉義序列（如 `\e[1;31;42m`），它只解析了 `1` (Bold)，而忽視了 `31` (紅字) 與 `42` (綠底)。
- 對於 256 色 (`\e[38;5;Nm` / `\e[48;5;Nm`) 以及 24-bit TrueColor (`\e[38;2;R;G;Bm` / `\e[48;2;R;G;Bm`)，原代碼完全無法識別。

### 4.2 修復步驟與代碼改動 (Remediation Plan)

#### 檔案：`packages/apps/LinuxTerminal/jni/libvterm/src/parser.c`
徹底重構 `parse_sgr` 函數與 256 色轉換輔助函數：

```c
// BEFORE:
static void parse_sgr(VTermScreen *vts, const char *param) {
    if (!param || *param == '\0') {
        vts->current_fg = (VTermColor){255, 255, 255};
        vts->current_bg = (VTermColor){0, 0, 0};
        memset(&vts->current_attrs, 0, sizeof(VTermScreenCellAttrs));
        return;
    }

    int code = atoi(param);
    if (code == 0) { ... }
    ...
}
```

```c
// AFTER:

// 256 色調色盤轉 RGB 輔助函數
static VTermColor lookup_256_color(int index) {
    if (index < 0 || index > 255) return (VTermColor){255, 255, 255};
    
    // 0..7: 標準 ANSI
    static const VTermColor ansi16[16] = {
        {0, 0, 0}, {205, 0, 0}, {0, 205, 0}, {205, 205, 0},
        {0, 0, 238}, {205, 0, 205}, {0, 205, 205}, {229, 229, 229},
        {127, 127, 127}, {255, 0, 0}, {0, 255, 0}, {255, 254, 0},
        {92, 92, 255}, {255, 0, 255}, {0, 255, 255}, {255, 255, 255}
    };
    if (index < 16) {
        return ansi16[index];
    }
    
    // 16..231: 6x6x6 色彩立方體
    if (index <= 231) {
        int idx = index - 16;
        int r = (idx / 36) ? (idx / 36) * 40 + 55 : 0;
        int g = ((idx / 6) % 6) ? ((idx / 6) % 6) * 40 + 55 : 0;
        int b = (idx % 6) ? (idx % 6) * 40 + 55 : 0;
        return (VTermColor){(uint8_t)r, (uint8_t)g, (uint8_t)b};
    }
    
    // 232..255: 24 階灰階
    int v = (index - 232) * 10 + 8;
    return (VTermColor){(uint8_t)v, (uint8_t)v, (uint8_t)v};
}

static void parse_sgr(VTermScreen *vts, const char *param) {
    if (!vts) return;
    if (!param || *param == '\0') {
        vts->current_fg = (VTermColor){255, 255, 255};
        vts->current_bg = (VTermColor){0, 0, 0};
        memset(&vts->current_attrs, 0, sizeof(VTermScreenCellAttrs));
        return;
    }

    // 分解分號分隔的整數參數陣列
    int args[32];
    int arg_count = 0;
    const char *ptr = param;

    while (*ptr && arg_count < 32) {
        if (*ptr == 'm') break;
        args[arg_count++] = atoi(ptr);
        while (*ptr && *ptr != ';' && *ptr != 'm') ptr++;
        if (*ptr == ';') ptr++;
    }

    if (arg_count == 0) {
        vts->current_fg = (VTermColor){255, 255, 255};
        vts->current_bg = (VTermColor){0, 0, 0};
        memset(&vts->current_attrs, 0, sizeof(VTermScreenCellAttrs));
        return;
    }

    for (int i = 0; i < arg_count; i++) {
        int code = args[i];
        if (code == 0) {
            vts->current_fg = (VTermColor){255, 255, 255};
            vts->current_bg = (VTermColor){0, 0, 0};
            memset(&vts->current_attrs, 0, sizeof(VTermScreenCellAttrs));
        } else if (code == 1) {
            vts->current_attrs.bold = 1;
        } else if (code == 3) {
            vts->current_attrs.italic = 1;
        } else if (code == 4) {
            vts->current_attrs.underline = 1;
        } else if (code == 7) {
            vts->current_attrs.reverse = 1;
        } else if (code == 9) {
            vts->current_attrs.strike = 1;
        } else if (code == 22) {
            vts->current_attrs.bold = 0;
        } else if (code == 23) {
            vts->current_attrs.italic = 0;
        } else if (code == 24) {
            vts->current_attrs.underline = 0;
        } else if (code == 27) {
            vts->current_attrs.reverse = 0;
        } else if (code == 29) {
            vts->current_attrs.strike = 0;
        } else if (code >= 30 && code <= 37) {
            vts->current_fg = lookup_256_color(code - 30);
        } else if (code == 39) {
            vts->current_fg = (VTermColor){255, 255, 255};
        } else if (code >= 40 && code <= 47) {
            vts->current_bg = lookup_256_color(code - 40);
        } else if (code == 49) {
            vts->current_bg = (VTermColor){0, 0, 0};
        } else if (code >= 90 && code <= 97) {
            vts->current_fg = lookup_256_color(code - 90 + 8);
        } else if (code >= 100 && code <= 107) {
            vts->current_bg = lookup_256_color(code - 100 + 8);
        } else if (code == 38) { // 前景色擴充 (256色 / TrueColor)
            if (i + 1 < arg_count) {
                int mode = args[i + 1];
                if (mode == 5 && i + 2 < arg_count) { // 256 色 \e[38;5;Nm
                    vts->current_fg = lookup_256_color(args[i + 2]);
                    i += 2;
                } else if (mode == 2 && i + 4 < arg_count) { // 24-bit TrueColor \e[38;2;R;G;Bm
                    vts->current_fg = (VTermColor){(uint8_t)args[i + 2], (uint8_t)args[i + 3], (uint8_t)args[i + 4]};
                    i += 4;
                }
            }
        } else if (code == 48) { // 背景色擴充 (256色 / TrueColor)
            if (i + 1 < arg_count) {
                int mode = args[i + 1];
                if (mode == 5 && i + 2 < arg_count) { // 256 色 \e[48;5;Nm
                    vts->current_bg = lookup_256_color(args[i + 2]);
                    i += 2;
                } else if (mode == 2 && i + 4 < arg_count) { // 24-bit TrueColor \e[48;2;R;G;Bm
                    vts->current_bg = (VTermColor){(uint8_t)args[i + 2], (uint8_t)args[i + 3], (uint8_t)args[i + 4]};
                    i += 4;
                }
            }
        }
    }
}
```

重構後的 `parse_sgr` 能夠完全解析所有標準 ANSI 複合樣式、256 色調色盤以及 24 位元 TrueColor。

---

## 5. 缺陷 4：TerminalInputConnection.java Forward Delete (向前刪除) 修復

### 5.1 根因分析 (Root Cause)
- 在 `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/TerminalInputConnection.java` 的 `deleteSurroundingText(beforeLength, afterLength)` 中：
- 非組字狀態下，代碼僅對 `beforeLength` 進行了迴圈並發送 ASCII Backspace (`0x7F`)，完全忽略了 `afterLength`！
- 當輸入法或實體按鍵發起 Forward Delete（例如 Delete 鍵觸發 `deleteSurroundingText(0, 1)`）時，因為 `beforeLength == 0`，未向 PTY 發送任何字元，導致刪除失敗。

### 5.2 修復步驟與代碼改動 (Remediation Plan)

#### 檔案：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/TerminalInputConnection.java`
修改 `deleteSurroundingText`，為 `afterLength` 加入發送 ANSI Forward Delete (`\033[3~`) 轉義序列的處理邏輯：

```java
// BEFORE:
    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (mComposingManager.isComposing()) {
            mComposingManager.deleteBeforeCursor(beforeLength);
            if (mComposingListener != null) {
                if (mComposingManager.isComposing()) {
                    mComposingListener.onComposingTextUpdated(mComposingManager.getComposingText(), mComposingManager.getCursorPosition());
                } else {
                    mComposingListener.onComposingCleared();
                }
            }
        } else {
            // Send Backspace bytes (\x7f) to PTY
            for (int i = 0; i < beforeLength; i++) {
                dispatchBytesToPty(new byte[]{(byte) 0x7F});
            }
        }
        return true;
    }
```

```java
// AFTER:
    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (mComposingManager.isComposing()) {
            mComposingManager.deleteBeforeCursor(beforeLength);
            if (mComposingListener != null) {
                if (mComposingManager.isComposing()) {
                    mComposingListener.onComposingTextUpdated(mComposingManager.getComposingText(), mComposingManager.getCursorPosition());
                } else {
                    mComposingListener.onComposingCleared();
                }
            }
        } else {
            // 1. 向後刪除 (Backspace): 發送 0x7F
            for (int i = 0; i < beforeLength; i++) {
                dispatchBytesToPty(new byte[]{(byte) 0x7F});
            }
            // 2. 向前刪除 (Forward Delete): 發送 \033[3~ 轉義序列
            if (afterLength > 0) {
                byte[] fwdDeleteBytes = "\033[3~".getBytes(StandardCharsets.US_ASCII);
                for (int i = 0; i < afterLength; i++) {
                    dispatchBytesToPty(fwdDeleteBytes);
                }
            }
        }
        return true;
    }
```

---

## 6. 驗證協議 (Verification Protocol for Worker 4)

Worker 4 完成代碼編修後，必須依照下列順序執行完全部驗證命令，並確保 100% 通過：

### 步驟 1：Java 代碼編譯驗證
```bash
javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_remediation_classes $(find packages/apps/LinuxTerminal/src -name "*.java") tests/unit/TerminalAppUnitTest.java
```
*預期結果*：Exit Code 0，無任何編譯錯誤。

### 步驟 2：Java 單元測試套件執行
```bash
java -cp /tmp/m3_remediation_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
```
*預期結果*：輸出 `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`。

### 步驟 3：Native C++ 壓力測試套件編譯與執行
```bash
g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_challenger2_stress.cpp packages/apps/LinuxTerminal/jni/vterm_parser.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_challenger2_stress && /tmp/m3_native_challenger2_stress
```
*預期結果*：輸出 `ALL NATIVE C++ STRESS TESTS PASSED SUCCESSFULLY`，零 SIGABRT，無記憶體崩潰。

### 步骤 4：Python 端對端 (E2E) 測試套件驗證
```bash
python3 tests/e2e/runner.py --filter F-R3
```
*預期結果*：80/80 測試通過，Pass Rate 100.0%。

---
*本計畫書由 Explorer 6 製作完成，敬請 Worker 4 遵照執行。*
