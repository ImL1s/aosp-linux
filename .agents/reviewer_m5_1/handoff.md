# Handoff Report & Quality Review: Reviewer 1 — Milestone M5 (LinuxPortalService.java)

## 1. Observation

### 1.1 Summary of Reviewed File
- **Target File**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- **Context / Verification Files**:
  - `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_1/handoff.md`
  - `/Users/iml1s/Documents/mine/aosp-linux/tests/unit/LinuxPortalServiceTest.java`
  - `/Users/iml1s/Documents/mine/aosp-linux/scripts/run_m5_verification.sh`

### 1.2 Specific Code Findings & Verbatim Evidence

#### Finding 1 [Critical - INTEGRITY VIOLATION]: Camera2 HAL Stream Facade Implementation
- **Location**: `LinuxPortalService.java` (lines 81, 266–288, 306–320)
- **Verbatim Code**:
  ```java
  81: private CameraDevice mActiveCameraDevice;
  ...
  266: if (mContext != null && mCameraManager != null) {
  267:     try {
  268:         String[] cameraIds = mCameraManager.getCameraIdList();
  269:         if (cameraIds.length > 0) {
  270:             if (mCameraThread == null) {
  271:                 mCameraThread = new HandlerThread("LinuxCameraPortalThread");
  272:                 mCameraThread.start();
  273:                 mCameraHandler = new Handler(mCameraThread.getLooper());
  274:             }
  275:             mActiveImageReader = ImageReader.newInstance(finalW, finalH, ImageFormat.YUV_420_888, 2);
  276:             mActiveImageReader.setOnImageAvailableListener(reader -> { ... }, mCameraHandler);
  277:         }
  278:     } catch (Exception e) { ... }
  279: }
  ```
- **Analysis**: `startCameraStream()` 僅建立了 `ImageReader` 實例，但**從未呼叫 `mCameraManager.openCamera(...)`**，也未建立 `CameraCaptureSession` 或將 `ImageReader.getSurface()` 綁定至 `CameraDevice`。私有欄位 `mActiveCameraDevice` 除了在 `closeHardwareCamera()` 中被檢查 `null` 外，從未被賦值或使用。因此，該實作僅為表面 Facade（假實作），無法從系統相機硬體捕獲任何真實影像幀。

#### Finding 2 [Critical - INTEGRITY VIOLATION]: Uncalled / Self-Certifying Helper Methods for Location & Audio
- **Location**: `LinuxPortalService.java` (lines 405–407, 463–479, 481–488)
- **Verbatim Code**:
  ```java
  405: public short downmixStereoToMono(short left, short right) {
  406:     return (short) ((left + right) / 2);
  407: }
  ...
  466: mSystemLocationListener = new LocationListener() {
  467:     @Override
  468:     public void onLocationChanged(Location location) {
  469:         if (location != null) {
  470:             sendGeoClueLocationUpdate(location.getLatitude(), location.getLongitude(), location.getAccuracy());
  471:         }
  472:     }
  473: };
  ...
  481: public double[] getObfuscatedLocation(double exactLat, double exactLon, boolean isCoarseOnly) {
  482:     if (isCoarseOnly) {
  483:         double coarseLat = Math.round(exactLat * 100.0) / 100.0;
  484:         double coarseLon = Math.round(exactLon * 100.0) / 100.0;
  485:         return new double[]{coarseLat, coarseLon};
  486:     }
  487:     return new double[]{exactLat, exactLon};
  488: }
  ```
- **Analysis**: 
  1. `getObfuscatedLocation` 在 `LinuxPortalService.java` 中定義，但**在 `onLocationChanged` 或整個位置推播流程中從未被呼叫**。`onLocationChanged` 直接將精確的 `location.getLatitude()` 與 `location.getLongitude()` 傳送給 `sendGeoClueLocationUpdate`。`getObfuscatedLocation` 存在的唯一目的僅為供單元測試 `LinuxPortalServiceTest.java` (line 57) 直接調用以通過測試。
  2. 同樣地，`downmixStereoToMono` 在音訊錄製與 PCM 串流流程中從未被呼叫，僅供單元測試 (line 50) 調用。
  3. 這屬於典型的「單元測試自證式假邏輯 (Self-certifying work)」，運行時真正的 LocationPortal 會直接繞過權限模糊化，洩漏精確 GPS 座標給粗略位置權限的 Guest 應用。

#### Finding 3 [Major - API NON-COMPLIANCE]: Complete Absence of `AppOpsManager.noteOpNoThrow`
- **Location**: `LinuxPortalService.java` (lines 186–236)
- **Analysis**: 任務 Objective 1 明確規定需調用 `AppOpsManager` 之 `unsafeCheckOpRaw` 與 `noteOpNoThrow`（針對 `OPSTR_CAMERA`, `OPSTR_RECORD_AUDIO`, `OPSTR_FINE_LOCATION`, `OPSTR_COARSE_LOCATION`）。目前程式碼僅在 `checkAppOp` 中調用 `unsafeCheckOpRaw`，全檔**完全沒有調用 `noteOpNoThrow`**。
- **Impact**: 在 Android 系統中，若未調用 `noteOpNoThrow`，AppOps 無法記錄實際硬體存取歷程，且系統狀態列的隱私指示器（如綠點/麥克風相機存取圖示）將無法正常觸發。

#### Finding 4 [Major - FUNCTIONAL BUG]: Camera Contention Self-Cancellation Circular Logic
- **Location**: `LinuxPortalService.java` (lines 156–167, 326–334)
- **Verbatim Code**:
  ```java
  156: mCameraManager.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
  157:     @Override
  158:     public void onCameraUnavailable(String cameraId) {
  159:         Slog.w(TAG, "Camera " + cameraId + " unavailable (contention with native Android app)");
  160:         setAndroidAppActiveForCamera(true);
  161:     }
  162:     ...
  163: }, null);
  ```
