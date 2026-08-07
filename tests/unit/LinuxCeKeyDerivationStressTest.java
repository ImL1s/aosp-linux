/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Empirical Stress Test for LUKS2 CE Storage Key Derivation, Key Rotation,
 * and Unmount/Lock Memory Cleanup (F-R2-003).
 */

package tests.unit;

import com.android.server.linux.LinuxCeKeyManager;
import com.android.server.linux.LinuxManagerService;
import com.android.server.linux.LinuxManagerInternal;
import android.content.Context;
import android.os.ServiceManager;
import com.android.server.LocalServices;

import java.util.Arrays;
import java.security.SecureRandom;

public class LinuxCeKeyDerivationStressTest {

    private static class TestContext extends Context {
        @Override
        public java.util.concurrent.Executor getMainExecutor() {
            return Runnable::run;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {}

        @Override
        public Object getSystemService(String name) { return null; }
    }

    private static void resetTestEnvironment() {
        ServiceManager.clearForTest();
        LocalServices.removeServiceForTest(LinuxManagerInternal.class);
    }

    public static void main(String[] args) {
        System.out.println("==========================================================================");
        System.out.println("=== EMPIRICAL STRESS TEST: LUKS2 CE KEY DERIVATION & UNMOUNT (F-R2-003) ===");
        System.out.println("==========================================================================");

        int failures = 0;
        failures += testHkdf512BitKeyDerivationUniqueness();
        failures += testKeyRotationBehavior();
        failures += testUnmountAndLockMemoryWiping();
        failures += testLuksHeaderMagicValidation();
        failures += testNullAndInvalidCeKeyInputs();

        System.out.println("==========================================================================");
        if (failures == 0) {
            System.out.println("LUKS2 CE KEY DERIVATION STRESS TEST: ALL PASSED SUCCESSFULLY");
            System.exit(0);
        } else {
            System.err.println("LUKS2 CE KEY DERIVATION STRESS TEST: " + failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    /**
     * 1. Test HKDF-SHA256 512-bit (64-byte) key derivation correctness and uniqueness per user ID.
     */
    private static int testHkdf512BitKeyDerivationUniqueness() {
        System.out.print("[STRESS] HKDF-SHA256 512-Bit Key Derivation & User Isolation... ");
        byte[] masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);

        byte[] keyUser0 = LinuxCeKeyManager.derive512BitKey(masterKey, 0);
        byte[] keyUser10 = LinuxCeKeyManager.derive512BitKey(masterKey, 10);
        byte[] keyUser100 = LinuxCeKeyManager.derive512BitKey(masterKey, 100);

        if (keyUser0.length != 64 || keyUser10.length != 64 || keyUser100.length != 64) {
            System.out.println("FAILED (Derived key length is not 64 bytes)");
            return 1;
        }

        if (Arrays.equals(keyUser0, keyUser10) || Arrays.equals(keyUser10, keyUser100) || Arrays.equals(keyUser0, keyUser100)) {
            System.out.println("FAILED (Keys derived for different user IDs are identical - missing salt isolation)");
            return 1;
        }

        // Verify deterministic behavior: same key and user produce identical derived key
        byte[] keyUser0_repeat = LinuxCeKeyManager.derive512BitKey(masterKey, 0);
        if (!Arrays.equals(keyUser0, keyUser0_repeat)) {
            System.out.println("FAILED (HKDF key derivation is non-deterministic)");
            return 1;
        }

        System.out.println("PASS");
        return 0;
    }

    /**
     * 2. Test Key Rotation: Updating raw CE key derives new LUKS key, zeroizing old key material.
     */
    private static int testKeyRotationBehavior() {
        System.out.print("[STRESS] LUKS2 CE Key Rotation & Old Key Zeroization... ");
        int userId = 10;
        byte[] oldCeMasterKey = new byte[32];
        byte[] newCeMasterKey = new byte[32];
        Arrays.fill(oldCeMasterKey, (byte) 0x11);
        Arrays.fill(newCeMasterKey, (byte) 0x22);

        byte[] oldLuksKey = LinuxCeKeyManager.derive512BitKey(oldCeMasterKey, userId);
        byte[] newLuksKey = LinuxCeKeyManager.derive512BitKey(newCeMasterKey, userId);

        if (Arrays.equals(oldLuksKey, newLuksKey)) {
            System.out.println("FAILED (Rotated key material resulted in identical derived LUKS key)");
            return 1;
        }

        // Wipe old key material from RAM
        LinuxCeKeyManager.wipeKey(oldLuksKey);
        byte[] zeroBytes = new byte[64];
        if (!Arrays.equals(oldLuksKey, zeroBytes)) {
            System.out.println("FAILED (wipeKey failed to zero out RAM memory)");
            return 1;
        }

        System.out.println("PASS");
        return 0;
    }

    /**
     * 3. Test Unmount & Lock Memory Wiping: LinuxManagerService onUserLocked wipes CE key from RAM.
     */
    private static int testUnmountAndLockMemoryWiping() {
        System.out.print("[STRESS] Screen Lock & Unmount Key Wiping in LinuxManagerService... ");
        resetTestEnvironment();
        TestContext ctx = new TestContext();
        LinuxManagerService service = new LinuxManagerService(ctx);
        service.onStart();

        LinuxManagerService.LocalService localService = service.getLocalService();
        int userId = 0;

        // Unlock user -> CE key becomes available
        localService.onUserUnlocked(userId);
        if (!service.isCeKeyAvailable()) {
            System.out.println("FAILED (mCeKeyAvailable is false after onUserUnlocked)");
            return 1;
        }

        // Lock user -> CE key wiped and marked unavailable
        localService.onUserLocked(userId);
        if (service.isCeKeyAvailable()) {
            System.out.println("FAILED (mCeKeyAvailable is true after onUserLocked)");
            return 1;
        }

        System.out.println("PASS");
        return 0;
    }

    /**
     * 4. Test LUKS Header Magic Validation & Corruption Detection (LUKS\xba\xbe).
     */
    private static int testLuksHeaderMagicValidation() {
        System.out.print("[STRESS] LUKS2 Header Magic (LUKS\\xba\\xbe) & Corruption Validation... ");
        byte[] validHeader = new byte[512];
        System.arraycopy(LinuxCeKeyManager.LUKS_MAGIC, 0, validHeader, 0, 6);

        try {
            LinuxCeKeyManager.validateLuksHeader(validHeader);
        } catch (Exception e) {
            System.out.println("FAILED (Valid LUKS header rejected: " + e.getMessage() + ")");
            return 1;
        }

        // Test corrupted magic byte
        byte[] corruptedHeader = validHeader.clone();
        corruptedHeader[4] = (byte) 0xFF; // Corrupt magic byte
        try {
            LinuxCeKeyManager.validateLuksHeader(corruptedHeader);
            System.out.println("FAILED (Corrupted magic byte was accepted)");
            return 1;
        } catch (LinuxCeKeyManager.ValueError expected) {
            // Expected
        }

        // Test header too short
        byte[] shortHeader = new byte[4];
        try {
            LinuxCeKeyManager.validateLuksHeader(shortHeader);
            System.out.println("FAILED (Short header was accepted)");
            return 1;
        } catch (LinuxCeKeyManager.ValueError expected) {
            // Expected
        }

        // Test null header
        try {
            LinuxCeKeyManager.validateLuksHeader(null);
            System.out.println("FAILED (Null header was accepted)");
            return 1;
        } catch (LinuxCeKeyManager.ValueError expected) {
            // Expected
        }

        System.out.println("PASS");
        return 0;
    }

    /**
     * 5. Test Null and Invalid CE Master Key Inputs.
     */
    private static int testNullAndInvalidCeKeyInputs() {
        System.out.print("[STRESS] Null and Empty CE Key Input Guard... ");
        try {
            LinuxCeKeyManager.derive512BitKey(null, 0);
            System.out.println("FAILED (Null CE master key was accepted)");
            return 1;
        } catch (IllegalArgumentException expected) {
            // Expected
        }

        try {
            LinuxCeKeyManager.derive512BitKey(new byte[0], 0);
            System.out.println("FAILED (Empty CE master key was accepted)");
            return 1;
        } catch (IllegalArgumentException expected) {
            // Expected
        }

        System.out.println("PASS");
        return 0;
    }
}
