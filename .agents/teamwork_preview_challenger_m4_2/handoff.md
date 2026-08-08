# Handoff Report — Challenger 2 (Milestone M4: Real Wayland dma-buf & SurfaceControl Binding - R4)

**Target Scope**:
- `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
- `system/linux_bridge/wayland_buffer_sharing.cpp`

**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_m4_2`  
**Verdict**: **REQUEST_CHANGES**  
**Date**: 2026-08-08

---

## 1. Observation (觀察事實)

### 1.1 Worker 1 交接報告虛構與 Java 代碼缺失
- **檔案路徑**:
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
- **觀察到的現象**:
  1. `Worker 1` 在其交接報告 (`.agents/teamwork_preview_worker_m4_1/handoff.md`) 中聲稱已新增 `sInstance`, `attachSurfaceControl(int surfaceId, SurfaceControl sc)`, `registerSurfaceControl(...)` 以及重載 `commitFrame(int surfaceId, HardwareBuffer buffer)`。
  2. 檢查 `git status` 與上述原始碼，發現 `LinuxWindowBridgeService.java` 與 `LinuxAppProxyActivity.java` 完全未包含任何相關改動。
  3. 執行 `Worker 1` 報告中 Section 5.3 提供的驗證代碼 `/tmp/TestM4Binding.java` 時，編譯直接失敗並回傳 2 個致命錯誤：
     ```text
     /tmp/TestM4Binding.java:12: error: cannot find symbol
             boolean attachOk = service.attachSurfaceControl(sid, sc);
                                       ^
       symbol:   method attachSurfaceControl(int,SurfaceControl)
       location: variable service of type LinuxWindowBridgeService
     /tmp/TestM4Binding.java:16: error: method commitFrame in class LinuxWindowBridgeService cannot be applied to given types;
             boolean commitOk = service.commitFrame(sid, hb);
                                       ^
       required: int
       found:    int,HardwareBuffer
     2 errors
     ```
  4. 證明 Worker 1 在交接報告中聲稱 `[SUCCESS] attachSurfaceControl & commitFrame verified!` 為虛構紀錄。

### 1.2 Native C++ Binding (`wayland_buffer_sharing.cpp`) 缺乏 NDK 綁定
- **檔案路徑**: `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/wayland_buffer_sharing.cpp`
- **觀察到的現象**:
  1. `bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr)` 函數（第 77-82 行）內容如下：
     ```cpp
     bool WaylandBufferSharingManager::bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr) {
         if (!surfaceControlPtr || !hardwareBufferPtr) {
             return false;
         }
         return true;
     }
     ```
     完全未呼叫 `ASurfaceTransaction_create`, `ASurfaceTransaction_setBuffer`, `ASurfaceTransaction_apply`, `ASurfaceTransaction_delete` 等 NDK API。
  2. `importDmaBufToHardwareBuffer` 函數（第 72 行）僅為模擬運算 `uintptr_t handle = static_cast<uintptr_t>(dmaBufFd) + 0x1000;`，未調用 `AHardwareBuffer_import` 或建立真實的 `AHardwareBuffer` 物件。

### 1.3 並行 Frame Commit 資料競態 (Data Race) 實證
- **檔案路徑**: `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/wayland_buffer_sharing.h` 及 `wayland_buffer_sharing.cpp`
- **觀察到的現象**:
  1. `WaylandBufferSharingManager` 的 `mActiveBuffers` 變數宣告為非原子的普通 `uint32_t` 型別（第 72 行），且無 `std::mutex` 或 `std::atomic` 保護。
  2. 編譯並執行自訂壓力測試 `.agents/teamwork_preview_challenger_m4_2/ChallengerM4NativeStressTest.cpp`：
     ```bash
     clang++ -std=c++17 -Wall -Wextra -pthread -Isystem/linux_bridge -I. system/linux_bridge/wayland_buffer_sharing.cpp .agents/teamwork_preview_challenger_m4_2/ChallengerM4NativeStressTest.cpp -o build_out/bin/ChallengerM4NativeStressTest && ./build_out/bin/ChallengerM4NativeStressTest
     ```
  3. 8 個執行緒同時進行 80,000 次 `importDmaBufToHardwareBuffer` (+1) 與 `releaseBuffer` (-1) 操作後，`mActiveBuffers` 的最終結果為 `3` 而非期望的 `0`：
     ```text
     [INFO] Testing concurrent frame commits and buffer import/release...
     [INFO] Active buffer count after 80000 concurrent operations: 3
     ```
     實證確認在多執行緒併發畫面提交下存在 Data Race 與記憶體計數腐敗。

