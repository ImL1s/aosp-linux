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
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.linux.ILinuxManager;
import android.system.linux.ILinuxStatusCallback;
import android.system.linux.ILinuxTerminalCallback;
import android.system.linux.LinuxAppInfo;
import android.system.linux.LinuxManager;

import com.android.server.LocalServices;
import com.android.server.linux.LinuxBridgeService;
import com.android.server.linux.LinuxManagerInternal;
import com.android.server.linux.LinuxManagerService;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Empirical Challenger Test Harness for M1 Framework State Machine, Boot Timeout Guard,
 * Permission Enforcement, and Multi-Threaded PTY Callback Dispatching.
 */
public class ChallengerM1StressTest {

    private static class PermissionTestContext extends Context {
        private final Set<String> mGrantedPermissions = new HashSet<>();

        public void grantPermission(String permission) {
            mGrantedPermissions.add(permission);
        }

        public void revokePermission(String permission) {
            mGrantedPermissions.remove(permission);
        }

        public void clearPermissions() {
            mGrantedPermissions.clear();
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            if (!mGrantedPermissions.contains(permission)) {
                throw new SecurityException("Permission denied: " + permission + " (" + message + ")");
            }
        }

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
        System.out.println("=== CHALLENGER M1 EMPIRICAL STRESS TEST SUITE ===");
        System.out.println("==================================================");

        int failures = 0;
        failures += testUnprivilegedPermissionEnforcement();
        failures += testMultiThreadedPtyCallbackDispatching();
        failures += testBootTimeoutGuardAndStateTransitions();

        System.out.println("==================================================");
        if (failures == 0) {
            System.out.println("CHALLENGER VERDICT: ALL STRESS HARNESSES PASSED (APPROVE)");
            System.exit(0);
        } else {
            System.err.println("CHALLENGER VERDICT: " + failures + " STRESS HARNESS(ES) FAILED (REQUEST_CHANGES)");
            System.exit(1);
        }
    }

