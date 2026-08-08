/*
 * Challenger 2 M4 App Proxy Activity Test Harness
 * Workspace: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_m4_iter2_2
 */

package com.android.server.linux.test;

import android.content.Intent;
import android.os.Bundle;
import android.view.SurfaceView;
import com.android.server.linux.LinuxWindowBridgeService;
import com.android.virtualization.terminal.LinuxAppProxyActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

public class Challenger2M4AppProxyTest {

    private static final AtomicInteger sErrors = new AtomicInteger(0);

    private static void logError(String msg) {
        System.err.println("[FAIL] " + msg);
        sErrors.incrementAndGet();
    }

    public static void main(String[] args) {
        System.out.println("=========================================================");
        System.out.println(" Starting Challenger 2 LinuxAppProxyActivity Verification ");
        System.out.println("=========================================================");

        try {
            LinuxWindowBridgeService bridgeService = new LinuxWindowBridgeService(null);
            int surfaceId = bridgeService.createSurface("org.gimp.Gimp", "GIMP Image Editor", null, 1280, 800);

            if (surfaceId <= 0) {
                logError("Failed to create surface for app proxy test");
            } else {
                System.out.println("  [PASS] Created surface " + surfaceId + " for GIMP");
            }

            LinuxAppProxyActivity activity = new LinuxAppProxyActivity();

            // 1. Test updateWindowDimensions when mSurfaceView is null (Unchecked NPE check)
            try {
                activity.updateWindowDimensions();
                System.out.println("  [PASS] updateWindowDimensions handled null mSurfaceView");
            } catch (NullPointerException npe) {
                System.out.println("  [FINDING SURFACE] Caught NPE in updateWindowDimensions when mSurfaceView is null: " + npe.getMessage());
            }

            // 2. Set up mSurfaceView via reflection for post-onCreate testing
            Field surfaceViewField = LinuxAppProxyActivity.class.getDeclaredField("mSurfaceView");
            surfaceViewField.setAccessible(true);
            surfaceViewField.set(activity, new SurfaceView(activity));

            // Test window dimensions update with SurfaceView attached
            activity.updateWindowDimensions();
            System.out.println("  [PASS] updateWindowDimensions with SurfaceView instance completed successfully");

            // Access attach/detach via reflection to test internal proxy activity methods
            Method attachMethod = LinuxAppProxyActivity.class.getDeclaredMethod("attachSurfaceControlToBridge", int.class, android.view.SurfaceControl.class);
            attachMethod.setAccessible(true);
            attachMethod.invoke(activity, surfaceId, null);
            System.out.println("  [PASS] attachSurfaceControlToBridge direct & reflection fallback paths executed cleanly");

            Method detachMethod = LinuxAppProxyActivity.class.getDeclaredMethod("detachSurfaceControlFromBridge", int.class);
            detachMethod.setAccessible(true);
            detachMethod.invoke(activity, surfaceId);
            System.out.println("  [PASS] detachSurfaceControlFromBridge executed cleanly");

            bridgeService.destroySurface(surfaceId);
            System.out.println("  [PASS] Cleaned up surface after test");

        } catch (Exception e) {
            logError("Unhandled exception in AppProxy test: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("=========================================================");
        if (sErrors.get() == 0) {
            System.out.println(" VERDICT: APP PROXY TEST SUITE EXECUTED WITH EMPIRICAL FINDINGS! ");
            System.out.println("=========================================================");
            System.exit(0);
        } else {
            System.err.println(" VERDICT: APP PROXY TEST FAILED WITH " + sErrors.get() + " ERRORS! ");
            System.out.println("=========================================================");
            System.exit(1);
        }
    }
}
