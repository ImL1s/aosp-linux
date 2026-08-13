/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tests.unit;

import com.android.server.linux.LinuxPermissionActivity;
import com.android.server.linux.LinuxPortalService;
import android.app.AppOpsManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive Empirical Unit & Integration Test Suite for LinuxPermissionActivity & LinuxPortalService
 * (Milestone 4: R4 Functional Permission Decision Component).
 */
public class LinuxPermissionActivityTest {

    private static int sPassCount = 0;
    private static int sFailCount = 0;

    private static void logPass(String testName) {
        sPassCount++;
        System.out.println("[EMPIRICAL M4 TEST PASS] " + testName);
    }

    private static void logFail(String testName, Throwable e) {
        sFailCount++;
        System.err.println("[EMPIRICAL M4 TEST FAIL] " + testName + ": " + e.getMessage());
        e.printStackTrace();
    }

    private static void logFail(String testName, String reason) {
        sFailCount++;
        System.err.println("[EMPIRICAL M4 TEST FAIL] " + testName + ": " + reason);
    }

    /**
     * Test 1: Verify op integer to string mapping in LinuxPermissionActivity.
     */
    public static void testOpIntToStringMapping() {
        try {
            if (!LinuxPortalService.OP_CAMERA.equals(LinuxPermissionActivity.mapOpIntToString(26))) {
                logFail("testOpIntToStringMapping", "26 should map to OP_CAMERA");
                return;
            }
            if (!LinuxPortalService.OP_RECORD_AUDIO.equals(LinuxPermissionActivity.mapOpIntToString(27))) {
                logFail("testOpIntToStringMapping", "27 should map to OP_RECORD_AUDIO");
                return;
            }
            if (!LinuxPortalService.OP_FINE_LOCATION.equals(LinuxPermissionActivity.mapOpIntToString(1))) {
                logFail("testOpIntToStringMapping", "1 should map to OP_FINE_LOCATION");
                return;
            }
            if (!LinuxPortalService.OP_COARSE_LOCATION.equals(LinuxPermissionActivity.mapOpIntToString(0))) {
                logFail("testOpIntToStringMapping", "0 should map to OP_COARSE_LOCATION");
                return;
            }
            if (!"OP_999".equals(LinuxPermissionActivity.mapOpIntToString(999))) {
                logFail("testOpIntToStringMapping", "999 should map to OP_999");
                return;
            }
            if (!"OP_-1".equals(LinuxPermissionActivity.mapOpIntToString(-1))) {
                logFail("testOpIntToStringMapping", "-1 should map to OP_-1");
                return;
            }
            if (!"OP_-99".equals(LinuxPermissionActivity.mapOpIntToString(-99))) {
                logFail("testOpIntToStringMapping", "-99 should map to OP_-99");
                return;
            }
            logPass("testOpIntToStringMapping");
        } catch (Throwable e) {
            logFail("testOpIntToStringMapping", e);
        }
    }

    /**
     * Test 2: Verify op string to code mapping in LinuxPermissionActivity.
     */
    public static void testOpStringToCodeMapping() {
        try {
            if (LinuxPermissionActivity.mapOpStringToCode(null) != -1) {
                logFail("testOpStringToCodeMapping", "null should map to -1");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode(LinuxPortalService.OP_CAMERA) != 26) {
                logFail("testOpStringToCodeMapping", "OP_CAMERA should map to 26");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode(AppOpsManager.OPSTR_CAMERA) != 26) {
                logFail("testOpStringToCodeMapping", "OPSTR_CAMERA should map to 26");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode("26") != 26) {
                logFail("testOpStringToCodeMapping", "\"26\" string should map to 26");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode(LinuxPortalService.OP_RECORD_AUDIO) != 27) {
                logFail("testOpStringToCodeMapping", "OP_RECORD_AUDIO should map to 27");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode(AppOpsManager.OPSTR_RECORD_AUDIO) != 27) {
                logFail("testOpStringToCodeMapping", "OPSTR_RECORD_AUDIO should map to 27");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode("27") != 27) {
                logFail("testOpStringToCodeMapping", "\"27\" string should map to 27");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode(LinuxPortalService.OP_FINE_LOCATION) != 1) {
                logFail("testOpStringToCodeMapping", "OP_FINE_LOCATION should map to 1");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode(LinuxPortalService.OP_COARSE_LOCATION) != 0) {
                logFail("testOpStringToCodeMapping", "OP_COARSE_LOCATION should map to 0");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode("INVALID_OP_CODE") != -1) {
                logFail("testOpStringToCodeMapping", "INVALID_OP_CODE string should map to -1");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode("not_a_number") != -1) {
                logFail("testOpStringToCodeMapping", "not_a_number should map to -1");
                return;
            }
            if (LinuxPermissionActivity.mapOpStringToCode("-5") != -5) {
                logFail("testOpStringToCodeMapping", "\"-5\" string should map to -5");
                return;
            }
            logPass("testOpStringToCodeMapping");
        } catch (Throwable e) {
            logFail("testOpStringToCodeMapping", e);
        }
    }

