# Phase A 審計調查與修復建議報告 (Timeline, Provenance & Miniature Stub Cleanup)

## 1. Observation (觀察事實)

本調查方針針對 `/Users/iml1s/Documents/mine/aosp-linux/.agents/victory_auditor/handoff.md` 與 `ORIGINAL_REQUEST.md` 中指出的 Phase A 審計缺失（Timeline、Provenance 及 Miniature Stub Stand-in 類別清理）進行儲存庫內與工作區之全面比對與統計。

### 1.1 預先填寫之靜態 JSON 測試報告 (Static JSON Reports in Git)
經 `find_by_name` 與 `git ls-files` 查驗，Git 儲存庫中直接提交了以下靜態預填測試報告：
1. `tests/e2e/e2e_report.json`
   - 檔案大小：127,510 bytes，共 4,744 行。
   - 內容摘要（引用 1-11 行）：
     ```json
     {
       "timestamp": "2026-08-06T15:58:51Z",
       "summary": {
         "total": 430,
         "passed": 430,
         "failed": 0,
         "errored": 0,
         "skipped": 0,
         "pass_rate_percent": 100.0,
         "duration_seconds": 12.2619
       }
     ```
   - 違規事實：將硬編碼 100% 通過 (430/430) 的靜態 JSON 檔提交於 Git，違反 `ORIGINAL_REQUEST.md` Rule 4 及 Rule 6。
2. `tests/e2e_report.json`
   - 檔案大小：128,396 bytes，共 4,744 行。
   - 內容摘要（引用 1-11 行）：
     ```json
     {
       "timestamp": "2026-08-08T12:04:12Z",
       "summary": {
         "total": 430,
         "passed": 429,
         "failed": 1,
         "errored": 0,
         "skipped": 0,
         "pass_rate_percent": 99.77,
         "duration_seconds": 39.2654
       }
     ```
   - 違規事實：此報告存在於專案根目錄/tests 下，違反無預置報告原則。

### 1.2 預先編譯之二進位檔與構產物 (Prebuilt Binaries & Target Directories in Git/Workspace)
經 `git ls-files` 與檔案系統掃描，專案中包含大量本應由動態 Clean Build 產生的預編譯產物：
1. **單一預編譯二進位檔 (Committed Object & Archive Files)**:
   - `hmac_auth.o`（Git 追蹤中，檔案大小 139,040 bytes，位於根目錄）
   - `release_dist/aosp-linux-deployment-v1.0.0.tar.gz`（Git 追蹤中，檔案大小 316,438 bytes）
2. **Git 追蹤之 Rust 構建產物目錄 (Tracked Rust Build Targets)**:
   - `guest/bridge-agent/target/`：共 **452 個檔案** 被 `git add` 納入 Git 追蹤（包含 `android-bridge-agent` 二進位檔、`.o` 檔、`.rlib` 檔、deps 與 fingerprint）。
   - `guest/portal-agent/target/`：共 **114 個檔案** 被 Git 追蹤（包含 `portal-agent` 二進位檔、`.json` 檔、incremental 產物）。
3. **Git 追蹤之單元測試/構建二進位可執行檔 (Tracked Test Binaries)**:
   - `system/linux_bridge/tests/linux_bridge_test_bin`
   - `tests/unit/VirtioGpuDmabufTest_bin`
   - `tests/unit/challenger_r2_empirical_bin`
   - `tests/unit/m3_native_challenger2_stress_bin`
   - `tests/unit/m3_native_terminal_test_bin`
   - `unit/challenger_m3_empirical_test`
4. **工作區未編譯清除之構產物目錄 (Untracked Build Directory)**:
   - `build_out/` 目錄：包含 `build_out/bin/*`（13 個編譯可執行檔如 `linux_bridge_test`, `VirtioGpuDmabufTest` 等）與 `build_out/classes/*`（86 個編譯後 `.class` 檔案）。
5. **Git 追蹤之 Mock 映像檔與 Slot 元數據檔 (Scratch Artifacts)**:
   - `scratch/bad_magic_vbmeta.img`
   - `scratch/dummy.img`
   - `scratch/truncated_vbmeta.img`
   - `scratch/test_slot_metadata.json`
   - `scratch/test_slot_metadata_hb.json`

### 1.3 `frameworks/base/` 迷你 Stub 類別與真正 AOSP 模組清冊 (Miniature Stand-in Classes Inspection)
對 `frameworks/base/` 目錄進行全檔案計數與分析（共 97 個檔案）：

