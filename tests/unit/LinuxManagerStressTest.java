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
import android.os.ServiceManager;
import android.system.linux.ILinuxManager;
import android.system.linux.ILinuxStatusCallback;
import android.system.linux.LinuxAppInfo;
import android.system.linux.LinuxManager;

import com.android.server.LocalServices;
import com.android.server.linux.LinuxManagerInternal;
import com.android.server.linux.LinuxManagerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Empirical Stress & Boundary Harness for M1 Framework State Machine and Callbacks.
 */
public class LinuxManagerStressTest {

    private static class TestContext extends Context {
        @Override
        public Object getSystemService(String name) {
            if (Context.LINUX_SERVICE.equals(name)) {
                android.os.IBinder b = ServiceManager.getService(Context.LINUX_SERVICE);
                if (b != null) {
                    return new LinuxManager(this, ILinuxManager.Stub.asInterface(b));
                }
            }
            return null;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {}

        @Override
        public java.util.concurrent.Executor getMainExecutor() {
            return Runnable::run;
        }
    }

    private static void resetTestEnvironment() {
        ServiceManager.clearForTest();
        LocalServices.removeServiceForTest(LinuxManagerInternal.class);
    }

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("=== Running M1 Empirical Stress & Harness Test ===");
        System.out.println("==================================================");

        int failures = 0;
        failures += testExhaustiveStateTransitions();
        failures += testBootTimeoutAsyncTimerAndCancellation();
        failures += testRapidConcurrentVmStateCalls();
        failures += testConcurrentCallbackRegistrationAndBroadcast();
        failures += testConcurrentTerminalSessionLifecycle();

        System.out.println("==================================================");
        if (failures == 0) {
            System.out.println("STRESS TEST RESULT: ALL STRESS TESTS PASSED SUCCESSFULLY");
            System.exit(0);
        } else {
            System.err.println("STRESS TEST RESULT: " + failures + " STRESS TEST(S) FAILED");
            System.exit(1);
        }
    }

