# Handoff Report — Challenger 1 (Milestone M4: Empirical Verification & Stress Testing)

**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m4/challenger_1`  
**Milestone**: M4 (Seamless Wayland GUI Window Forwarding & Recents Overview Mapping)  
**Date**: 2026-08-06  
**Verdict**: **REJECT**  

---

## 1. Observation

### 1.1 Direct Technical Observations & Test Execution Results
Empirical stress testing was conducted against Worker 1's M4 implementation. While baseline verification scripts (`scripts/run_m4_verification.sh` and `python3 tests/e2e/runner.py --filter R4`) pass all happy-path tests, custom empirical stress testing (`tests/unit/ChallengerM4StressTest.java`) revealed 3 critical failure modes in `LinuxWindowBridgeService.java` and `WindowResizePacer.java`.

#### Test Execution Logs:
```
==================================================
   CHALLENGER 1 EMPIRICAL STRESS TEST SUITE (M4)  
==================================================
E/LinuxWindowBridgeService: Cannot create surface for app.test.1: Max concurrent task limit reached (20)
[EMPIRICAL STRESS TEST FAIL] testRelaunchAppAtMaxTaskLimit: Re-launching active app 'app.test.1' when 20 tasks exist returned -1 (REJECTED by limit check before reuse check)
[EMPIRICAL STRESS TEST FAIL] testNullAppIdHandling: NullPointerException thrown when appId is null: java.lang.NullPointerException: Cannot invoke "Object.hashCode()" because "key" is null
[EMPIRICAL STRESS TEST PASS] testTaskChurnAndRecycling
[EMPIRICAL STRESS TEST FAIL] testWindowResizePacerFlushDuplicateCallback: flushPendingResize() triggered a DUPLICATE callback after delayed runnable had already executed! countBefore=2, countAfterFlush=3
[EMPIRICAL STRESS TEST PASS] testRapidResizeBurstPacing
--------------------------------------------------
EMPIRICAL STRESS TEST SUMMARY: 2 PASS, 3 FAIL
==================================================
```

### 1.2 Identified Bugs & Failure Details

1. **Bug 1: Task Re-launch Failure when 20 Tasks are Active**
   - **File**: `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java:97-112`
   - **Code snippet**:
     ```java
     public synchronized int createSurface(String appId, String title, String iconPath, int width, int height) {
         if (mSurfaces.size() >= MAX_CONCURRENT_TASKS) {
             Slog.e(TAG, "Cannot create surface for " + appId + ": Max concurrent task limit reached (" + MAX_CONCURRENT_TASKS + ")");
             return -1;
         }
         if (mAppToTaskIdMap.containsKey(appId)) { ... }
     ```
   - **Flaw**: The task limit check (`mSurfaces.size() >= MAX_CONCURRENT_TASKS`) is performed at line 97 BEFORE checking if `appId` is already running at line 103 (`mAppToTaskIdMap.containsKey(appId)`).
   - **Impact**: When 20 Linux apps are open, attempting to focus/re-launch an already running app fails with `-1` error instead of bringing the existing task to the front.

2. **Bug 2: SystemServer Crash on Null `appId`**
   - **File**: `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java:103` & `119`
   - **Code snippet**:
     ```java
     private final Map<String, Integer> mAppToTaskIdMap = new ConcurrentHashMap<>();
     ...
     if (mAppToTaskIdMap.containsKey(appId)) { ... }
     ```
   - **Flaw**: `ConcurrentHashMap` does not accept `null` keys. Calling `createSurface(null, ...)` causes `mAppToTaskIdMap.containsKey(null)` to throw `java.lang.NullPointerException`.
   - **Impact**: If any Wayland window without a defined `appId` string is created, SystemServer crashes with an unhandled NullPointerException.

3. **Bug 3: Duplicate Resize Callback on `flushPendingResize()`**
   - **File**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/window/WindowResizePacer.java:59-65` & `69-75`
   - **Code snippet**:
     ```java
     mPendingResizeRunnable = () -> {
         synchronized (WindowResizePacer.this) {
             mLastResizeTimeMs = System.currentTimeMillis();
             mCallback.onResizeConfigured(mTargetWidth, mTargetHeight);
         }
     };
     ...
     public synchronized void flushPendingResize() {
         if (mPendingResizeRunnable != null) {
             mHandler.removeCallbacks(mPendingResizeRunnable);
             mPendingResizeRunnable = null;
             mCallback.onResizeConfigured(mTargetWidth, mTargetHeight);
         }
     }
     ```
   - **Flaw**: The delayed runnable lambda does not reset `mPendingResizeRunnable = null;` after executing. When `flushPendingResize()` is called later, `mPendingResizeRunnable` is still non-null, triggering a duplicate `onResizeConfigured()` call.
   - **Impact**: Redundant Wayland `xdg_toplevel.configure` events sent to guest apps, causing window flicker and unnecessary buffer re-allocation.

---

## 2. Logic Chain

1. **Re-launching Active App at 20-Task Limit**:
   - Observation: `mSurfaces.size()` returns 20 when 20 tasks are running.
   - Observation: Re-launching `app1` (already running) does not add a new surface; it reuses surface 1.
   - Deduction: `mSurfaces.size() >= 20` check blocks `createSurface("app1", ...)` at line 97 before line 103 can check `mAppToTaskIdMap.containsKey("app1")`.
   - Conclusion: High-priority bug requiring order of operations fix in `LinuxWindowBridgeService.java`.

2. **Null Pointer Exception in SystemServer**:
   - Observation: `ConcurrentHashMap` throws `NullPointerException` on `containsKey(null)`.
   - Deduction: Any window creation call with `appId == null` crashes the service process.
   - Conclusion: `appId` must be sanitized or checked for null before querying `mAppToTaskIdMap`.

3. **WindowResizePacer Lifecycle**:
   - Observation: `mPendingResizeRunnable` remains assigned after the delayed task finishes.
   - Deduction: `flushPendingResize()` evaluates `mPendingResizeRunnable != null` as true even when no pending resize is actually waiting.
   - Conclusion: `mPendingResizeRunnable` must be set to `null` inside the lambda body upon completion.

---

## 3. Caveats

- **Scope of Rejection**: Rejection is based on empirical failures in `LinuxWindowBridgeService.java` and `WindowResizePacer.java`. C++ native `wayland_buffer_sharing.cpp`, Launcher3 `LinuxAppTracker.java`, and guest `portal-agent` passed all stress tests cleanly.
- **Fix Recommendation**: Remediation requires simple patches in `LinuxWindowBridgeService.java` (reordering the task reuse check before limit enforcement, adding null guard for `appId`) and `WindowResizePacer.java` (setting `mPendingResizeRunnable = null` inside runnable completion block).

---

## 4. Conclusion

Verdict: **REJECT**  
Milestone M4 implementation fails empirical stress testing with 3 reproducible bugs (Task re-launch lockout under max tasks, NullPointerException crash on null appId, and duplicate window configure callbacks on flush).

---

## 5. Verification Method

To independently verify and reproduce these empirical failures:

```bash
# Compile and run Challenger 1's empirical stress test suite
javac -d /Users/iml1s/Documents/mine/aosp-linux/build_out/classes \
      -cp /Users/iml1s/Documents/mine/aosp-linux/build_out/classes \
      /Users/iml1s/Documents/mine/aosp-linux/tests/unit/ChallengerM4StressTest.java

java -cp /Users/iml1s/Documents/mine/aosp-linux/build_out/classes tests.unit.ChallengerM4StressTest
```

**Expected Empirical Failure Output**:
```
EMPIRICAL STRESS TEST SUMMARY: 2 PASS, 3 FAIL
```