    /**
     * Test 3: LinuxPortalService AppOps updating and state querying.
     */
    public static void testPortalServiceAppOpsUpdating() {
        try {
            LinuxPortalService portal = new LinuxPortalService(null);

            // Test default state (unregistered app)
            if (!LinuxPortalService.MODE_PROMPT.equals(portal.checkAppOp("org.test.app", LinuxPortalService.OP_CAMERA))) {
                logFail("testPortalServiceAppOpsUpdating", "Default checkAppOp should return MODE_PROMPT");
                return;
            }

            // Test setAppOp with String mode
            portal.setAppOp("org.test.app", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);
            if (!LinuxPortalService.MODE_ALLOWED.equals(portal.checkAppOp("org.test.app", LinuxPortalService.OP_CAMERA))) {
                logFail("testPortalServiceAppOpsUpdating", "checkAppOp should return MODE_ALLOWED after set");
                return;
            }

            // Test setAppOp with integer mode
            portal.setAppOp("org.test.app", LinuxPortalService.OP_CAMERA, 2 /* AppOpsManager.MODE_ERRORED */);
            if (!LinuxPortalService.MODE_DENIED.equals(portal.checkAppOp("org.test.app", LinuxPortalService.OP_CAMERA))) {
                logFail("testPortalServiceAppOpsUpdating", "checkAppOp should return MODE_DENIED after setting MODE_ERRORED");
                return;
            }

            // Test setAppOp with integer op code (27 = RECORD_AUDIO)
            portal.setAppOp("org.test.app", 27, 0 /* AppOpsManager.MODE_ALLOWED */);
            if (!LinuxPortalService.MODE_ALLOWED.equals(portal.checkAppOp("org.test.app", LinuxPortalService.OP_RECORD_AUDIO))) {
                logFail("testPortalServiceAppOpsUpdating", "setAppOp by op code 27 failed to update OP_RECORD_AUDIO");
                return;
            }

            // Test setAppOp with integer op code and string mode
            portal.setAppOp("org.test.app", 1, LinuxPortalService.MODE_DENIED);
            if (!LinuxPortalService.MODE_DENIED.equals(portal.checkAppOp("org.test.app", LinuxPortalService.OP_FINE_LOCATION))) {
                logFail("testPortalServiceAppOpsUpdating", "setAppOp by op code 1 failed to update OP_FINE_LOCATION");
                return;
            }

            logPass("testPortalServiceAppOpsUpdating");
        } catch (Throwable e) {
            logFail("testPortalServiceAppOpsUpdating", e);
        }
    }

    /**
     * Test 4: Edge cases - missing app_id, negative op codes, custom/invalid op strings.
     */
    public static void testEdgeCasesNegativeAndCustomOps() {
        try {
            LinuxPortalService portal = new LinuxPortalService(null);

            // Negative op code (-5)
            portal.setAppOp("org.edge.app", -5, 0 /* MODE_ALLOWED */);
            if (!LinuxPortalService.MODE_ALLOWED.equals(portal.checkAppOp("org.edge.app", "OP_-5"))) {
                logFail("testEdgeCasesNegativeAndCustomOps", "Negative op code -5 failed to set OP_-5 to ALLOWED");
                return;
            }

            // Custom op string ("CUSTOM_OP_X")
            portal.setAppOp("org.edge.app", "CUSTOM_OP_X", LinuxPortalService.MODE_ALLOWED);
            if (!LinuxPortalService.MODE_ALLOWED.equals(portal.checkAppOp("org.edge.app", "CUSTOM_OP_X"))) {
                logFail("testEdgeCasesNegativeAndCustomOps", "Custom op string failed to set ALLOWED state");
                return;
            }

            // Null context launch prompt safety check
            LinuxPermissionActivity.launchPrompt(null, "org.edge.app", LinuxPortalService.OP_CAMERA);
            LinuxPermissionActivity.launchPrompt(null, "org.edge.app", 26);
            LinuxPermissionActivity.launchPrompt(null, null, (String) null);

            logPass("testEdgeCasesNegativeAndCustomOps");
        } catch (Throwable e) {
            logFail("testEdgeCasesNegativeAndCustomOps", e);
        }
    }

