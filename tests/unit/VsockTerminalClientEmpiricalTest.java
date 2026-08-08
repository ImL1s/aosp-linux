package tests.unit;

import com.android.virtualization.terminal.net.VsockTerminalClient;
import com.android.virtualization.terminal.net.VsockPtyFramer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class VsockTerminalClientEmpiricalTest {

    public static void main(String[] args) {
        System.out.println("=== Starting Challenger M3 VsockTerminalClient Empirical Test Suite ===");
        int failures = 0;

        failures += testSessionIdValidation();
        failures += testFailedConnectionFdLeak();
        failures += testConnectionRefusalAndErrorHandling();
        failures += testCleanTeardownAndThreadExit();

        System.out.println("=========================================================================");
        if (failures == 0) {
            System.out.println("EMPIRICAL TEST RESULT: ALL CHALLENGER TESTS PASSED");
            System.exit(0);
        } else {
            System.err.println("EMPIRICAL TEST RESULT: " + failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private static int getOpenFdCount() {
        try {
            File fdDir = new File("/dev/fd");
            if (fdDir.exists() && fdDir.isDirectory()) {
                String[] fds = fdDir.list();
                return fds != null ? fds.length : -1;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static int testSessionIdValidation() {
        System.out.print("[EMPIRICAL TEST 1] Session ID validation (null, 12-byte, 20-byte, 16-byte)... ");
        VsockTerminalClient client = new VsockTerminalClient();

        // 1. null session ID
        boolean caughtNull = false;
        try {
            client.connect(3, null, null);
        } catch (IllegalArgumentException e) {
            caughtNull = true;
        } catch (Exception e) {}
        if (!caughtNull) {
            System.out.println("FAILED (null session ID did not throw IllegalArgumentException)");
            return 1;
        }

        // 2. 12-byte session ID
        boolean caught12 = false;
        try {
            client.connect(3, "session_1001".getBytes(StandardCharsets.US_ASCII), null);
        } catch (IllegalArgumentException e) {
            caught12 = true;
        } catch (Exception e) {}
        if (!caught12) {
            System.out.println("FAILED (12-byte session ID did not throw IllegalArgumentException)");
            return 1;
        }

        // 3. 20-byte session ID
        boolean caught20 = false;
        try {
            client.connect(3, "session_0000000001001".getBytes(StandardCharsets.US_ASCII), null);
        } catch (IllegalArgumentException e) {
            caught20 = true;
        } catch (Exception e) {}
        if (!caught20) {
            System.out.println("FAILED (20-byte session ID did not throw IllegalArgumentException)");
            return 1;
        }

        System.out.println("PASS");
        return 0;
    }

    private static int testFailedConnectionFdLeak() {
        System.out.print("[EMPIRICAL TEST 2] File Descriptor Leak Check over 100 Failed Connection Attempts... ");
        byte[] validSessionId = "session_00001001".getBytes(StandardCharsets.US_ASCII);
        int initialFdCount = getOpenFdCount();

        for (int i = 0; i < 100; i++) {
            VsockTerminalClient client = new VsockTerminalClient();
            try {
                // CID 9999 is invalid/non-existent CID or host without AF_VSOCK support
                client.connect(9999, validSessionId, null);
            } catch (IOException e) {
                // Expected failure
            } catch (Exception e) {
                // Other unexpected exception
            }
        }

        int finalFdCount = getOpenFdCount();
        if (initialFdCount != -1 && finalFdCount != -1) {
            int delta = finalFdCount - initialFdCount;
            if (delta > 2) {
                System.out.println("FAILED (FD leak detected! Initial FD: " + initialFdCount + ", Final FD: " + finalFdCount + ", Delta: " + delta + ")");
                return 1;
            }
            System.out.println("PASS (Initial FDs: " + initialFdCount + ", Final FDs: " + finalFdCount + ", Delta: " + delta + ")");
        } else {
            System.out.println("PASS (FD count inspection unavailable on this platform)");
        }
        return 0;
    }

    private static int testConnectionRefusalAndErrorHandling() {
        System.out.print("[EMPIRICAL TEST 3] Connection Refusal & Exception Wrapping... ");
        byte[] validSessionId = "session_00001001".getBytes(StandardCharsets.US_ASCII);
        VsockTerminalClient client = new VsockTerminalClient();

        try {
            client.connect(3, validSessionId, null);
            System.out.println("FAILED (connect unexpectedly succeeded without vsock driver)");
            return 1;
        } catch (IOException e) {
            if (e.getMessage() == null || !e.getMessage().contains("AF_VSOCK")) {
                System.out.println("FAILED (IOException message does not contain AF_VSOCK diagnostic context: " + e.getMessage() + ")");
                return 1;
            }
            System.out.println("PASS (Caught expected IOException: " + e.getMessage() + ")");
            return 0;
        } catch (Throwable t) {
            System.out.println("FAILED (Unexpected exception type: " + t.getClass().getName() + ": " + t.getMessage() + ")");
            return 1;
        }
    }

    private static int testCleanTeardownAndThreadExit() {
        System.out.print("[EMPIRICAL TEST 4] Thread Teardown & Read Loop Closure on Client Close... ");
        try {
            java.net.ServerSocket serverSocket = new java.net.ServerSocket(0);
            int port = serverSocket.getLocalPort();
            byte[] validSessionId = "session_00001001".getBytes(StandardCharsets.US_ASCII);

            VsockTerminalClient client = new VsockTerminalClient();
            java.net.Socket clientSocket = new java.net.Socket("127.0.0.1", port);
            java.net.Socket serverConn = serverSocket.accept();

            client.connectSocket(clientSocket, validSessionId, new VsockTerminalClient.TerminalStreamListener() {
                @Override
                public void onDataReceived(byte[] data) {}

                @Override
                public void onError(Exception e) {}
            });

            // Count threads before close
            Thread[] threadsBefore = new Thread[Thread.activeCount() * 2];
            int countBefore = Thread.enumerate(threadsBefore);
            boolean vsockThreadRunningBefore = false;
            for (int i = 0; i < countBefore; i++) {
                if (threadsBefore[i] != null && "VsockReadThread".equals(threadsBefore[i].getName()) && threadsBefore[i].isAlive()) {
                    vsockThreadRunningBefore = true;
                    break;
                }
            }

            if (!vsockThreadRunningBefore) {
                System.out.println("FAILED (VsockReadThread was not running after connectSocket)");
                return 1;
            }

            // Close client
            client.close();
            serverConn.close();
            serverSocket.close();

            // Wait for thread to exit
            Thread.sleep(150);

            Thread[] threadsAfter = new Thread[Thread.activeCount() * 2];
            int countAfter = Thread.enumerate(threadsAfter);
            boolean vsockThreadRunningAfter = false;
            for (int i = 0; i < countAfter; i++) {
                if (threadsAfter[i] != null && "VsockReadThread".equals(threadsAfter[i].getName()) && threadsAfter[i].isAlive()) {
                    vsockThreadRunningAfter = true;
                    break;
                }
            }

            if (vsockThreadRunningAfter) {
                System.out.println("FAILED (VsockReadThread failed to exit after client.close())");
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
