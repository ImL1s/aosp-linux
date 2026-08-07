package tests.unit;

import com.android.server.linux.storage.LinuxStorageProvider;

public class LinuxStorageProviderTest {
    public static void main(String[] args) {
        System.out.println("=== Running LinuxStorageProviderTest ===");

        LinuxStorageProvider provider = new LinuxStorageProvider();

        // Check exposed roots
        if (!provider.getExposedRoots().contains("/home/user") || provider.getExposedRoots().contains("/sys")) {
            throw new RuntimeException("Test Failed: System root exposure check failed");
        }

        // Check VM offline error
        provider.setVmRunning(false);
        try {
            provider.queryRoots(null);
            throw new RuntimeException("Test Failed: Should have thrown ConnectionError when VM is offline");
        } catch (LinuxStorageProvider.ConnectionError e) {
            System.out.println("Pass: Caught expected VMOfflineException: " + e.getMessage());
        }
        provider.setVmRunning(true);

        // Check LUKS2 CE lock error
        provider.setCeKeyAvailable(false);
        try {
            provider.queryDocument("home/user/doc.txt", null);
            throw new RuntimeException("Test Failed: Should have thrown PermissionError when CE volume is locked");
        } catch (LinuxStorageProvider.PermissionError e) {
            System.out.println("Pass: Caught expected EncryptedStorageException: " + e.getMessage());
        }
        provider.setCeKeyAvailable(true);

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

        System.out.println("PASS: LinuxStorageProviderTest executed successfully.");
    }
}