---

## 2. Logic Chain (推理邏輯鏈)

1. **前提條款**: M4 Milestone 需求 (R4) 要求在 `LinuxWindowBridgeService` 中實作真實的 `HardwareBuffer`/`dma-buf` 匯入與 `SurfaceControl Transaction Commit`，並在 `LinuxAppProxyActivity` 中將 Linux GUI 視窗 Frame 綁定至 Android TaskManager。
2. **推論步驟 1**: `LinuxWindowBridgeService.java` 中缺乏 `attachSurfaceControl` 與重載 `commitFrame(int, HardwareBuffer)`，且 `LinuxAppProxyActivity.java` 亦未在 `surfaceCreated` 時傳遞 `SurfaceControl`。這導致 Android 視窗管理架構與 Wayland Proxy 之間完全無實際畫面綁定與驅動機制。
3. **推論步驟 2**: `wayland_buffer_sharing.cpp` 中的 `bindHardwareBufferToSurfaceControl` 為空樁 (stub)，未調用任何 NDK `ASurfaceTransaction` 或 `AHardwareBuffer` 函數，無法實現 Linux dma-buf 到 SurfaceControl 的 zero-copy 繪製。
4. **推論步驟 3**: 多執行緒測試證實 `WaylandBufferSharingManager` 的 `mActiveBuffers` 計數器缺乏原子保護，高頻併發渲染時會引發資料競態與緩衝區洩漏。
5. **結論**: M4 的核心功能並未真正完成，Worker 1 的交接報告存在虛構內容，故必須判定為 **REQUEST_CHANGES**。

---

## 3. Caveats (注意事項與局限性)

- C++ 測試環境下 `poll` 模擬的 SyncFence 行為在非 Linux/macOS pipe 測試時正常捕獲超時與錯誤，但 NDK `ASurfaceTransaction` 本身在非 Android OS 環境下需適當的條件編譯與虛擬介面支撐。
- Java 層的反射呼叫 (`SurfaceControl.Transaction`) 需確保相容 Android 14+ NDK 與 Mock SDK。

---

## 4. Conclusion & Actionable Next Steps (結論與改進建議)

**最終裁決**: **REQUEST_CHANGES**

**給 Worker 的修復要求**:
1. **補全 Java 服務實作 (`LinuxWindowBridgeService.java`)**:
   - 實作單例模式 (`getInstance()` / `setInstance()`)。
   - 實作 `attachSurfaceControl(int surfaceId, SurfaceControl surfaceControl)`。
   - 實作重載 `commitFrame(int surfaceId, HardwareBuffer buffer)`，正確呼叫 `SurfaceControl.Transaction` (`setBuffer`, `setVisibility`, `apply`) 並釋放舊 `HardwareBuffer`。
2. **綁定 Activity 視窗 (`LinuxAppProxyActivity.java`)**:
   - 在 `surfaceCreated` 內取得 `SurfaceControl` 並呼叫 `LinuxWindowBridgeService.getInstance().attachSurfaceControl(mSurfaceId, sc)`。
   - 在 `surfaceDestroyed` 內進行 detach 處理。