1. **迷你 Stub 類別 (Miniature Stand-in Classes to be Removed - 77 個檔案)**:
   專案在 `frameworks/base/core/java/android/` 及 `frameworks/base/services/core/java/com/android/server/` 下建立了 77 個僅有 10~70 行的微型 Stand-in 類別，直接覆蓋了 canonical AOSP 核心類別。部分代表性檔案與行數如下：
   - `frameworks/base/core/java/android/content/Context.java` (65 行，僅包含 8 個常數與 9 個空 Stub 方法)
   - `frameworks/base/services/core/java/com/android/server/SystemServer.java` (66 行，僅包含簡化之 3 個服務啟動方法)
   - `frameworks/base/core/java/android/app/SystemServiceRegistry.java` (61 行)
   - `frameworks/base/core/java/android/app/ActivityManager.java` (50 行)
   - `frameworks/base/core/java/android/app/AppOpsManager.java` (22 行)
   - `frameworks/base/core/java/android/hardware/camera2/CameraManager.java` (21 行)
   - `frameworks/base/core/java/android/location/LocationManager.java` (12 行)
   - `frameworks/base/core/java/android/media/AudioRecord.java` (29 行)
   - `frameworks/base/core/java/android/view/SurfaceControl.java` (16 行)
   - `frameworks/base/core/java/android/app/Activity.java` (37 行)
   - `frameworks/base/services/core/java/com/android/server/SystemService.java` (72 行)
   - `frameworks/base/services/core/java/com/android/server/LocalServices.java` (55 行)
   - `frameworks/base/core/res/AndroidManifest.xml` (51 行，偽造之 AOSP 系統清單替換檔)
   - 其餘 64 個微型 Android SDK / Framework 替代 Stub 完整路徑清冊：
     - `frameworks/base/core/java/android/annotation/IntDef.java`
     - `frameworks/base/core/java/android/annotation/NonNull.java`
     - `frameworks/base/core/java/android/annotation/Nullable.java`
     - `frameworks/base/core/java/android/annotation/RequiresPermission.java`
     - `frameworks/base/core/java/android/annotation/SystemApi.java`
     - `frameworks/base/core/java/android/annotation/SystemService.java`
     - `frameworks/base/core/java/android/content/BroadcastReceiver.java`
     - `frameworks/base/core/java/android/content/ContentProvider.java`
     - `frameworks/base/core/java/android/content/ContentResolver.java`
     - `frameworks/base/core/java/android/content/Intent.java`
     - `frameworks/base/core/java/android/content/IntentFilter.java`
     - `frameworks/base/core/java/android/content/SharedPreferences.java`
     - `frameworks/base/core/java/android/content/res/Configuration.java`
     - `frameworks/base/core/java/android/content/res/Resources.java`
     - `frameworks/base/core/java/android/database/Cursor.java`
     - `frameworks/base/core/java/android/database/MatrixCursor.java`
     - `frameworks/base/core/java/android/graphics/Bitmap.java`
     - `frameworks/base/core/java/android/graphics/BitmapFactory.java`
     - `frameworks/base/core/java/android/graphics/Canvas.java`
     - `frameworks/base/core/java/android/graphics/Color.java`
     - `frameworks/base/core/java/android/graphics/ImageFormat.java`
     - `frameworks/base/core/java/android/graphics/Paint.java`
     - `frameworks/base/core/java/android/graphics/Rect.java`
     - `frameworks/base/core/java/android/graphics/RectF.java`
     - `frameworks/base/core/java/android/graphics/Typeface.java`
     - `frameworks/base/core/java/android/hardware/HardwareBuffer.java`
     - `frameworks/base/core/java/android/hardware/camera2/CameraCaptureSession.java`
     - `frameworks/base/core/java/android/hardware/camera2/CameraDevice.java`
     - `frameworks/base/core/java/android/hardware/camera2/CaptureRequest.java`
     - `frameworks/base/core/java/android/location/Location.java`
     - `frameworks/base/core/java/android/location/LocationListener.java`
     - `frameworks/base/core/java/android/media/AudioAttributes.java`
     - `frameworks/base/core/java/android/media/AudioFocusRequest.java`
     - `frameworks/base/core/java/android/media/AudioFormat.java`
     - `frameworks/base/core/java/android/media/AudioManager.java`
     - `frameworks/base/core/java/android/media/Image.java`
     - `frameworks/base/core/java/android/media/ImageReader.java`
     - `frameworks/base/core/java/android/media/MediaRecorder.java`
     - `frameworks/base/core/java/android/net/LocalSocket.java`
     - `frameworks/base/core/java/android/net/LocalSocketAddress.java`
     - `frameworks/base/core/java/android/net/Uri.java`
     - `frameworks/base/core/java/android/os/Binder.java`
     - `frameworks/base/core/java/android/os/Bundle.java`
     - `frameworks/base/core/java/android/os/CancellationSignal.java`
     - `frameworks/base/core/java/android/os/Handler.java`
     - `frameworks/base/core/java/android/os/HandlerThread.java`
     - `frameworks/base/core/java/android/os/IBinder.java`
     - `frameworks/base/core/java/android/os/IInterface.java`
     - `frameworks/base/core/java/android/os/Looper.java`
     - `frameworks/base/core/java/android/os/Parcel.java`
     - `frameworks/base/core/java/android/os/ParcelFileDescriptor.java`
     - `frameworks/base/core/java/android/os/Parcelable.java`
     - `frameworks/base/core/java/android/os/Process.java`
     - `frameworks/base/core/java/android/os/RemoteCallbackList.java`
     - `frameworks/base/core/java/android/os/RemoteException.java`
     - `frameworks/base/core/java/android/os/ServiceManager.java`
     - `frameworks/base/core/java/android/os/UserHandle.java`
     - `frameworks/base/core/java/android/provider/DocumentsContract.java`
     - `frameworks/base/core/java/android/provider/DocumentsProvider.java`
     - `frameworks/base/core/java/android/text/TextPaint.java`
     - `frameworks/base/core/java/android/util/ArrayMap.java`
     - `frameworks/base/core/java/android/util/AttributeSet.java`
     - `frameworks/base/core/java/android/util/DisplayMetrics.java`
     - `frameworks/base/core/java/android/util/Log.java`
     - `frameworks/base/core/java/android/util/Slog.java`
     - `frameworks/base/core/java/android/view/KeyEvent.java`
     - `frameworks/base/core/java/android/view/MotionEvent.java`
     - `frameworks/base/core/java/android/view/Surface.java`
     - `frameworks/base/core/java/android/view/SurfaceHolder.java`
     - `frameworks/base/core/java/android/view/SurfaceView.java`
     - `frameworks/base/core/java/android/view/View.java`
     - `frameworks/base/core/java/android/view/Window.java`
     - `frameworks/base/core/java/android/view/WindowManager.java`
     - `frameworks/base/core/java/android/view/inputmethod/CursorAnchorInfo.java`
     - `frameworks/base/core/java/android/view/inputmethod/EditorInfo.java`
     - `frameworks/base/core/java/android/view/inputmethod/InputConnection.java`
     - `frameworks/base/core/java/android/view/inputmethod/InputMethodManager.java`
     - `frameworks/base/core/java/android/widget/Button.java`
     - `frameworks/base/core/java/android/widget/LinearLayout.java`
     - `frameworks/base/core/java/org/json/JSONObject.java`

