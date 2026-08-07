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
import com.android.server.SystemServer;
import com.android.server.linux.LinuxManagerInternal;
import com.android.server.linux.LinuxManagerService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class LinuxManagerServiceTest {

    private static class TestContext extends Context {
        private String mLastEnforcedPermission = null;

        @Override
        public Object getSystemService(String name) {
            return SystemServiceRegistryFetcher(name);
        }

        private Object SystemServiceRegistryFetcher(String name) {
            if (Context.LINUX_SERVICE.equals(name)) {
                android.os.IBinder b = ServiceManager.getService(Context.LINUX_SERVICE);
                if (b != null) {
                    return new LinuxManager(this, ILinuxManager.Stub.asInterface(b));
                }
            }
            return null;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            mLastEnforcedPermission = permission;
        }

        @Override
        public Executor getMainExecutor() {
            return Runnable::run;
        }
    }

    private static void resetTestEnvironment() {
        ServiceManager.clearForTest();
        LocalServices.removeServiceForTest(LinuxManagerInternal.class);
    }

    public static void main(String[] args) {
        System.out.println("=== Starting M1 LinuxManagerService Test Suite ===");
        int failures = 0;

        failures += testSystemServerRegistration();
        failures += testStateTransitionsNormalLifecycle();
        failures += testBootTimeoutGuard();
        failures += testStatusCallbacks();
        failures += testAppListingAndTerminalSession();
        failures += testPtyDataCallbackDispatching();
        failures += testPermissionEnforcement();

        System.out.println("==================================================");
        if (failures == 0) {
            System.out.println("JAVA TEST RESULT: ALL TESTS PASSED SUCCESSFULLY");
            System.exit(0);
        } else {
            System.err.println("JAVA TEST RESULT: " + failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private static int testSystemServerRegistration() {
        System.out.print("[TEST] SystemServer Registration... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        SystemServer systemServer = new SystemServer(ctx);
        systemServer.startOtherServices();

        if (ServiceManager.getService(Context.LINUX_SERVICE) == null) {
            System.out.println("FAILED (ServiceManager missing LINUX_SERVICE)");
            return 1;
        }

        LinuxManagerInternal internalService = LocalServices.getService(LinuxManagerInternal.class);
        if (internalService == null) {
            System.out.println("FAILED (LocalServices missing LinuxManagerInternal)");
            return 1;
        }

        System.out.println("PASS");
        return 0;
    }

    private static int testStateTransitionsNormalLifecycle() {
        System.out.print("[TEST] State Machine Normal Lifecycle (OFF -> STARTING -> RUNNING -> SUSPENDED -> RUNNING -> STOPPED)... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();

        ILinuxManager.Stub binder = service.getBinderService();

        try {
            if (binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (Initial state is not STOPPED)");
                return 1;
            }

            if (!binder.startVm() || binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED (Transition to STARTING failed)");
                return 1;
            }

            service.notifyVmStarted();
            if (binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED (Transition to RUNNING failed)");
                return 1;
            }

            if (!binder.suspendVm() || binder.getState() != LinuxManager.STATE_SUSPENDED) {
                System.out.println("FAILED (Transition to SUSPENDED failed)");
                return 1;
            }

            if (!binder.resumeVm() || binder.getState() != LinuxManager.STATE_RUNNING) {
                System.out.println("FAILED (Transition to RUNNING after resume failed)");
                return 1;
            }

            if (!binder.stopVm(false) || binder.getState() != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (Transition to STOPPED failed)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testBootTimeoutGuard() {
        System.out.print("[TEST] 15-Second Boot Timeout Guard (STARTING -> ERROR)... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();

        ILinuxManager.Stub binder = service.getBinderService();

        try {
            binder.startVm();
            if (binder.getState() != LinuxManager.STATE_STARTING) {
                System.out.println("FAILED (Not in STARTING state)");
                return 1;
            }

            // Manually trigger boot timeout handler to verify timeout transition logic
            service.handleBootTimeout();

            if (binder.getState() != LinuxManager.STATE_ERROR) {
                System.out.println("FAILED (State did not transition to ERROR on boot timeout, got: " + binder.getState() + ")");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testStatusCallbacks() {
        System.out.print("[TEST] Status Callback Registration & Notifications... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();

        ILinuxManager.Stub binder = service.getBinderService();
        List<Integer> receivedStates = new ArrayList<>();

        ILinuxStatusCallback callback = new ILinuxStatusCallback.Stub() {
            @Override
            public void onStateChanged(int newState, int oldState, int reasonCode, String message) {
                receivedStates.add(newState);
            }

            @Override
            public void onResourceUsageUpdated(long memoryUsedBytes, long memoryTotalBytes, float cpuUsagePercent) {}
        };

        try {
            binder.registerStatusCallback(callback);
            binder.startVm();
            service.notifyVmStarted();
            binder.stopVm(false);

            if (receivedStates.size() != 3 ||
                receivedStates.get(0) != LinuxManager.STATE_STARTING ||
                receivedStates.get(1) != LinuxManager.STATE_RUNNING ||
                receivedStates.get(2) != LinuxManager.STATE_STOPPED) {
                System.out.println("FAILED (Callbacks received mismatch: " + receivedStates + ")");
                return 1;
            }

            binder.unregisterStatusCallback(callback);
            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testAppListingAndTerminalSession() {
        System.out.print("[TEST] App Listing & Terminal Session Management... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();

        ILinuxManager.Stub binder = service.getBinderService();

        try {
            List<LinuxAppInfo> apps = binder.getInstalledApps();
            if (apps == null || apps.isEmpty()) {
                System.out.println("FAILED (Installed apps list empty)");
                return 1;
            }

            boolean launchResult = binder.launchLinuxApp(apps.get(0).getAppId(), 0);
            if (!launchResult) {
                System.out.println("FAILED (launchLinuxApp returned false)");
                return 1;
            }

            String sessionId = binder.createTerminalSession(80, 24, null);
            if (sessionId == null || sessionId.isEmpty()) {
                System.out.println("FAILED (createTerminalSession returned empty sessionId)");
                return 1;
            }

            binder.resizeTerminalSession(sessionId, 100, 40);
            binder.writeTerminalInput(sessionId, "ls -l\n".getBytes());
            binder.closeTerminalSession(sessionId);

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testPtyDataCallbackDispatching() {
        System.out.print("[TEST] PTY Data Callback Dispatching to ILinuxTerminalCallback... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();

        ILinuxManager.Stub binder = service.getBinderService();

        try {
            final boolean[] dataReceived = new boolean[]{false};
            final String[] receivedSessionId = new String[1];
            final byte[][] receivedPayload = new byte[1][];

            android.system.linux.ILinuxTerminalCallback.Stub callback = new android.system.linux.ILinuxTerminalCallback.Stub() {
                @Override
                public void onDataReceived(String sessionId, byte[] data) {
                    dataReceived[0] = true;
                    receivedSessionId[0] = sessionId;
                    receivedPayload[0] = data;
                }

                @Override
                public void onTitleChanged(String sessionId, String title) {}

                @Override
                public void onBell(String sessionId) {}

                @Override
                public void onSessionClosed(String sessionId, int exitCode) {}
            };

            String sessionId = binder.createTerminalSession(80, 24, callback);

            java.lang.reflect.Field bridgeField = LinuxManagerService.class.getDeclaredField("mBridgeService");
            bridgeField.setAccessible(true);
            Object bridgeService = bridgeField.get(service);

            java.lang.reflect.Field callbackField = com.android.server.linux.LinuxBridgeService.class.getDeclaredField("mCallback");
            callbackField.setAccessible(true);
            com.android.server.linux.LinuxBridgeService.LinuxBridgeCallback bridgeCallback =
                    (com.android.server.linux.LinuxBridgeService.LinuxBridgeCallback) callbackField.get(bridgeService);

            byte[] samplePtyData = "user@debian:~$ ls -l\n".getBytes();
            bridgeCallback.onPtyDataReceived(sessionId, samplePtyData);

            if (!dataReceived[0]) {
                System.out.println("FAILED (Terminal callback onDataReceived was not invoked)");
                return 1;
            }

            if (!sessionId.equals(receivedSessionId[0])) {
                System.out.println("FAILED (Session ID mismatch in PTY callback)");
                return 1;
            }

            if (!java.util.Arrays.equals(samplePtyData, receivedPayload[0])) {
                System.out.println("FAILED (Payload mismatch in PTY callback)");
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

    private static int testPermissionEnforcement() {
        System.out.print("[TEST] MANAGE_LINUX_ENVIRONMENT & USE_LINUX_TERMINAL Permission Enforcement... ");
        resetTestEnvironment();

        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();

        ILinuxManager.Stub binder = service.getBinderService();

        try {
            binder.startVm();
            if (!"android.permission.MANAGE_LINUX_ENVIRONMENT".equals(ctx.mLastEnforcedPermission)) {
                System.out.println("FAILED (Permission MANAGE_LINUX_ENVIRONMENT was not enforced for startVm)");
                return 1;
            }

            binder.createTerminalSession(80, 24, null);
            if (!"android.permission.USE_LINUX_TERMINAL".equals(ctx.mLastEnforcedPermission)) {
                System.out.println("FAILED (Permission USE_LINUX_TERMINAL was not enforced for createTerminalSession)");
                return 1;
            }

            binder.resizeTerminalSession("session_test", 100, 40);
            if (!"android.permission.USE_LINUX_TERMINAL".equals(ctx.mLastEnforcedPermission)) {
                System.out.println("FAILED (Permission USE_LINUX_TERMINAL was not enforced for resizeTerminalSession)");
                return 1;
            }

            binder.writeTerminalInput("session_test", "ls\n".getBytes());
            if (!"android.permission.USE_LINUX_TERMINAL".equals(ctx.mLastEnforcedPermission)) {
                System.out.println("FAILED (Permission USE_LINUX_TERMINAL was not enforced for writeTerminalInput)");
                return 1;
            }

            binder.closeTerminalSession("session_test");
            if (!"android.permission.USE_LINUX_TERMINAL".equals(ctx.mLastEnforcedPermission)) {
                System.out.println("FAILED (Permission USE_LINUX_TERMINAL was not enforced for closeTerminalSession)");
                return 1;
            }

            binder.getInstalledApps();
            if (!"android.permission.USE_LINUX_TERMINAL".equals(ctx.mLastEnforcedPermission)) {
                System.out.println("FAILED (Permission USE_LINUX_TERMINAL was not enforced for getInstalledApps)");
                return 1;
            }

            binder.launchLinuxApp("org.gnome.Terminal", 0);
            if (!"android.permission.USE_LINUX_TERMINAL".equals(ctx.mLastEnforcedPermission)) {
                System.out.println("FAILED (Permission USE_LINUX_TERMINAL was not enforced for launchLinuxApp)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }
}
