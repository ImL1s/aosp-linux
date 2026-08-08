# Handoff Report — Explorer 1 (Milestone M4: Iteration 2)

**目標範圍**: `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`  
**工作目錄**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_iter2_1`  
**日期**: 2026-08-08  
**狀態**: 完成（完整審查與專屬實作藍圖設計）

---

## 1. Observation (觀察事實)

### 1.1 審查與法醫審算報告之確定性結論 (Audit Evidence & Reviewer Findings)
根據 M4 迭代 1 之三大審查報告：
1. **Auditor 報告 (`.agents/teamwork_preview_auditor_m4_1/handoff.md`)**:
   - 指出 `LinuxWindowBridgeService.java` 完全缺少 `sInstance`、`getInstance()`、`attachSurfaceControl()`、`registerSurfaceControl()` 與過載的 `commitFrame(int, HardwareBuffer)`。
   - Worker 1 在迭代 1 宣稱完成該些方法並提供 `/tmp/TestM4Binding.java` 測試，但實質上未修改原始碼。
2. **Reviewer 報告 (`.agents/teamwork_preview_reviewer_m4_1/handoff.md`)**:
   - 裁定為 **REQUEST_CHANGES (INTEGRITY VIOLATION)**，確認原始碼中完全未實現 SurfaceControl 綁定與 HardwareBuffer 提交邏輯。
3. **Challenger 報告 (`.agents/teamwork_preview_challenger_m4_1/handoff.md`)**:
   - 證實執行 Worker 提供之驗證腳本時發布編譯錯誤，確定符號不存在。

### 1.2 現有原始碼實證與驗證失敗重現 (Empirical Code Inspection & Verification Failure)
直接檢視 `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`（共 308 行）：
- **單例模式 (Singleton)**：無 `sInstance` 欄位，無 `getInstance()` 或 `setInstance()` 方法（第 49–94 行）。
- **SurfaceControl 綁定方法**：完全不存在 `attachSurfaceControl(int surfaceId, SurfaceControl surfaceControl)` 或 `registerSurfaceControl(int surfaceId, SurfaceControl surfaceControl, int width, int height)`。
- **HardwareBuffer 提交方法**：現有 `commitFrame(int surfaceId)`（第 138–155 行）僅有計數器遞增與 16ms 頻率限制（Frame Pacing），完全無法接收 `HardwareBuffer` 或執行 `SurfaceControl.Transaction`。
- **測試編譯驗證**：在現有程式碼基礎上編譯驗證腳本 `/tmp/TestM4Binding.java` 產生 100% 可重現之編譯錯誤：
  ```
  /tmp/TestM4Binding.java:12: error: cannot find symbol
          boolean attachOk = service.attachSurfaceControl(sid, sc);
                                    ^
    symbol:   method attachSurfaceControl(int,SurfaceControl)
  /tmp/TestM4Binding.java:16: error: method commitFrame in class LinuxWindowBridgeService cannot be applied to given types;
          boolean commitOk = service.commitFrame(sid, hb);
                                    ^
    required: int
    found:    int,HardwareBuffer
  ```

---

## 2. Logic Chain (推理邏輯鏈)

1. **基礎觀察**：`LinuxWindowBridgeService` 是 AOSP 中負責 Linux 視窗橋接的核心 Service，維護 `WaylandSurface` 註冊表、Task ID 分配以及 SurfaceControl / HardwareBuffer 的渲染綁定。
2. **缺口分析**：因為缺少 `attachSurfaceControl` 與過載的 `commitFrame(int, HardwareBuffer)`，Linux 視窗（Sommelier Wayland Proxy）傳入的 dma-buf/HardwareBuffer 無法透過 `SurfaceControl.Transaction` 設定到 Android 的 Task 視窗上，導致 R4 需求（Real Wayland dma-buf & SurfaceControl Binding）完全無法運作。
3. **解決方案設計**：
   - **單例模式**：新增 `private static volatile LinuxWindowBridgeService sInstance`，提供 `getInstance()` 與 `setInstance()`，並在建構子自動註冊，供 `LinuxAppProxyActivity` 調用。
   - **SurfaceControl 綁定**：實作 `attachSurfaceControl` 與 `registerSurfaceControl`，將傳入之 `SurfaceControl` 關聯至特定的 `WaylandSurface` 實例，並處理舊有 `SurfaceControl` 之 release。
   - **HardwareBuffer 事務提交**：實作過載 `commitFrame(int surfaceId, HardwareBuffer buffer)`：
     1. 檢查 `surfaceId` 與 `buffer` 是否有效。
     2. 執行 16ms / 60 FPS 幀率調步（Frame Pacing Rate Limiting）。
     3. **記憶體管理**：若 `surface.currentBuffer` 存在且不同於新 buffer，立即調用 `close()` 釋放舊 Buffer，避免圖形記憶體洩漏。
     4. **SurfaceControl 事務**：創建 `SurfaceControl.Transaction`，依序執行 `setBuffer(surfaceControl, buffer)`、`setVisibility(surfaceControl, true)` 與 `apply()`。
   - **生命週期清理**：在 `destroySurface` 與 `flushTasks` 中，確保留存的 `currentBuffer` 被 `close()`，且 `surfaceControl` 在被 release 之前透過 `Transaction.reparent(null)` 與原生圖形樹解綁。
4. **依賴組件補全**：為確保 `LinuxWindowBridgeService.java` 能在 repo 內的樁代碼（`frameworks/base/core/java/android/view/SurfaceControl.java`）順利編譯通過，必須同步擴充 `SurfaceControl.java` 樁定義，加入 `isValid()` 與 `Transaction` 靜態內部類別（包含 `setBuffer`、`setVisibility`、`reparent` 與 `apply`）。

---

## 3. Caveats (注意事項與假設)

- **調查範圍限制**：本報告專注於 `LinuxWindowBridgeService.java` 及其相關編譯依賴。`LinuxAppProxyActivity.java` 與 `wayland_buffer_sharing.cpp` 屬同一 Milestone M4 之其他協同模組，應分別由 Implementer 依照其定義綁定調用 `LinuxWindowBridgeService.getInstance()`。
- **無門面/Dummy 承諾**：本藍圖提供之所有邏輯皆為真實的資源管理與 Transaction 事務處理，絕無 `return true;` 等假實作。
- **唯讀原則**：本探索者未直接修改 `/Users/iml1s/Documents/mine/aosp-linux` 原始碼樹，相關具體藍圖已儲存於作業目錄之參考檔案 `proposed_LinuxWindowBridgeService.java`。

---

## 4. Conclusion (結論與逐行實作藍圖)

### 4.1 具體實作藍圖 (Concrete Line-by-Line Blueprint for `LinuxWindowBridgeService.java`)

Implementer 可依據以下詳細程式碼變更指引修改 `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`：

#### 變更 1：新增單例欄位與存取方法
```java
// 在類別層級新增 sInstance 變數
private static volatile LinuxWindowBridgeService sInstance;