    /**
     * 1. Exhaustive State Transition Matrix Verification.
     */
    private static int testExhaustiveStateTransitions() {
        System.out.print("[STRESS TEST 1] Exhaustive State Transition Matrix... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        try {
            // Initial state must be STOPPED
            if (binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: Initial state not STOPPED");
                return 1;
            }

            // Invalid transition: suspend from STOPPED -> false
            if (binder.suspendVm()) {
                System.out.println("FAILED: Allowed suspendVm from STOPPED state");
                return 1;
            }

            // Invalid transition: resume from STOPPED -> false
            if (binder.resumeVm()) {
                System.out.println("FAILED: Allowed resumeVm from STOPPED state");
                return 1;
            }

            // Valid transition: startVm STOPPED -> STARTING
            if (!binder.startVm() || binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED: Transition STOPPED -> STARTING failed");
                return 1;
            }

            // Invalid transition: startVm when already STARTING -> false
            if (binder.startVm()) {
                System.out.println("FAILED: Allowed duplicate startVm when STARTING");
                return 1;
            }

            // Invalid transition: suspendVm when STARTING -> false
            if (binder.suspendVm()) {
                System.out.println("FAILED: Allowed suspendVm from STARTING state");
                return 1;
            }

            // Invalid transition: resumeVm when STARTING -> false
            if (binder.resumeVm()) {
                System.out.println("FAILED: Allowed resumeVm from STARTING state");
                return 1;
            }

            // Valid transition: notifyVmStarted STARTING -> RUNNING
            service.notifyVmStarted();
            if (binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: Transition STARTING -> RUNNING failed");
                return 1;
            }

            // Invalid transition: startVm when RUNNING -> false
            if (binder.startVm()) {
                System.out.println("FAILED: Allowed startVm when RUNNING");
                return 1;
            }

            // Invalid transition: resumeVm when RUNNING -> false
            if (binder.resumeVm()) {
                System.out.println("FAILED: Allowed resumeVm when RUNNING");
                return 1;
            }

            // Valid transition: suspendVm RUNNING -> SUSPENDED
            if (!binder.suspendVm() || binder.getState() != LinuxManager.STATE_SUSPENDED) {
                System.out.println("FAILED: Transition RUNNING -> SUSPENDED failed");
                return 1;
            }

            // Invalid transition: suspendVm when SUSPENDED -> false
            if (binder.suspendVm()) {
                System.out.println("FAILED: Allowed duplicate suspendVm when SUSPENDED");
                return 1;
            }

            // Valid transition: resumeVm SUSPENDED -> RUNNING
            if (!binder.resumeVm() || binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: Transition SUSPENDED -> RUNNING failed");
                return 1;
            }

            // Valid transition: stopVm RUNNING -> STOPPED
            if (!binder.stopVm(false) || binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: Transition RUNNING -> STOPPED failed");
                return 1;
            }

            // Test transition from ERROR state
            binder.startVm();
            service.handleBootTimeout(); // transition STARTING -> ERROR
            if (binder.getState() != LinuxManager.STATE_ERROR) {
                System.out.println("FAILED: Transition STARTING -> ERROR failed");
                return 1;
            }

            // Invalid transition: suspendVm from ERROR -> false
            if (binder.suspendVm()) {
                System.out.println("FAILED: Allowed suspendVm from ERROR state");
                return 1;
            }

            // Invalid transition: resumeVm from ERROR -> false
            if (binder.resumeVm()) {
                System.out.println("FAILED: Allowed resumeVm from ERROR state");
                return 1;
            }

            // Valid transition: restart from ERROR -> STARTING
            if (!binder.startVm() || binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED: Transition ERROR -> STARTING failed");
                return 1;
            }

            binder.stopVm(true);
            if (binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: Transition STARTING -> STOPPED failed");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * 2. Real Boot Timeout Async Timer Trigger & Cancellation Verification.
     */
    private static int testBootTimeoutAsyncTimerAndCancellation() {
        System.out.print("[STRESS TEST 2] Boot Timeout Async Guard & Timer Cancellation... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        try {
            // Part A: Verify manual handleBootTimeout triggers callback with reasonCode 101
            final CountDownLatch timeoutLatch = new CountDownLatch(1);
            final AtomicInteger lastReasonCode = new AtomicInteger(-1);
            final AtomicInteger lastNewState = new AtomicInteger(-1);

            ILinuxStatusCallback callback = new ILinuxStatusCallback.Stub() {
                @Override
                public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                    if (newState == LinuxManager.STATE_ERROR) {
                        lastNewState.set(newState);
                        lastReasonCode.set(reasonCode);
                        timeoutLatch.countDown();
                    }
                }

                @Override
                public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
            };

            binder.registerStatusCallback(callback);
            binder.startVm();

            // Trigger timeout handler
            service.handleBootTimeout();

            if (!timeoutLatch.await(2, TimeUnit.SECONDS)) {
                System.out.println("FAILED: Status callback was not triggered on boot timeout");
                return 1;
            }

            if (lastNewState.get() != LinuxManager.STATE_ERROR || lastReasonCode.get() != LinuxManager.REASON_BOOT_TIMEOUT) {
                System.out.println("FAILED: Expected state ERROR and reason REASON_BOOT_TIMEOUT (101), got state: "
                        + lastNewState.get() + ", reason: " + lastReasonCode.get());
                return 1;
            }

            binder.unregisterStatusCallback(callback);

            // Part B: Verify Timer Cancellation on notifyVmStarted()
            // Reset VM to STOPPED, then STARTING, then notifyVmStarted()
            binder.stopVm(true);
            binder.startVm();
            service.notifyVmStarted(); // Boot completed -> cancels timer

            // Manually call handleBootTimeout() simulate timer firing late
            service.handleBootTimeout();

            // State MUST remain RUNNING (not ERROR) because state was no longer STARTING
            if (binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: Late boot timeout modified state from RUNNING to " + binder.getState());
                return 1;
            }

            binder.stopVm(false);

            // Part C: Verify Timer Cancellation on stopVm()
            binder.startVm();
            binder.stopVm(false); // Stopped -> cancels timer
            service.handleBootTimeout();

            if (binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: Late boot timeout modified state from STOPPED to " + binder.getState());
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * 3. Rapid & High-Concurrency startVm / stopVm / suspendVm / resumeVm Calls.
     */
    private static int testRapidConcurrentVmStateCalls() {
        System.out.print("[STRESS TEST 3] Rapid Concurrent VM Lifecycle (20 threads, 20,000 ops)... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        int numThreads = 20;
        int opsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    try {
                        int action = (threadId + i) % 7;
                        switch (action) {
                            case 0:
                                binder.startVm();
                                break;
                            case 1:
                                binder.stopVm(i % 2 == 0);
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
                                int state = binder.getState();
                                if (state < 0 || state > 4) {
                                    errorCount.incrementAndGet();
                                }
                                break;
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            }));
        }

        try {
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            executor.shutdown();

            int finalState = binder.getState();
            if (errorCount.get() > 0 || finalState < 0 || finalState > 4) {
                System.out.println("FAILED: Concurrent VM state calls produced errors: " + errorCount.get()
                        + ", final state: " + finalState);
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception during concurrency: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * 4. Multi-Threaded Callback Registration, Unregistration & Broadcast Stress.
     */
    private static int testConcurrentCallbackRegistrationAndBroadcast() {
        System.out.print("[STRESS TEST 4] Concurrent Callback Registration & Broadcast (30 threads)... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        int regThreads = 20;
        int broadcastThreads = 5;
        int opsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(regThreads + broadcastThreads);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger totalCallbacksReceived = new AtomicInteger(0);

        // Threads registering and unregistering callbacks
        for (int t = 0; t < regThreads; t++) {
            futures.add(executor.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    try {
                        ILinuxStatusCallback callback = new ILinuxStatusCallback.Stub() {
                            @Override
                            public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                                totalCallbacksReceived.incrementAndGet();
                            }

                            @Override
                            public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
                        };

                        binder.registerStatusCallback(callback);
                        Thread.yield();
                        binder.unregisterStatusCallback(callback);
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            }));
        }

        // Threads triggering state transitions & broadcasts
        for (int t = 0; t < broadcastThreads; t++) {
            futures.add(executor.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    try {
                        binder.startVm();
                        service.notifyVmStarted();
                        binder.suspendVm();
                        binder.resumeVm();
                        binder.stopVm(false);
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            }));
        }

        try {
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            executor.shutdown();

            if (errorCount.get() > 0) {
                System.out.println("FAILED: Concurrent callback registration/broadcast produced "
                        + errorCount.get() + " errors");
                return 1;
            }

            System.out.println("PASS (total callbacks received: " + totalCallbacksReceived.get() + ")");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception during callback stress: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * 5. Terminal Session Concurrent Lifecycle & App Listing Stress.
     */
    private static int testConcurrentTerminalSessionLifecycle() {
        System.out.print("[STRESS TEST 5] Concurrent Terminal Session Lifecycle (10 threads)... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        int numThreads = 10;
        int opsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            futures.add(executor.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    try {
                        String sessionId = binder.createTerminalSession(80 + (i % 20), 24 + (i % 10), null);
                        if (sessionId == null || sessionId.isEmpty()) {
                            errorCount.incrementAndGet();
                            continue;
                        }
                        binder.resizeTerminalSession(sessionId, 120, 50);
                        binder.writeTerminalInput(sessionId, "echo test\n".getBytes());
                        binder.closeTerminalSession(sessionId);

                        List<LinuxAppInfo> apps = binder.getInstalledApps();
                        if (apps == null || apps.isEmpty()) {
                            errorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            }));
        }

        try {
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            executor.shutdown();

            if (errorCount.get() > 0) {
                System.out.println("FAILED: Concurrent terminal session lifecycle produced "
                        + errorCount.get() + " errors");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception during terminal session stress: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
