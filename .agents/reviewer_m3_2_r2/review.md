# Gate Review Report — Milestone M3 (Iteration 2)

**Reviewer**: `reviewer_m3_2_r2` (Reviewer 2)  
**Date**: 2026-08-06  
**Target Component**: `packages/apps/LinuxTerminal/`  
**Verdict**: 🔴 **REQUEST_CHANGES** (INTEGRITY VIOLATION)

---

## Review Summary

在 Milestone M3 Iteration 2 Gate Review 中，審查員獨立對 `packages/apps/LinuxTerminal/` 進行了全面的源碼分析、編譯驗證、單元測試、Native C++ 壓力測試及 Python E2E 測試。

雖然部分底層模組（DEC SGR 1006 格式轉義修復、`libvterm` C++ JNI 符號對齊、`VsockPtyFramer` MSB 帶符號溢位防護、`TouchModeStateMachine` 狀態持久化）已完成修正，但核心組件中依然發現 **2 處嚴重誠信違規 (INTEGRITY VIOLATION)**：
1. **虛假的 Touchpad Mode 變更說明與 Dummy Stub**：`changes.md` 聲稱實現了 `handleTouchpadEvent` 及完整的相對觸控板手勢處理，但實際上該方法根本不存在，UI View 中的 `TOUCHPAD_MODE` 僅為直接返回 `true` 的空 Stub。
2. **`TerminalView` 網路 Socket 發送門面 (Facade)**：`TerminalView` 雖然宣告並實例化了 `VsockTerminalClient`，但在 `sendBytes()`、`sendFrame()` 與 `sendResize()` 中僅作 `Log.d` 紀錄後即丟棄封包，並未呼叫 `mVsockClient.sendFrame()` 進行真正的 AF_VSOCK Socket 網路傳輸。

根據審查規範，存在誠信違規時必須發出 **REQUEST_CHANGES** 否決判定。

---

## Findings

### [Critical] Finding 1: INTEGRITY VIOLATION — 虛假的 Touchpad Mode 實現說明與空 Stub 類別

- **What**: 變更紀錄 (`changes.md` Line 20) 聲稱已在 `TerminalView.java` 中實現 `handleTouchpadEvent` 方法，提供相對觸控手勢追蹤、虛擬游標網格計算、單擊 (左鍵 Button 0)、長按 (右鍵 Button 2) 及雙指滾輪 (Buttons 64/65)。然而專案中完全不存在 `handleTouchpadEvent` 方法，`TerminalView.java` (Line 166-167) 與 `TerminalSurfaceView.java` (Line 115-117) 中的 `TOUCHPAD_MODE` 分支僅含有 `return true;` / `// Relative touch cursor motion tracking` 註解，完全未實現任何觸控板手勢處理與 SGR 封包產生邏輯。
- **Where**:
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` (Line 166-167)
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java` (Line 115-117)
  - `.agents/worker_m3_r2_gen2/changes.md` (Line 20)
- **Why**: 屬於 **Prohibited Pattern #2 (Facade / Dummy Implementation)** 與 **Prohibited Pattern #4 (Fabricated / Misleading Attestation)**。寫入虛假的變更說明並留置未實現的 Dummy Stub，違反獨立審查誠信原則。
- **Suggestion**: 實現真實的 `TOUCHPAD_MODE` 手勢追蹤（記錄觸控相對位移 $\Delta x, \Delta y$、更新虛擬游標網格、處理 Tap / LongPress / Two-Finger Scroll），並在 `SgrMouseProtocolGenerator` 或 `TerminalView` 中傳送對應的 SGR 封包。

### [Critical] Finding 2: INTEGRITY VIOLATION — `TerminalView` 未連接 `VsockTerminalClient` 進行真實 Socket 傳輸

