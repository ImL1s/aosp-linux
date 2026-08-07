# Handoff Report: Challenger 1 — Milestone M5 (Empirical Stress Verifier for Hardware Portals, Audio & Virtiofs)

**Agent**: Challenger 1 (`challenger_m5_1`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m5_1`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Focus**: Milestone M5 (Features F-R5-001 through F-R5-008)  
**Date**: 2026-08-06  
**Verdict**: **REJECT (駁回)**

---

## 1. Observation (觀察事實)

經編寫並執行實證壓力測試 Harness `tests/unit/ChallengerM5EmpiricalStressTest.java` 以及 C++ Virtiofs 測試 harness `tests/unit/virtiofs_stress_test.cpp`，觀察到以下明確結果與缺陷：

1. **AppOps `MODE_PROMPT` 越權漏洞 (`LinuxPortalService.java:121-127, 130-145, 189-196`)**:
   - `requestCameraAccess` 與 `requestMicrophoneAccess` 僅判斷 `if (MODE_DENIED.equals(mode)) return false;`。
   - 當 `checkAppOp` 回傳預設 `MODE_PROMPT` 時，方法直接回傳 `true` 放行串流。
   - 實證測試輸出：
     `[STRESS TEST 1] Verifying AppOps MODE_PROMPT handling in LinuxPortalService...`
     `Initial AppOp mode for org.untrusted.app: PROMPT`
     `[BUG CONFIRMED] Security Flaw: Ungranted app in MODE_PROMPT was automatically allowed access!`

2. **`LinuxPermissionActivity` 併發彈窗佇列丟失與競態 (`LinuxPermissionActivity.java:38, 60-96`)**:
   - `sPendingPromptsQueue` (靜態 `ArrayList`) 與 `sIsDialogVisible` 的存取僅有實例層級的 `synchronized` 保護。
   - 當彈窗已顯示時 (`sIsDialogVisible == true`)，`showPrompt` 直接丟棄併發請求。
   - 實證測試輸出 (50 執行緒併發)：
     `W/LinuxPermissionActivity: Duplicate prompt suppressed while dialog is visible for app_0 ...`
     `Pending queue size after 50 concurrent locked prompts: 0`
     `[BUG CONFIRMED] Concurrency Flaw: Non-thread-safe queue resulted in dropped prompts (Expected 50, got 0)!`

3. **`LinuxStorageProvider` 系統根目錄穿越漏洞 (`LinuxStorageProvider.java:62, 151-154`)**:
   - `queryChildDocuments` 僅檢查 `SYSTEM_ROOTS.contains("/" + parentDocumentId)`，未能防範子目錄與相對路徑。
   - 實證測試輸出：
     `[TRAVERSAL BYPASS] Path allowed without SecurityException: etc/passwd`
     `[TRAVERSAL BYPASS] Path allowed without SecurityException: /etc/shadow`
     `[TRAVERSAL BYPASS] Path allowed without SecurityException: sys/kernel`
     `[TRAVERSAL BYPASS] Path allowed without SecurityException: /dev/mem`
     `[TRAVERSAL BYPASS] Path allowed without SecurityException: /home/user/../../etc/shadow`
     `[TRAVERSAL BYPASS] Path allowed without SecurityException: ../proc/kallsyms`

4. **`LinuxAudioPolicyHandler` 堆疊中斷下 AudioFocus 音量鴨音覆蓋 (`LinuxAudioPolicyHandler.java:100-128`)**:
   - 通話鴨音 (`LOSS_TRANSIENT_CAN_DUCK`, 0.2f) 期間發生鬧鐘中斷 (`LOSS_TRANSIENT`)，鬧鐘結束收到 `GAIN` 後，音量直接重置為 `1.0f` 全音量，忽略了通話仍在進行。
   - 實證測試輸出：
     `After Phone Call Duck: State=LOSS_TRANSIENT_CAN_DUCK, Volume=0.2, Paused=false`
     `After Alarm Interrupt: State=LOSS_TRANSIENT, Volume=0.2, Paused=true`
     `After Alarm Ends (Phone Call still active): State=GAIN, Volume=1.0, Paused=false`
     `[BUG CONFIRMED] Audio Policy Flaw: Volume restored to 1.0f (Full Volume) while phone call ducking scenario was still active!`

5. **通過之實證驗證項目**:
   - `virtiofs` 多進程 `flock` 鎖爭奪驗證通過 (1 lock acquired, 9 busy).
   - `virtiofs` 5GB 大檔案 offset 寫入與 stat 驗證通過.
   - Host Native 相機應用搶佔時，Guest 相機 Session 被正確停用 (isActive = false).

---

## 2. Logic Chain (推導邏輯鏈)

1. **前提 1**: 根據系統規範，所有 Guest 硬體請求必須經由 AppOps 授權；當 AppOps 為 `MODE_PROMPT` 時，絕不可直接提供硬體串流。
2. **前提 2**: 併發彈窗請求必須安全排隊，不可因視窗開啟中而靜默丟棄請求。
3. **前提 3**: SAF `LinuxStorageProvider` 必須嚴格隔離 Android 端對 Linux 系統根目錄 (/sys, /proc, /etc, /dev) 的存取，不得允許子路徑或穿越。
4. **前提 4**: AudioFocus 必須正確處理多源搶佔與恢復，通話中（鴨音狀態）時鬧鐘結束不得恢復成全音量。
5. **推論**: 由於實證測試在 `LinuxPortalService`, `LinuxPermissionActivity`, `LinuxStorageProvider`, `LinuxAudioPolicyHandler` 中明確重現了越權放行、請求丟棄、目錄穿越與音量暴增 4 項漏洞，顯示 Worker 1 之實現未達生產級與資安防禦標準。
6. **結論**: Milestone M5 (F-R5-001 ~ F-R5-008) 判定為 **REJECT (駁回)**，必須退回修復。

---

## 3. Caveats (限制與保留事項)

- 本次驗證專注於 F-R5-001 至 F-R5-008 (Hardware Portals, AppOps, Audio, Virtiofs & SAF Provider)。
- F-R5-009 至 F-R5-014 (SELinux Policy 與 Guest A/B OTA Watchdog) 由 Challenger 2 負責測試，本報告不涵蓋其結論。

---

## 4. Conclusion (判定結論)

**VERDICT: REJECT (駁回)**  
Milestone M5 (F-R5-001 ~ F-R5-008) 存在 1 項 CRITICAL 與 3 項 HIGH 級別的實證漏洞，無法予以核可 (APPROVE)。請 Worker 1 參照 `analysis.md` 與本報告之實證證據進行修復。

---

## 5. Verification Method (獨立驗證方法)

若要獨立重現本報告之實證測試結果：

1. **編譯並執行 Java 實證壓力測試 Harness**:
   ```bash
   mkdir -p /Users/iml1s/Documents/mine/aosp-linux/build_out/classes
   javac -d /Users/iml1s/Documents/mine/aosp-linux/build_out/classes \
     $(find /Users/iml1s/Documents/mine/aosp-linux/frameworks/base/core/java /Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java -name "*.java") \
     /Users/iml1s/Documents/mine/aosp-linux/tests/unit/ChallengerM5EmpiricalStressTest.java

   java -cp /Users/iml1s/Documents/mine/aosp-linux/build_out/classes tests.unit.ChallengerM5EmpiricalStressTest
   ```

2. **編譯並執行 Virtiofs 壓力測試 Harness**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -pthread \
     /Users/iml1s/Documents/mine/aosp-linux/tests/unit/virtiofs_stress_test.cpp \
     -o /Users/iml1s/Documents/mine/aosp-linux/build_out/bin/virtiofs_stress_test

   /Users/iml1s/Documents/mine/aosp-linux/build_out/bin/virtiofs_stress_test
   ```

3. **檢查分析報告**:
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m5_1/analysis.md`
