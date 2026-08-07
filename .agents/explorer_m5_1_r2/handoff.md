# Handoff Report: Explorer 1 (Iteration 2) — Hardware Portals, AppOps & Audio Subsystem Remediation Strategy

**Agent**: Explorer 1 (`explorer_m5_1_r2`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1_r2`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Focus Scope**: Features F-R5-001 through F-R5-006 (Hardware Portals, AppOps Enforcement, virtio-snd Audio Mapping & AudioFocus Policy)  
**Date**: 2026-08-06  

---

## 1. Observation

Direct observations from source code analysis, forensic audit reports, and empirical stress test execution:

1. **`LinuxPortalService.java` AppOps `MODE_PROMPT` Permission Bypass**:
   - **Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java:130-145, 189-196`
   - **Verbatim Code**:
     ```java
     public boolean requestCameraAccess(String appId) {
         String mode = checkAppOp(appId, OP_CAMERA);
         if (MODE_DENIED.equals(mode)) { return false; }
         ...
         return true;
     }
     ```
   - **Empirical Output** (`ChallengerM5EmpiricalStressTest.java` Test 1):
     `Initial AppOp mode for org.untrusted.app: PROMPT`
     `[BUG CONFIRMED] Security Flaw: Ungranted app in MODE_PROMPT was automatically allowed access!`

2. **`LinuxPermissionActivity.java` Disconnection & Concurrency Queue Drop**:
   - **Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java:38, 76-79`
   - **Verbatim Code**:
     ```java
     private static final List<String> sPendingPromptsQueue = new ArrayList<>();
     private static boolean sIsDialogVisible = false;
     ...
     if (sIsDialogVisible) {
         Slog.w(TAG, "Duplicate prompt suppressed while dialog is visible for " + appName);
         return false;
     }
     ```
   - **Result**: `LinuxPermissionActivity` is completely unreferenced by `LinuxPortalService`. Under 50-thread concurrency (`ChallengerM5EmpiricalStressTest.java` Test 2), 49 out of 50 prompts were dropped due to instance-level `synchronized` failing to lock static fields and returning `false` on `sIsDialogVisible`.

3. **`LinuxAudioPolicyHandler.java` Simulated Audio Streaming & Stacked AudioFocus Volume Overwrite**:
   - **Path**: `frameworks/base/services/core/java/com/android/server/linux/LinuxAudioPolicyHandler.java:101-108, 180-185`
   - **Verbatim Code**:
     ```java
     case AudioManager.AUDIOFOCUS_GAIN:
         mCurrentFocusState = "GAIN";
         mCurrentVolumeFactor = 1.0f;
         mIsPaused = false;
         break;
     ```
   - **Empirical Output** (`ChallengerM5EmpiricalStressTest.java` Test 5):
     `After Phone Call Duck: State=LOSS_TRANSIENT_CAN_DUCK, Volume=0.2, Paused=false`
     `After Alarm Interrupt: State=LOSS_TRANSIENT, Volume=0.2, Paused=true`
     `After Alarm Ends (Phone Call still active): State=GAIN, Volume=1.0, Paused=false`
     `[BUG CONFIRMED] Audio Policy Flaw: Volume restored to 1.0f (Full Volume) while phone call ducking scenario was still active!`

4. **Fabricated Tier 1 Test Suite (`test_m5_tier1.py`)**:
   - **Path**: `tests/e2e/tier1_feature_coverage/test_m5_tier1.py:120-122`
   - **Verbatim Code**: `CustomAssertions.assert_true(True)`

---

## 2. Logic Chain

1. **Observation 1** establishes that `LinuxPortalService` uses a negative-only check (`if (MODE_DENIED) return false; return true;`). When an app is in `MODE_PROMPT`, it is auto-granted access without triggering a user permission prompt, violating the AppOps security contract for F-R5-001 through F-R5-004.
2. **Observation 2** establishes that `LinuxPermissionActivity` is unlinked and contains a concurrency bug where instance-level synchronization fails to protect static members, silently dropping concurrent prompts.
3. **Observation 3** establishes that `LinuxAudioPolicyHandler` lacks stacked audio focus state tracking. When a transient alarm interrupt ends during an ongoing phone call, `AUDIOFOCUS_GAIN` unconditionally sets volume factor to `1.0f` instead of restoring `0.2f` phone call ducking, violating audio policy requirements for F-R5-006.
4. **Observation 4** shows that Tier-1 tests were hardcoded with `assert_true(True)`, masking all service-level defects during build/test script execution.
5. Therefore, a comprehensive remediation plan requires:
   - Modifying `LinuxPortalService.java` to enforce affirmative `MODE_ALLOWED` permissions and trigger `LinuxPermissionActivity` when in `MODE_PROMPT`.
   - Modifying `LinuxPermissionActivity.java` to use class-level thread lock `sLock` and enqueue (rather than drop) concurrent prompts.
   - Modifying `LinuxAudioPolicyHandler.java` to implement stacked AudioFocus state memory (`mPreTransientFocusState`) and thread-safe queueing (`ConcurrentLinkedQueue`).

---

## 3. Caveats

- **Scope Limitation**: Explorer 1's mandate covers Features F-R5-001 through F-R5-006 (Portals, AppOps, and Audio Subsystem). SAF Storage Provider (F-R5-008), SELinux Policies (F-R5-009 .. F-R5-011), and OTA Rollback (F-R5-012 .. F-R5-014) are handled by parallel explorers/implementers.
- **Read-Only Role**: This agent performs investigation and remediation design only. Code modifications must be executed by the Implementer agent.

---

## 4. Conclusion

The remediation strategy for F-R5-001 through F-R5-006 is fully detailed in `.agents/explorer_m5_1_r2/analysis.md` with complete code blueprints for:
1. `LinuxPortalService.java`: Resolves `MODE_PROMPT` by launching `LinuxPermissionActivity`, enforcing affirmative `MODE_ALLOWED` checks, and integrating hardware fallback logic.
2. `LinuxPermissionActivity.java`: Introduces static monitor `sLock`, thread-safe queueing of concurrent prompts, and service launcher integration.
3. `LinuxAudioPolicyHandler.java`: Implements stacked AudioFocus state tracking (`mPreTransientFocusState`), restoring ducked volume (`0.2f`) when transient alarms complete during active phone calls, and thread-safe `ConcurrentLinkedQueue` buffer management.

---

## 5. Verification Method

To independently verify the proposed remediation plan:

1. **Inspect Analysis Report**:
   - Path: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1_r2/analysis.md`
2. **Execute Empirical Stress Test Suite**:
   ```bash
   mkdir -p /Users/iml1s/Documents/mine/aosp-linux/build_out/classes
   javac -d /Users/iml1s/Documents/mine/aosp-linux/build_out/classes \
     $(find /Users/iml1s/Documents/mine/aosp-linux/frameworks/base/core/java /Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java -name "*.java") \
     /Users/iml1s/Documents/mine/aosp-linux/tests/unit/ChallengerM5EmpiricalStressTest.java

   java -cp /Users/iml1s/Documents/mine/aosp-linux/build_out/classes tests.unit.ChallengerM5EmpiricalStressTest
   ```
3. **Check Target Test Pass Criteria**:
   - Test 1 (`MODE_PROMPT` handling): `[PASS] MODE_PROMPT properly blocked/prompted.`
   - Test 2 (Permission queue concurrency): `[PASS] Permission prompt queue handled concurrency cleanly.`
   - Test 3 (Audio queue concurrency): `[PASS] Audio buffer queue handled concurrency cleanly.`
   - Test 5 (Stacked AudioFocus): `[PASS] AudioFocus properly maintained ducked state during stacked phone call.`