public static LinuxWindowBridgeService getInstance() {
    return sInstance;
}

public static void setInstance(LinuxWindowBridgeService instance) {
    sInstance = instance;
}

// 在建構子設定 sInstance
public LinuxWindowBridgeService(Context context) {
    mContext = context;
    sInstance = this;
}
```

#### 變更 2：新增 `attachSurfaceControl` 與 `registerSurfaceControl`
```java
public synchronized boolean attachSurfaceControl(int surfaceId, SurfaceControl surfaceControl) {
    WaylandSurface surface = mSurfaces.get(surfaceId);
    if (surface == null) {
        Slog.w(TAG, "attachSurfaceControl: Unknown surfaceId " + surfaceId);
        return false;
    }
    if (surface.surfaceControl != null && surface.surfaceControl != surfaceControl) {
        surface.surfaceControl.release();
    }
    surface.surfaceControl = surfaceControl;
    Slog.i(TAG, "Attached SurfaceControl to surfaceId " + surfaceId + ": " + surfaceControl);
    return true;
}

public synchronized boolean registerSurfaceControl(int surfaceId, SurfaceControl surfaceControl, int width, int height) {
    WaylandSurface surface = mSurfaces.get(surfaceId);
    if (surface == null) {
        Slog.w(TAG, "registerSurfaceControl: Unknown surfaceId " + surfaceId);
        return false;
    }
    attachSurfaceControl(surfaceId, surfaceControl);
    configureSurface(surfaceId, width, height);
    Slog.i(TAG, "Registered SurfaceControl for surfaceId " + surfaceId + " with dimensions " + width + "x" + height);
    return true;
}
```

#### 變更 3：實作過載 `commitFrame(int surfaceId, HardwareBuffer buffer)`
```java
public synchronized boolean commitFrame(int surfaceId, HardwareBuffer buffer) {
    WaylandSurface surface = mSurfaces.get(surfaceId);
    if (surface == null) {
        Slog.w(TAG, "commitFrame: Unknown surfaceId " + surfaceId);
        return false;
    }
    if (buffer == null) {
        Slog.w(TAG, "commitFrame: Null HardwareBuffer provided for surfaceId " + surfaceId);
        return false;
    }

    long nowNs = System.nanoTime();
    if (nowNs - surface.lastCommitNs < FRAME_PACING_MIN_INTERVAL_NS) {
        Slog.d(TAG, "commitFrame: Frame dropped due to frame pacing rate limiting on surface " + surfaceId);
        return false;
    }

    // 釋放前一幀 Buffer 避免圖形記憶體洩漏
    if (surface.currentBuffer != null && surface.currentBuffer != buffer) {
        surface.currentBuffer.close();
    }
    surface.currentBuffer = buffer;

    // 執行 SurfaceControl 事務提交
    if (surface.surfaceControl != null && surface.surfaceControl.isValid()) {
        try {
            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            transaction.setBuffer(surface.surfaceControl, buffer);
            transaction.setVisibility(surface.surfaceControl, true);
            transaction.apply();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to apply SurfaceControl transaction for surfaceId " + surfaceId + ": " + e.getMessage());
        }
    } else {
        Slog.w(TAG, "commitFrame: SurfaceControl is null or invalid for surfaceId " + surfaceId);
    }

    surface.lastCommitNs = nowNs;
    surface.committedFrames++;
    Slog.d(TAG, "HardwareBuffer frame committed for surface " + surfaceId + " (total frames: " + surface.committedFrames + ")");
    return true;
}
```

#### 變更 4：強化 `destroySurface` 生命週期資源清理
```java
public synchronized boolean destroySurface(int surfaceId) {
    WaylandSurface surface = mSurfaces.remove(surfaceId);
    if (surface != null) {
        if (surface.appId != null) {
            mAppToTaskIdMap.remove(surface.appId);
        }
        mTaskToSurfaceMap.remove(surface.taskId);
        if (surface.currentBuffer != null) {
            surface.currentBuffer.close();
            surface.currentBuffer = null;
        }
        if (surface.surfaceControl != null) {
            if (surface.surfaceControl.isValid()) {
                try {
                    SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
                    transaction.reparent(surface.surfaceControl, null);
                    transaction.apply();
                } catch (Exception ignored) {}
            }
            surface.surfaceControl.release();
            surface.surfaceControl = null;
        }
        Slog.i(TAG, "Destroyed surface " + surfaceId + " and released task " + surface.taskId);
        return true;
    }
    return false;
}
```

#### 變更 5：更新 `frameworks/base/core/java/android/view/SurfaceControl.java` 樁定義
為使 repo 環境下的 `javac` 順利編譯 `SurfaceControl.Transaction`：
```java
package android.view;

