package tests.unit;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import com.android.server.LocalServices;
import com.android.server.linux.LinuxManagerInternal;
import com.android.server.linux.storage.LinuxStorageProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Challenger 2 Empirical Test for Milestone M5 Iteration 2:
 * Verifies LinuxStorageProvider SAF provider behavior:
 * 1. Rejection of queries when VM is stopped or LUKS2 CE key locked.
 * 2. Read-only vs read-write mount flag behavior.
 * 3. ContentResolver root URI notification on state transitions.
 * 4. Security & System root path traversal rejection.
 */
public class ChallengerM5Iter2LinuxStorageProviderTest {

    private static class TestContentResolver extends ContentResolver {
        private int mNotifyCount = 0;

        @Override
        public void notifyChange(Uri uri, Object observer) {
            mNotifyCount++;
        }

        public int getNotifyCount() {
            return mNotifyCount;
        }

        public void clear() {
            mNotifyCount = 0;
        }
    }

    private static class TestContext extends Context {
        private final TestContentResolver mContentResolver = new TestContentResolver();

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public Object getSystemService(String name) {
            return null;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {}

        @Override
        public Executor getMainExecutor() {
            return null;
        }
    }

    private static class FakeLinuxManagerInternal extends LinuxManagerInternal {
        private boolean mVmRunning = true;
        private boolean mCeKeyAvailable = true;
        private boolean mIsReadOnlyMount = false;
        private StorageStateListener mListener;

        public void setVmRunning(boolean running) {
            mVmRunning = running;
        }

        public void setCeKeyAvailable(boolean available) {
            mCeKeyAvailable = available;
        }

        public void setReadOnlyMount(boolean readOnly) {
            mIsReadOnlyMount = readOnly;
        }

        @Override
        public boolean isVmRunning() {
            return mVmRunning;
        }

        @Override
        public int getVmState() {
            return mVmRunning ? 2 /* STATE_RUNNING */ : 0 /* STATE_STOPPED */;
        }

        @Override
        public void onUserUnlocked(int userId) {
            mCeKeyAvailable = true;
        }

        @Override
        public boolean isCeKeyAvailable() {
            return mCeKeyAvailable;
        }

        @Override
        public boolean isReadOnlyMount() {
            return mIsReadOnlyMount;
        }

        @Override
        public void registerStorageStateListener(StorageStateListener listener) {
            mListener = listener;
        }

        @Override
        public void unregisterStorageStateListener(StorageStateListener listener) {
            if (mListener == listener) {
                mListener = null;
            }
        }

        public StorageStateListener getListener() {
            return mListener;
        }

        public void triggerVmStateChanged(int newState, int oldState) {
            if (mListener != null) {
                mListener.onVmStateChanged(newState, oldState);
            }
        }

        public void triggerCeKeyStatusChanged(boolean available) {
            if (mListener != null) {
                mListener.onCeKeyStatusChanged(available);
            }
        }