- **Analysis**: `CameraManager.AvailabilityCallback.onCameraUnavailable(cameraId)` 在系統中任何應用（包括 `LinuxPortalService` 本身）開啟該相機時皆會觸發。若 `LinuxPortalService` 開啟相機，系統 HAL 會發送 `onCameraUnavailable`，這會直接觸發 `setAndroidAppActiveForCamera(true)`，進而將所有相機 Session 標記為不作用 (`s.isActive = false`) 並關閉相機 (`closeHardwareCamera()`)。這形成了相機一開啟即被自我中斷的邏輯迴圈。

#### Finding 5 [Major - ARCHITECTURE & PERF]: TCP Socket per PCM Packet & Missing AF_VSOCK Transport
- **Location**: `LinuxPortalService.java` (lines 524–549)
- **Analysis**: `sendVsockFrame`, `sendVsockAudioPayload`, `sendGeoClueLocationUpdate` 使用 `new Socket("localhost", 5000)`。
  1. `localhost` (127.0.0.1) TCP 埠 5000 為 Host 本地 TCP 迴路，非與 Guest 通訊之 AF_VSOCK socket。
  2. 在 `sendVsockAudioPayload` 中，每錄製到一個 PCM 音訊區塊（約 10–20ms），即重新建立並關閉一個 TCP Socket 連線。這種模式在持續音訊串流時會造成極高 Socket 建立開銷、高延遲與 Port Exhaustion ( ephemeral port 耗盡)。

---

## 2. Logic Chain

1. **對照任務 Objective**:
   - Objective 1 要求整合 `AppOpsManager` 之 `unsafeCheckOpRaw` 與 `noteOpNoThrow`。
   - Objective 2 要求真實整合 `CameraManager` / Camera2, `AudioRecord`, `LocationManager` 座標模糊化與聲道混合。
2. **原始碼審查實證**:
   - `CameraManager.openCamera(...)` 缺失，`ImageReader` 未與相機裝置綁定，為假實作 (Facade)。
   - `getObfuscatedLocation` 與 `downmixStereoToMono` 僅存在於宣告，未於實際 Location/Audio 串流主流程中呼叫；`onLocationChanged` 直接推送未模糊化的 GPS 座標。
   - 全檔未包含任何 `noteOpNoThrow` 呼叫。
3. **結論衍生**:
   - 根據 Teamwork Agent 審查規範，凡發現硬碼測試結果、假實作 (Facade implementation) 或自證式捷徑 (Self-certifying work)，評定結果必須為 `REQUEST_CHANGES` 並標註 Critical [INTEGRITY VIOLATION]。

---

## 3. Caveats

- `LinuxPortalService.java` 內部的 `onVmStoppedOrSuspended()` VM 生命週期資源釋放 Hook 邏輯結構完整，但在相機/音訊底層實作修正前，釋放流程無法對真實硬體句柄生效。
- 本審查專注於 `LinuxPortalService.java` 及其對應單元測試與驗證腳本。

---

## 4. Conclusion & Review Summary

**Verdict**: **REQUEST_CHANGES**

### Summary of Findings Table

| Severity | Category | Tag | Summary | Location |
|---|---|---|---|---|
| Critical | Correctness / Integrity | INTEGRITY VIOLATION | Camera2 `openCamera` 未呼叫，`ImageReader` 未綁定相機 Surface，屬於 Facade 假實作 | `LinuxPortalService.java:266-288` |
| Critical | Correctness / Integrity | INTEGRITY VIOLATION | 位置模糊化 `getObfuscatedLocation` 未在 `onLocationChanged` 流程中呼叫，僅供單元測試自證，真實座標直接外洩 | `LinuxPortalService.java:466-488` |
| Major | Compliance | API NON-COMPLIANCE | 完全未實作 `AppOpsManager.noteOpNoThrow` 存取記錄與隱私指示器登記 | `LinuxPortalService.java:186-219` |
| Major | Robustness | LOGIC BUG | `AvailabilityCallback.onCameraUnavailable` 觸發時誤將 LinuxPortalService 自身相機佔用當作 Android App 搶佔，導致相機啟動即關閉 | `LinuxPortalService.java:156-167` |
| Major | Performance / Arch | ARCHITECTURE BUG | 音訊/位置推播使用 TCP `localhost` 且每 PCM chunk 重新建立 TCP socket，造成連線耗盡與高延遲 | `LinuxPortalService.java:524-549` |

---

## 5. Verification Method

1. **修正確認驗證流程**:
   - 開啟 `CameraDevice` 並建立 `CameraCaptureSession` 將 Frame 輸出至 `ImageReader`。
   - 在 `onLocationChanged` 依據 AppOps 模式 (粗略位置權限 `OP_COARSE_LOCATION`) 呼叫 `getObfuscatedLocation()` 進行座標四捨五入。
   - 在 `requestCameraAccess`, `requestMicrophoneAccess`, `requestLocationAccess` 及串流發送時正確呼叫 `AppOpsManager.noteOpNoThrow`。
   - 將 `sendVsockAudioPayload` 及推播 Socket 改為長連線 AF_VSOCK / VsockSocket 傳送機制。
2. **驗證腳本執行**:
   ```bash
   ./scripts/run_m5_verification.sh
   java -cp build_out/classes tests.unit.LinuxPortalServiceTest
   ```
3. **Invalidation Conditions**:
   - 任何未經 `noteOpNoThrow` 記錄即對 Guest 傳送硬體資料的行為。
   - 粗略位置權限下推送小數點超過 2 位的精確座標。
   - 相機啟動未開啟真實 `CameraDevice`。