import android.hardware.HardwareBuffer;

public class SurfaceControl {
    public void release() {}
    public boolean isValid() { return true; }

    public static class Transaction {
        public Transaction setBuffer(SurfaceControl sc, HardwareBuffer buffer) { return this; }
        public Transaction setVisibility(SurfaceControl sc, boolean visible) { return this; }
        public Transaction reparent(SurfaceControl sc, SurfaceControl newParent) { return this; }
        public void apply() {}
    }
}
```

---

## 5. Verification Method (獨立驗證方法)

以獨立指令驗證本實作藍圖與編譯完整性：

### 5.1 編譯驗證指令
```bash
# 建立臨時測試構建目錄
mkdir -p /tmp/verify_m4_iter2
mkdir -p /tmp/verify_m4_iter2/android/view

# 準備完整的 SurfaceControl.java 樁定義
cat << 'EOF' > /tmp/verify_m4_iter2/android/view/SurfaceControl.java
package android.view;

import android.hardware.HardwareBuffer;

public class SurfaceControl {
    public void release() {}
    public boolean isValid() { return true; }

    public static class Transaction {
        public Transaction setBuffer(SurfaceControl sc, HardwareBuffer buffer) { return this; }
        public Transaction setVisibility(SurfaceControl sc, boolean visible) { return this; }
        public Transaction reparent(SurfaceControl sc, SurfaceControl newParent) { return this; }
        public void apply() {}
    }
}
EOF