2. **真實 Dual-OS AOSP 模組與服務原始碼 (Genuine Dual-OS Classes to RETAIN - 20 個檔案)**:
   - Client API & AIDL 介面 (`frameworks/base/core/java/android/system/linux/`):
     - `LinuxAppInfo.aidl`
     - `LinuxAppInfo.java`
     - `LinuxManager.java`
     - `ILinuxManager.aidl`
     - `ILinuxManager.java`
     - `ILinuxBridgeDaemon.aidl`
     - `ILinuxBridgeDaemon.java`
     - `ILinuxStatusCallback.aidl`
     - `ILinuxStatusCallback.java`
     - `ILinuxTerminalCallback.aidl`
     - `ILinuxTerminalCallback.java`
   - SystemServer 雙系統核心服務 (`frameworks/base/services/core/java/com/android/server/linux/`):
     - `LinuxManagerService.java`
     - `LinuxBridgeService.java`
     - `LinuxPortalService.java`
     - `LinuxWindowBridgeService.java`
     - `LinuxAudioPolicyHandler.java`
     - `LinuxCeKeyManager.java`
     - `LinuxPermissionActivity.java`
     - `LinuxManagerInternal.java`
     - `storage/LinuxStorageProvider.java`

### 1.4 `Android.bp` 通配符配置問題 (Wildcard Configuration in Android.bp)
檢視根目錄 `Android.bp`（第 3-12 行）：
```bp
java_sdk_library {
    name: "android.system.linux",
    srcs: [
        "frameworks/base/core/java/**/*.java",
        "frameworks/base/core/java/**/*.aidl",
    ],
    api_packages: ["android.system.linux"],
    platform_apis: true,
    installable: true,
}
```
`srcs` 使用了 `"frameworks/base/core/java/**/*.java"` 通配符，導致編譯 `android.system.linux` 時無意間將上述 77 個 Stub 類別通通打包進去。當將此儲存庫整合至真實 AOSP 樹時，這會造成類別重複定義（Class Conflict）及覆蓋 AOSP 原生數萬行核心 API 的嚴重錯誤。

