package tests.unit;

import com.android.server.linux.LinuxPortalService;
import com.android.server.linux.LinuxAudioPolicyHandler;
import com.android.server.linux.LinuxPermissionActivity;
import com.android.server.linux.storage.LinuxStorageProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Challenger 1 M5 Empirical Stress Test Harness.
 * Tests edge cases, concurrency, race conditions, path traversal, permission bypasses,
 * and audio focus interruption state machines for Features F-R5-001 through F-R5-008.
 */
public class ChallengerM5EmpiricalStressTest {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   CHALLENGER 1 M5 EMPIRICAL STRESS TEST SUITE    ");
        System.out.println("==================================================");

        int totalTests = 0;
        int passedTests = 0;
        int failedTests = 0;

        // Test 1: AppOps MODE_PROMPT Permission Bypass in LinuxPortalService
        totalTests++;
        System.out.println("\n[STRESS TEST 1] Verifying AppOps MODE_PROMPT handling in LinuxPortalService...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            // Unset app should return MODE_PROMPT
            String app = "org.untrusted.app";
            String mode = portal.checkAppOp(app, LinuxPortalService.OP_CAMERA);
            System.out.println("Initial AppOp mode for " + app + ": " + mode);
            
            boolean cameraAllowed = portal.requestCameraAccess(app);
            boolean micAllowed = portal.requestMicrophoneAccess(app);
            
            if ("PROMPT".equals(mode) && (cameraAllowed || micAllowed)) {
                System.err.println("  [BUG CONFIRMED] Security Flaw: Ungranted app in MODE_PROMPT was automatically allowed access!");
                System.err.println("  Camera allowed: " + cameraAllowed + ", Mic allowed: " + micAllowed);
                failedTests++;
            } else {
                System.out.println("  [PASS] MODE_PROMPT properly blocked/prompted.");
                passedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 1 threw exception: " + e);
            failedTests++;
        }

        // Test 2: Thread-Safety of LinuxPermissionActivity sPendingPromptsQueue under Concurrency
        totalTests++;
        System.out.println("\n[STRESS TEST 2] Testing LinuxPermissionActivity prompt queue concurrency (50 threads)...");
        try {
            LinuxPermissionActivity activity1 = new LinuxPermissionActivity();
            activity1.setScreenLocked(true);

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger exceptionCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int id = i;
                executor.submit(() -> {
                    try {
                        LinuxPermissionActivity act = new LinuxPermissionActivity();
                        act.showPrompt("app_" + id, "OP_CAMERA");
                    } catch (Exception e) {
                        System.err.println("Concurrent showPrompt exception: " + e);
                        exceptionCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            List<String> queue = LinuxPermissionActivity.getPendingPromptsQueue();
            System.out.println("Pending queue size after 50 concurrent locked prompts: " + queue.size());
            System.out.println("Concurrent exceptions caught: " + exceptionCount.get());

            if (exceptionCount.get() > 0 || queue.size() != threadCount) {
                System.err.println("  [BUG CONFIRMED] Concurrency Flaw: Non-thread-safe queue resulted in exceptions (" 
                        + exceptionCount.get() + ") or dropped prompts (Expected " + threadCount + ", got " + queue.size() + ")!");
                failedTests++;
            } else {
                System.out.println("  [PASS] Permission prompt queue handled concurrency cleanly.");
                passedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 2 threw exception: " + e);
            failedTests++;
        }

        // Test 3: Thread-Safety of LinuxAudioPolicyHandler mAudioBufferQueue under Burst Enqueue
        totalTests++;
        System.out.println("\n[STRESS TEST 3] Testing LinuxAudioPolicyHandler audio queue concurrency (20 threads, 2000 frames)...");
        try {
            LinuxAudioPolicyHandler audioHandler = new LinuxAudioPolicyHandler(null);
            int threadCount = 20;
            int framesPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger exceptionCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < framesPerThread; j++) {
                            audioHandler.enqueueFrame("frame_" + threadId + "_" + j);
                        }
                    } catch (Exception e) {
                        System.err.println("Concurrent enqueueFrame exception: " + e);
                        exceptionCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            System.out.println("Audio queue size: " + audioHandler.getQueueSize() + " (Max capacity: 100)");
            System.out.println("Concurrent exceptions caught: " + exceptionCount.get());

            if (exceptionCount.get() > 0) {
                System.err.println("  [BUG CONFIRMED] Concurrency Flaw: mAudioBufferQueue throws exception under high-rate PCM audio streaming!");
                failedTests++;
            } else {
                System.out.println("  [PASS] Audio buffer queue handled concurrency cleanly.");
                passedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 3 threw exception: " + e);
            failedTests++;
        }

        // Test 4: Path Traversal Vulnerability in LinuxStorageProvider System Root Check
        totalTests++;
        System.out.println("\n[STRESS TEST 4] Testing Path Traversal / Subpath Bypass in LinuxStorageProvider...");
        try {
            LinuxStorageProvider provider = new LinuxStorageProvider();
            String[] maliciousPaths = new String[]{
                "etc/passwd",
                "/etc/shadow",
                "sys/kernel",
                "/dev/mem",
                "/home/user/../../etc/shadow",
                "../proc/kallsyms"
            };

            int bypassedCount = 0;
            for (String path : maliciousPaths) {
                try {
                    provider.queryChildDocuments(path, null, null);
                    System.err.println("  [TRAVERSAL BYPASS] Path allowed without SecurityException: " + path);
                    bypassedCount++;
                } catch (SecurityException se) {
                    System.out.println("  [BLOCKED] Correctly blocked path: " + path);
                } catch (Exception e) {
                    System.out.println("  [EXCEPTION] Other error for path " + path + ": " + e);
                }
            }

            if (bypassedCount > 0) {
                System.err.println("  [BUG CONFIRMED] Security Flaw: " + bypassedCount + " malicious system path traversals bypassed SecurityException!");
                failedTests++;
            } else {
                System.out.println("  [PASS] All system path traversals blocked.");
                passedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 4 threw exception: " + e);
            failedTests++;
        }

        // Test 5: Audio Focus Priority & Restoration State Machine under Stacked Phone Call + Alarm Interrupts
        totalTests++;
        System.out.println("\n[STRESS TEST 5] Testing AudioFocus state machine under stacked Phone Call (Duck) + Alarm (Pause)...");
        try {
            LinuxAudioPolicyHandler audioHandler = new LinuxAudioPolicyHandler(null);
            audioHandler.setFocusState("GAIN");
            System.out.println("Initial state: " + audioHandler.getFocusState() + ", Volume: " + audioHandler.getVolumeFactor() + ", Paused: " + audioHandler.isPaused());

            // 1. Phone Call arrives -> Duck audio (volume 0.2f)
            audioHandler.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
            System.out.println("After Phone Call Duck: State=" + audioHandler.getFocusState() + ", Volume=" + audioHandler.getVolumeFactor() + ", Paused=" + audioHandler.isPaused());

            // 2. Alarm fires while call is active -> Pause audio
            audioHandler.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            System.out.println("After Alarm Interrupt: State=" + audioHandler.getFocusState() + ", Volume=" + audioHandler.getVolumeFactor() + ", Paused=" + audioHandler.isPaused());

            // 3. Alarm ends -> Audio focus GAIN delivered, BUT Phone Call is STILL active!
            audioHandler.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN);
            System.out.println("After Alarm Ends (Phone Call still active): State=" + audioHandler.getFocusState() + ", Volume=" + audioHandler.getVolumeFactor() + ", Paused=" + audioHandler.isPaused());

            if (audioHandler.getVolumeFactor() > 0.25f && audioHandler.getFocusState().equals("GAIN")) {
                System.err.println("  [BUG CONFIRMED] Audio Policy Flaw: Volume restored to 1.0f (Full Volume) while phone call ducking scenario was still active!");
                failedTests++;
            } else {
                System.out.println("  [PASS] AudioFocus properly maintained ducked state during stacked phone call.");
                passedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 5 threw exception: " + e);
            failedTests++;
        }

        // Test 6: Concurrent Camera & Microphone Portal Requests with Camera Contention
        totalTests++;
        System.out.println("\n[STRESS TEST 6] Testing Camera Contention & Hardware Disconnect Edge Cases...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("app.cam", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);
            
            // Start camera session
            LinuxPortalService.CameraSession s1 = portal.startCameraStream("app.cam", "sess_1", 1920, 1080, 60);
            if (s1 == null || !s1.isActive) {
                throw new RuntimeException("Camera stream start failed");
            }

            // Native Android app claims camera -> should deactivate guest camera session
            portal.setAndroidAppActiveForCamera(true);
            if (s1.isActive) {
                System.err.println("  [BUG CONFIRMED] Camera session remained active after native Android app claimed camera!");
                failedTests++;
            } else {
                System.out.println("  [PASS] Guest camera session correctly deactivated during Android app contention.");
                passedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 6 threw exception: " + e);
            failedTests++;
        }

        // Summary
        System.out.println("\n==================================================");
        System.out.println("   STRESS TEST SUMMARY: " + passedTests + " PASSED, " + failedTests + " FAILED out of " + totalTests + " TESTS.");
        System.out.println("==================================================");

        if (failedTests > 0) {
            System.exit(1);
        }
    }
}
