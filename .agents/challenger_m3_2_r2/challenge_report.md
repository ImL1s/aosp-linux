# Milestone M3 Iteration 2 Gate Review — Adversarial Challenge Report

**審查對象**: Milestone M3 (Native Touch Terminal & IME Engine)  
**對抗驗證角色**: Challenger 2 (Empirical Challenger)  
**最終審查判定 (Verdict)**: `REJECT` (退回修復)  
**日期**: 2026-08-06  

---

## 1. 審查摘要與判定 (Executive Summary & Verdict)

本對抗報告針對 Milestone M3 (涵蓋全部 7 項 Feature: F-R3-001 ~ F-R3-007) 之 CJK IME 處理、Vsock Port 5001 PTY 訊框邊界條件、惡意/異常封包注入、多位元組 UTF-8 Socket 分段處理以及 SGR 觸控滑鼠協定進行實證驗證與原生 C++ 壓力測試。

經過原生 C++ 壓力測試程式 (`/tmp/m3_native_challenger2_stress`) 實證測試，**發現一項致命性缺陷 (Critical Flaw)**：`vterm_parser.cpp` 中的原生多位元組 UTF-8Socket 分段解析演算法在處理跨 Socket 分割讀取的 3-byte CJK 字元（例如「測」`0xE6 0xB8 0xA1`）時，會因前向搜尋 Lead Byte 過程中錯誤遞減寫入長度 `validLen`，導致 UTF-8 第一字節 `0xE6` 被單獨傳送至 `vterm_input_write`，後續兩位元組 `[0xB8, 0xA1]` 則被留存於 `mUtf8PartialBuffer`，造成 **CJK UTF-8 編碼徹底毀損與畫面亂碼**。

同時，針對 C++ 封包 Framer 與 Java 端的對比稽核中，亦發現 Socket 流同步機制與大貼上資料塊分段（1024-byte Chunking）未考慮 UTF-8 字元邊界等潛在威脅。

因此，Challenger 2 對 Milestone M3 Iteration 2 給出 **`REJECT`** 判定，要求團隊依據本報告修復原生 UTF-8 分段解析邏輯後重新提交。

---

## 2. 發現之缺陷與風險分析 (Detailed Findings & Flaws)

### 🔴 【Critical Issue 1】`vterm_parser.cpp` 多位元組 CJK UTF-8 Socket 分段解析長度計算毀損 (UTF-8 Stream Corruption)

- **受影響元件**: `packages/apps/LinuxTerminal/jni/vterm_parser.cpp` (方法: `VTermParserBridge::feedBytes`, 行 51-76)
- **實證現象 (Empirical Reproduction)**:
  當 3 位元組 CJK UTF-8 字元（如「測」`0xE6 0xB8 0xA1`）或 4 位元組 Emoji 字元分次經過 Vsock Socket 讀取（每次傳送 1 byte 或 2 bytes）並調用 `feedBytes()` 時，原生 C++ 測試觸發 Assertion 失敗：
  ```text
  [CPP STRESS 05] CJK IME UTF-8 Socket Fragmentation & Wide-Char Parsing...
  Assertion failed: (cells[0].codepoint == 0x6E2C), function test_utf8_cjk_fragmentation_stress, file m3_native_challenger2_stress.cpp, line 175.
  ```