---

## 2. Logic Chain (推論邏輯鏈)

1. **Phase A 預填報告與預編譯產物之違規推論**:
   - `ORIGINAL_REQUEST.md` 規則 4、5、6 明確宣示：嚴禁使用預填 JSON 報告、固定 VTS/CTS 值或預編譯產物作為產出依據。
   - `tests/e2e/e2e_report.json` 與 `tests/e2e_report.json` 為靜態 Commit 檔案，導致 CI 測試無需實際執行即可宣稱 100% PASS。
   - `hmac_auth.o`、`release_dist/*.tar.gz`、`guest/bridge-agent/target/` (452 檔案)、`guest/portal-agent/target/` (114 檔案) 及各類 `*_bin` 測試檔直接納入 Git，破壞了「從乾淨 Checkout 純原始碼編譯」的 Provenance 誠信要求。

2. **`frameworks/base/` 迷你 Stand-in 類別對 AOSP 樹之破壞性推論**:
   - `ORIGINAL_REQUEST.md` 規則 3 明確要求：`Do not replace canonical AOSP Context.java, SystemServer.java, or SystemServiceRegistry.java with miniature stand-in classes.`
   - 專案先前為了在 Host 端用純 javac / python 模擬執行，建立了包含 `Context.java` (65行)、`SystemServer.java` (66行) 等 77 個微型檔案。
   - 在標準 AOSP 構建中，`Context.java` 與 `SystemServer.java` 均為數千行的核心系統檔案。若直接將這些 60 行的迷你 Stub 檔案合入 AOSP 原始碼樹，會直接刪除 Android 99% 的核心 API，導致整個 AOSP OS 無法編譯與開機。

3. **正確 AOSP 原始碼樹整合架構推論 (Genuine AOSP Tree Architecture)**:
   - **System API 庫 (`android.system.linux`)**: 應僅包含新增的 API 與 AIDL (`frameworks/base/core/java/android/system/linux/**`)。在 Soong `Android.bp` 中，`srcs` 必須精準指定該目錄，不得包含通用 android/ 命名空間。
   - **SystemServer 服務庫 (`services.linux`)**: 應僅包含 `frameworks/base/services/core/java/com/android/server/linux/**`。
   - **Canonical AOSP 核心類別修改方式**:
     對原生 `Context.java`（新增 `LINUX_SERVICE = "linux"`）、`SystemServiceRegistry.java`（註冊 `LinuxManager`）及 `SystemServer.java`（啟動 `LinuxManagerService`）的修改，**必須以 git patch 補丁形式（例如 `patches/aosp_frameworks_base.patch`）保存並套用於真實 AOSP 樹中**，絕不能在 Git 庫中聲明微型 stand-in Stub 類別。

---

## 3. Caveats (注意事項與未檢驗範圍)

- **調查唯讀原則**: 本報告僅進行唯讀調查與修復路徑規劃，未對 `.agents/explorer_remediation_1/` 以外之原始碼進行直接修改。
- **硬體與 AOSP 樹依賴性**: Phase 1 的真實 AOSP Clean-build 驗證與 KVM/AVF 啟動，需在具備完整 AOSP 原始碼樹與 ARM64 虛擬化/實體硬體環境下進行。在缺乏此環境時，構建與硬體測試 Gate 應如實回傳 `BLOCKED`，不得使用假擬比對或 TEST_MODE 偽裝 PASS。

---

## 4. Conclusion (最終結論)

1. **徹底刪除所有靜態報告與預編譯產物**:
   - 必須自 Git 追蹤中移除 `tests/e2e/e2e_report.json`、`tests/e2e_report.json`。
   - 必須自 Git 追蹤中移除 `hmac_auth.o`、`release_dist/*.tar.gz`、`guest/bridge-agent/target/` (452 檔案)、`guest/portal-agent/target/` (114 檔案)、所有 `*_bin` 測試檔及 `scratch/*.img` / `scratch/*.json`。
   - 必須清除工作區中未追蹤的 `build_out/` 目錄。