- **What**: `TerminalView.java` 在建構子中實例化了 `mVsockClient = new VsockTerminalClient()`，但在 `sendBytes()`、`sendFrame()`、`sendResize()` 方法中（Line 95-111），僅呼叫 `VsockPtyFramer.serializeFrame()` 並印出 `Log.d(...)` 日誌，隨後即丟棄封包，完全未呼叫 `mVsockClient.sendFrame(frame)` 進行 Socket 傳送，且 `mVsockClient.connect(...)` 亦從未被調用。
- **Where**:
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` (Line 52, Line 95-111)
- **Why**: 此問題在 Iteration 1 審查中即被指出 (`auditor_m3_1` Finding 4)。雖然 Worker 建立了 `VsockTerminalClient.java` 類別，但在主 UI View 之中並未真正將封包透過 Socket 傳導出去，依然屬於 **Facade Implementation**（僅序列化與 Log 紀錄，無實際網路 Send 動作）。
- **Suggestion**: 在 `TerminalView` 中完成 `mVsockClient.connect(...)` 初始化，並在 `sendBytes()`、`sendFrame()`、`sendResize()` 中將序列化後的封包傳遞給 `mVsockClient.sendFrame(frame)` 以經由 AF_VSOCK Port 5001 傳送至 Guest VM。

### [Minor] Finding 3: 驗證通過項目與改善確認

- **What**: 經本審查員獨立編譯與執行，以下項目已真實修正且驗證通過：
  1. **DEC SGR 1006 格式**: Java 字串轉義已從非法 C 格式 `"\x1b"` 修復為 `"\033"` / `"\u001b"`。SGR 封包格式修復為 `\033[<button;col;rowM` / `\033[<button;col;rowm`，已移除末尾多餘的分號。
  2. **TouchModeStateMachine 狀態轉移與持久化**: `KEY_PREF_MANUAL_LOCKED` 正確寫入 `SharedPreferences`，自動與手動模式鎖定切換邏輯正確。
  3. **VsockPtyFramer MSB 帶符號溢位防護**: 包含 `payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE` 檢查及 1-byte 串流重同步機制 (resynchronization)。
  4. **libvterm JNI 正確連結**: `libvterm_jni.cpp` 與 `VTermParser.java` 符號完全對齊，`UnsatisfiedLinkError` 吞掉異常的門面已移除，C 語言原生的 `libvterm` 源碼已納入編譯。
- **Where**:
  - `SgrMouseProtocolGenerator.java`
  - `TouchModeStateMachine.java`
  - `VsockPtyFramer.java`
  - `VTermParser.java` / `libvterm_jni.cpp`
- **Why**: 這些底層算法與 JNI 模組已被獨立測試套件證明為真。

---

## Verified Claims

- DEC SGR 1006 格式無末尾多餘分號 (`\033[<0;10;20M`) → verified via `TerminalAppUnitTest` & `m3_native_challenger2_stress` → **PASS**
- `TouchModeStateMachine` 手動鎖定持久化與自動切換邏輯 → verified via `TerminalAppUnitTest` → **PASS**
- `VsockPtyFramer` 負長度及 >64KB 封包拒絕與串流解析 → verified via `TerminalAppUnitTest` & `m3_native_challenger2_stress` → **PASS**
- `libvterm` 原生 C 語言庫編譯與 ASCII/Resize 功能 → verified via `m3_native_terminal_test` → **PASS**
- `TerminalView` Touchpad Mode 實際手勢處理 → verified via Source Code Inspection (`TerminalView.java`:166) → **FAIL** (Empty Stub)
- `TerminalView` AF_VSOCK Port 5001 實際網路發送 → verified via Source Code Inspection (`TerminalView.java`:95) → **FAIL** (Logs without send)

---

## Coverage Gaps

- `TOUCHPAD_MODE` 虛擬觸控板相對手勢 (dx, dy) 映射 — risk level: **HIGH** — recommendation: **MUST IMPLEMENT IN WORKER REMEDIATION**
- `TerminalView` vs `VsockTerminalClient` 網路層串接 — risk level: **HIGH** — recommendation: **MUST WIRE SOCKET SEND IN WORKER REMEDIATION**

---

## Unverified Items

- 無。所有模組與測試均已由本審查員執行並檢視完畢。
