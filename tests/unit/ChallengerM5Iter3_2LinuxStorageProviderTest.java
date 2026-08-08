package tests.unit;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import com.android.server.LocalServices;
import com.android.server.linux.LinuxManagerInternal;
import com.android.server.linux.storage.LinuxStorageProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Challenger 2 Empirical Test Suite for Milestone M5 Iteration 3:
 * Comprehensive stress and empirical verification of LinuxStorageProvider SAF behavior.
 */
public class ChallengerM5Iter3_2LinuxStorageProviderTest {

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
        public java.util.concurrent.Executor getMainExecutor() {
            return null;
        }
    }

    private static class FakeLinuxManagerInternal extends LinuxManagerInternal {
        private volatile boolean mVmRunning = true;
        private volatile boolean mCeKeyAvailable = true;
        private volatile boolean mIsReadOnlyMount = false;
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

    public static void main(String[] args) throws Exception {
        System.out.println("==========================================================================");
        System.out.println(" CHALLENGER 2 M5 ITER 3 EMPIRICAL SUITE: LinuxStorageProvider SAF Testing ");
        System.out.println("==========================================================================");

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
        // 1. REJECTION WHEN VM IS STOPPED
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 1] Rejection of SAF operations when VM is STOPPED...");
        fakeLmi.setVmRunning(false);
        fakeLmi.setCeKeyAvailable(true);

        boolean queryRootsConnErr = false;
        try { provider.queryRoots(null); } catch (LinuxStorageProvider.ConnectionError e) { queryRootsConnErr = true; }

        boolean queryDocConnErr = false;
        try { provider.queryDocument("home/user", null); } catch (LinuxStorageProvider.ConnectionError e) { queryDocConnErr = true; }

        boolean queryChildConnErr = false;
        try { provider.queryChildDocuments("home/user", null, null); } catch (LinuxStorageProvider.ConnectionError e) { queryChildConnErr = true; }

        boolean openDocReadConnErr = false;
        try { provider.openDocument("home/user/test.txt", "r", null); } catch (LinuxStorageProvider.ConnectionError e) { openDocReadConnErr = true; }

        boolean openDocWriteConnErr = false;
        try { provider.openDocument("home/user/test.txt", "w", null); } catch (LinuxStorageProvider.ConnectionError e) { openDocWriteConnErr = true; }

        if (queryRootsConnErr && queryDocConnErr && queryChildConnErr && openDocReadConnErr && openDocWriteConnErr) {
            System.out.println("  PASS: All 5 SAF operations correctly rejected with ConnectionError when VM is powered off.");
            passedTests++;
        } else {
            System.err.println("  FAIL: VM stopped rejection failed! queryRoots=" + queryRootsConnErr + ", queryDoc=" + queryDocConnErr +
                    ", queryChild=" + queryChildConnErr + ", openRead=" + openDocReadConnErr + ", openWrite=" + openDocWriteConnErr);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // 2. REJECTION WHEN LUKS2 CE KEY IS LOCKED
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 2] Rejection of SAF operations when LUKS2 CE Key is LOCKED...");
        fakeLmi.setVmRunning(true);
        fakeLmi.setCeKeyAvailable(false);

        boolean queryRootsPermErr = false;
        try { provider.queryRoots(null); } catch (LinuxStorageProvider.PermissionError e) { queryRootsPermErr = true; }

        boolean queryDocPermErr = false;
        try { provider.queryDocument("home/user", null); } catch (LinuxStorageProvider.PermissionError e) { queryDocPermErr = true; }

        boolean queryChildPermErr = false;
        try { provider.queryChildDocuments("home/user", null, null); } catch (LinuxStorageProvider.PermissionError e) { queryChildPermErr = true; }

        boolean openDocReadPermErr = false;
        try { provider.openDocument("home/user/test.txt", "r", null); } catch (LinuxStorageProvider.PermissionError e) { openDocReadPermErr = true; }

        boolean openDocWritePermErr = false;
        try { provider.openDocument("home/user/test.txt", "w", null); } catch (LinuxStorageProvider.PermissionError e) { openDocWritePermErr = true; }

        if (queryRootsPermErr && queryDocPermErr && queryChildPermErr && openDocReadPermErr && openDocWritePermErr) {
            System.out.println("  PASS: All 5 SAF operations correctly rejected with PermissionError when LUKS2 CE storage is locked.");
            passedTests++;
        } else {
            System.err.println("  FAIL: CE key locked rejection failed! queryRoots=" + queryRootsPermErr + ", queryDoc=" + queryDocPermErr +
                    ", queryChild=" + queryChildPermErr + ", openRead=" + openDocReadPermErr + ", openWrite=" + openDocWritePermErr);
            failedTests++;
        }

        // Restore active VM and CE key state
        fakeLmi.setVmRunning(true);
        fakeLmi.setCeKeyAvailable(true);

        // ---------------------------------------------------------------------
        // 3. READ-ONLY VS READ-WRITE MOUNT FLAG BEHAVIOR
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 3.1] Read-Only Mount: Write Modes in openDocument are blocked...");
        fakeLmi.setReadOnlyMount(true);

        String[] writeModes = new String[]{"w", "wt", "wa", "rw", "rwt"};
        int blockedWriteModes = 0;
        for (String mode : writeModes) {
            try {
                provider.openDocument("home/user/test.txt", mode, null);
            } catch (SecurityException e) {
                if (e.getMessage() != null && e.getMessage().contains("Storage is mounted read-only")) {
                    blockedWriteModes++;
                }
            } catch (Exception ignored) {}
        }

        if (blockedWriteModes == writeModes.length) {
            System.out.println("  PASS: All write modes ('w', 'wt', 'wa', 'rw', 'rwt') blocked with SecurityException under read-only mount.");
            passedTests++;
        } else {
            System.err.println("  FAIL: Read-only write mode blocking failed! Blocked " + blockedWriteModes + "/" + writeModes.length);
            failedTests++;
        }

        totalTests++;
        System.out.println("\n[TEST 3.2] Query Document & Child Documents under Read-Only vs Read-Write mount...");
        fakeLmi.setReadOnlyMount(true);
        Cursor roDocCursor = provider.queryDocument("home/user", null);
        Cursor roChildCursor = provider.queryChildDocuments("home/user", null, null);

        fakeLmi.setReadOnlyMount(false);
        Cursor rwDocCursor = provider.queryDocument("home/user", null);
        Cursor rwChildCursor = provider.queryChildDocuments("home/user", null, null);

        if (roDocCursor != null && roChildCursor != null && rwDocCursor != null && rwChildCursor != null) {
            System.out.println("  PASS: queryDocument and queryChildDocuments executed cleanly for both Read-Only and Read-Write mounts.");
            passedTests++;
        } else {
            System.err.println("  FAIL: Cursor returned null during mount mode queries.");
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // 4. CONTENTRESOLVER NOTIFICATIONS ON STATE TRANSITIONS
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 4] ContentResolver Root URI Notification triggers on state transitions...");
        TestContentResolver resolver = testContext.mContentResolver;

        resolver.clear();
        fakeLmi.triggerVmStateChanged(2, 0);
        boolean vmNotifyOk = resolver.getNotifyCount() == 1;

        resolver.clear();
        fakeLmi.triggerCeKeyStatusChanged(true);
        boolean ceNotifyOk = resolver.getNotifyCount() == 1;

        resolver.clear();
        fakeLmi.triggerStorageMountChanged(true);
        boolean mountNotifyOk = resolver.getNotifyCount() == 1;

        resolver.clear();
        String sampleDocUri = "content://com.android.linux.storage/document/home/user/file.txt";
        provider.notifyDocumentChanged(sampleDocUri);
        boolean docNotifyOk = resolver.getNotifyCount() == 1
                && provider.getNotificationUris().contains(sampleDocUri);

        if (vmNotifyOk && ceNotifyOk && mountNotifyOk && docNotifyOk) {
            System.out.println("  PASS: ContentResolver.notifyChange successfully dispatched for VM, CE Key, Storage Mount, and Document updates.");
            passedTests++;
        } else {
            System.err.println("  FAIL: ContentResolver notification failed! vmNotify=" + vmNotifyOk + ", ceNotify=" + ceNotifyOk +
                    ", mountNotify=" + mountNotifyOk + ", docNotify=" + docNotifyOk);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // 5. PATH TRAVERSAL AND SYSTEM ROOT SECURITY
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 5] Path Traversal and System Root Security Checks...");
        String[] traversalTargets = new String[]{
                "/sys", "/proc", "/etc", "/dev",
                "home/user/../../etc/passwd",
                "mnt/shared/../../../sys/kernel",
                "sys/devices",
                "proc/cpuinfo"
        };
        int blockedTraversals = 0;
        for (String target : traversalTargets) {
            try {
                provider.queryDocument(target, null);
            } catch (SecurityException e) {
                blockedTraversals++;
            } catch (Exception ignored) {}
        }

        if (blockedTraversals == traversalTargets.length) {
            System.out.println("  PASS: All " + traversalTargets.length + " path traversal and system root attempts correctly blocked.");
            passedTests++;
        } else {
            System.err.println("  FAIL: Security traversal test failed! Blocked " + blockedTraversals + "/" + traversalTargets.length);
            failedTests++;
        }

        // ---------------------------------------------------------------------
        // 6. CONCURRENT STRESS TEST
        // ---------------------------------------------------------------------
        totalTests++;
        System.out.println("\n[TEST 6] Concurrent Multi-Threaded Stress Test...");
        int threadCount = 10;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successOps = new AtomicInteger(0);
        AtomicInteger totalOps = new AtomicInteger(0);

        fakeLmi.setVmRunning(true);
        fakeLmi.setCeKeyAvailable(true);
        fakeLmi.setReadOnlyMount(false);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        totalOps.incrementAndGet();
                        try {
                            if (threadId % 2 == 0) {
                                Cursor c = provider.queryRoots(null);
                                if (c != null) successOps.incrementAndGet();
                            } else {
                                Cursor c = provider.queryDocument("home/user", null);
                                if (c != null) successOps.incrementAndGet();
                            }
                        } catch (Exception ignored) {}
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        if (successOps.get() == totalOps.get() && totalOps.get() == threadCount * operationsPerThread) {
            System.out.println("  PASS: Executed " + successOps.get() + "/" + totalOps.get() + " concurrent operations without errors or thread contention issues.");
            passedTests++;
        } else {
            System.err.println("  FAIL: Concurrent stress test failed! Completed " + successOps.get() + "/" + totalOps.get());
            failedTests++;
        }

        LocalServices.removeServiceForTest(LinuxManagerInternal.class);

        System.out.println("\n==========================================================================");
        System.out.println(" SUMMARY: " + passedTests + " PASSED, " + failedTests + " FAILED out of " + totalTests + " SUITES.");
        System.out.println("==========================================================================");

        if (failedTests > 0) {
            System.exit(1);
        }
    }
}
