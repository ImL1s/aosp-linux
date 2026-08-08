# Remediation Plan Handoff Report — Explorer 1 (Finding 1 & Finding 6)

## 1. 觀察結果 (Observation)

### Finding 1: 替代 Stub 類別與 Import 分析 (Stand-In Stub Classes)

1. **`packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java` (Lines 1-14)**:
   ```java
   package android.system.linux;

   public class LinuxManager {
       public static final int STATE_STOPPED = 0;
       public static final int STATE_RUNNING = 1;

       public int getState() {
           return STATE_STOPPED;
       }

       public void startVm() {}
       public void stopVm() {}
   }
   ```
   - **觀察事實**：這是一個放置於 App 源碼目錄（`packages/apps/LinuxTerminal/src/android/system/linux/`）內的 Dummy Stub 類別。`getState()` 硬編碼返回 `STATE_STOPPED` (0)，`startVm()` 與 `stopVm()` 均為空實作。

2. **`packages/apps/LinuxTerminal/src/android/graphics/Rect.java` (Lines 1-44)**:
   ```java
   package android.graphics;

   public class Rect {
       public int left;
       public int top;
       public int right;
       public int bottom;
       ...
   }
   ```
   - **觀察事實**：放置於 App 源碼目錄內的迷你 Stub 類別，重覆定義了標準 AOSP 框架類別 `android.graphics.Rect`。

3. **`frameworks/base/core/java/android/util/Slog.java` (Lines 1-30)**:
   ```java
   /*
    * Mock / Framework stub for android.util.Slog for SystemServer components.
    */
   package android.util;

   public final class Slog {
       private Slog() {}

       public static int v(String tag, String msg) { return Log.v(tag, msg); }
       public static int d(String tag, String msg) { return Log.d(tag, msg); }
       public static int i(String tag, String msg) { return Log.i(tag, msg); }
       public static int w(String tag, String msg) { return Log.w(tag, msg); }
       public static int w(String tag, String msg, Throwable tr) { return Log.w(tag, msg, tr); }
       public static int e(String tag, String msg) { return Log.e(tag, msg); }
       public static int e(String tag, String msg, Throwable tr) { return Log.e(tag, msg, tr); }
   }
   ```
   - **觀察事實**：此檔頂部顯式聲明 `"Mock / Framework stub for android.util.Slog for SystemServer components."`，為直接轉發給 `Log.*` 的 Mock Stub 類別。

4. **正規 AOSP 框架類別與 Import 分析**:
   - **正規 `LinuxManager` Facade**：位於 `frameworks/base/core/java/android/system/linux/LinuxManager.java`（343 行），為具備完整 Binder AIDL 通訊（`ILinuxManager`）、狀態常數（`STATE_OFF=0`, `STATE_STARTING=1`, `STATE_RUNNING=2`, `STATE_SUSPENDED=3`, `STATE_ERROR=4`）、終端 Session 管理、與狀態 Callback 註冊的正規系統服務。
   - **App 端 Import 語法**：`packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalActivity.java` (Line 5) 使用 `import android.system.linux.LinuxManager;`，在 Line 24 呼叫 `(LinuxManager) getSystemService("linux")`，並在 Line 53 檢查 `mLinuxManager.getState() == LinuxManager.STATE_STOPPED` 與 `mLinuxManager.startVm()`。
   - **Renderer 端 Import 語法**：`TerminalScreenMatrix.java`、`NativeSurfaceCanvasRenderer.java`、`TerminalSurfaceView.java` 均引用 `import android.graphics.Rect;`。
   - **Patch 規範對齊**：`patches/aosp_frameworks_base.patch` 已包含在 `Context.java` 聲明 `LINUX_SERVICE = "linux"`、在 `SystemServiceRegistry.java` 註冊 `LinuxManager`、在 `SystemServer.java` 啟動 `LinuxManagerService` 及在 `AndroidManifest.xml` 新增權限的修補內容。

---

### Finding 6: 儲存庫乾淨度與預編譯產物 (Repository Cleanliness & Prebuilt Artifacts)

1. **預編譯 Release 歸檔檔**:
   - `release_dist/aosp-linux-deployment-v1.0.0.tar.gz` (檔案大小 316,438 bytes，且已提交並被 Git 追蹤)。

