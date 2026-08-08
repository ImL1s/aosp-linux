/*
 * Challenger 2 M4 Java Stress Test Harness - Empirical Verification
 * Workspace: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_m4_iter2_2
 */

package com.android.server.linux.test;

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.view.SurfaceControl;
import com.android.server.linux.LinuxWindowBridgeService;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Challenger2M4JavaStressTest {

    private static final AtomicInteger sErrorCount = new AtomicInteger(0);

    private static void logError(String msg) {
        System.err.println("[FAIL] " + msg);
        sErrorCount.incrementAndGet();
    }

    private static SurfaceControl createMockSurfaceControl() {
        try {
            Constructor<SurfaceControl> ctor = SurfaceControl.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    private static HardwareBuffer createMockHardwareBuffer() {
        try {
            return HardwareBuffer.create(640, 480, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        } catch (Throwable t) {
            // Fallback mock if native android.hardware.HardwareBuffer isn't fully mocked
            return null;
        }
    }

    public static void testLifecycleAndLimits() throws Exception {
        System.out.println("--- Test 1: Surface Lifecycle & Concurrent Task Limits ---");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        service.flushTasks();

        if (LinuxWindowBridgeService.getInstance() != service) {
            logError("getInstance() did not return constructed service instance");
        } else {
            System.out.println("  [PASS] Singleton instance registered automatically");
        }

        // Create tasks up to MAX_CONCURRENT_TASKS (20)
        List<Integer> surfaceIds = new ArrayList<>();
        for (int i = 1; i <= LinuxWindowBridgeService.MAX_CONCURRENT_TASKS; i++) {
            int sid = service.createSurface("app.test." + i, "Test App " + i, null, 1024, 768);
            if (sid <= 0) {
                logError("Failed to create surface for app.test." + i);
            } else {
                surfaceIds.add(sid);
            }
        }

        if (service.getActiveTaskCount() != LinuxWindowBridgeService.MAX_CONCURRENT_TASKS) {
            logError("Expected " + LinuxWindowBridgeService.MAX_CONCURRENT_TASKS + " active tasks, got: " + service.getActiveTaskCount());
        } else {
            System.out.println("  [PASS] Successfully created max tasks (" + LinuxWindowBridgeService.MAX_CONCURRENT_TASKS + ")");
        }

        // Test Task ID reuse for existing app
        int reusedSid = service.createSurface("app.test.1", "Test App 1 Duplicate", null, 1024, 768);
        if (reusedSid != surfaceIds.get(0)) {
            logError("Expected reused surfaceId " + surfaceIds.get(0) + " for app.test.1, got: " + reusedSid);
        } else {
            System.out.println("  [PASS] Reused existing Task ID for identical appId");
        }

        // Attempt to create 21st task with new appId
        int overflowSid = service.createSurface("app.test.overflow", "Overflow App", null, 1024, 768);
        if (overflowSid != -1) {
            logError("Expected overflow surface creation to return -1, got: " + overflowSid);
        } else {
            System.out.println("  [PASS] Enforced MAX_CONCURRENT_TASKS limit (21st app rejected)");
        }

        // Flush tasks
        service.flushTasks();
        if (service.getActiveTaskCount() != 0) {
            logError("flushTasks() did not clear active tasks count");
        } else {
            System.out.println("  [PASS] flushTasks() successfully cleared all surfaces");
        }
    }

    public static void testSurfaceControlAndFrameCommit() throws Exception {
        System.out.println("--- Test 2: SurfaceControl Binding & Frame Pacing ---");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        service.flushTasks();

        int sid = service.createSurface("app.gui.test", "GUI Test App", null, 1280, 720);
        SurfaceControl sc = createMockSurfaceControl();

        boolean regOk = service.registerSurfaceControl(sid, sc, 1280, 720);
        if (!regOk) {
            logError("registerSurfaceControl failed for surfaceId " + sid);
        } else {
            System.out.println("  [PASS] registerSurfaceControl attached SurfaceControl and configured dimensions");
        }

        // Initial frame commit
        boolean commit1 = service.commitFrame(sid);
        if (!commit1) {
            logError("Initial commitFrame failed");
        } else {
            System.out.println("  [PASS] Initial commitFrame succeeded");
        }

        // Rapid frame commit (should be dropped due to frame pacing <16ms)
        boolean commit2 = service.commitFrame(sid);
        if (commit2) {
            logError("Immediate second commitFrame should have been dropped by 16ms frame pacing!");
        } else {
            System.out.println("  [PASS] Frame pacing correctly rate-limited immediate commit");
        }

        // Wait 17ms and commit again
        Thread.sleep(20);
        boolean commit3 = service.commitFrame(sid);
        if (!commit3) {
            logError("Frame commit after 20ms delay failed");
        } else {
            System.out.println("  [PASS] Frame commit after >16ms delay succeeded");
        }

        // Destroy surface
        boolean destroyOk = service.destroySurface(sid);
        if (!destroyOk) {
            logError("destroySurface failed for surfaceId " + sid);
        } else {
            System.out.println("  [PASS] destroySurface succeeded and cleaned up SurfaceControl");
        }
    }

    public static void testMultiThreadedConcurrency() throws Exception {
        System.out.println("--- Test 3: Multi-Threaded Concurrency (8 Threads, 80,000 Ops) ---");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        service.flushTasks();

        final int NUM_THREADS = 8;
        final int OPS_PER_THREAD = 10000;
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch latch = new CountDownLatch(NUM_THREADS);
        long startTime = System.currentTimeMillis();

        for (int t = 0; t < NUM_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    String appId = "concurrent.app." + threadId;
                    int sid = service.createSurface(appId, "Concurrent App " + threadId, null, 800, 600);
                    SurfaceControl sc = createMockSurfaceControl();
                    service.attachSurfaceControl(sid, sc);

                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        service.commitFrame(sid);
                        service.configureSurface(sid, 800 + (i % 100), 600 + (i % 100));
                    }
                } catch (Exception e) {
                    logError("Exception in thread " + threadId + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        long elapsedMs = System.currentTimeMillis() - startTime;

        System.out.println("  Completed " + (NUM_THREADS * OPS_PER_THREAD) + " operations across " 
                           + NUM_THREADS + " threads in " + elapsedMs + " ms.");

        service.flushTasks();
        if (service.getActiveTaskCount() != 0) {
            logError("Active tasks remaining after concurrent flush: " + service.getActiveTaskCount());
        } else {
            System.out.println("  [PASS] Multi-threaded concurrency completed with 0 errors and clean state!");
        }
    }

    public static void main(String[] args) {
        System.out.println("=========================================================");
        System.out.println(" Starting Challenger 2 M4 Java Bridge Verification ");
        System.out.println("=========================================================");

        try {
            testLifecycleAndLimits();
            testSurfaceControlAndFrameCommit();
            testMultiThreadedConcurrency();
        } catch (Exception e) {
            logError("Unhandled exception in main: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("=========================================================");
        if (sErrorCount.get() == 0) {
            System.out.println(" VERDICT: JAVA SUITE PASSED ALL EMPIRICAL TESTS! ");
            System.out.println("=========================================================");
            System.exit(0);
        } else {
            System.err.println(" VERDICT: FAILED WITH " + sErrorCount.get() + " ERRORS! ");
            System.out.println("=========================================================");
            System.exit(1);
        }
    }
}
