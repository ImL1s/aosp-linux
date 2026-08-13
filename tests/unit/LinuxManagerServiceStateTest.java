package tests.unit;

import com.android.server.linux.LinuxManagerService;
import com.android.server.linux.LinuxBridgeService;
import android.system.linux.LinuxManager;
import android.system.linux.ILinuxStatusCallback;
import android.content.Context;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

public class LinuxManagerServiceStateTest {

    private static class TestContext extends android.content.ContextWrapper {
        public TestContext() {
            super(null);
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {}

        @Override
        public Executor getMainExecutor() {
            return Runnable::run;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Empirical Challenger M3_2: LinuxManagerService State Transition Test ===");
        int failures = 0;

        failures += testInitialState();
        failures += testStartVmStateTransitionAndTokenGeneration();
        failures += testHandshakeCompletedTransitionToRunning();
        failures += testDuplicateHandshakeIdempotency();
        failures += testSpuriousHandshakeWhenStopped();
        failures += testSpuriousHandshakeWhenError();
        failures += testBootTimeoutTransitionToError();
        failures += testHandshakeAfterTimeoutIgnored();
        failures += testDisconnectTransitionToStopped();
        failures += testRestartVmAfterStop();
        failures += testHmacAuthTokenPayloadStructure();

        System.out.println("==========================================================================");
        if (failures == 0) {
            System.out.println("JAVA EMPIRICAL TEST RESULT: ALL STATE TRANSITION TESTS PASSED");
            System.exit(0);
        } else {
            System.err.println("JAVA EMPIRICAL TEST RESULT: " + failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private static int testInitialState() {
        System.out.print("[TEST 1] Initial VM State is STOPPED... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            if (service.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (Expected STATE_STOPPED, got " + service.getState() + ")");
                return 1;
            }
            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            e.printStackTrace(System.out);
            return 1;
        }
    }

    private static int testStartVmStateTransitionAndTokenGeneration() {
        System.out.print("[TEST 2] startVm() Transitions STOPPED -> STARTING & Schedules Timeout... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            LinuxManagerService.BinderService binder = service.getBinderService();

            AtomicInteger lastNewState = new AtomicInteger(-1);
            AtomicInteger lastOldState = new AtomicInteger(-1);

            binder.registerStatusCallback(new ILinuxStatusCallback.Stub() {
                @Override
                public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                    lastNewState.set(newState);
                    lastOldState.set(oldState);
                }

                @Override
                public void onResourceUsageUpdated(long ramBytes, long diskBytes, float cpuPercent) {}
            });

            boolean started = binder.startVm();
            if (!started) {
                System.out.println("FAILED (binder.startVm() returned false)");
                return 1;
            }

            if (service.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED (State is not STATE_STARTING, got " + service.getState() + ")");
                return 1;
            }

            if (lastNewState.get() != LinuxManager.STATE_STARTING || lastOldState.get() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (Status callback mismatch: old=" + lastOldState.get() + ", new=" + lastNewState.get() + ")");
                return 1;
            }

            // Attempting to start again when already STARTING must fail
            if (binder.startVm()) {
                System.out.println("FAILED (startVm() returned true when already STARTING)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testHandshakeCompletedTransitionToRunning() {
        System.out.print("[TEST 3] notifyVmStarted() Transitions STARTING -> RUNNING... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            LinuxManagerService.BinderService binder = service.getBinderService();

            AtomicInteger lastNewState = new AtomicInteger(-1);
            AtomicInteger lastOldState = new AtomicInteger(-1);

            binder.registerStatusCallback(new ILinuxStatusCallback.Stub() {
                @Override
                public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                    lastNewState.set(newState);
                    lastOldState.set(oldState);
                }

                @Override
                public void onResourceUsageUpdated(long ramBytes, long diskBytes, float cpuPercent) {}
            });

            binder.startVm();
            service.notifyVmStarted();

            if (service.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED (Expected STATE_RUNNING, got " + service.getState() + ")");
                return 1;
            }

            if (lastNewState.get() != LinuxManager.STATE_RUNNING || lastOldState.get() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED (Status callback mismatch: old=" + lastOldState.get() + ", new=" + lastNewState.get() + ")");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testDuplicateHandshakeIdempotency() {
        System.out.print("[TEST 4] Duplicate Handshake Notification is Idempotent... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            LinuxManagerService.BinderService binder = service.getBinderService();

            AtomicInteger changeCount = new AtomicInteger(0);

            binder.registerStatusCallback(new ILinuxStatusCallback.Stub() {
                @Override
                public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                    changeCount.incrementAndGet();
                }

                @Override
                public void onResourceUsageUpdated(long ramBytes, long diskBytes, float cpuPercent) {}
            });

            binder.startVm(); // count = 1 (STARTING)
            service.notifyVmStarted(); // count = 2 (RUNNING)
            service.notifyVmStarted(); // Duplicate call, should do nothing
            service.notifyVmStarted(); // Duplicate call, should do nothing

            if (service.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED (Expected STATE_RUNNING, got " + service.getState() + ")");
                return 1;
            }

            if (changeCount.get() != 2) {
                System.out.println("FAILED (Expected 2 state change broadcasts, got " + changeCount.get() + ")");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testSpuriousHandshakeWhenStopped() {
        System.out.print("[TEST 5] Spurious notifyVmStarted() when STOPPED is Ignored... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            if (service.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (Initial state not STOPPED)");
                return 1;
            }

            service.notifyVmStarted(); // Should be ignored

            if (service.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (State changed from STOPPED to " + service.getState() + ")");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testSpuriousHandshakeWhenError() {
        System.out.print("[TEST 6] Spurious notifyVmStarted() when ERROR is Ignored... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            LinuxManagerService.BinderService binder = service.getBinderService();

            binder.startVm();
            service.handleBootTimeout(); // Force state to STATE_ERROR

            if (service.getState() != LinuxManager.STATE_ERROR) {
                System.out.println("FAILED (State is not STATE_ERROR after boot timeout)");
                return 1;
            }

            service.notifyVmStarted(); // Late handshake arrival, should be ignored

            if (service.getState() != LinuxManager.STATE_ERROR) {
                System.out.println("FAILED (Late handshake changed ERROR state to " + service.getState() + ")");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testBootTimeoutTransitionToError() {
        System.out.print("[TEST 7] handleBootTimeout() Transitions STARTING -> ERROR... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            LinuxManagerService.BinderService binder = service.getBinderService();

            AtomicInteger lastNewState = new AtomicInteger(-1);
            AtomicInteger lastOldState = new AtomicInteger(-1);

            binder.registerStatusCallback(new ILinuxStatusCallback.Stub() {
                @Override
                public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                    lastNewState.set(newState);
                    lastOldState.set(oldState);
                }

                @Override
                public void onResourceUsageUpdated(long ramBytes, long diskBytes, float cpuPercent) {}
            });

            binder.startVm();
            service.handleBootTimeout();

            if (service.getState() != LinuxManager.STATE_ERROR) {
                System.out.println("FAILED (Expected STATE_ERROR, got " + service.getState() + ")");
                return 1;
            }

            if (lastNewState.get() != LinuxManager.STATE_ERROR || lastOldState.get() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED (Status callback mismatch: old=" + lastOldState.get() + ", new=" + lastNewState.get() + ")");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testHandshakeAfterTimeoutIgnored() {
        System.out.print("[TEST 8] Handshake Arriving After Boot Timeout is Ignored... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            LinuxManagerService.BinderService binder = service.getBinderService();

            binder.startVm();
            service.handleBootTimeout();

            // Simulate delayed guest handshake completion
            service.notifyVmStarted();

            if (service.getState() != LinuxManager.STATE_ERROR) {
                System.out.println("FAILED (State modified after timeout by late handshake)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testDisconnectTransitionToStopped() {
        System.out.print("[TEST 9] stopVm() Transitions RUNNING -> STOPPED... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            LinuxManagerService.BinderService binder = service.getBinderService();

            binder.startVm();
            service.notifyVmStarted();
            if (service.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED (Not in RUNNING state)");
                return 1;
            }

            binder.stopVm(false);

            if (service.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (Expected STATE_STOPPED, got " + service.getState() + ")");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testRestartVmAfterStop() {
        System.out.print("[TEST 10] Re-starting VM after STOPPED works cleanly... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            LinuxManagerService.BinderService binder = service.getBinderService();

            // Cycle 1
            binder.startVm();
            service.notifyVmStarted();
            binder.stopVm(true);
            if (service.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (Cycle 1 stop failed)");
                return 1;
            }

            // Cycle 2
            boolean started = binder.startVm();
            if (!started || service.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED (Cycle 2 startVm failed)");
                return 1;
            }
            service.notifyVmStarted();
            if (service.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED (Cycle 2 notifyVmStarted failed)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int testHmacAuthTokenPayloadStructure() {
        System.out.print("[TEST 11] generateHmacAuthToken() returns 64-byte payload (32-byte token + 32-byte secret)... ");
        try {
            TestContext ctx = new TestContext();
            LinuxManagerService service = new LinuxManagerService(ctx);
            byte[] payload = service.generateHmacAuthToken();

            if (payload == null || payload.length != 64) {
                System.out.println("FAILED (Payload length is not 64 bytes: " + (payload != null ? payload.length : "null") + ")");
                return 1;
            }

            // Ensure token (0..31) and secret (32..63) are non-zero random bytes
            boolean tokenNonZero = false;
            boolean secretNonZero = false;

            for (int i = 0; i < 32; i++) {
                if (payload[i] != 0) tokenNonZero = true;
                if (payload[i + 32] != 0) secretNonZero = true;
            }

            if (!tokenNonZero || !secretNonZero) {
                System.out.println("FAILED (Token or Secret bytes are all zeros)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            return 1;
        }
    }
}
