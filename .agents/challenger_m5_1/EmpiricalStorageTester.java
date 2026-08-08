package tests.challenger;

import com.android.server.LocalServices;
import com.android.server.linux.LinuxManagerInternal;
import com.android.server.linux.storage.LinuxStorageProvider;

import java.lang.reflect.Method;

public class EmpiricalStorageTester {

    private static class FakeLinuxManagerInternal extends LinuxManagerInternal {
        @Override
        public boolean isVmRunning() { return true; }
        @Override
        public int getVmState() { return 2; }
        @Override
        public void onUserUnlocked(int userId) {}
        @Override
        public boolean isCeKeyAvailable() { return true; }
        @Override
        public boolean isReadOnlyMount() { return false; }
        @Override
        public void registerStorageStateListener(StorageStateListener listener) {}
        @Override
        public void unregisterStorageStateListener(StorageStateListener listener) {}
    }

    public static void main(String[] args) {
        System.out.println("=== Running Empirical Storage Security Harness ===");

        FakeLinuxManagerInternal fakeLmi = new FakeLinuxManagerInternal();
        LocalServices.addService(LinuxManagerInternal.class, fakeLmi);

        LinuxStorageProvider provider = new LinuxStorageProvider();

        int bugs = 0;

        // Test path traversal via reflection on getFileForDocId
        try {
            Method method = LinuxStorageProvider.class.getDeclaredMethod("getFileForDocId", String.class);
            method.setAccessible(true);

            // Test 1: Path traversal out of /home/user using relative path
            try {
                method.invoke(provider, "home/user/../../../../etc/passwd");
                System.err.println("FAIL [Storage Bug]: Path traversal allowed home/user/../../../../etc/passwd");
                bugs++;
            } catch (Exception e) {
                System.out.println("Pass [Storage Test 1]: Traversal blocked: " + e.getCause().getMessage());
            }

            // Test 2: System root access via /etc
            try {
                method.invoke(provider, "/etc/shadow");
                System.err.println("FAIL [Storage Bug]: Direct system root access allowed /etc/shadow");
                bugs++;
            } catch (Exception e) {
                System.out.println("Pass [Storage Test 2]: System root blocked: " + e.getCause().getMessage());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        LocalServices.removeServiceForTest(LinuxManagerInternal.class);

        System.out.println("=== Storage Harness Complete. Bugs found: " + bugs + " ===");
    }
}