    /**
     * 1. Stress test permission checks for unprivileged callers.
     */
    private static int testUnprivilegedPermissionEnforcement() {
        System.out.print("[CHALLENGE 1] Unprivileged Caller Permission Enforcement... ");
        resetTestEnvironment();

        PermissionTestContext ctx = new PermissionTestContext();
        ctx.clearPermissions(); // Unprivileged app

        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        int failures = 0;

        // Test MANAGE_LINUX_ENVIRONMENT permission checks
        try {
            binder.startVm();
            System.out.println("\n  FAILED: startVm() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.stopVm(false);
            System.out.println("\n  FAILED: stopVm() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.suspendVm();
            System.out.println("\n  FAILED: suspendVm() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.resumeVm();
            System.out.println("\n  FAILED: resumeVm() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.installGuestImage(null, 1024);
            System.out.println("\n  FAILED: installGuestImage() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        // Test USE_LINUX_TERMINAL permission checks
        try {
            binder.createTerminalSession(80, 24, null);
            System.out.println("\n  FAILED: createTerminalSession() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.resizeTerminalSession("sess_unpriv", 100, 50);
            System.out.println("\n  FAILED: resizeTerminalSession() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.writeTerminalInput("sess_unpriv", "echo test\n".getBytes());
            System.out.println("\n  FAILED: writeTerminalInput() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.closeTerminalSession("sess_unpriv");
            System.out.println("\n  FAILED: closeTerminalSession() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.getInstalledApps();
            System.out.println("\n  FAILED: getInstalledApps() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        try {
            binder.launchLinuxApp("org.gnome.Terminal", 0);
            System.out.println("\n  FAILED: launchLinuxApp() did not throw SecurityException for unprivileged caller");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        // Verify state was NOT altered by rejected calls
        try {
            if (binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("\n  FAILED: VM state altered despite SecurityException throwing");
                failures++;
            }
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: binder.getState() threw RemoteException: " + e);
            failures++;
        }

        // Test selective permission: grant ONLY USE_LINUX_TERMINAL
        ctx.grantPermission("android.permission.USE_LINUX_TERMINAL");
        try {
            String sessId = binder.createTerminalSession(80, 24, null);
            if (sessId == null) {
                System.out.println("\n  FAILED: createTerminalSession returned null when USE_LINUX_TERMINAL was granted");
                failures++;
            } else {
                binder.closeTerminalSession(sessId);
            }
        } catch (SecurityException e) {
            System.out.println("\n  FAILED: createTerminalSession threw SecurityException when USE_LINUX_TERMINAL was granted");
            failures++;
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: createTerminalSession threw RemoteException: " + e);
            failures++;
        }

        // Verify startVm still fails when MANAGE_LINUX_ENVIRONMENT is missing
        try {
            binder.startVm();
            System.out.println("\n  FAILED: startVm() succeeded without MANAGE_LINUX_ENVIRONMENT permission");
            failures++;
        } catch (SecurityException expected) {
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: Unexpected RemoteException: " + e);
            failures++;
        }

        // Grant MANAGE_LINUX_ENVIRONMENT as well
        ctx.grantPermission("android.permission.MANAGE_LINUX_ENVIRONMENT");
        try {
            if (!binder.startVm()) {
                System.out.println("\n  FAILED: startVm() returned false when MANAGE_LINUX_ENVIRONMENT was granted");
                failures++;
            }
        } catch (SecurityException e) {
            System.out.println("\n  FAILED: startVm() threw SecurityException when MANAGE_LINUX_ENVIRONMENT was granted");
            failures++;
        } catch (RemoteException e) {
            System.out.println("\n  FAILED: startVm() threw RemoteException: " + e);
            failures++;
        }

        // Multi-threaded unprivileged caller hammer (15 threads)
        ctx.clearPermissions();
        int threads = 15;
        int opsPerThread = 300;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger unprivilegedViolations = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            futures.add(executor.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    try {
                        binder.startVm();
                        unprivilegedViolations.incrementAndGet();
                    } catch (SecurityException expected) {
                    } catch (RemoteException ignored) {}

                    try {
                        binder.createTerminalSession(80, 24, null);
                        unprivilegedViolations.incrementAndGet();
                    } catch (SecurityException expected) {
                    } catch (RemoteException ignored) {}

                    try {
                        binder.launchLinuxApp("test", 0);
                        unprivilegedViolations.incrementAndGet();
                    } catch (SecurityException expected) {
                    } catch (RemoteException ignored) {}
                }
            }));
        }

        try {
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            executor.shutdown();

            if (unprivilegedViolations.get() > 0) {
                System.out.println("\n  FAILED: " + unprivilegedViolations.get() + " unprivileged calls bypassed permission checks!");
                failures++;
            }
        } catch (Exception e) {
            System.out.println("\n  FAILED during unprivileged multi-threaded stress: " + e.getMessage());
            failures++;
        }

        if (failures == 0) {
            System.out.println("PASS");
            return 0;
        } else {
            return 1;
        }
    }

    /**
     * 2. Stress test PTY data callback delivery under multi-threaded concurrency.
     */
    private static int testMultiThreadedPtyCallbackDispatching() {
        System.out.print("[CHALLENGE 2] Multi-Threaded PTY Callback Delivery & Data Integrity... ");
        resetTestEnvironment();

        PermissionTestContext ctx = new PermissionTestContext();
        ctx.grantPermission("android.permission.MANAGE_LINUX_ENVIRONMENT");
        ctx.grantPermission("android.permission.USE_LINUX_TERMINAL");

        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        try {
            Field bridgeField = LinuxManagerService.class.getDeclaredField("mBridgeService");
            bridgeField.setAccessible(true);
            Object bridgeService = bridgeField.get(service);

            Field callbackField = LinuxBridgeService.class.getDeclaredField("mCallback");
            callbackField.setAccessible(true);
            LinuxBridgeService.LinuxBridgeCallback bridgeCallback =
                    (LinuxBridgeService.LinuxBridgeCallback) callbackField.get(bridgeService);

            int numSessions = 20;
            Map<String, ByteArrayOutputStream> expectedBuffers = new ConcurrentHashMap<>();
            Map<String, ByteArrayOutputStream> receivedBuffers = new ConcurrentHashMap<>();

            List<String> sessionIds = new ArrayList<>();
            for (int i = 0; i < numSessions; i++) {
                ByteArrayOutputStream rxBuf = new ByteArrayOutputStream();

                ILinuxTerminalCallback cb = new ILinuxTerminalCallback.Stub() {
                    @Override
                    public void onDataReceived(String sessionId, byte[] data) {
                        if (data != null) {
                            synchronized (rxBuf) {
                                try {
                                    rxBuf.write(data);
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    @Override
                    public void onTitleChanged(String sessionId, String title) {}

                    @Override
                    public void onBell(String sessionId) {}

                    @Override
                    public void onSessionClosed(String sessionId, int exitCode) {}
                };

                String sId = binder.createTerminalSession(80, 24, cb);
                sessionIds.add(sId);
                expectedBuffers.put(sId, new ByteArrayOutputStream());
                receivedBuffers.put(sId, rxBuf);
            }

            // Dedicated producer thread per session for 20 concurrent sessions (20 threads sending 500 packets each)
            int producerThreads = numSessions;
            int packetsPerProducer = 500;
            ExecutorService executor = Executors.newFixedThreadPool(producerThreads + 3);
            List<Future<?>> futures = new ArrayList<>();
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int t = 0; t < producerThreads; t++) {
                final int sessionIdx = t;
                final String targetSession = sessionIds.get(sessionIdx);
                futures.add(executor.submit(() -> {
                    for (int p = 0; p < packetsPerProducer; p++) {
                        try {
                            byte[] payload = ("Sess" + sessionIdx + "-P" + p + "-" + System.nanoTime() + "\n").getBytes();

                            ByteArrayOutputStream expBuf = expectedBuffers.get(targetSession);
                            if (expBuf != null) {
                                synchronized (expBuf) {
                                    expBuf.write(payload);
                                }
                            }

                            // Deliver PTY data concurrently via bridge callback
                            bridgeCallback.onPtyDataReceived(targetSession, payload);
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                }));
            }

            // 3 background threads dynamically opening and closing auxiliary sessions concurrently
            for (int aux = 0; aux < 3; aux++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        try {
                            String tempSession = binder.createTerminalSession(80, 24, null);
                            bridgeCallback.onPtyDataReceived(tempSession, "temp_data\n".getBytes());
                            binder.writeTerminalInput(tempSession, "test_input".getBytes());
                            binder.closeTerminalSession(tempSession);
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            executor.shutdown();

            if (errorCount.get() > 0) {
                System.out.println("FAILED: Multi-threaded PTY delivery encountered " + errorCount.get() + " exceptions");
                return 1;
            }

            // Verify data integrity for all 20 sessions
            for (String sId : sessionIds) {
                byte[] expected = expectedBuffers.get(sId).toByteArray();
                byte[] actual = receivedBuffers.get(sId).toByteArray();

                if (!Arrays.equals(expected, actual)) {
                    System.out.println("FAILED: Data corruption/mismatch in session " + sId
                            + " (expected " + expected.length + " bytes, got " + actual.length + " bytes)");
                    return 1;
                }
            }

            // Test boundary payload (16MB max payload)
            byte[] largePayload = new byte[16 * 1024 * 1024]; // 16MB
            Arrays.fill(largePayload, (byte) 0x41); // 'A'

            ByteArrayOutputStream largeRxBuf = new ByteArrayOutputStream();
            ILinuxTerminalCallback largeCb = new ILinuxTerminalCallback.Stub() {
                @Override
                public void onDataReceived(String sessionId, byte[] data) {
                    if (data != null) {
                        try {
                            largeRxBuf.write(data);
                        } catch (Exception ignored) {}
                    }
                }
                @Override public void onTitleChanged(String sessionId, String title) {}
                @Override public void onBell(String sessionId) {}
                @Override public void onSessionClosed(String sessionId, int exitCode) {}
            };

            String largeSessId = binder.createTerminalSession(120, 50, largeCb);
            bridgeCallback.onPtyDataReceived(largeSessId, largePayload);

            if (largeRxBuf.size() != largePayload.length) {
                System.out.println("FAILED: 16MB boundary payload delivery failed (received "
                        + largeRxBuf.size() + " bytes)");
                return 1;
            }

            binder.closeTerminalSession(largeSessId);

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * 3. Stress test 15-second boot timeout guard and state machine transitions.
     */
    private static int testBootTimeoutGuardAndStateTransitions() {
        System.out.print("[CHALLENGE 3] 15-Second Boot Timeout Guard & State Machine Integrity... ");
        resetTestEnvironment();

        PermissionTestContext ctx = new PermissionTestContext();
        ctx.grantPermission("android.permission.MANAGE_LINUX_ENVIRONMENT");
        ctx.grantPermission("android.permission.USE_LINUX_TERMINAL");

        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();
        ILinuxManager.Stub binder = service.getBinderService();

        try {
            // Verify initial state
            if (binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: Initial state not STATE_STOPPED");
                return 1;
            }

            // 1. Verify boot timeout callback reason & state transition (STARTING -> ERROR)
            final AtomicInteger receivedState = new AtomicInteger(-1);
            final AtomicInteger receivedReason = new AtomicInteger(-1);
            final CountDownLatch timeoutLatch = new CountDownLatch(1);

            ILinuxStatusCallback statusCb = new ILinuxStatusCallback.Stub() {
                @Override
                public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                    if (newState == LinuxManager.STATE_ERROR) {
                        receivedState.set(newState);
                        receivedReason.set(reasonCode);
                        timeoutLatch.countDown();
                    }
                }
                @Override public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
            };

            binder.registerStatusCallback(statusCb);
            binder.startVm();

            if (binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED: State after startVm() is not STATE_STARTING");
                return 1;
            }

            // Trigger timeout handler directly to test timeout transition logic
            service.handleBootTimeout();

            if (!timeoutLatch.await(1, TimeUnit.SECONDS)) {
                System.out.println("FAILED: Timeout callback did not trigger");
                return 1;
            }

            if (binder.getState() != LinuxManager.STATE_ERROR || receivedReason.get() != LinuxManager.REASON_BOOT_TIMEOUT) {
                System.out.println("FAILED: Expected STATE_ERROR (4) and REASON_BOOT_TIMEOUT (101), got state: "
                        + binder.getState() + ", reason: " + receivedReason.get());
                return 1;
            }

            // 2. Recovery from ERROR state: calling startVm() again
            if (!binder.startVm() || binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED: Cannot restart VM from STATE_ERROR");
                return 1;
            }

            // 3. Normal boot completion: notifyVmStarted() transitions STARTING -> RUNNING and cancels boot timeout
            service.notifyVmStarted();
            if (binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: notifyVmStarted() failed to set state to STATE_RUNNING");
                return 1;
            }

            // 4. Stale timeout handler invocation after VM is RUNNING
            service.handleBootTimeout(); // Should be a no-op because state is RUNNING
            if (binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: Stale boot timeout corrupted state from STATE_RUNNING to " + binder.getState());
                return 1;
            }

            // 5. Suspend & Resume loop
            if (!binder.suspendVm() || binder.getState() != LinuxManager.STATE_SUSPENDED) {
                System.out.println("FAILED: Transition RUNNING -> SUSPENDED failed");
                return 1;
            }

            if (!binder.resumeVm() || binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED: Transition SUSPENDED -> RUNNING failed");
                return 1;
            }

            // 6. Stop VM
            if (!binder.stopVm(false) || binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED: Transition RUNNING -> STOPPED failed");
                return 1;
            }

            binder.unregisterStatusCallback(statusCb);

            // 7. Extreme High-Concurrency State Machine Stress Test (25 threads, 50,000 operations)
            int threads = 25;
            int opsPerThread = 2000;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            AtomicInteger raceErrors = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < opsPerThread; i++) {
                        try {
                            int op = (threadId + i) % 6;
                            switch (op) {
                                case 0:
                                    binder.startVm();
                                    break;
                                case 1:
                                    service.notifyVmStarted();
                                    break;
                                case 2:
                                    binder.suspendVm();
                                    break;
                                case 3:
                                    binder.resumeVm();
                                    break;
                                case 4:
                                    binder.stopVm(i % 2 == 0);
                                    break;
                                case 5:
                                    service.handleBootTimeout();
                                    break;
                            }
                            int s = binder.getState();
                            if (s < 0 || s > 4) {
                                raceErrors.incrementAndGet();
                            }
                        } catch (Exception e) {
                            raceErrors.incrementAndGet();
                        }
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            executor.shutdown();

            if (raceErrors.get() > 0) {
                System.out.println("FAILED: High-concurrency state machine stress produced " + raceErrors.get() + " errors");
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
}