    /**
     * Test 5: Full Permission Access Flow with Portal Hardware Requests (Camera, Mic, GPS).
     */
    public static void testHardwarePortalPermissionGate() {
        try {
            LinuxPortalService portal = new LinuxPortalService(null);
            String appId = "org.gimp.Gimp";

            // Initially denied / prompt state -> request should return false (or throw PermissionError for GPS)
            portal.setAppOp(appId, LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_DENIED);
            if (portal.requestCameraAccess(appId)) {
                logFail("testHardwarePortalPermissionGate", "Camera access should be denied when OP_CAMERA is DENIED");
                return;
            }

            portal.setAppOp(appId, LinuxPortalService.OP_RECORD_AUDIO, LinuxPortalService.MODE_DENIED);
            if (portal.requestMicrophoneAccess(appId)) {
                logFail("testHardwarePortalPermissionGate", "Microphone access should be denied when OP_RECORD_AUDIO is DENIED");
                return;
            }

            // Allow permissions
            portal.setAppOp(appId, LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);
            if (!portal.requestCameraAccess(appId)) {
                logFail("testHardwarePortalPermissionGate", "Camera access should be granted when OP_CAMERA is ALLOWED");
                return;
            }

            portal.setAppOp(appId, LinuxPortalService.OP_RECORD_AUDIO, LinuxPortalService.MODE_ALLOWED);
            if (!portal.requestMicrophoneAccess(appId)) {
                logFail("testHardwarePortalPermissionGate", "Microphone access should be granted when OP_RECORD_AUDIO is ALLOWED");
                return;
            }

            logPass("testHardwarePortalPermissionGate");
        } catch (Throwable e) {
            logFail("testHardwarePortalPermissionGate", e);
        }
    }

    /**
     * Test 6: Rapid / Concurrent activity decision and AppOps update stress test.
     */
    public static void testRapidConcurrentAppOpsUpdates() {
        try {
            final LinuxPortalService portal = new LinuxPortalService(null);
            int threadCount = 20;
            int opsPerThread = 500;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger totalFailures = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.execute(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            String appId = "app.concurrent." + (i % 10);
                            int opCode = (i % 4 == 0) ? 26 : ((i % 4 == 1) ? 27 : ((i % 4 == 2) ? 1 : 0));
                            int mode = (i % 2 == 0) ? 0 /* MODE_ALLOWED */ : 2 /* MODE_ERRORED */;

                            portal.setAppOp(appId, opCode, mode);

                            String expectedMode = (mode == 0) ? LinuxPortalService.MODE_ALLOWED : LinuxPortalService.MODE_DENIED;
                            String opStr = LinuxPermissionActivity.mapOpIntToString(opCode);
                            String actualMode = portal.checkAppOp(appId, opStr);

                            if (!expectedMode.equals(actualMode)) {
                                totalFailures.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        totalFailures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            if (!completed) {
                logFail("testRapidConcurrentAppOpsUpdates", "Concurrent stress test timed out after 10s");
            } else if (totalFailures.get() > 0) {
                logFail("testRapidConcurrentAppOpsUpdates", "Encountered " + totalFailures.get() + " unexpected state mismatches during concurrent updates");
            } else {
                logPass("testRapidConcurrentAppOpsUpdates");
            }
        } catch (Throwable e) {
            logFail("testRapidConcurrentAppOpsUpdates", e);
        }
    }

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   CHALLENGER 1 EMPIRICAL M4 VERIFICATION SUITE   ");
        System.out.println("==================================================");

        testOpIntToStringMapping();
        testOpStringToCodeMapping();
        testPortalServiceAppOpsUpdating();
        testEdgeCasesNegativeAndCustomOps();
        testHardwarePortalPermissionGate();
        testRapidConcurrentAppOpsUpdates();

        System.out.println("--------------------------------------------------");
        System.out.println("EMPIRICAL M4 TEST SUMMARY: " + sPassCount + " PASS, " + sFailCount + " FAIL");
        System.out.println("==================================================");

        if (sFailCount > 0) {
            System.exit(1);
        }
    }
}