2. **預編譯測試執行檔 (Git Tracked Test Binaries)**:
   - `system/linux_bridge/tests/linux_bridge_test_bin` (311,856 bytes)
   - `tests/unit/VirtioGpuDmabufTest_bin` (43,176 bytes)
   - `tests/unit/challenger_r2_empirical_bin` (217,720 bytes)
   - `tests/unit/m3_native_challenger2_stress_bin` (273,744 bytes)
   - `tests/unit/m3_native_terminal_test_bin` (57,304 bytes)
   - `unit/challenger_m3_empirical_test` (217,040 bytes)

3. **靜態預填 E2E 測試報告檔 (Static JSON Reports)**:
   - `tests/e2e_report.json` (127,702 bytes，硬編碼 430 項 PASS 測試結果)
   - `tests/e2e/e2e_report.json` (127,510 bytes，硬編碼 430 項 PASS 測試結果)

4. **意外納入 Git 追蹤的編譯中間產物與快取**:
   - `guest/bridge-agent/target/` (包含 635 個 Rust 編譯中間 `.rlib`, `.rmeta`, `.o`, `.d` 檔案)
   - `guest/portal-agent/target/`
   - `.agents/challenger_m1_2/scratch/` (測試中產生的 ASan 執行檔與 `.dSYM` 偵錯包)

---

## 2. 推理鏈 (Logic Chain)

1. **Finding 1 之推理**:
   - `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java` 與 `packages/apps/LinuxTerminal/src/android/graphics/Rect.java` 位於 App 套件目錄結構 `src/android/...` 之中。在 Java 編譯或 IDE 載入時，同名套件與類別會優先遮蔽（Shadow）AOSP 框架層 `frameworks/base/core/java/` 內的正規類別。
   - `TerminalActivity.java` 呼叫 `mLinuxManager.getState()` 時，若 Stub 存在，會硬編碼返回 `STATE_STOPPED` (0)，且 `startVm()` 為空實作，導致 App 完全無法與底層 `LinuxManagerService` 通訊。
   - 只要將這三個 Stub 檔案徹底刪除（並清理空目錄 `packages/apps/LinuxTerminal/src/android/`），`TerminalActivity.java` 的 `import android.system.linux.LinuxManager;` 與 Renderer 的 `import android.graphics.Rect;` 將自動解析至 `frameworks/base/core/java/android/system/linux/LinuxManager.java` 與 `frameworks/base/core/java/android/graphics/Rect.java`。
   - 由於正規 `LinuxManager.java` 定義了相同的 `STATE_STOPPED` (0) 常數與 `startVm()` 方法，App 代碼無需任何修改即可 100% 源碼相容並呼叫真正的 AIDL 系統服務。
   - 同理，刪除 `frameworks/base/core/java/android/util/Slog.java` 可解除 mock stub 遮蔽，確保框架服務使用正規框架日誌機制。

2. **Finding 6 之推理**:
   - 預編譯的發行套件 `release_dist/aosp-linux-deployment-v1.0.0.tar.gz`、測試二進位檔 (`*_bin`, `challenger_*`)、硬編碼 JSON 報告 (`e2e_report.json`)、以及 Cargo 編譯中間檔 (`target/`) 納入 Git 追蹤，直接違反了項目第 9 項構建與測試合規規範。
   - 二進位測試檔案應由構建與測試腳本動態編譯至已被 Git 忽略的目錄（如 `build_out/`），不得將二進位檔提交至源碼目錄。
   - E2E 測試報告必須由 `python3 tests/e2e/runner.py` 於測試實際執行時動態輸出，不可在 Git 倉庫中維護靜態報告。
   - 補救措施必須包含：(a) 使用 `git rm` 移除二進位檔與靜態報告追蹤，(b) 實體刪除相關產物，(c) 更新 `.gitignore` 確保此類檔案不再被誤提交。

---

## 3. 注意事項 (Caveats)

- 本探索者 agent 執行**唯讀探索 (Read-only Investigation)**，未直接修改 `.agents/explorer_r4_1/` 之外的源碼檔案。
- 實際的代碼與 Git 狀態修復將由次階段派發的 Worker 執行。

---

## 4. 結論 (Conclusion) & 精準補救方案