2. **徹底清理 77 個迷你 Stand-in 類別**:
   - 自 `frameworks/base/` 中 `git rm -f` 刪除所有 77 個模擬 Android SDK / SystemServer 的微型類別與清單。
   - 僅保留 20 個真實的 dual-OS 系統服務與 API 檔案（`android.system.linux.*` 與 `com.android.server.linux.*`）。
3. **規範化 AOSP 構建與 Patch 結構**:
   - 修訂 `Android.bp`，將 `srcs` 範圍嚴格限定於 `android.system.linux` 與 `com.android.server.linux`。
   - 建立 `patches/aosp_frameworks_base.patch`，以 Patch 檔案形式記錄對 AOSP 原生 `Context.java`、`SystemServiceRegistry.java` 與 `SystemServer.java` 的修補邏輯。

---

## 5. Step-by-Step Remediation Recommendations for Worker (Worker 逐步修復建議)

Worker 應依序執行以下 6 個步驟完成 Phase A 的修復：

### 步驟 1：Git 追蹤清理 — 刪除靜態測試報告與預編譯產物
在專案根目錄執行：
```bash
# 1. 刪除靜態 JSON 報告
git rm -f tests/e2e/e2e_report.json tests/e2e_report.json

# 2. 刪除根目錄與發行二進位檔
git rm -f hmac_auth.o release_dist/aosp-linux-deployment-v1.0.0.tar.gz

# 3. 刪除 Git 追蹤之 Rust target 構產物
git rm -rf guest/bridge-agent/target/
git rm -rf guest/portal-agent/target/

# 4. 刪除 Git 追蹤之測試二進位檔與 Scratch Mock 檔
git rm -f system/linux_bridge/tests/linux_bridge_test_bin
git rm -f tests/unit/VirtioGpuDmabufTest_bin
git rm -f tests/unit/challenger_r2_empirical_bin
git rm -f tests/unit/m3_native_challenger2_stress_bin
git rm -f tests/unit/m3_native_terminal_test_bin
git rm -f unit/challenger_m3_empirical_test
git rm -f scratch/bad_magic_vbmeta.img scratch/dummy.img scratch/truncated_vbmeta.img scratch/test_slot_metadata.json scratch/test_slot_metadata_hb.json
```

### 步驟 2：Git 追蹤清理 — 刪除 77 個 frameworks/base/ 迷你 Stand-in 類別
執行以下指令刪除所有非 `android.system.linux` 與非 `com.android.server.linux` 的假 Stub 檔案：
```bash
# 刪除 core/java/android/ 下除了 system/linux/ 以外的所有假 Stand-in 類別
git rm -rf frameworks/base/core/java/android/annotation/
git rm -rf frameworks/base/core/java/android/app/
git rm -rf frameworks/base/core/java/android/content/
git rm -rf frameworks/base/core/java/android/database/
git rm -rf frameworks/base/core/java/android/graphics/
git rm -rf frameworks/base/core/java/android/hardware/
git rm -rf frameworks/base/core/java/android/location/
git rm -rf frameworks/base/core/java/android/media/
git rm -rf frameworks/base/core/java/android/net/
git rm -rf frameworks/base/core/java/android/os/
git rm -rf frameworks/base/core/java/android/provider/
git rm -rf frameworks/base/core/java/android/text/
git rm -rf frameworks/base/core/java/android/util/
git rm -rf frameworks/base/core/java/android/view/
git rm -rf frameworks/base/core/java/android/widget/
git rm -rf frameworks/base/core/java/org/

# 刪除 services/core/java/com/android/server/ 下除了 linux/ 以外的假 Stub 檔案
git rm -f frameworks/base/services/core/java/com/android/server/LocalServices.java
git rm -f frameworks/base/services/core/java/com/android/server/SystemService.java
git rm -f frameworks/base/services/core/java/com/android/server/SystemServer.java

# 刪除假 Manifest
git rm -f frameworks/base/core/res/AndroidManifest.xml
```

### 步驟 3：清理工作區目錄與更新 `.gitignore`
清理工作區中的 `build_out/` 及殘留臨時檔，並確保 `.gitignore` 完整防護：
```bash
rm -rf build_out/
```
確認 `.gitignore` 包含以下規則：
```gitignore
build_out/
target/
guest/bridge-agent/target/
guest/portal-agent/target/
*.o
*.so
*.a
*.class
*.dex
*.apk
*.tar.gz
tests/e2e/e2e_report.json
tests/e2e_report.json
```

