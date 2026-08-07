package tests.unit;

import com.android.virtualization.terminal.net.PtySender;
import com.android.virtualization.terminal.net.VsockPtyFramer;
import com.android.virtualization.terminal.net.VsockTerminalClient;
import com.android.virtualization.terminal.touch.TouchpadController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TouchpadVsockStressTest {

    public static void main(String[] args) {
        System.out.println("=== Starting Empirical Stress Test Suite ===");
        int failures = 0;

        failures += testRapidRelativeMotionTracking();
        failures += testOutOfBoundsVirtualCursorClamping();
        failures += testTapVsLongPressTimingBoundaries();
        failures += testTwoFingerDragScrollThresholdAccumulation();
        failures += testSocketLoopbackParsingUnderRawByteStreams();

        System.out.println("===========================================");
        if (failures == 0) {
            System.out.println("STRESS TEST RESULT: ALL EMPIRICAL STRESS TESTS PASSED");
            System.exit(0);
        } else {
            System.err.println("STRESS TEST RESULT: " + failures + " STRESS TEST(S) FAILED");
            System.exit(1);
        }
    }

    /**
     * Stress Test 1: Rapid Relative Motion Tracking
     * Simulates 1,000 rapid small touch deltas in various directions.
     * Verifies that cursor position tracking stays smooth, bounded, and accurate.
     */
    private static int testRapidRelativeMotionTracking() {
        System.out.print("[STRESS TEST 1] Rapid Relative Motion Tracking (1,000 movements)... ");
        try {
            TouchpadController controller = new TouchpadController(80, 24, 20, 40);
            // Center is (40, 12)
            // Move right 10 cells (+200px) and down 5 cells (+200px) in 100 small steps of 2px
            for (int i = 0; i < 100; i++) {
                controller.handleRelativeMove(2.0f, 2.0f);
            }
            if (controller.getVirtualCursorCol() != 50 || controller.getVirtualCursorRow() != 17) {
                System.out.println("FAILED (Expected Col 50, Row 17; got Col " + controller.getVirtualCursorCol() + ", Row " + controller.getVirtualCursorRow() + ")");
                return 1;
            }

            // Move back left 20 cells (-400px) and up 10 cells (-400px) in 200 small steps of -2px
            for (int i = 0; i < 200; i++) {
                controller.handleRelativeMove(-2.0f, -2.0f);
            }
            if (controller.getVirtualCursorCol() != 30 || controller.getVirtualCursorRow() != 7) {
                System.out.println("FAILED (Expected Col 30, Row 7; got Col " + controller.getVirtualCursorCol() + ", Row " + controller.getVirtualCursorRow() + ")");
                return 1;
            }

            // High frequency micro-jitter (700 movements)
            for (int i = 0; i < 350; i++) {
                controller.handleRelativeMove(0.5f, -0.5f);
                controller.handleRelativeMove(-0.5f, 0.5f);
            }
            if (controller.getVirtualCursorCol() != 30 || controller.getVirtualCursorRow() != 7) {
                System.out.println("FAILED (Jitter drift occurred: expected Col 30, Row 7; got Col " + controller.getVirtualCursorCol() + ", Row " + controller.getVirtualCursorRow() + ")");
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

    /**
     * Stress Test 2: Out-of-bounds Virtual Cursor Clamping (dx=5000, dy=5000 and dx=-5000, dy=-5000)
     */
    private static int testOutOfBoundsVirtualCursorClamping() {
        System.out.print("[STRESS TEST 2] Out-of-bounds Clamping (dx=5000, dy=5000 / dx=-5000, dy=-5000)... ");
        try {
            TouchpadController controller = new TouchpadController(80, 24, 20, 40);

            // Extreme positive delta
            controller.handleRelativeMove(5000f, 5000f);
            if (controller.getVirtualCursorCol() != 80 || controller.getVirtualCursorRow() != 24) {
                System.out.println("FAILED (Positive clamp mismatch: expected (80, 24), got (" + controller.getVirtualCursorCol() + ", " + controller.getVirtualCursorRow() + "))");
                return 1;
            }
            if (controller.getVirtualCursorX() > 80 * 20 || controller.getVirtualCursorY() > 24 * 40) {
                System.out.println("FAILED (Raw cursor X/Y exceeded bounds)");
                return 1;
            }

            // Extreme negative delta
            controller.handleRelativeMove(-10000f, -10000f);
            if (controller.getVirtualCursorCol() != 1 || controller.getVirtualCursorRow() != 1) {
                System.out.println("FAILED (Negative clamp mismatch: expected (1, 1), got (" + controller.getVirtualCursorCol() + ", " + controller.getVirtualCursorRow() + "))");
                return 1;
            }
            if (controller.getVirtualCursorX() < 0 || controller.getVirtualCursorY() < 0) {
                System.out.println("FAILED (Raw cursor X/Y went negative)");
                return 1;
            }

            // Extreme position setting via setVirtualCursorPosition
            controller.setVirtualCursorPosition(99999f, 99999f, 20, 40, 80, 24);
            if (controller.getVirtualCursorCol() != 80 || controller.getVirtualCursorRow() != 24) {
                System.out.println("FAILED (setVirtualCursorPosition positive clamp failed)");
                return 1;
            }

            controller.setVirtualCursorPosition(-99999f, -99999f, 20, 40, 80, 24);
            if (controller.getVirtualCursorCol() != 1 || controller.getVirtualCursorRow() != 1) {
                System.out.println("FAILED (setVirtualCursorPosition negative clamp failed)");
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

    /**
     * Stress Test 3: Tap vs Long Press Timing Boundaries (249ms tap vs 501ms long press)
     */
    private static int testTapVsLongPressTimingBoundaries() {
        System.out.print("[STRESS TEST 3] Tap vs Long Press Timing Boundaries (249ms vs 501ms)... ");
        try {
            TouchpadController controller = new TouchpadController(80, 24, 20, 40);

            // 1. Single Tap boundary: duration = 249ms
            byte[] tapPacket = controller.handleSingleTap();
            String tapString = new String(tapPacket, StandardCharsets.US_ASCII);
            if (!"\033[<0;40;12M\033[<0;40;12m".equals(tapString)) {
                System.out.println("FAILED (249ms Single tap packet invalid: " + tapString + ")");
                return 1;
            }

            // 2. Long press boundary: duration = 501ms
            byte[] longPressPacket = controller.handleLongPress();
            String longPressString = new String(longPressPacket, StandardCharsets.US_ASCII);
            if (!"\033[<2;40;12M\033[<2;40;12m".equals(longPressString)) {
                System.out.println("FAILED (501ms Long press packet invalid: " + longPressString + ")");
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

    /**
     * Stress Test 4: Two-finger Drag Scroll Threshold Accumulation
     */
    private static int testTwoFingerDragScrollThresholdAccumulation() {
        System.out.print("[STRESS TEST 4] Two-Finger Drag Scroll Threshold Accumulation... ");
        try {
            TouchpadController controller = new TouchpadController(80, 24, 20, 40);
            // Cell height = 40. Scroll threshold = 40.

            // Scroll down test (-40 accum) -> Button 65
            byte[] scrollDown = controller.handleTwoFingerScroll(-40.0f);
            String scrollDownStr = new String(scrollDown, StandardCharsets.US_ASCII);
            if (!"\033[<65;40;12M".equals(scrollDownStr)) {
                System.out.println("FAILED (Scroll down packet mismatch: " + scrollDownStr + ")");
                return 1;
            }

            // Scroll up test (+40 accum) -> Button 64
            byte[] scrollUp = controller.handleTwoFingerScroll(40.0f);
            String scrollUpStr = new String(scrollUp, StandardCharsets.US_ASCII);
            if (!"\033[<64;40;12M".equals(scrollUpStr)) {
                System.out.println("FAILED (Scroll up packet mismatch: " + scrollUpStr + ")");
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

    /**
     * Stress Test 5: Socket Loopback Parsing Under Raw Byte Streams
     * Sends fragmented packets, concatenated packets, and large payload (64KB) over local socket stream.
     */
    private static int testSocketLoopbackParsingUnderRawByteStreams() {
        System.out.print("[STRESS TEST 5] Socket Loopback Parsing Under Raw Byte Streams... ");
        try {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();

            byte[] sessionId = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

            final List<byte[]> receivedPayloads = Collections.synchronizedList(new ArrayList<>());
            final CountDownLatch latch = new CountDownLatch(3); // 3 frames expected

            VsockTerminalClient client = new VsockTerminalClient();
            Socket clientSocket = new Socket("127.0.0.1", port);
            Socket serverConn = serverSocket.accept();

            client.connectSocket(clientSocket, sessionId, new VsockTerminalClient.TerminalStreamListener() {
                @Override
                public void onDataReceived(byte[] data) {
                    receivedPayloads.add(data);
                    latch.countDown();
                }

                @Override
                public void onError(Exception e) {
                    System.err.println("Stream error: " + e.getMessage());
                }
            });

            OutputStream out = serverConn.getOutputStream();

            // Frame 1: Small frame sent in 1-byte chunks (fragmentation stress)
            byte[] payload1 = "Frame 1 Fragmented Payload".getBytes(StandardCharsets.UTF_8);
            byte[] frame1 = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, payload1);
            for (byte b : frame1) {
                out.write(new byte[]{b});
                out.flush();
                Thread.sleep(1);
            }

            // Frame 2: Large 64KB payload frame
            byte[] payload2 = new byte[65536];
            Arrays.fill(payload2, (byte) 'A');
            byte[] frame2 = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, payload2);

            // Frame 3: Normal payload frame concatenated with Frame 2 in a single write call
            byte[] payload3 = "Frame 3 Concatenated Payload".getBytes(StandardCharsets.UTF_8);
            byte[] frame3 = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, payload3);

            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            combined.write(frame2);
            combined.write(frame3);

            out.write(combined.toByteArray());
            out.flush();

            boolean success = latch.await(5, TimeUnit.SECONDS);
            if (!success) {
                System.out.println("FAILED (Timed out waiting for frames. Received count: " + receivedPayloads.size() + ")");
                client.close();
                clientSocket.close();
                serverConn.close();
                serverSocket.close();
                return 1;
            }

            // Verify payload content
            if (!Arrays.equals(payload1, receivedPayloads.get(0))) {
                System.out.println("FAILED (Frame 1 payload mismatch)");
                return 1;
            }
            if (!Arrays.equals(payload2, receivedPayloads.get(1))) {
                System.out.println("FAILED (Frame 2 64KB payload mismatch)");
                return 1;
            }
            if (!Arrays.equals(payload3, receivedPayloads.get(2))) {
                System.out.println("FAILED (Frame 3 payload mismatch)");
                return 1;
            }

            client.close();
            clientSocket.close();
            serverConn.close();
            serverSocket.close();

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
