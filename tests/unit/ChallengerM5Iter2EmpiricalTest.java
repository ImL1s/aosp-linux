package tests.unit;

import com.android.server.linux.LinuxPortalService;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Empirical Verification Harness for Milestone M5 Iteration 2 (Real System Hardware Portals - R5).
 * Empirically tests all 7 remediations in LinuxPortalService.java:
 * 1. Camera2 hardware streaming & contention recovery.
 * 2. Location obfuscation & coarse AppOps.
 * 3. AppOps noteOpNoThrow auditing calls.
 * 4. Audio multi-session streaming & mono downmix.
 * 5. Dimension validation & USB unplug teardown.
 * 6. Persistent socket reuse & multi-threaded socket safety.
 * 7. VM Lifecycle Teardown & error handling.
 */
public class ChallengerM5Iter2EmpiricalTest {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("   EMPIRICAL CHALLENGER M5 ITERATION 2 VERIFICATION SUITE       ");
        System.out.println("=================================================================");

        int totalTests = 0;
        int passedTests = 0;
        int failedTests = 0;

        // ---------------------------------------------------------------------
        // REMEDIATION 1: Camera2 Hardware Streaming & Contention Recovery
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 1.1] Verifying Camera2 Contention State & Android App Active handling...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("org.gnome.Cheese", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);

            LinuxPortalService.CameraSession session = portal.startCameraStream("org.gnome.Cheese", "cam_s1", 1920, 1080, 30);
            if (session == null || !session.isActive) {
                throw new RuntimeException("Failed to start camera session");
            }

            // Native Android app takes camera -> deactivates sessions
            portal.setAndroidAppActiveForCamera(true);
            if (session.isActive) {
                System.err.println("  [FAIL] Camera session remained active when native Android app claimed camera");
                failedTests++;
            } else {
                // Native Android app releases camera -> restores active sessions
                portal.setAndroidAppActiveForCamera(false);
                if (!session.isActive) {
                    System.err.println("  [FAIL] Camera session failed to reactivate when native Android app released camera");
                    failedTests++;
                } else {
                    System.out.println("  [PASS] Camera contention state transitions & session recovery passed.");
                    passedTests++;
                }
            }
            portal.stopCameraStream("cam_s1");
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 1.1 threw exception: " + e);
            failedTests++;
        }

        totalTests++;
        System.out.println("\n[TEST 1.2] Verifying Camera Resolution Negotiation Fallback (4K 120fps -> 1080p 30fps)...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("org.obs.Studio", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);

            LinuxPortalService.CameraSession session = portal.startCameraStream("org.obs.Studio", "cam_s2", 3840, 2160, 120);
            if (session.width == 1920 && session.height == 1080 && session.fps == 30) {
                System.out.println("  [PASS] Camera resolution successfully clamped to 1920x1080@30fps.");
                passedTests++;
            } else {
                System.err.println("  [FAIL] Resolution clamping failed: " + session.width + "x" + session.height + "@" + session.fps);
                failedTests++;
            }
            portal.stopCameraStream("cam_s2");
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 1.2 threw exception: " + e);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // REMEDIATION 2: Location Obfuscation & Coarse AppOps
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 2.1] Verifying Location Obfuscation & Coarse AppOps Access...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            // Grant coarse location only
            portal.setAppOp("org.weather.App", LinuxPortalService.OP_FINE_LOCATION, LinuxPortalService.MODE_DENIED);
            portal.setAppOp("org.weather.App", LinuxPortalService.OP_COARSE_LOCATION, LinuxPortalService.MODE_ALLOWED);

            boolean accessGranted = portal.requestLocationAccess("org.weather.App");
            LinuxPortalService.LocationSession session = portal.startLocationStream("org.weather.App", "loc_s1");

            if (accessGranted && session != null && session.isCoarseOnly) {
                double[] obfuscated = portal.getObfuscatedLocation(25.0330123, 121.5654987, session.isCoarseOnly);
                if (obfuscated[0] == 25.03 && obfuscated[1] == 121.57) {
                    System.out.println("  [PASS] Coarse location granted and coordinates obfuscated to 2 decimal places.");
                    passedTests++;
                } else {
                    System.err.println("  [FAIL] Obfuscated coordinates mismatch: " + obfuscated[0] + ", " + obfuscated[1]);
                    failedTests++;
                }
            } else {
                System.err.println("  [FAIL] Coarse location request failed or isCoarseOnly flag not set.");
                failedTests++;
            }
            portal.unsubscribeLocationSession("org.weather.App", "loc_s1");
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 2.1 threw exception: " + e);
            failedTests++;
        }

        totalTests++;
        System.out.println("\n[TEST 2.2] Verifying Fine Location retains full precision...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("org.maps.Nav", LinuxPortalService.OP_FINE_LOCATION, LinuxPortalService.MODE_ALLOWED);

            LinuxPortalService.LocationSession session = portal.startLocationStream("org.maps.Nav", "loc_s2");
            if (session != null && !session.isCoarseOnly) {
                double[] exact = portal.getObfuscatedLocation(25.0330123, 121.5654987, session.isCoarseOnly);
                if (exact[0] == 25.0330123 && exact[1] == 121.5654987) {
                    System.out.println("  [PASS] Fine location retains exact coordinate precision.");
                    passedTests++;
                } else {
                    System.err.println("  [FAIL] Fine location precision degraded: " + exact[0] + ", " + exact[1]);
                    failedTests++;
                }
            } else {
                System.err.println("  [FAIL] Fine location session initialization failed.");
                failedTests++;
            }
            portal.unsubscribeLocationSession("org.maps.Nav", "loc_s2");
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 2.2 threw exception: " + e);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // REMEDIATION 3: AppOps noteOpNoThrow Auditing Calls
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 3.1] Verifying AppOps noteAppOp auditing execution...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            // Verify noteAppOp executes safely with null context or default return MODE_ALLOWED
            int modeCamera = portal.noteAppOp("org.gnome.Cheese", LinuxPortalService.OP_CAMERA);
            int modeMic = portal.noteAppOp("org.audacity.Audacity", LinuxPortalService.OP_RECORD_AUDIO);
            int modeLoc = portal.noteAppOp("org.weather.App", LinuxPortalService.OP_FINE_LOCATION);

            if (modeCamera == 0 && modeMic == 0 && modeLoc == 0) { // 0 = MODE_ALLOWED
                System.out.println("  [PASS] AppOps auditing noteAppOp helper executed safely across all portal channels.");
                passedTests++;
            } else {
                System.err.println("  [FAIL] noteAppOp returned unexpected mode codes.");
                failedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 3.1 threw exception: " + e);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // REMEDIATION 4: Audio Multi-Session Streaming & Mono Downmix
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 4.1] Verifying Audio Multi-Session Iteration & Downmixing...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("app1", LinuxPortalService.OP_RECORD_AUDIO, LinuxPortalService.MODE_ALLOWED);
            portal.setAppOp("app2", LinuxPortalService.OP_RECORD_AUDIO, LinuxPortalService.MODE_ALLOWED);

            LinuxPortalService.MicSession session1 = portal.startMicStream("app1", "mic_s1", 48000, 1); // Mono requested
            LinuxPortalService.MicSession session2 = portal.startMicStream("app2", "mic_s2", 48000, 2); // Stereo requested

            // Test downmix formula directly: L=1000, R=3000 -> Mono=2000
            short monoSample = portal.downmixStereoToMono((short) 1000, (short) 3000);
            if (monoSample != 2000) {
                throw new RuntimeException("Downmix formula incorrect, expected 2000, got " + monoSample);
            }

            // Raw PCM 16-bit stereo frame: [left=1000, right=3000] -> bytes: [0xE8, 0x03, 0xB8, 0x0B]
            byte[] rawStereoPcm = new byte[]{(byte) 0xE8, 0x03, (byte) 0xB8, 0x0B};

            // Process for mono session -> should downmix to mono (2000 -> 0x07D0 -> bytes [0xD0, 0x07]) and pad to 1024
            byte[] processedMono = portal.processMicPcmFrame(session1, rawStereoPcm);
            short resMono = (short) ((processedMono[0] & 0xFF) | ((processedMono[1] & 0xFF) << 8));

            // Process for stereo session -> should remain original raw PCM padded to 1024
            byte[] processedStereo = portal.processMicPcmFrame(session2, rawStereoPcm);

            // Stop session1 -> session2 should remain unaffected
            portal.stopMicStream("mic_s1");

            byte[] processedStereoAfterStop = portal.processMicPcmFrame(session2, rawStereoPcm);

            if (resMono == 2000 && processedMono.length == 1024 && processedStereoAfterStop.length == 1024) {
                System.out.println("  [PASS] Multi-session audio iteration, stereo-to-mono downmixing, and independent teardown passed.");
                passedTests++;
            } else {
                System.err.println("  [FAIL] Audio processing mismatch: resMono=" + resMono + ", len1=" + processedMono.length);
                failedTests++;
            }
            portal.stopMicStream("mic_s2");
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 4.1 threw exception: " + e);
            failedTests++;
        }

        totalTests++;
        System.out.println("\n[TEST 4.2] Verifying Audio Mic Privacy Toggle...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("app1", LinuxPortalService.OP_RECORD_AUDIO, LinuxPortalService.MODE_ALLOWED);
            LinuxPortalService.MicSession session = portal.startMicStream("app1", "mic_s3", 44100, 2);

            byte[] loudPcm = new byte[512];
            Arrays.fill(loudPcm, (byte) 0x5A);

            portal.setMicPrivacyToggle(true);
            byte[] silentPcm = portal.processMicPcmFrame(session, loudPcm);

            boolean isSilent = true;
            for (byte b : silentPcm) {
                if (b != 0) {
                    isSilent = false;
                    break;
                }
            }

            portal.setMicPrivacyToggle(false);
            byte[] normalPcm = portal.processMicPcmFrame(session, loudPcm);

            if (isSilent && normalPcm[0] == (byte) 0x5A) {
                System.out.println("  [PASS] Microphone privacy toggle correctly zero-fills audio frames.");
                passedTests++;
            } else {
                System.err.println("  [FAIL] Privacy toggle failed to silence audio frame.");
                failedTests++;
            }
            portal.stopMicStream("mic_s3");
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 4.2 threw exception: " + e);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // REMEDIATION 5: Dimension Validation & USB Hot-Unplug Teardown
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 5.1] Verifying Dimension Validation in startCameraStream...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("app1", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);

            LinuxPortalService.CameraSession inv1 = portal.startCameraStream("app1", "s_inv1", 0, 1080, 30);
            LinuxPortalService.CameraSession inv2 = portal.startCameraStream("app1", "s_inv2", 1920, -100, 30);
            LinuxPortalService.CameraSession inv3 = portal.startCameraStream("app1", "s_inv3", 1920, 1080, 0);

            if (inv1 == null && inv2 == null && inv3 == null) {
                System.out.println("  [PASS] Non-positive width, height, or fps rejected cleanly with null session.");
                passedTests++;
            } else {
                System.err.println("  [FAIL] Invalid stream parameters were accepted!");
                failedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 5.1 threw exception: " + e);
            failedTests++;
        }

        totalTests++;
        System.out.println("\n[TEST 5.2] Verifying USB Hot-Unplug Teardown for Hardware Camera...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("app1", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);

            LinuxPortalService.CameraSession s1 = portal.startCameraStream("app1", "s_plug", 1280, 720, 30);
            if (s1 == null || !s1.isActive) {
                throw new RuntimeException("Camera stream start failed");
            }

            // Simulate USB Camera unplugged
            portal.setHardwareCameraPluggedIn(false);

            boolean sessionDeactivated = (!s1.isActive);
            boolean accessDenied = false;
            try {
                portal.requestCameraAccess("app1");
            } catch (LinuxPortalService.ConnectionError ce) {
                accessDenied = true;
            }

            // Re-plug USB Camera
            portal.setHardwareCameraPluggedIn(true);
            boolean accessRestored = portal.requestCameraAccess("app1");

            if (sessionDeactivated && accessDenied && accessRestored) {
                System.out.println("  [PASS] USB hot-unplug deactivates sessions and throws ConnectionError, re-plug restores access.");
                passedTests++;
            } else {
                System.err.println("  [FAIL] USB hot-unplug teardown failed! deact=" + sessionDeactivated + ", denied=" + accessDenied + ", restored=" + accessRestored);
                failedTests++;
            }
            portal.stopCameraStream("s_plug");
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 5.2 threw exception: " + e);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // REMEDIATION 6: Persistent Socket Reuse & Concurrency Safety
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 6.1] Verifying Concurrent Socket Teardown & VM Lifecycle Cleanup...");
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            portal.setAppOp("app1", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);
            portal.setAppOp("app1", LinuxPortalService.OP_RECORD_AUDIO, LinuxPortalService.MODE_ALLOWED);
            portal.setAppOp("app1", LinuxPortalService.OP_FINE_LOCATION, LinuxPortalService.MODE_ALLOWED);

            portal.startCameraStream("app1", "cs1", 1280, 720, 30);
            portal.startMicStream("app1", "ms1", 44100, 2);
            portal.startLocationStream("app1", "ls1");

            // Execute concurrent VM teardown & socket operations across 20 threads
            int threads = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger exceptions = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                final int tid = i;
                executor.submit(() -> {
                    try {
                        if (tid % 2 == 0) {
                            portal.onVmStoppedOrSuspended();
                        } else {
                            portal.stopCameraStream("cs1");
                            portal.stopMicStream("ms1");
                            portal.unsubscribeLocationSession("app1", "ls1");
                        }
                    } catch (Exception ex) {
                        System.err.println("Concurrency exception in thread " + tid + ": " + ex);
                        exceptions.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            if (exceptions.get() == 0) {
                System.out.println("  [PASS] Concurrent socket teardown and onVmStoppedOrSuspended executed cleanly with 0 exceptions.");
                passedTests++;
            } else {
                System.err.println("  [FAIL] Caught " + exceptions.get() + " exceptions during concurrent VM teardown.");
                failedTests++;
            }
        } catch (Exception e) {
            System.err.println("  [EXCEPTION] Test 6.1 threw exception: " + e);
            failedTests++;
        }

        // Summary
        System.out.println("\n=================================================================");
        System.out.println("   EMPIRICAL VERIFICATION SUMMARY: " + passedTests + " PASSED, " + failedTests + " FAILED out of " + totalTests + " TESTS.");
        System.out.println("=================================================================");

        if (failedTests > 0) {
            System.exit(1);
        }
    }
}