- **根因分析 (Root Cause Analysis)**:
  在 `vterm_parser.cpp` 中，`feedBytes` 為了處理末尾未完成的 UTF-8 字元，使用 `validLen` 從 `buffer.size()` 開始倒退檢查 Lead Byte：
  ```cpp
  size_t validLen = buffer.size();
  while (validLen > 0) {
      uint8_t b = buffer[validLen - 1];
      if ((b & 0x80) == 0) break;
      if ((b & 0xE0) == 0xC0) {
          if (buffer.size() - (validLen - 1) < 2) validLen--;
          break;
      }
      if ((b & 0xF0) == 0xE0) {
          if (buffer.size() - (validLen - 1) < 3) validLen--;
          break;
      }
      validLen--; // <--- 致命漏洞點！每次遇到 Continuation byte (0x80~0xBF) 就遞減 validLen
  }
  ```
  當 `buffer` 已經累積完整 3 位元組 `[0xE6, 0xB8, 0xA1]` 時：
  1. 第 1 次迴圈：檢查 `buffer[2] (0xA1)`，為 Continuation byte，執行 `validLen--` (使 `validLen` 變為 2)。
  2. 第 2 次迴圈：檢查 `buffer[1] (0xB8)`，為 Continuation byte，執行 `validLen--` (使 `validLen` 變為 1)。
  3. 第 3 次迴圈：檢查 `buffer[0] (0xE6)`，為 Lead byte `(b & 0xF0) == 0xE0`，`buffer.size() - 0 = 3 >= 3`（成立，break）。
  4. 結束時 `validLen` 竟被扣為 1！
  5. 導致 `vterm_input_write` 僅寫入 `buffer[0]` (`0xE6`)，其餘 `[0xB8, 0xA1]` 被當作未完成字節截斷並移入 `mUtf8PartialBuffer`！
  6. 下一次 Socket 讀取時 `mUtf8PartialBuffer` 的孤立 Continuation byte 被放在最前面，導致後續所有 UTF-8 數據流全面爆發亂碼與毀損。

---

### 🟡 【High Issue 2】`pty_framing_handler.cpp` 遇到無效 Header 種類時丟棄全域 Buffer 導致流斷裂 (Stream Resynchronization Failure)

- **受影響元件**: `packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp` (方法: `PtyFramingHandlerNative::processIncomingChunk`, 行 53-67)
- **現象與對比**:
  在 C++ `PtyFramingHandlerNative` 中，當收到的 21-byte Header 包含無效 `typeByte` (`< 0x01` 或 `> 0x05`) 或 `payloadLen > 64KB` 時：
  ```cpp
  if (typeByte < 0x01 || typeByte > 0x05) {
      mBuffer.clear(); // 致命點：清空全域 mBuffer
      return;
  }
  ```
  相反地，Java `VsockPtyFramer.java` 在遇到無效 `typeByte` 時僅前進 1 位元組 (`readOffset += 1`) 以重新進行 Stream Resynchronization (流同步)。
  C++ 端的 `mBuffer.clear()` 會直接丟棄所有已快取在緩衝區中的未處理訊框與後續有效封包，一旦 Socket 網路傳輸發生 1 byte 雜訊或邊界偏移，將造成 PTY 通訊永久斷開。

---

### 🟡 【Medium Issue 3】`TerminalInputConnection.java` 大量文字貼上之 1024-Byte 分組切割破壞 UTF-8 字元完整性 (UTF-8 Splitting across Frame Boundaries)