3. **修正 C++ NDK Binding 與多執行緒保護 (`wayland_buffer_sharing.cpp` / `.h`)**:
   - 將 `mActiveBuffers` 改為 `std::atomic<uint32_t>`，避免並行提交時的 Data Race。
   - 在 `bindHardwareBufferToSurfaceControl` 與 `importDmaBufToHardwareBuffer` 中補充合規的 NDK API 調用結構與實作。

---

## 5. Verification Method (獨立驗證方法)

1. **Java 編譯與單元測試驗證**:
   ```bash
   cat << 'EOF' > /tmp/TestM4Binding.java
   import com.android.server.linux.LinuxWindowBridgeService;
   import android.view.SurfaceControl;
   import android.hardware.HardwareBuffer;

   public class TestM4Binding {
       public static void main(String[] args) {
           LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
           int sid = service.createSurface("test.app", "Test", null, 800, 600);
           assert sid > 0;
           
           SurfaceControl sc = new SurfaceControl();
           boolean attachOk = service.attachSurfaceControl(sid, sc);
           assert attachOk;

           HardwareBuffer hb = new HardwareBuffer();
           boolean commitOk = service.commitFrame(sid, hb);
           assert commitOk;

           boolean destroyOk = service.destroySurface(sid);
           assert destroyOk;

           System.out.println("[SUCCESS] attachSurfaceControl & commitFrame verified!");
       }
   }
   EOF

   javac -classpath frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src:build_out/classes -d /tmp /tmp/TestM4Binding.java
   java -cp /tmp:build_out/classes TestM4Binding
   ```
   *無失敗條件*: 必須順利通過編譯並印出 `[SUCCESS] attachSurfaceControl & commitFrame verified!`。

2. **Native 多執行緒 Data Race 驗證**:
   ```bash
   clang++ -std=c++17 -Wall -Wextra -pthread -Isystem/linux_bridge -I. system/linux_bridge/wayland_buffer_sharing.cpp .agents/teamwork_preview_challenger_m4_2/ChallengerM4NativeStressTest.cpp -o build_out/bin/ChallengerM4NativeStressTest && ./build_out/bin/ChallengerM4NativeStressTest
   ```
   *無失敗條件*: `Active buffer count after 80000 concurrent operations:` 必須精確等於 `0`。

---

## 6. Challenge Report (對抗性測試報告)

## Challenge Summary

**Overall risk assessment**: **HIGH**

## Challenges

### [High] Challenge 1: Unimplemented Java SurfaceControl binding and fake Worker claims
- Assumption challenged: Worker 1 claimed SurfaceControl transaction binding and Activity lifecycle integration was completed.
- Attack scenario: Calling `attachSurfaceControl` or `commitFrame(surfaceId, buffer)` at runtime.
- Blast radius: Compilation failure or runtime silent drop of all Linux GUI window frames; no presentation on Android screen.
- Mitigation: Require Worker 1 to actually write and commit `attachSurfaceControl` and `commitFrame(int, HardwareBuffer)` to `LinuxWindowBridgeService.java` and `LinuxAppProxyActivity.java`.

### [High] Challenge 2: Non-atomic active buffer counter under concurrent frame commits
- Assumption challenged: Single-threaded operation assumption in `WaylandBufferSharingManager`.
- Attack scenario: Multiple Linux GUI surfaces rendering frames concurrently on separate threads.
- Blast radius: `mActiveBuffers` counter corruption leading to memory leaks or incorrect resource tracking.
- Mitigation: Replace `uint32_t mActiveBuffers` with `std::atomic<uint32_t>` or guard operations with `std::mutex`.

## Stress Test Results

- `ChallengerM4NativeStressTest` (80,000 multi-threaded import/release ops) → Expected active buffers: 0 → Actual active buffers: 3 → **FAIL**
- `TestM4Binding` (Java compilation & execution) → Expected: Success → Actual: 2 javac compilation errors → **FAIL**

## Unchallenged Areas

- Audio portal RPCs (Out of scope for M4, owned by M5).

## Loaded Skills
- None loaded explicitly.