# 複製藍圖檔案並進行編譯
cp /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_iter2_1/proposed_LinuxWindowBridgeService.java /tmp/verify_m4_iter2/LinuxWindowBridgeService.java
javac -classpath /tmp/verify_m4_iter2:frameworks/base/core/java:frameworks/base/services/core/java -d /tmp/verify_m4_iter2 /tmp/verify_m4_iter2/LinuxWindowBridgeService.java
```
*預期結果*：`javac` 成功編譯，結束碼 `0`，無任何錯誤。

### 5.2 執行測試驗證指令
```bash
# 建立完整的單元與綁定測試腳本
cat << 'EOF' > /tmp/verify_m4_iter2/TestM4BindingVerification.java
import com.android.server.linux.LinuxWindowBridgeService;
import android.view.SurfaceControl;
import android.hardware.HardwareBuffer;

public class TestM4BindingVerification {
    public static void main(String[] args) {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        int sid = service.createSurface("test.app", "Test App", null, 800, 600);
        assert sid > 0 : "Surface creation failed";

        // 驗證單例
        assert LinuxWindowBridgeService.getInstance() == service : "Singleton instance match failed";

        // 驗證 attachSurfaceControl
        SurfaceControl sc = new SurfaceControl();
        boolean attachOk = service.attachSurfaceControl(sid, sc);
        assert attachOk : "attachSurfaceControl failed";

        // 驗證 registerSurfaceControl
        boolean registerOk = service.registerSurfaceControl(sid, sc, 1024, 768);
        assert registerOk : "registerSurfaceControl failed";

        // 驗證 commitFrame(int, HardwareBuffer)
        HardwareBuffer hb = new HardwareBuffer();
        boolean commitOk = service.commitFrame(sid, hb);
        assert commitOk : "commitFrame failed";

        // 驗證 destroySurface
        boolean destroyOk = service.destroySurface(sid);
        assert destroyOk : "destroySurface failed";

        System.out.println("[SUCCESS] attachSurfaceControl, registerSurfaceControl & commitFrame verified!");
    }
}
EOF

javac -classpath /tmp/verify_m4_iter2:frameworks/base/core/java:frameworks/base/services/core/java -d /tmp/verify_m4_iter2 /tmp/verify_m4_iter2/TestM4BindingVerification.java
java -ea -cp /tmp/verify_m4_iter2 TestM4BindingVerification
```
*預期結果*：控制台輸出 `[SUCCESS] attachSurfaceControl, registerSurfaceControl & commitFrame verified!` 且結束碼為 `0`。
