/*
 * Copyright (C) 2026 The Android Open Source Project
 * Empirical Stress & Edge Case Test Harness for Milestone M4 (Iteration 2 Verification)
 */

package tests.challenger;

import android.hardware.HardwareBuffer;
import android.view.SurfaceControl;
import com.android.server.linux.LinuxWindowBridgeService;
import com.android.virtualization.terminal.LinuxAppProxyActivity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChallengerM4JavaStressTest {

    static class MockHardwareBuffer extends HardwareBuffer {
        public final int id;
        public int closeCount = 0;

        public MockHardwareBuffer(int id) {
            this.id = id;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    static class MockSurfaceControl extends SurfaceControl {
        public int releaseCount = 0;
        public boolean valid = true;

        @Override
        public void release() {
            releaseCount++;
        }

        @Override
        public boolean isValid() {
            return valid;
        }
    }

    public static void testHardwareBufferLifecycleAndPacing() throws Exception {
        System.out.println("[JAVA CHALLENGE 1] HardwareBuffer Lifecycle & Frame Pacing...");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        int sid = service.createSurface("org.gnu.emacs", "Emacs", "/icon.png", 1024, 768);
        assert sid > 0 : "Failed to create surface";

        MockSurfaceControl sc = new MockSurfaceControl();
        boolean regOk = service.registerSurfaceControl(sid, sc, 1024, 768);
        assert regOk : "Failed to register SurfaceControl";

        MockHardwareBuffer buf1 = new MockHardwareBuffer(1);
        MockHardwareBuffer buf2 = new MockHardwareBuffer(2);
        MockHardwareBuffer buf3 = new MockHardwareBuffer(3);

        // 1. Initial Frame Commit
        boolean commit1 = service.commitFrame(sid, buf1);
        assert commit1 : "Frame 1 commit failed";
        assert buf1.closeCount == 0 : "buf1 should not be closed yet";

        // 2. Immediate frame commit (<16ms) -> Frame Pacing Drop
        boolean commit2Dropped = service.commitFrame(sid, buf2);
        assert !commit2Dropped : "Frame 2 commit should be dropped due to rate limiting";
        assert buf1.closeCount == 0 : "buf1 should remain active when buf2 is dropped";

        // 3. Frame commit after 20ms pacing delay
        Thread.sleep(20);
        boolean commit2 = service.commitFrame(sid, buf2);
        assert commit2 : "Frame 2 commit after delay failed";
        assert buf1.closeCount == 1 : "buf1 should be closed when buf2 replaces it";
        assert buf2.closeCount == 0 : "buf2 should not be closed yet";

        // 4. Commit SAME buffer again after pacing delay
        Thread.sleep(20);
        boolean commitSameBuf = service.commitFrame(sid, buf2);
        assert commitSameBuf : "Frame commit with same buffer failed";
        assert buf2.closeCount == 0 : "buf2 should not be closed when committed again";

        // 5. Commit buf3 after delay -> buf2 closed
        Thread.sleep(20);
        boolean commit3 = service.commitFrame(sid, buf3);
        assert commit3 : "Frame 3 commit failed";
        assert buf2.closeCount == 1 : "buf2 should be closed when buf3 replaces it";

        // 6. Surface Destruction -> buf3 closed and SurfaceControl reparented/released
        boolean destroyOk = service.destroySurface(sid);
        assert destroyOk : "Failed to destroy surface";
        assert buf3.closeCount == 1 : "buf3 should be closed upon surface destruction";
        assert sc.releaseCount == 1 : "SurfaceControl should be released upon surface destruction";

        System.out.println("  [PASS] HardwareBuffer lifecycle and pacing verified!");
    }

    public static void testSurfaceControlAttachmentAndReplacement() {
        System.out.println("[JAVA CHALLENGE 2] SurfaceControl Attachment & Replacement...");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        int sid = service.createSurface("com.gimp.Gimp", "GIMP", "/icon.png", 1920, 1080);

        MockSurfaceControl sc1 = new MockSurfaceControl();
        MockSurfaceControl sc2 = new MockSurfaceControl();

        // Attach sc1
        service.attachSurfaceControl(sid, sc1);
        assert sc1.releaseCount == 0;

        // Replace with sc2 -> sc1 must be released
        service.attachSurfaceControl(sid, sc2);
        assert sc1.releaseCount == 1 : "sc1 should have been released when sc2 was attached";
        assert sc2.releaseCount == 0;

        // Detach by passing null -> sc2 must be released
        service.attachSurfaceControl(sid, null);
        assert sc2.releaseCount == 1 : "sc2 should have been released when detached with null";

        // Non-existent surface ID
        boolean failAttach = service.attachSurfaceControl(9999, sc1);
        assert !failAttach : "Attaching to non-existent surface should fail";

        System.out.println("  [PASS] SurfaceControl attachment and replacement verified!");
    }

    public static void testNullHandlesAndEdgeCases() {
        System.out.println("[JAVA CHALLENGE 3] Null Surface Handles & Edge Cases...");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);

        // 1. Commit with null buffer
        int sid = service.createSurface("org.inkscape.Inkscape", "Inkscape", "/icon.png", 800, 600);
        boolean nullBufCommit = service.commitFrame(sid, (HardwareBuffer) null);
        assert !nullBufCommit : "commitFrame with null buffer should return false";

        // 2. Commit to invalid surface ID
        MockHardwareBuffer buf = new MockHardwareBuffer(99);
        boolean invalidSidCommit = service.commitFrame(99999, buf);
        assert !invalidSidCommit : "commitFrame with invalid sid should return false";
        assert buf.closeCount == 0 : "Buffer must not be closed on invalid sid commit";

        // 3. Register on invalid surface ID
        MockSurfaceControl sc = new MockSurfaceControl();
        boolean invalidSidReg = service.registerSurfaceControl(-1, sc, 100, 100);
        assert !invalidSidReg;

        // 4. Destroy invalid surface ID
        boolean invalidSidDestroy = service.destroySurface(8888);
        assert !invalidSidDestroy;

        // 5. Configure invalid surface ID
        boolean invalidSidConfig = service.configureSurface(7777, 500, 500);
        assert !invalidSidConfig;

        System.out.println("  [PASS] Null handles and edge cases verified!");
    }

    public static void testHighFrameRateAndRapidDestructionStress() throws Exception {
        System.out.println("[JAVA CHALLENGE 4] High Frame Rate & Rapid Surface Destruction Stress...");
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);

        int numWorkers = 8;
        int iterationsPerWorker = 200;
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers + 1);
        AtomicBoolean stopSignal = new AtomicBoolean(false);
        AtomicInteger totalCommits = new AtomicInteger(0);
        AtomicInteger totalDestroys = new AtomicInteger(0);

        // Background worker randomly flushing or closing tasks from recents
        executor.submit(() -> {
            while (!stopSignal.get()) {
                try {
                    Thread.sleep(15);
                    service.flushTasks();
                } catch (InterruptedException ignored) {}
            }
        });

        CountDownLatch latch = new CountDownLatch(numWorkers);

        for (int w = 0; w < numWorkers; w++) {
            final int workerId = w;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterationsPerWorker; i++) {
                        int sid = service.createSurface("app.stress." + workerId, "App " + workerId, "/icon.png", 800, 600);
                        if (sid > 0) {
                            MockSurfaceControl sc = new MockSurfaceControl();
                            service.registerSurfaceControl(sid, sc, 800, 600);

                            MockHardwareBuffer bufA = new MockHardwareBuffer(i * 2);
                            MockHardwareBuffer bufB = new MockHardwareBuffer(i * 2 + 1);

                            if (service.commitFrame(sid, bufA)) {
                                totalCommits.incrementAndGet();
                            }
                            Thread.sleep(2); // Rapid burst < 16ms
                            if (service.commitFrame(sid, bufB)) {
                                totalCommits.incrementAndGet();
                            }

                            service.configureSurface(sid, 1024, 768);
                            if (service.destroySurface(sid)) {
                                totalDestroys.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        stopSignal.set(true);
        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);

        assert finished : "Stress test timed out!";
        System.out.println("  [PASS] High frame rate & rapid destruction stress finished cleanly! Commits: " + totalCommits.get() + ", Destroys: " + totalDestroys.get());
    }

    public static void main(String[] args) {
        System.out.println("=== Starting Empirical Challenger Java Test Suite (M4 Iteration 2) ===");
        try {
            testHardwareBufferLifecycleAndPacing();
            testSurfaceControlAttachmentAndReplacement();
            testNullHandlesAndEdgeCases();
            testHighFrameRateAndRapidDestructionStress();
            System.out.println("ALL JAVA CHALLENGER TESTS PASSED SUCCESSFULLY!");
        } catch (Throwable t) {
            System.err.println("JAVA CHALLENGER TEST FAILED:");
            t.printStackTrace();
            System.exit(1);
        }
    }
}
