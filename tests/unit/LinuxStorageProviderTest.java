package tests.unit;

import com.android.server.LocalServices;
import com.android.server.linux.LinuxManagerInternal;
import com.android.server.linux.storage.LinuxStorageProvider;

public class LinuxStorageProviderTest {

    private static class FakeLinuxManagerInternal extends LinuxManagerInternal {
        private boolean mVmRunning = true;
        private boolean mCeKeyAvailable = true;
        private boolean mIsReadOnlyMount = false;

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
        public void registerStorageStateListener(StorageStateListener listener) {}

        @Override
        public void unregisterStorageStateListener(StorageStateListener listener) {}
    }

    public static void main(String[] args) {
        System.out.println("=== Running LinuxStorageProviderTest ===");

        FakeLinuxManagerInternal fakeLmi = new FakeLinuxManagerInternal();
        LocalServices.addService(LinuxManagerInternal.class, fakeLmi);

        LinuxStorageProvider provider = new LinuxStorageProvider();

        // Check exposed roots
        if (!provider.getExposedRoots().contains("/home/user") || provider.getExposedRoots().contains("/sys")) {
            throw new RuntimeException("Test Failed: System root exposure check failed");
        }

        // Check VM offline error
        fakeLmi.setVmRunning(false);
        try {
            provider.queryRoots(null);
            throw new RuntimeException("Test Failed: Should have thrown ConnectionError when VM is offline");
        } catch (LinuxStorageProvider.ConnectionError e) {
            System.out.println("Pass: Caught expected VMOfflineException: " + e.getMessage());
        }
        fakeLmi.setVmRunning(true);

        // Check LUKS2 CE lock error
        fakeLmi.setCeKeyAvailable(false);
        try {
            provider.queryDocument("home/user/doc.txt", null);
            throw new RuntimeException("Test Failed: Should have thrown PermissionError when CE volume is locked");
        } catch (LinuxStorageProvider.PermissionError e) {
            System.out.println("Pass: Caught expected EncryptedStorageException: " + e.getMessage());
        }
        fakeLmi.setCeKeyAvailable(true);

        // System root query blocking
        try {
            provider.queryChildDocuments("/etc", null, null);
            throw new RuntimeException("Test Failed: Should have thrown SecurityException when querying system root");
        } catch (SecurityException e) {
            System.out.println("Pass: Caught expected SecurityException: " + e.getMessage());
        }

        // NotifyChange trigger
        provider.notifyDocumentChanged("content://com.android.linux.storage/document/home/user/test.txt");
        if (provider.getNotificationUris().size() != 1) {
            throw new RuntimeException("Test Failed: Notification change trigger failed");
        }

        LocalServices.removeServiceForTest(LinuxManagerInternal.class);

        System.out.println("PASS: LinuxStorageProviderTest executed successfully.");
    }
}
