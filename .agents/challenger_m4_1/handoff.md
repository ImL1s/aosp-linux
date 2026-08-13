# Handoff Report — Milestone 4 (R4): Permission Decision Component Stress-Test

## 1. Observation (觀察)

- **審查目標**：
  1. `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java`
  2. `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- **測試撰寫與執行**：
  撰寫實證測試套件 `tests/unit/LinuxPermissionActivityTest.java` 以及測試輔助樁 `tests/unit/stubs/android/os/Binder.java` 與 `tests/unit/stubs/android/util/Log.java`。
- **編譯與執行指令**：
  ```bash
  javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java -d /tmp/m4_test tests/unit/stubs/android/os/Binder.java tests/unit/stubs/android/util/Log.java
  javac -classpath /tmp/m4_test:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java -sourcepath packages/apps/Launcher3/src:packages/apps/LinuxTerminal/src:frameworks/base/core/java:frameworks/base/services/core/java:tests/unit -d /tmp/m4_test frameworks/base/services/core/java/com/android/server/linux/*.java tests/unit/LinuxPermissionActivityTest.java
  java -cp /tmp/m4_test:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.LinuxPermissionActivityTest
  ```
- **實證測試結果**：
  ```
  ==================================================
     CHALLENGER 1 EMPIRICAL M4 VERIFICATION SUITE   
  ==================================================
  [EMPIRICAL M4 TEST PASS] testOpIntToStringMapping
  [EMPIRICAL M4 TEST PASS] testOpStringToCodeMapping
  [EMPIRICAL M4 TEST PASS] testPortalServiceAppOpsUpdating
  [EMPIRICAL M4 TEST PASS] testEdgeCasesNegativeAndCustomOps
  [EMPIRICAL M4 TEST PASS] testHardwarePortalPermissionGate
  [EMPIRICAL M4 TEST PASS] testRapidConcurrentAppOpsUpdates
  --------------------------------------------------
  EMPIRICAL M4 TEST SUMMARY: 6 PASS, 0 FAIL
  ==================================================
  ```

## 2. Logic Chain (邏輯鏈)

1. **`LinuxPermissionActivity` Extra 解析與轉換邏輯**：
   - 測試 `mapOpIntToString`：代碼 `26` -> `"OP_CAMERA"`、`27` -> `"OP_RECORD_AUDIO"`、`1` -> `"OP_FINE_LOCATION"`、`0` -> `"OP_COARSE_LOCATION"`；針對負數（如 `-1`、`-99`）或未定義 Op（如 `999`），皆回傳預設 `"OP_" + op`（如 `"OP_-1"`），避免索引溢位或例外。
   - 測試 `mapOpStringToCode`：字串 `"OP_CAMERA"`、`"android:camera"`、`"26"` 皆可正確解析為整數 `26`；非數字或無效字串（如 `"INVALID_OP_CODE"`、`null`）回傳 `-1`；負數字串如 `"-5"` 透過 `Integer.parseInt` 解析為 `-5`。
2. **邊界條件與缺漏欄位處理**：
   - 當 Intent 缺漏 `app_id` 或為空字串 `""` 時，`onCreate` 的檢查條件 `appId == null || appId.isEmpty() || (opStr == null && opInt == -1)` 判定成立，紀錄 Slog 警告並調用 `finish()` 結束 Activity，不觸發 UI 與 AppOps 寫入，保證強健性。
   - 當 `op` 為負數或自訂字串時，`LinuxPortalService` 正常存入 `mAppOpsStore`，不會發生崩潰。
3. **`LinuxPortalService` AppOps 更新與並發安全**：
   - `LinuxPortalService` 之 `mAppOpsStore` 採用 `ConcurrentHashMap<String, Map<String, String>>`，支援多線程安全更新。
   - 在高並發測試 `testRapidConcurrentAppOpsUpdates` 中，開啟 20 個線程並行執行 10,000 次 `setAppOp` 與 `checkAppOp` 寫入讀取操作，無任何死鎖、race condition 或狀態不一致現象。
4. **硬體 Portal 閘門連動**：
   - `requestCameraAccess` 與 `requestMicrophoneAccess` 正確連動 `AppOpsManager.MODE_ALLOWED` / `MODE_ERRORED` 狀態，當權限改為 `MODE_DENIED` 時拒絕訪問，權限開啟時允許訪問。

## 3. Caveats (注意事項)

- 在桌面 JVM 環境下運行 `Activity` 與 `Binder` 單元測試時，需透過輕量樁（`Binder.java` 與 `Log.java`）避免 `android.jar` 拋出 `RuntimeException("Stub!")`。此乃 JVM 單元測試環境常態，不影響 AOSP 真機運作。

## 4. Conclusion & Verdict (結論與判定)

- **最終判定**: **APPROVE**
- Worker 在 `LinuxPermissionActivity.java` 與 `LinuxPortalService.java` 中的實作完全符合 Milestone 4 (R4 Functional Permission Decision Component) 的要求，語法編譯無誤，Extra 解析與 AppOps 狀態存取極具強健性，並通過 6/6 項壓力與邊界測試。

## 5. Verification Method (驗證方法)

執行下列命令驗證實證測試與 Java 編譯：
```bash
cd /Users/iml1s/Documents/mine/aosp-linux
mkdir -p /tmp/m4_test
javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java -d /tmp/m4_test tests/unit/stubs/android/os/Binder.java tests/unit/stubs/android/util/Log.java
javac -classpath /tmp/m4_test:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:frameworks/base/services/core/java -sourcepath packages/apps/Launcher3/src:packages/apps/LinuxTerminal/src:frameworks/base/core/java:frameworks/base/services/core/java:tests/unit -d /tmp/m4_test frameworks/base/services/core/java/com/android/server/linux/*.java tests/unit/LinuxPermissionActivityTest.java
java -cp /tmp/m4_test:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.LinuxPermissionActivityTest
```
確認輸出為 `EMPIRICAL M4 TEST SUMMARY: 6 PASS, 0 FAIL` 且返回碼為 `0`。