- **受影響元件**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/TerminalInputConnection.java` (方法: `dispatchBytesToPty`, 行 186-197)
- **現象說明**:
  `TerminalInputConnection` 將剪貼簿黏貼或輸入的大量 UTF-8 位元組切分為 1024-byte 區塊進行發送：
  ```java
  int chunkSize = 1024;
  for (int i = 0; i < bytes.length; i += chunkSize) {
      int len = Math.min(chunkSize, bytes.length - i);
      byte[] chunk = new byte[len];
      System.arraycopy(bytes, i, chunk, 0, len);
      mPtySender.sendBytes(chunk);
  }
  ```
  若輸入包含大量 CJK 文字或 Emoji，且第 1024 個位元組正好是 3 位元組或 4 位元組 UTF-8 字元的第一個 Lead Byte，該 UTF-8 字元將會被硬性拆分到兩個不同的 Vsock PTY DATA 訊框中。如果 Guest 端 PTY agent 依訊框處理數據而未做跨訊框 UTF-8 緩衝，會引發 Terminal 側字元解析異常。

---

## 3. 對抗壓力測試結果 summary (Stress Test Results)

原生壓力測試執行命令：
`g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni -Ipackages/apps/LinuxTerminal/jni/libvterm/include tests/unit/m3_native_challenger2_stress.cpp packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp packages/apps/LinuxTerminal/jni/vterm_parser.cpp packages/apps/LinuxTerminal/jni/libvterm/src/*.c -o /tmp/m3_native_challenger2_stress && /tmp/m3_native_challenger2_stress`

| 測試項目 ID | 測試內容與情境 | 預期結果 | 實測結果 | 判定 |
|---|---|---|---|---|
| **CPP STRESS 01** | SGR Mouse Generator 高頻率吞吐量測試 (100,000 motion pkts) | > 1,000,000 pkts/sec | 4.76 x 10^6 pkts/sec (21 ms) | **PASS** |
| **CPP STRESS 02** | SGR Modifier 組合鍵與邊界座標單元格轉換 (Shift/Ctrl/Alt, out-of-bounds) | 座標自動 Clamp 至 [1, Cols/Rows]，正確編碼 `\x1b[<Cb;X;YM` | 邊界與 Modifier 編碼完全匹配 | **PASS** |
| **CPP STRESS 03** | Vsock Port 5001 PTY 訊框 Header Fuzzing (Type=0xFF, Payload>64KB, Session Mismatch, 2-byte socket reads) | 正確丟棄無效/超大/Session不匹配訊框，重組分段讀取 | 成功重組與過濾異常封包 | **PASS** |
| **CPP STRESS 04** | IEEE 802.3 CRC32 數據完整性計算驗證 ("123456789") | CRC32 == 0xCBF43926 | 0xCBF43926 匹配 | **PASS** |
| **CPP STRESS 05** | CJK IME UTF-8 Socket 分段 1-byte 讀取與寬字元解析 (「測」「試」「😀」) | 1-byte 逐字節輸入後正確重組「測」(0x6E2C, width=2) | **FAIL (Assertion failed: cells[0].codepoint == 0x6E2C)** | 🔴 **FAIL** |

---

## 4. 具體建議修復方案 (Recommended Remediation Steps)

1. **修復 `vterm_parser.cpp` 中的 Lead Byte 索引計算邏輯**:
   不應在掃描倒退時遞減 `validLen`。應像 `libvterm_jni.cpp` 一樣，使用獨立的索引變數 `i` 來定位 Lead Byte，並在確認 Lead Byte 及其預期長度後，再據此計算正確的 `validLen`：
   ```cpp
   size_t total = buffer.size();
   size_t validLen = total;
   if (total > 0) {
       size_t i = total - 1;
       while (i < total && (buffer[i] & 0xC0) == 0x80) {
           if (i == 0) break;
           i--;
       }
       if (i < total) {
           uint8_t lead = buffer[i];
           size_t expected = 1;
           if ((lead & 0xE0) == 0xC0) expected = 2;
           else if ((lead & 0xF0) == 0xE0) expected = 3;
           else if ((lead & 0xF8) == 0xF0) expected = 4;

           if (total - i < expected) {
               mUtf8PartialBuffer.assign(buffer.begin() + i, buffer.end());
               validLen = i;
           }
       }
   }
   ```

2. **對齊 `pty_framing_handler.cpp` 與 Java 端之流同步機制**:
   在 `PtyFramingHandlerNative::processIncomingChunk` 中，遇到無效 `typeByte` 時不要呼叫 `mBuffer.clear()`，改為只丟棄 1 個位元組 (`readOffset += 1`) 並繼續迴圈，維持 vsock 流同步。

3. **優化 `TerminalInputConnection.java` 1024-byte Chunking 邊界**:
   在分段發送前，確保 1024 位元組切分點不會落在 UTF-8 多位元組字元的帶頭字節或續航字節之間。

---

## 5. 總結判定 (Final Verdict)

- **Verdict**: **`REJECT`**
- **退回原因**: `vterm_parser.cpp` 存在真實發生的 CJK 多位元組 UTF-8 分段數據流毀損 bug，無法通過 CJK IME 高強度分段傳輸驗證。請修復後重新進行 Iteration Review。
