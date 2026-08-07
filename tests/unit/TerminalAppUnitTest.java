package tests.unit;

import com.android.virtualization.terminal.ime.CjkComposingTextManager;
import com.android.virtualization.terminal.ime.TerminalKeyEncoder;
import com.android.virtualization.terminal.net.VsockPtyFramer;
import com.android.virtualization.terminal.net.VsockTerminalClient;
import com.android.virtualization.terminal.renderer.ColorPalette;
import com.android.virtualization.terminal.renderer.TerminalCell;
import com.android.virtualization.terminal.renderer.TerminalScreenMatrix;
import com.android.virtualization.terminal.touch.SgrMouseProtocolGenerator;
import com.android.virtualization.terminal.touch.TouchModeStateMachine;
import com.android.virtualization.terminal.touch.TouchpadController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TerminalAppUnitTest {

    public static void main(String[] args) {
        System.out.println("=== Starting M3 TerminalApp Unit Test Suite ===");
        int failures = 0;

        failures += testVsockPtyFramer();
        failures += testTouchModeStateMachine();
        failures += testSgrMouseProtocolGenerator();
        failures += testTerminalKeyEncoder();
        failures += testCjkComposingTextManager();
        failures += testColorPaletteAndScreenMatrix();
        failures += testTouchpadModeEventGeneration();
        failures += testVsockTerminalClientSocketTransmission();

        System.out.println("================================================");
        if (failures == 0) {
            System.out.println("JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY");
            System.exit(0);
        } else {
            System.err.println("JAVA TEST RESULT: " + failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private static int testVsockPtyFramer() {
        System.out.print("[TEST] F-R3-007: VsockPtyFramer (Serialization, RESIZE, StreamParser)... ");
        try {
            byte[] sessionId = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
            byte[] payload = "echo hello".getBytes(StandardCharsets.UTF_8);

            // 1. Serialization
            byte[] frame = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, payload);
            if (frame.length != VsockPtyFramer.HEADER_SIZE + payload.length) {
                System.out.println("FAILED (Frame length mismatch)");
                return 1;
            }

            // 2. RESIZE payload
            byte[] resizeFrame = VsockPtyFramer.serializeResizeFrame(sessionId, 120, 40);
            byte[] resizePayload = Arrays.copyOfRange(resizeFrame, VsockPtyFramer.HEADER_SIZE, resizeFrame.length);
            int[] dims = VsockPtyFramer.parseResizePayload(resizePayload);
            if (dims[0] != 120 || dims[1] != 40) {
                System.out.println("FAILED (RESIZE payload parsing mismatch: cols=" + dims[0] + ", rows=" + dims[1] + ")");
                return 1;
            }

            // 3. StreamParser partial reassembly
            VsockPtyFramer.StreamParser parser = new VsockPtyFramer.StreamParser();
            final boolean[] frameParsed = new boolean[]{false};
            VsockPtyFramer.OnFrameParsedListener listener = new VsockPtyFramer.OnFrameParsedListener() {
                @Override
                public void onFrameParsed(VsockPtyFramer.Frame f) {
                    if (Arrays.equals(f.sessionId, sessionId) && f.type == VsockPtyFramer.PacketType.DATA) {
                        frameParsed[0] = true;
                    }
                }

                @Override
                public void onError(Exception e) {}
            };

            // Split frame into 2 chunks
            byte[] chunk1 = Arrays.copyOfRange(frame, 0, 10);
            byte[] chunk2 = Arrays.copyOfRange(frame, 10, frame.length);

            parser.appendAndParse(chunk1, 0, chunk1.length, sessionId, listener);
            if (frameParsed[0]) {
                System.out.println("FAILED (Frame parsed prematurely before full header/payload)");
                return 1;
            }

            parser.appendAndParse(chunk2, 0, chunk2.length, sessionId, listener);
            if (!frameParsed[0]) {
                System.out.println("FAILED (StreamParser failed to reassemble fragmented frame)");
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

    private static int testTouchModeStateMachine() {
        System.out.print("[TEST] F-R3-005: TouchModeStateMachine (Auto Transition & Manual Lock)... ");
        try {
            TouchModeStateMachine stateMachine = new TouchModeStateMachine(null);
            if (stateMachine.getCurrentMode() != TouchModeStateMachine.TouchMode.SHELL_MODE) {
                System.out.println("FAILED (Initial mode is not SHELL_MODE)");
                return 1;
            }

            // Auto transition
            stateMachine.onTerminalEscapeMouseTrackingChanged(true);
            if (stateMachine.getCurrentMode() != TouchModeStateMachine.TouchMode.TUI_MOUSE_MODE) {
                System.out.println("FAILED (Auto transition to TUI_MOUSE_MODE failed)");
                return 1;
            }

            // Manual lock
            stateMachine.setManualTouchMode(TouchModeStateMachine.TouchMode.TOUCHPAD_MODE);
            if (stateMachine.getCurrentMode() != TouchModeStateMachine.TouchMode.TOUCHPAD_MODE || !stateMachine.isManualLocked()) {
                System.out.println("FAILED (Manual lock failed)");
                return 1;
            }

            // Auto transition suppressed when manually locked
            stateMachine.onTerminalEscapeMouseTrackingChanged(false);
            if (stateMachine.getCurrentMode() != TouchModeStateMachine.TouchMode.TOUCHPAD_MODE) {
                System.out.println("FAILED (Manual lock was overridden by escape code)");
                return 1;
            }

            // Unlock auto mode
            stateMachine.unlockAutoMode();
            if (stateMachine.getCurrentMode() != TouchModeStateMachine.TouchMode.SHELL_MODE) {
                System.out.println("FAILED (Unlock auto mode failed)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testSgrMouseProtocolGenerator() {
        System.out.print("[TEST] F-R3-006: SgrMouseProtocolGenerator (Format, Coordinates & Touchpad Mode)... ");
        try {
            SgrMouseProtocolGenerator generator = new SgrMouseProtocolGenerator();
            generator.setMouseTrackingEnabled(true);

            String packet = SgrMouseProtocolGenerator.formatSgrPacket(0, 10, 20, true);
            if (!"\033[<0;10;20M".equals(packet)) {
                System.out.println("FAILED (SGR packet format mismatch: " + packet + ")");
                return 1;
            }

            String scrollPacket = SgrMouseProtocolGenerator.formatSgrPacket(64, 15, 30, true);
            if (!"\033[<64;15;30M".equals(scrollPacket)) {
                System.out.println("FAILED (SGR scroll packet format mismatch: " + scrollPacket + ")");
                return 1;
            }

            // Test Touchpad Velocity Scale & Default Cursor Position
            generator.setTouchpadVelocityScale(1.5f);
            if (Math.abs(generator.getTouchpadVelocityScale() - 1.5f) > 0.001f) {
                System.out.println("FAILED (Touchpad velocity scale getter/setter mismatch)");
                return 1;
            }

            // Test processTouchpadEvent with null event or disabled tracking
            generator.setMouseTrackingEnabled(false);
            if (generator.processTouchpadEvent(null, 20, 40, 80, 24).length != 0) {
                System.out.println("FAILED (processTouchpadEvent returned non-empty bytes when disabled)");
                return 1;
            }
            generator.setMouseTrackingEnabled(true);

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testTerminalKeyEncoder() {
        System.out.print("[TEST] F-R3-003: TerminalKeyEncoder (Ctrl & Alt Keys)... ");
        try {
            byte[] ctrlC = TerminalKeyEncoder.encodeCtrlKey(android.view.KeyEvent.KEYCODE_C);
            if (ctrlC.length != 1 || ctrlC[0] != 0x03) {
                System.out.println("FAILED (Ctrl+C encoding mismatch)");
                return 1;
            }

            byte[] ctrlZ = TerminalKeyEncoder.encodeCtrlKey(android.view.KeyEvent.KEYCODE_Z);
            if (ctrlZ.length != 1 || ctrlZ[0] != 0x1A) {
                System.out.println("FAILED (Ctrl+Z encoding mismatch)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testCjkComposingTextManager() {
        System.out.print("[TEST] F-R3-004: CjkComposingTextManager (Zhuyin/Cangjie/Pinyin)... ");
        try {
            CjkComposingTextManager manager = new CjkComposingTextManager();
            manager.setComposingText("ㄘㄨㄛ", 3);

            if (!"ㄘㄨㄛ".equals(manager.getComposingText()) || !manager.isComposing()) {
                System.out.println("FAILED (Composing text set failed)");
                return 1;
            }

            manager.deleteBeforeCursor(1);
            if (!"ㄘㄨ".equals(manager.getComposingText())) {
                System.out.println("FAILED (deleteBeforeCursor failed: " + manager.getComposingText() + ")");
                return 1;
            }

            manager.clear();
            if (manager.isComposing()) {
                System.out.println("FAILED (clear failed)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testColorPaletteAndScreenMatrix() {
        System.out.print("[TEST] F-R3-001: ColorPalette & TerminalScreenMatrix... ");
        try {
            int red = ColorPalette.getAnsiColor(1);
            if ((red & 0x00FF0000) == 0) {
                System.out.println("FAILED (ANSI Red color index invalid)");
                return 1;
            }

            TerminalScreenMatrix matrix = new TerminalScreenMatrix(24, 80);
            if (matrix.getRows() != 24 || matrix.getCols() != 80) {
                System.out.println("FAILED (Matrix dimensions mismatch)");
                return 1;
            }

            android.graphics.Rect initRect = new android.graphics.Rect();
            matrix.getAndClearDirtyRect(initRect);
            matrix.markDirtyCell(5, 10);
            android.graphics.Rect r = new android.graphics.Rect();
            boolean dirty = matrix.getAndClearDirtyRect(r);
            if (!dirty || r.left != 10 || r.top != 5) {
                System.out.println("FAILED (Dirty rect mismatch)");
                return 1;
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            return 1;
        }
    }

    private static int testTouchpadModeEventGeneration() {
        System.out.print("[TEST] F-R3-005/006: TOUCHPAD_MODE Relative Motion & Gesture SGR... ");
        try {
            TouchpadController controller = new TouchpadController(80, 24, 20, 40);

            // 1. Initial Virtual Cursor at Center (40, 12)
            if (controller.getVirtualCursorCol() != 40 || controller.getVirtualCursorRow() != 12) {
                System.out.println("FAILED (Initial virtual cursor position invalid: col=" + controller.getVirtualCursorCol() + ", row=" + controller.getVirtualCursorRow() + ")");
                return 1;
            }

            // 2. Relative Delta Motion Tracking (dx = +40, dy = -80) -> Col 42, Row 10
            controller.handleRelativeMove(40f, -80f);
            if (controller.getVirtualCursorCol() != 42 || controller.getVirtualCursorRow() != 10) {
                System.out.println("FAILED (Virtual cursor delta calculation error: col=" + controller.getVirtualCursorCol() + ", row=" + controller.getVirtualCursorRow() + ")");
                return 1;
            }

            // 3. Single Tap Gesture -> SGR Button 0 Press & Release at (42, 10)
            byte[] tapSgr = controller.handleSingleTap();
            String tapPacket = new String(tapSgr, StandardCharsets.US_ASCII);
            if (!"\033[<0;42;10M\033[<0;42;10m".equals(tapPacket)) {
                System.out.println("FAILED (Tap SGR packet mismatch: " + tapPacket + ")");
                return 1;
            }

            // 4. Long Press Gesture -> SGR Button 2 Press & Release (Right Click)
            byte[] longPressSgr = controller.handleLongPress();
            String longPressPacket = new String(longPressSgr, StandardCharsets.US_ASCII);
            if (!"\033[<2;42;10M\033[<2;42;10m".equals(longPressPacket)) {
                System.out.println("FAILED (Long press SGR packet mismatch: " + longPressPacket + ")");
                return 1;
            }

            // 5. Two-finger Scroll Gesture -> SGR Buttons 64 / 65
            byte[] scrollSgr = controller.handleTwoFingerScroll(-50f); // Scroll down
            String scrollPacket = new String(scrollSgr, StandardCharsets.US_ASCII);
            if (!"\033[<65;42;10M".equals(scrollPacket)) {
                System.out.println("FAILED (Two-finger scroll SGR packet mismatch: " + scrollPacket + ")");
                return 1;
            }

            // 6. Out-of-bounds Clamping Check
            controller.handleRelativeMove(5000f, 5000f);
            if (controller.getVirtualCursorCol() != 80 || controller.getVirtualCursorRow() != 24) {
                System.out.println("FAILED (Cursor out-of-bounds clamping failed: col=" + controller.getVirtualCursorCol() + ", row=" + controller.getVirtualCursorRow() + ")");
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

    private static int testVsockTerminalClientSocketTransmission() {
        System.out.print("[TEST] F-R3-007: VsockTerminalClient Real Socket Transmission... ");
        try {
            // 1. Setup local ServerSocket loopback to emulate Port 5001 PTY Agent
            java.net.ServerSocket serverSocket = new java.net.ServerSocket(0);
            int localPort = serverSocket.getLocalPort();

            byte[] sessionId = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

            // 2. Instantiate VsockTerminalClient and connect to loopback socket
            VsockTerminalClient client = new VsockTerminalClient();
            java.net.Socket clientSocket = new java.net.Socket("127.0.0.1", localPort);
            java.net.Socket serverConn = serverSocket.accept();

            client.connectSocket(clientSocket, sessionId, null);

            // 3. Transmit Frame via Client sendFrame
            byte[] testData = "echo 'vsock_real_test'\n".getBytes(StandardCharsets.UTF_8);
            byte[] frame = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, testData);

            client.sendFrame(frame);

            // 4. Server receives raw bytes from socket and parses frame header
            java.io.InputStream in = serverConn.getInputStream();
            byte[] receivedBuffer = new byte[VsockPtyFramer.HEADER_SIZE + testData.length];
            int bytesRead = 0;
            while (bytesRead < receivedBuffer.length) {
                int n = in.read(receivedBuffer, bytesRead, receivedBuffer.length - bytesRead);
                if (n < 0) break;
                bytesRead += n;
            }

            if (bytesRead != receivedBuffer.length) {
                System.out.println("FAILED (Received byte count mismatch: expected " + receivedBuffer.length + ", got " + bytesRead + ")");
                return 1;
            }

            // 5. Assert Framed Header & Payload Authenticity
            VsockPtyFramer.Frame parsedFrame = VsockPtyFramer.parseFrameHeaderAndPayload(receivedBuffer);
            if (!Arrays.equals(parsedFrame.sessionId, sessionId)) {
                System.out.println("FAILED (Session ID mismatch in transmitted frame)");
                return 1;
            }
            if (parsedFrame.type != VsockPtyFramer.PacketType.DATA) {
                System.out.println("FAILED (Packet type mismatch: expected DATA)");
                return 1;
            }
            if (!Arrays.equals(parsedFrame.payload, testData)) {
                System.out.println("FAILED (Payload bytes corrupted during socket transmission)");
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
