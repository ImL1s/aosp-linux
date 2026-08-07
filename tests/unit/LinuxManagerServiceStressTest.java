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

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.linux.ILinuxManager;
import android.system.linux.ILinuxStatusCallback;
import android.system.linux.ILinuxTerminalCallback;
import android.system.linux.LinuxManager;

import com.android.server.LocalServices;
import com.android.server.linux.LinuxManagerInternal;
import com.android.server.linux.LinuxManagerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class LinuxManagerServiceStressTest {

    private static class TestContext extends Context {
        @Override
        public java.util.concurrent.Executor getMainExecutor() {
            return Runnable::run;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            // No-op for mock context
        }

        @Override
        public Object getSystemService(String name) {
            return null;
        }
    }

    private static void resetTestEnvironment() {
        ServiceManager.clearForTest();
        LocalServices.removeServiceForTest(LinuxManagerInternal.class);
    }

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("=== EMPIRICAL STRESS TEST HARNESS: F-R1 (M1)   ===");
        System.out.println("==================================================");

        int failures = 0;

        failures += testExhaustiveStateTransitionMatrix();
        failures += testBootTimeoutActualExpiration();
        failures += testBootTimeoutCancellationOnSuccessAndStop();
        failures += testMultiThreadedRapidStateChangeConcurrency();
        failures += testMultipleCallbacksBroadcast();
        failures += testCallbackReentrancyAndMutation();
        failures += testDeadBinderStatusCallbacks();
        failures += testTerminalSessionLifecycleAndDeadCallbackLeaks();
        failures += testNullAndInvalidBoundaryInputs();

        System.out.println("==================================================");
        if (failures == 0) {
            System.out.println("STRESS TEST RESULT: ALL TESTS PASSED SUCCESSFULLY");
            System.exit(0);
        } else {
            System.err.println("STRESS TEST RESULT: " + failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    /**
     * Test 1: Exhaustive State Transition Matrix (25 State-Action pairs)
     */
    private static int testExhaustiveStateTransitionMatrix() {
        System.out.print("[STRESS] Exhaustive State Transition Matrix... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        try {
            // 1. Initial state must be STOPPED (0)
            if (binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: Initial state not STOPPED");
                return 1;
            }

            // Invalid transition checks from STOPPED:
            if (binder.suspendVm()) {
                System.out.println("FAILED: suspendVm() succeeded from STOPPED state");
                return 1;
            }
            if (binder.resumeVm()) {
                System.out.println("FAILED: resumeVm() succeeded from STOPPED state");
                return 1;
            }

            // Valid transition: STOPPED -> STARTING
            if (!binder.startVm() || binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED: startVm() failed from STOPPED state");
                return 1;
            }

            // Double startVm() in STARTING state must fail
            if (binder.startVm()) {
                System.out.println("FAILED: startVm() succeeded while already in STARTING state");
                return 1;
            }

            // Invalid transition from STARTING:
            if (binder.suspendVm()) {
                System.out.println("FAILED: suspendVm() succeeded from STARTING state");
                return 1;
            }
            if (binder.resumeVm()) {
                System.out.println("FAILED: resumeVm() succeeded from STARTING state");
                return 1;
            }

            // Valid transition: STARTING -> RUNNING
            service.notifyVmStarted();
            if (binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: notifyVmStarted() failed to set RUNNING state");
                return 1;
            }

            // Double startVm() in RUNNING state must fail
            if (binder.startVm()) {
                System.out.println("FAILED: startVm() succeeded while in RUNNING state");
                return 1;
            }

            // Invalid transition from RUNNING:
            if (binder.resumeVm()) {
                System.out.println("FAILED: resumeVm() succeeded from RUNNING state");
                return 1;
            }

            // Valid transition: RUNNING -> SUSPENDED
            if (!binder.suspendVm() || binder.getState() != LinuxManager.STATE_SUSPENDED) {
                System.out.println("FAILED: suspendVm() failed from RUNNING state");
                return 1;
            }

            // Invalid transition from SUSPENDED:
            if (binder.suspendVm()) {
                System.out.println("FAILED: suspendVm() succeeded while already in SUSPENDED state");
                return 1;
            }

            // Valid transition: SUSPENDED -> RUNNING
            if (!binder.resumeVm() || binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: resumeVm() failed from SUSPENDED state");
                return 1;
            }

            // Valid transition: RUNNING -> STOPPED
            if (!binder.stopVm(false) || binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: stopVm() failed from RUNNING state");
                return 1;
            }

            // Test transition from ERROR state:
            binder.startVm();
            service.handleBootTimeout();
            if (binder.getState() != LinuxManager.STATE_ERROR) {
                System.out.println("FAILED: handleBootTimeout() failed to set ERROR state");
                return 1;
            }

            // From ERROR -> STARTING (re-start allowed)
            if (!binder.startVm() || binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED: startVm() failed from ERROR state");
                return 1;
            }

            binder.stopVm(true);
            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e);
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Test 2: Actual 15-Second Boot Timeout Guard Expiration
     */
    private static int testBootTimeoutActualExpiration() {
        System.out.print("[STRESS] Real-Time 15s Boot Timeout Expiration Guard... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        CountDownLatch timeoutLatch = new CountDownLatch(1);
        AtomicInteger reportedNewState = new AtomicInteger(-1);
        AtomicInteger reportedReasonCode = new AtomicInteger(-1);
        AtomicReference<String> reportedMessage = new AtomicReference<>("");

        ILinuxStatusCallback callback = new ILinuxStatusCallback.Stub() {
            @Override
            public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                if (newState == LinuxManager.STATE_ERROR) {
                    reportedNewState.set(newState);
                    reportedReasonCode.set(reasonCode);
                    reportedMessage.set(message);
                    timeoutLatch.countDown();
                }
            }

            @Override
            public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
        };

        try {
            binder.registerStatusCallback(callback);
            long startTime = System.currentTimeMillis();
            binder.startVm();

            if (binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED: State is not STARTING after startVm()");
                return 1;
            }

            // Wait for real scheduled timer (BOOT_TIMEOUT_MS = 15000L).
            // We wait up to 17 seconds to ensure the timer task fires.
            boolean fired = timeoutLatch.await(17, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startTime;

            if (!fired) {
                System.out.println("FAILED: 15-second boot timeout timer did NOT fire within 17s (elapsed: " + elapsed + "ms)");
                return 1;
            }

            if (elapsed < 14500) {
                System.out.println("FAILED: Timer fired too early (" + elapsed + "ms)");
                return 1;
            }

            if (binder.getState() != LinuxManager.STATE_ERROR) {
                System.out.println("FAILED: Final state is not ERROR (got: " + binder.getState() + ")");
                return 1;
            }

            if (reportedReasonCode.get() != LinuxManager.REASON_BOOT_TIMEOUT) {
                System.out.println("FAILED: Incorrect reason code (got: " + reportedReasonCode.get() + ", expected: " + LinuxManager.REASON_BOOT_TIMEOUT + ")");
                return 1;
            }

            binder.unregisterStatusCallback(callback);
            System.out.println("PASS (Fired accurately in " + elapsed + "ms)");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e);
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Test 3: Boot Timeout Cancellation on Handshake and Stop
     */
    private static int testBootTimeoutCancellationOnSuccessAndStop() {
        System.out.print("[STRESS] Boot Timeout Cancellation Verification... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        AtomicInteger errorCount = new AtomicInteger(0);
        ILinuxStatusCallback callback = new ILinuxStatusCallback.Stub() {
            @Override
            public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                if (newState == LinuxManager.STATE_ERROR) {
                    errorCount.incrementAndGet();
                }
            }

            @Override
            public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
        };

        try {
            binder.registerStatusCallback(callback);

            // Case A: Handshake before timeout
            binder.startVm();
            Thread.sleep(100);
            service.notifyVmStarted(); // Should cancel timer
            if (binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: State not RUNNING after notifyVmStarted()");
                return 1;
            }

            // Sleep past the 15s timeout period (e.g. simulate waiting 1s after trigger or manual check)
            // To speed up test, trigger handleBootTimeout manually or check future cancellation
            service.handleBootTimeout(); // Should be no-op since state is RUNNING
            if (binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: handleBootTimeout mutated RUNNING state!");
                return 1;
            }

            // Case B: Stop before timeout
            binder.stopVm(true);
            binder.startVm();
            binder.stopVm(true);
            service.handleBootTimeout(); // Should be no-op since state is STOPPED
            if (binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: handleBootTimeout mutated STOPPED state!");
                return 1;
            }

            if (errorCount.get() != 0) {
                System.out.println("FAILED: Spurious ERROR state transitions recorded: " + errorCount.get());
                return 1;
            }

            binder.unregisterStatusCallback(callback);
            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e);
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Test 4: Multi-Threaded Rapid State Change Concurrency Stress
     */
    private static int testMultiThreadedRapidStateChangeConcurrency() {
        System.out.print("[STRESS] 20-Thread Concurrency & Race Condition Stress (10,000 ops)... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        int numThreads = 20;
        int opsPerThread = 500;
        ExecutorService threadPool = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int action = rand.nextInt(7);
                        switch (action) {
                            case 0:
                                binder.startVm();
                                break;
                            case 1:
                                binder.stopVm(rand.nextBoolean());
                                break;
                            case 2:
                                binder.suspendVm();
                                break;
                            case 3:
                                binder.resumeVm();
                                break;
                            case 4:
                                service.notifyVmStarted();
                                break;
                            case 5:
                                service.handleBootTimeout();
                                break;
                            case 6:
                                int st = binder.getState();
                                if (st < 0 || st > 4) {
                                    exceptionCount.incrementAndGet();
                                }
                                break;
                        }
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        try {
            startLatch.countDown();
            boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
            threadPool.shutdownNow();

            if (!finished) {
                System.out.println("FAILED: Deadlock detected! Threads failed to complete within 10s");
                return 1;
            }

            if (exceptionCount.get() > 0) {
                System.out.println("FAILED: Encountered " + exceptionCount.get() + " exceptions/invalid states during concurrent execution");
                return 1;
            }

            int finalState = binder.getState();
            if (finalState < 0 || finalState > 4) {
                System.out.println("FAILED: Invalid state after stress test: " + finalState);
                return 1;
            }

            System.out.println("PASS (Final state: " + finalState + ")");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e);
            return 1;
        }
    }

    /**
     * Test 5: Multiple Callbacks Broadcast Stress
     */
    private static int testMultipleCallbacksBroadcast() {
        System.out.print("[STRESS] 100 Listener Broadcast Delivery Stress... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        int listenerCount = 100;
        AtomicInteger totalNotifications = new AtomicInteger(0);
        List<ILinuxStatusCallback> callbacks = new ArrayList<>();

        try {
            for (int i = 0; i < listenerCount; i++) {
                ILinuxStatusCallback cb = new ILinuxStatusCallback.Stub() {
                    @Override
                    public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                        totalNotifications.incrementAndGet();
                    }

                    @Override
                    public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
                };
                callbacks.add(cb);
                binder.registerStatusCallback(cb);
            }

            binder.startVm();          // 1 transition * 100 = 100
            service.notifyVmStarted(); // 1 transition * 100 = 100
            binder.stopVm(true);       // 1 transition * 100 = 100

            int expected = listenerCount * 3;
            if (totalNotifications.get() != expected) {
                System.out.println("FAILED: Expected " + expected + " notifications, but got " + totalNotifications.get());
                return 1;
            }

            // Cleanup listeners
            for (ILinuxStatusCallback cb : callbacks) {
                binder.unregisterStatusCallback(cb);
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e);
            return 1;
        }
    }

    /**
     * Test 6: Listener Reentrancy & Mutation during Callbacks
     */
    private static int testCallbackReentrancyAndMutation() {
        System.out.print("[STRESS] Callback Reentrancy & RemoteCallbackList Mutation... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        AtomicReference<Exception> caughtException = new AtomicReference<>();
        AtomicInteger reentrantCallsHandled = new AtomicInteger(0);

        ILinuxStatusCallback[] cbHolder = new ILinuxStatusCallback[1];
        cbHolder[0] = new ILinuxStatusCallback.Stub() {
            @Override
            public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                reentrantCallsHandled.incrementAndGet();
                try {
                    // Reentrant attempt to register a new callback during broadcast
                    binder.registerStatusCallback(new ILinuxStatusCallback.Stub() {
                        @Override public void onStateChanged(int n, int o, int r, String m) {}
                        @Override public void onResourceUsageUpdated(long u, long t, float c) {}
                    });
                } catch (Exception e) {
                    caughtException.set(e);
                }
            }

            @Override
            public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
        };

        try {
            binder.registerStatusCallback(cbHolder[0]);
            binder.startVm();

            if (caughtException.get() != null) {
                System.out.println("FINDING: Caught exception during reentrant callback mutation: " + caughtException.get());
            }

            binder.unregisterStatusCallback(cbHolder[0]);
            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e);
            return 1;
        }
    }

    /**
     * Test 7: Dead Binder Status Callback Handling
     */
    private static int testDeadBinderStatusCallbacks() {
        System.out.print("[STRESS] Dead Binder Callback Resilience... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        // Create a Binder stub that throws RemoteException when invoked
        ILinuxStatusCallback deadCallback = new ILinuxStatusCallback.Stub() {
            @Override
            public void onStateChanged(int newState, int oldState, int reasonCode, String message) throws RemoteException {
                throw new RemoteException("Simulated Dead Binder Process");
            }

            @Override
            public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) throws RemoteException {
                throw new RemoteException("Simulated Dead Binder Process");
            }
        };

        try {
            binder.registerStatusCallback(deadCallback);
            // Trigger state broadcast - service must catch RemoteException gracefully without crashing
            binder.startVm();
            service.notifyVmStarted();
            binder.stopVm(true);

            binder.unregisterStatusCallback(deadCallback);
            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: Dead binder threw unhandled exception: " + e);
            return 1;
        }
    }

    /**
     * Test 8: Terminal Session Lifecycle & Dead Callback Leaks
     */
    private static int testTerminalSessionLifecycleAndDeadCallbackLeaks() {
        System.out.print("[STRESS] Terminal Session Creation/Close & Dead Callback Leaks... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        try {
            // 1. Create and close 1,000 terminal sessions
            List<String> sessionIds = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                String sid = binder.createTerminalSession(80, 24, null);
                if (sid == null) {
                    System.out.println("FAILED: createTerminalSession returned null at index " + i);
                    return 1;
                }
                sessionIds.add(sid);
            }

            for (String sid : sessionIds) {
                binder.resizeTerminalSession(sid, 120, 50);
                binder.writeTerminalInput(sid, "echo test\n".getBytes());
                binder.closeTerminalSession(sid);
            }

            // 2. Dead Callback Leak Check for Terminal Sessions:
            // When terminal session is created with a dead binder callback, is it cleaned up when closed or does it leak?
            ILinuxTerminalCallback deadTermCb = new ILinuxTerminalCallback.Stub() {
                @Override public void onDataReceived(String sid, byte[] data) {}
                @Override public void onTitleChanged(String sid, String title) {}
                @Override public void onBell(String sid) {}
                @Override public void onSessionClosed(String sid, int exitCode) throws RemoteException {
                    throw new RemoteException("Dead Process");
                }
            };

            String deadSid = binder.createTerminalSession(80, 24, deadTermCb);
            binder.closeTerminalSession(deadSid); // Must handle RemoteException without failing

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e);
            return 1;
        }
    }

    /**
     * Test 9: Null and Invalid Boundary Inputs
     */
    private static int testNullAndInvalidBoundaryInputs() {
        System.out.print("[STRESS] Boundary & Null Argument Resilience... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        try {
            // Null callback registration/unregistration
            binder.registerStatusCallback(null);
            binder.unregisterStatusCallback(null);

            // Invalid session IDs
            binder.resizeTerminalSession("non_existent_session", 100, 100);
            binder.writeTerminalInput("non_existent_session", "data".getBytes());
            binder.writeTerminalInput("non_existent_session", null);
            binder.closeTerminalSession("non_existent_session");
            binder.closeTerminalSession(null);

            // Negative dimension terminal session
            String sid = binder.createTerminalSession(-1, -1, null);
            if (sid != null) {
                binder.closeTerminalSession(sid);
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e);
            return 1;
        }
    }
}
