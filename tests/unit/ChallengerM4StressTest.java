/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Empirical Stress Test Suite for Milestone M4 Verification.
 * Created by Challenger 1.
 */

package tests.unit;

import com.android.server.linux.LinuxWindowBridgeService;
import com.android.virtualization.terminal.window.WindowResizePacer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ChallengerM4StressTest {

    private static int sPassCount = 0;
    private static int sFailCount = 0;

    private static void logPass(String testName) {
        sPassCount++;
        System.out.println("[EMPIRICAL STRESS TEST PASS] " + testName);
    }

    private static void logFail(String testName, String reason) {
        sFailCount++;
        System.err.println("[EMPIRICAL STRESS TEST FAIL] " + testName + ": " + reason);
    }

    /**
     * Test 1: Re-launching an already running app when MAX_CONCURRENT_TASKS (20) are active.
     */
    public static void testRelaunchAppAtMaxTaskLimit() {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        List<Integer> surfaces = new ArrayList<>();
        
        // Launch 20 distinct apps
        for (int i = 1; i <= LinuxWindowBridgeService.MAX_CONCURRENT_TASKS; i++) {
            int sid = service.createSurface("app.test." + i, "Test App " + i, null, 800, 600);
            if (sid <= 0) {
                logFail("testRelaunchAppAtMaxTaskLimit", "Failed to create surface " + i + " during initial fill");
                return;
            }
            surfaces.add(sid);
        }

        if (service.getActiveTaskCount() != 20) {
            logFail("testRelaunchAppAtMaxTaskLimit", "Expected 20 active tasks, got " + service.getActiveTaskCount());
            return;
        }

        // Now attempt to re-launch "app.test.1" (which is already active!)
        int sidReplaced = service.createSurface("app.test.1", "Test App 1", null, 800, 600);
        if (sidReplaced == -1) {
            logFail("testRelaunchAppAtMaxTaskLimit", "Re-launching active app 'app.test.1' when 20 tasks exist returned -1 (REJECTED by limit check before reuse check)");
        } else if (sidReplaced == surfaces.get(0)) {
            logPass("testRelaunchAppAtMaxTaskLimit");
        } else {
            logFail("testRelaunchAppAtMaxTaskLimit", "Expected surface ID " + surfaces.get(0) + ", got " + sidReplaced);
        }
    }

    /**
     * Test 2: Null appId crash test in createSurface.
     */
    public static void testNullAppIdHandling() {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        try {
            int sid = service.createSurface(null, "Anonymous App", null, 800, 600);
            if (sid <= 0) {
                logFail("testNullAppIdHandling", "Null appId returned invalid surface ID: " + sid);
            } else {
                logPass("testNullAppIdHandling");
            }
        } catch (NullPointerException e) {
            logFail("testNullAppIdHandling", "NullPointerException thrown when appId is null: " + e.toString());
        } catch (Exception e) {
            logFail("testNullAppIdHandling", "Unexpected exception for null appId: " + e.toString());
        }
    }

    /**
     * Test 3: Task creation and closing churn (1000 iterations).
     */
    public static void testTaskChurnAndRecycling() {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        try {
            for (int i = 0; i < 1000; i++) {
                String appId = "churn.app." + (i % 15); // cycle through 15 app IDs
                int sid = service.createSurface(appId, "Churn App " + i, null, 800, 600);
                if (sid <= 0 && service.getActiveTaskCount() < 20) {
                    logFail("testTaskChurnAndRecycling", "Failed to create surface at iteration " + i);
                    return;
                }
                
                // Randomly close some tasks to keep under limit
                if (service.getActiveTaskCount() >= 15) {
                    LinuxWindowBridgeService.WaylandSurface s = service.getSurface(sid);
                    if (s != null) {
                        service.closeTaskFromRecents(s.taskId);
                    }
                }
            }
            if (service.getActiveTaskCount() <= 20) {
                logPass("testTaskChurnAndRecycling");
            } else {
                logFail("testTaskChurnAndRecycling", "Active task count exceeded 20: " + service.getActiveTaskCount());
            }
        } catch (Exception e) {
            logFail("testTaskChurnAndRecycling", "Exception during churn test: " + e.toString());
        }
    }

    /**
     * Test 4: WindowResizePacer duplicate callback check on flush.
     */
    public static void testWindowResizePacerFlushDuplicateCallback() {
        AtomicInteger callbackCount = new AtomicInteger(0);
        WindowResizePacer pacer = new WindowResizePacer((w, h) -> {
            callbackCount.incrementAndGet();
        });

        // Step 1: Fire first resize (runs immediately)
        pacer.requestResize(400, 300);
        int count1 = callbackCount.get();

        // Step 2: Fire second resize 2ms later (schedules delayed runnable for ~14ms later)
        pacer.requestResize(500, 400);

        // Step 3: Sleep 50ms to allow delayed runnable to execute
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        int countAfterExecution = callbackCount.get();

        // Step 4: Call flushPendingResize() after delayed runnable has ALREADY executed
        pacer.flushPendingResize();
        int countAfterFlush = callbackCount.get();

        if (countAfterFlush > countAfterExecution) {
            logFail("testWindowResizePacerFlushDuplicateCallback", 
                    "flushPendingResize() triggered a DUPLICATE callback after delayed runnable had already executed! countBefore=" 
                    + countAfterExecution + ", countAfterFlush=" + countAfterFlush);
        } else {
            logPass("testWindowResizePacerFlushDuplicateCallback");
        }
    }

    /**
     * Test 5: Rapid Frame Pacing Drag Throttling (100 resizes in burst).
     */
    public static void testRapidResizeBurstPacing() {
        AtomicInteger totalConfigures = new AtomicInteger(0);
        final int[] lastWidth = new int[1];
        final int[] lastHeight = new int[1];

        WindowResizePacer pacer = new WindowResizePacer((w, h) -> {
            totalConfigures.incrementAndGet();
            lastWidth[0] = w;
            lastHeight[0] = h;
        });

        // Rapid fire 100 resize requests
        for (int i = 1; i <= 100; i++) {
            pacer.requestResize(500 + i, 400 + i);
        }

        pacer.flushPendingResize();

        if (lastWidth[0] != 600 || lastHeight[0] != 500) {
            logFail("testRapidResizeBurstPacing", "Final dimensions were " + lastWidth[0] + "x" + lastHeight[0] + ", expected 600x500");
        } else if (totalConfigures.get() > 10) {
            logFail("testRapidResizeBurstPacing", "Burst of 100 requests resulted in " + totalConfigures.get() + " configures (too high for debouncer)");
        } else {
            logPass("testRapidResizeBurstPacing");
        }
    }

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   CHALLENGER 1 EMPIRICAL STRESS TEST SUITE (M4)  ");
        System.out.println("==================================================");

        testRelaunchAppAtMaxTaskLimit();
        testNullAppIdHandling();
        testTaskChurnAndRecycling();
        testWindowResizePacerFlushDuplicateCallback();
        testRapidResizeBurstPacing();

        System.out.println("--------------------------------------------------");
        System.out.println("EMPIRICAL STRESS TEST SUMMARY: " + sPassCount + " PASS, " + sFailCount + " FAIL");
        System.out.println("==================================================");
        if (sFailCount > 0) {
            System.exit(1);
        }
    }
}
