/*
 * Copyright (C) 2026 The Android Open Source Project
 * Adversarial Stress & Edge-Case Test Harness for LinuxWindowBridgeService
 */

package tests.stress;

import com.android.server.linux.LinuxWindowBridgeService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AdversarialLinuxWindowBridgeServiceTest {

    public static void testConcurrentTaskLimitAndOverfill() {
        System.out.println("[STRESS] Testing Concurrent Task Limit (20 max) and Overfill Enforcement...");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);

        for (int i = 1; i <= LinuxWindowBridgeService.MAX_CONCURRENT_TASKS; i++) {
            int sid = service.createSurface("app.test." + i, "App " + i, "/icon.png", 800, 600);
            assert sid > 0 : "Failed to create surface " + i;
        }

        assert service.getActiveTaskCount() == 20;

        // 21st surface request must fail with -1
        int sidOverflow = service.createSurface("app.test.overflow", "Overflow App", "/icon.png", 800, 600);
        assert sidOverflow == -1 : "Expected -1 for 21st surface creation attempt";
        assert service.getActiveTaskCount() == 20;
        System.out.println("[PASS] 21st surface request rejected cleanly with -1 code");
    }

    public static void testTaskReuseOnRelaunch() {
        System.out.println("[STRESS] Testing Task ID Reuse on Re-launch...");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);

        int sid1 = service.createSurface("org.mozilla.firefox", "Firefox", "/icon.png", 1280, 800);
        LinuxWindowBridgeService.WaylandSurface surface1 = service.getSurface(sid1);
        int initialTaskId = surface1.taskId;

        int sid2 = service.createSurface("org.mozilla.firefox", "Firefox", "/icon.png", 1280, 800);
        assert sid1 == sid2 : "Re-launching Firefox should reuse existing surface ID";
        assert service.getActiveTaskCount() == 1;

        LinuxWindowBridgeService.WaylandSurface surface2 = service.getSurface(sid2);
        assert surface2.taskId == initialTaskId : "Re-launching Firefox should preserve Task ID " + initialTaskId;
        System.out.println("[PASS] Re-launching app reused existing surface ID and Task ID " + initialTaskId);
    }

    public static void testFramePacingRateLimiting() throws Exception {
        System.out.println("[STRESS] Testing Live Frame Pacing Rate Limiting (16ms / 60 FPS)...");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        int sid = service.createSurface("org.gnome.Terminal", "Terminal", "/icon.png", 1024, 768);

        // Immediate first frame commit -> PASS
        boolean frame1 = service.commitFrame(sid);
        assert frame1 : "Initial frame commit failed";

        // Immediate second frame commit (< 1ms later) -> DROPPED
        boolean frame2 = service.commitFrame(sid);
        assert !frame2 : "Second rapid frame commit should be dropped by rate limiter";

        // Sleep 20ms and commit third frame -> PASS
        Thread.sleep(20);
        boolean frame3 = service.commitFrame(sid);
        assert frame3 : "Third frame commit after 20ms delay should pass";

        System.out.println("[PASS] Frame pacing successfully dropped sub-16ms burst frames");
    }

    public static void testConcurrentMultiThreadedSurfaceOperations() throws Exception {
        System.out.println("[STRESS] Testing Concurrent Multi-Threaded Surface Operations...");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);

        int numThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger createdCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                int sid = service.createSurface("app.thread." + threadId, "Thread App " + threadId, "/icon.png", 800, 600);
                if (sid > 0) {
                    createdCount.incrementAndGet();
                    service.commitFrame(sid);
                    service.configureSurface(sid, 1024, 768);
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assert createdCount.get() == numThreads;
        assert service.getActiveTaskCount() == numThreads;

        service.onVmStateChanged(false); // VM crash/shutdown simulation
        assert service.getActiveTaskCount() == 0 : "VM shutdown should flush all surfaces";
        System.out.println("[PASS] Concurrent surface creation and VM shutdown flush verified");
    }

    public static void main(String[] args) {
        System.out.println("=== Running Adversarial LinuxWindowBridgeService Stress Tests ===");
        try {
            testConcurrentTaskLimitAndOverfill();
            testTaskReuseOnRelaunch();
            testFramePacingRateLimiting();
            testConcurrentMultiThreadedSurfaceOperations();
            System.out.println("ALL Adversarial LinuxWindowBridgeService STRESS TESTS PASSED!");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