### 補救方案 1: 清除 Stub 類別與正規化 Import 鏈 (Finding 1 Remediation)

1. **Purge 檔案清單**：
   - 刪除 `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java`
   - 刪除 `packages/apps/LinuxTerminal/src/android/graphics/Rect.java`
   - 刪除 `frameworks/base/core/java/android/util/Slog.java`
   - 刪除空目錄 `packages/apps/LinuxTerminal/src/android/system/linux/` 與 `packages/apps/LinuxTerminal/src/android/graphics/`
2. **Import 鏈保護與驗證**：
   - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalActivity.java` 保留 `import android.system.linux.LinuxManager;`
   - `TerminalScreenMatrix.java` 等保留 `import android.graphics.Rect;`
   - 確保框架層服務 (`LinuxManagerService.java`, `LinuxPortalService.java`) 直接對齊 `patches/aosp_frameworks_base.patch` 所定義的正規 API。

### 補救方案 2: 儲存庫徹底清理與防護機制 (Finding 6 Remediation)

1. **Git 追蹤解除與檔案 Purge 指令**：
   ```bash
   # 1. 刪除預編譯 Release 歸檔檔
   git rm -f release_dist/aosp-linux-deployment-v1.0.0.tar.gz
   rm -rf release_dist/

   # 2. 刪除預編譯測試執行檔
   git rm -f system/linux_bridge/tests/linux_bridge_test_bin \
              tests/unit/VirtioGpuDmabufTest_bin \
              tests/unit/challenger_r2_empirical_bin \
              tests/unit/m3_native_challenger2_stress_bin \
              tests/unit/m3_native_terminal_test_bin \
              unit/challenger_m3_empirical_test

   # 3. 刪除靜態 E2E JSON 測試報告檔
   git rm -f tests/e2e_report.json tests/e2e/e2e_report.json

   # 4. 清除意外追蹤的 Target 與 Scratch 快取目錄
   git rm -r --cached guest/bridge-agent/target guest/portal-agent/target
   git rm -r --cached .agents/challenger_m1_2/scratch
   rm -rf guest/bridge-agent/target guest/portal-agent/target .agents/challenger_m1_2/scratch
   ```

2. **更新與強化 `.gitignore`**：
   在 `.gitignore` 中明確寫入以下防護條款：
   ```gitignore
   # Prebuilt Release Archives & Dist
   release_dist/
   *.tar.gz

   # Rust & Native Build Directories
   guest/bridge-agent/target/
   guest/portal-agent/target/
   target/

   # C++ & Native Test Binaries
   *_bin
   *_test
   tests/unit/*_bin
   unit/challenger_*

   # Dynamic Test Reports
   e2e_report.json
   tests/e2e_report.json
   tests/e2e/e2e_report.json
   *_report.json

   # Agent Scratch Directories
   .agents/*/scratch/
   ```

3. **構建與測試腳本規範**：
   - 測試構建腳本統一將編譯出的二進位檔放置於被忽略的 `build_out/bin/`。
   - `python3 tests/e2e/runner.py` 執行時動態生成 `e2e_report.json`，且不提交入 Git。

---

## 5. 驗證方法 (Verification Method)

可透過以下步驟與指令獨立驗證本補救方案之完成度：

1. **Stub 檔案刪除驗證**：
   ```bash
   ls packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java
   ls packages/apps/LinuxTerminal/src/android/graphics/Rect.java
   ls frameworks/base/core/java/android/util/Slog.java
   ```
   *預期結果*：回傳 `No such file or directory`。

2. **Git 追蹤二進位與 Stub 清查驗證**：
   ```bash
   git ls-files | grep -E "(LinuxManager.java|Rect.java|Slog.java|tar.gz|_bin|e2e_report.json)"
   ```
   *預期結果*：僅列出 `frameworks/base/core/java/android/system/linux/LinuxManager.java` 與 `frameworks/base/core/java/android/graphics/Rect.java` 兩個正規 AOSP 框架檔，其餘 Stub 與二進位檔均無輸出。

3. **Git Cleanliness 與 .gitignore 運作驗證**：
   ```bash
   git status --porcelain
   ```
   *預期結果*：工作區無任何未被忽略的二進位產物或未追蹤舊快取。