        public void triggerStorageMountChanged(boolean isReadOnly) {
            if (mListener != null) {
                mListener.onStorageMountChanged(isReadOnly);
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("==========================================================");
        System.out.println(" CHALLENGER 2 EMPIRICAL TEST: LinuxStorageProvider (R5)   ");
        System.out.println("==========================================================");

        int totalTests = 0;
        int passedTests = 0;
        int failedTests = 0;

        FakeLinuxManagerInternal fakeLmi = new FakeLinuxManagerInternal();
        LocalServices.addService(LinuxManagerInternal.class, fakeLmi);

        TestContext testContext = new TestContext();
        LinuxStorageProvider provider = new LinuxStorageProvider();
        provider.attachInfo(testContext, null);
        provider.onCreate();

        // ---------------------------------------------------------------------
        // OBJECTIVE 1: Rejection of queries when VM is stopped or LUKS2 CE key locked
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 1.1] Verifying SAF query rejection when Linux VM is powered off...");
        fakeLmi.setVmRunning(false);
        fakeLmi.setCeKeyAvailable(true);

        boolean queryRootsRejected = false;
        try {
            provider.queryRoots(null);
        } catch (LinuxStorageProvider.ConnectionError e) {
            queryRootsRejected = true;
        }

        boolean queryDocRejected = false;
        try {
            provider.queryDocument("home/user", null);
        } catch (LinuxStorageProvider.ConnectionError e) {
            queryDocRejected = true;
        }

        boolean queryChildRejected = false;
        try {
            provider.queryChildDocuments("home/user", null, null);
        } catch (LinuxStorageProvider.ConnectionError e) {
            queryChildRejected = true;
        }

        boolean openDocRejected = false;
        try {
            provider.openDocument("home/user/file.txt", "r", null);
        } catch (LinuxStorageProvider.ConnectionError e) {
            openDocRejected = true;
        }

        if (queryRootsRejected && queryDocRejected && queryChildRejected && openDocRejected) {
            System.out.println("  [PASS] All 4 SAF methods correctly rejected query with ConnectionError when VM is offline.");
            passedTests++;
        } else {
            System.err.println("  [FAIL] VM offline rejection failed! queryRoots=" + queryRootsRejected 
                    + ", queryDoc=" + queryDocRejected + ", queryChild=" + queryChildRejected + ", openDoc=" + openDocRejected);
            failedTests++;
        }

        totalTests++;
        System.out.println("\n[TEST 1.2] Verifying SAF query rejection when LUKS2 CE key is locked...");
        fakeLmi.setVmRunning(true);
        fakeLmi.setCeKeyAvailable(false);

        boolean queryRootsCeRejected = false;
        try {
            provider.queryRoots(null);
        } catch (LinuxStorageProvider.PermissionError e) {
            queryRootsCeRejected = true;
        }

        boolean queryDocCeRejected = false;
        try {
            provider.queryDocument("home/user", null);
        } catch (LinuxStorageProvider.PermissionError e) {
            queryDocCeRejected = true;
        }

        boolean queryChildCeRejected = false;
        try {
            provider.queryChildDocuments("home/user", null, null);
        } catch (LinuxStorageProvider.PermissionError e) {
            queryChildCeRejected = true;
        }

        boolean openDocCeRejected = false;
        try {
            provider.openDocument("home/user/file.txt", "r", null);
        } catch (LinuxStorageProvider.PermissionError e) {
            openDocCeRejected = true;
        }

        if (queryRootsCeRejected && queryDocCeRejected && queryChildCeRejected && openDocCeRejected) {
            System.out.println("  [PASS] All 4 SAF methods correctly rejected query with PermissionError when LUKS2 CE volume is locked.");
            passedTests++;
        } else {
            System.err.println("  [FAIL] LUKS2 CE key locked rejection failed! queryRoots=" + queryRootsCeRejected 
                    + ", queryDoc=" + queryDocCeRejected + ", queryChild=" + queryChildCeRejected + ", openDoc=" + openDocCeRejected);
            failedTests++;
        }

        // Restore normal running state
        fakeLmi.setVmRunning(true);
        fakeLmi.setCeKeyAvailable(true);

        // ---------------------------------------------------------------------
        // OBJECTIVE 2: Read-only vs read-write mount flag behavior
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 2.1] Verifying Read-Only mount flag enforcement in SAF openDocument write modes...");
        fakeLmi.setReadOnlyMount(true);

        String[] writeModes = new String[]{"w", "wt", "wa", "rw", "rwt"};
        int blockedWriteModes = 0;
        for (String mode : writeModes) {
            try {
                provider.openDocument("home/user/test.txt", mode, null);
                System.err.println("  [ERROR] openDocument allowed write mode '" + mode + "' on read-only mount!");
            } catch (SecurityException e) {
                if (e.getMessage() != null && e.getMessage().contains("Storage is mounted read-only")) {
                    blockedWriteModes++;
                }
            } catch (Exception e) {
                // Ignore other file-not-found issues if read-only check passed
            }
        }

        if (blockedWriteModes == writeModes.length) {
            System.out.println("  [PASS] All " + writeModes.length + " write modes correctly blocked with SecurityException on read-only mount.");
            passedTests++;
        } else {
            System.err.println("  [FAIL] Read-only mount write mode blocking failed! Blocked " + blockedWriteModes + "/" + writeModes.length);
            failedTests++;
        }