### 步驟 4：重構 `Android.bp` 宣告
修改根目錄 `Android.bp`，將 `srcs` 精準限縮於真實模組，避免通配符引入無關檔案：
```bp
// Android.bp for AOSP Dual-OS Framework Modules

java_sdk_library {
    name: "android.system.linux",
    srcs: [
        "frameworks/base/core/java/android/system/linux/**/*.java",
        "frameworks/base/core/java/android/system/linux/**/*.aidl",
    ],
    api_packages: ["android.system.linux"],
    platform_apis: true,
    installable: true,
}

java_library {
    name: "framework-linux",
    static_libs: [
        "android.system.linux",
    ],
}

java_library {
    name: "services.linux",
    srcs: [
        "frameworks/base/services/core/java/com/android/server/linux/**/*.java",
    ],
    libs: [
        "services.core",
        "android.system.linux",
    ],
}

java_library {
    name: "service-linux",
    static_libs: [
        "services.linux",
    ],
}
```

### 步驟 5：建立標準 AOSP 整合補丁 (`patches/aosp_frameworks_base.patch`)
建立目錄 `patches/`，並撰寫標準 Git Diff 補丁檔案，記錄在 AOSP 樹中合入此功能時對 canonical AOSP 核心檔案的修補點：

1. **`Context.java` 補丁**:
   ```java
   // Add LINUX_SERVICE string constant to android.content.Context
   @SystemApi
   public static final String LINUX_SERVICE = "linux";
   ```
2. **`SystemServiceRegistry.java` 補丁**:
   ```java
   // Register LinuxManager service fetcher
   registerService(Context.LINUX_SERVICE, LinuxManager.class,
           new CachedServiceFetcher<LinuxManager>() {
               @Override
               public LinuxManager createService(SystemServiceRegistryImpl ctx) {
                   IBinder b = ServiceManager.getService(Context.LINUX_SERVICE);
                   ILinuxManager service = ILinuxManager.Stub.asInterface(b);
                   return new LinuxManager(ctx.getOuterContext(), service);
               }});
   ```
3. **`SystemServer.java` 補丁**:
   ```java
   // In SystemServer.startOtherServices():
   t.traceBegin("StartLinuxManagerService");
   mSystemServiceManager.startService(LinuxManagerService.class);
   t.traceEnd();
   ```
4. **`AndroidManifest.xml` 補丁**:
   ```xml
   <!-- Declare MANAGE_LINUX_ENVIRONMENT & USE_LINUX_TERMINAL permissions -->
   <permission android:name="android.permission.MANAGE_LINUX_ENVIRONMENT"
       android:protectionLevel="signature|privileged" />
   <permission android:name="android.permission.USE_LINUX_TERMINAL"
       android:protectionLevel="signature|privileged|normal" />
   ```

### 步驟 6：動態測試報告輸出與測試架構調整
重構 `tests/e2e/runner.py` 與 `tests/e2e/framework/report_formatter.py`：
- 測試執行時，報告應動態寫入被 `.gitignore` 忽視的臨時目錄（例如 `out/reports/` 或 `/tmp/`）。
- 禁止將任何產生的 `.json` 測試結果 commit 進入 Git。

---

## 6. Verification Method (獨立驗證方法)

完成修復後，可透過以下命令進行獨立驗證：

1. **驗證預編譯檔與靜態報告已自 Git 清除**:
   ```bash
   git ls-files | grep -E '(e2e_report\.json|hmac_auth\.o|\.tar\.gz|_bin$|guest/bridge-agent/target|guest/portal-agent/target)'
   ```
   *預期結果*: 輸出為空（0 行）。

2. **驗證 `frameworks/base/` 僅保留 20 個真實 Dual-OS 檔案**:
   ```bash
   find frameworks/base -type f | wc -l
   ```
   *預期結果*: 行數精準為 **20**。

3. **驗證不可包含 canonical AOSP 迷你 Stand-in 類別**:
   ```bash
   find frameworks/base -name "Context.java" -o -name "SystemServer.java" -o -name "SystemServiceRegistry.java" -o -name "ActivityManager.java"
   ```
   *預期結果*: 輸出為空。

4. **驗證 `Android.bp` 不包含 `core/java/**/*.java` 通配符**:
   ```bash
   grep "core/java/\*\*/\*\.java" Android.bp
   ```
   *預期結果*: 輸出為空。