        totalTests++;
        System.out.println("\n[TEST 2.2] Verifying queryDocument & queryChildDocuments return valid cursors under Read-Only vs Read-Write...");
        fakeLmi.setReadOnlyMount(true);
        Cursor roCursor = provider.queryDocument("home/user", null);
        Cursor roChildCursor = provider.queryChildDocuments("home/user", null, null);

        fakeLmi.setReadOnlyMount(false);
        Cursor rwCursor = provider.queryDocument("home/user", null);
        Cursor rwChildCursor = provider.queryChildDocuments("home/user", null, null);

        if (roCursor != null && roChildCursor != null && rwCursor != null && rwChildCursor != null) {
            System.out.println("  [PASS] queryDocument and queryChildDocuments executed successfully in read-only and read-write modes.");
            passedTests++;
        } else {
            System.err.println("  [FAIL] Query document returned null cursor.");
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // OBJECTIVE 3: ContentResolver root URI notification on state transitions
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 3.1] Verifying ContentResolver root URI notification on VM/CE/Mount state transitions...");
        TestContentResolver resolver = testContext.mContentResolver;
        resolver.clear();

        fakeLmi.triggerVmStateChanged(2, 0);
        boolean vmNotifyPassed = (resolver.getNotifyCount() == 1);

        resolver.clear();
        fakeLmi.triggerCeKeyStatusChanged(true);
        boolean ceNotifyPassed = (resolver.getNotifyCount() == 1);

        resolver.clear();
        fakeLmi.triggerStorageMountChanged(true);
        boolean mountNotifyPassed = (resolver.getNotifyCount() == 1);

        resolver.clear();
        String testDocUri = "content://com.android.linux.storage/document/home/user/sample.txt";
        provider.notifyDocumentChanged(testDocUri);
        boolean docNotifyPassed = (resolver.getNotifyCount() == 1)
                && provider.getNotificationUris().contains(testDocUri);

        if (vmNotifyPassed && ceNotifyPassed && mountNotifyPassed && docNotifyPassed) {
            System.out.println("  [PASS] ContentResolver notifyChange triggered correctly for all 4 state transition & document update events.");
            passedTests++;
        } else {
            System.err.println("  [FAIL] Notification verification failed! vmNotify=" + vmNotifyPassed 
                    + ", ceNotify=" + ceNotifyPassed + ", mountNotify=" + mountNotifyPassed + ", docNotify=" + docNotifyPassed);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // OBJECTIVE 4: System Root & Path Traversal Security Checks
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 4.1] Verifying Path Traversal and System Root Security Exceptions...");
        String[] maliciousPaths = new String[]{
            "/etc",
            "/sys",
            "/proc",
            "/dev",
            "etc/passwd",
            "/etc/shadow",
            "home/user/../../etc/shadow",
            "mnt/shared/../../../sys"
        };

        int blockedCount = 0;
        for (String malPath : maliciousPaths) {
            try {
                provider.queryDocument(malPath, null);
                System.err.println("  [SECURITY BUG] System path traversal permitted: " + malPath);
            } catch (SecurityException e) {
                blockedCount++;
            } catch (Exception e) {
                // Other unexpected exception
            }
        }

        if (blockedCount == maliciousPaths.length) {
            System.out.println("  [PASS] All " + maliciousPaths.length + " system root & path traversal attempts blocked with SecurityException.");
            passedTests++;
        } else {
            System.err.println("  [FAIL] Security path traversal check failed! Blocked " + blockedCount + "/" + maliciousPaths.length);
            failedTests++;
        }

        // Clean up
        LocalServices.removeServiceForTest(LinuxManagerInternal.class);

        System.out.println("\n==========================================================");
        System.out.println("   CHALLENGER 2 SUMMARY: " + passedTests + " PASSED, " + failedTests + " FAILED out of " + totalTests + " TESTS.");
        System.out.println("==========================================================");

        if (failedTests > 0) {
            System.exit(1);
        }
    }
}
