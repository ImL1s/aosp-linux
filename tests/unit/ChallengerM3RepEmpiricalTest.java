package tests.unit;

import com.android.virtualization.terminal.ime.CjkComposingTextManager;
import com.android.virtualization.terminal.ime.TerminalKeyEncoder;
import com.android.virtualization.terminal.net.PtySender;
import com.android.virtualization.terminal.net.VsockPtyFramer;
import com.android.virtualization.terminal.touch.SgrMouseProtocolGenerator;
import com.android.virtualization.terminal.touch.TouchModeStateMachine;
import com.android.virtualization.terminal.touch.TouchpadController;

import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChallengerM3RepEmpiricalTest {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   EMPIRICAL CHALLENGER (M3 REPLACEMENT) RIGOROUS VERIFICATION SUITE");
        System.out.println("================================================================================");

        int failures = 0;
        failures += testCjkComposingTextManager();
        failures += testTerminalKeyEncoderAndCjkCommit();
        failures += testTouchModeStateMachineAndManualLocking();
        failures += testSgrMouseProtocolAndTouchpadController();
        failures += testVsockPtyFramerStreamParsing();
        failures += testConcurrentStress();

        System.out.println("================================================================================");
        if (failures == 0) {
            System.out.println("CHALLENGER VERIFICATION RESULT: ALL EMPIRICAL TESTS PASSED SUCCESSFULLY");
            System.exit(0);
        } else {
            System.err.println("CHALLENGER VERIFICATION RESULT: " + failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private static int testCjkComposingTextManager() {
        System.out.print("[EMPIRICAL TEST 1] CjkComposingTextManager Boundary & Deletion... ");
        try {
            CjkComposingTextManager mgr = new CjkComposingTextManager();

            // 1. Set text exceeding MAX_COMPOSING_LENGTH (256)
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 300; i++) sb.append("注");
            mgr.setComposingText(sb.toString(), 1);
            if (mgr.getComposingText().length() != 256) {
                System.out.println("FAILED (Truncation to 256 chars failed: got " + mgr.getComposingText().length() + ")");
                return 1;
            }

            // 2. Cursor position clamping
            mgr.setComposingText("注音符號", 100);
            if (mgr.getCursorPosition() != 4) {
                System.out.println("FAILED (Cursor position overflow clamp failed: got " + mgr.getCursorPosition() + ")");
                return 1;
            }

            // 3. Deletion before cursor (cursor at end = index 5)
            mgr.setComposingText("倉頡輸入法", 1); // cursor at index 5 (after "法")
            mgr.deleteBeforeCursor(1); // delete "法"
            if (!"倉頡輸入".equals(mgr.getComposingText()) || mgr.getCursorPosition() != 4) {
                System.out.println("FAILED (deleteBeforeCursor failed: text=" + mgr.getComposingText() + ", cursor=" + mgr.getCursorPosition() + ")");
                return 1;
            }

            // 4. Deleting when cursor is at 0
            mgr.setComposingText("拼音", 0); // cursor at index 0
            mgr.deleteBeforeCursor(5);
            if (!"拼音".equals(mgr.getComposingText()) || mgr.getCursorPosition() != 0) {
                System.out.println("FAILED (deleteBeforeCursor at 0 modified buffer)");
                return 1;
            }

            mgr.clear();
            if (mgr.isComposing() || mgr.getCursorPosition() != 0) {
                System.out.println("FAILED (clear failed)");
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

    private static int testTerminalKeyEncoderAndCjkCommit() {
        System.out.print("[EMPIRICAL TEST 2] CJK Commit Byte Serialization & TerminalKeyEncoder... ");
        try {
            // 1. Multi-byte CJK UTF-8 Byte serialization
            String cjkString = "繁體中文測試鍵盤";
            byte[] utf8Bytes = cjkString.getBytes(StandardCharsets.UTF_8);
            if (utf8Bytes.length != 24) { // 8 characters * 3 bytes per UTF-8 CJK char = 24 bytes
                System.out.println("FAILED (UTF-8 byte length mismatch: got " + utf8Bytes.length + ")");
                return 1;
            }
            String reconstructedStr = new String(utf8Bytes, StandardCharsets.UTF_8);
            if (!cjkString.equals(reconstructedStr)) {
                System.out.println("FAILED (CJK UTF-8 reconstruction mismatch)");
                return 1;
            }

            // 2. Key encoding: Ctrl+C (KEYCODE_C with META_CTRL_ON)
            byte[] ctrlCSeq = TerminalKeyEncoder.encodeCtrlKey(KeyEvent.KEYCODE_C);
            if (ctrlCSeq.length != 1 || ctrlCSeq[0] != 0x03) {
                System.out.println("FAILED (Ctrl+C encoding mismatch)");
                return 1;
            }

            // 3. Ctrl+Z (KEYCODE_Z with META_CTRL_ON)
            byte[] ctrlZSeq = TerminalKeyEncoder.encodeCtrlKey(KeyEvent.KEYCODE_Z);
            if (ctrlZSeq.length != 1 || ctrlZSeq[0] != 0x1A) {
                System.out.println("FAILED (Ctrl+Z encoding mismatch)");
                return 1;
            }

            // 4. Ctrl+[ (ESC)
            byte[] ctrlBracket = TerminalKeyEncoder.encodeCtrlKey(KeyEvent.KEYCODE_LEFT_BRACKET);
            if (ctrlBracket.length != 1 || ctrlBracket[0] != 0x1B) {
                System.out.println("FAILED (Ctrl+[ encoding mismatch)");
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

    private static int testTouchModeStateMachineAndManualLocking() {
        System.out.print("[EMPIRICAL TEST 3] TouchModeStateMachine & Manual Locking... ");
        try {
            TouchModeStateMachine sm = new TouchModeStateMachine(null);
            if (sm.getCurrentMode() != TouchModeStateMachine.TouchMode.SHELL_MODE) {
                System.out.println("FAILED (Initial mode is not SHELL_MODE)");
                return 1;
            }

            final List<TouchModeStateMachine.TouchMode> transitions = new ArrayList<>();
            sm.addListener((oldMode, newMode, isManual) -> transitions.add(newMode));

            // 1. Auto escape mouse tracking enabled
            sm.onTerminalEscapeMouseTrackingChanged(true);
            if (sm.getCurrentMode() != TouchModeStateMachine.TouchMode.TUI_MOUSE_MODE) {
                System.out.println("FAILED (Auto transition to TUI_MOUSE_MODE failed)");
                return 1;
            }

            // 2. Manual lock to TOUCHPAD_MODE
            sm.setManualTouchMode(TouchModeStateMachine.TouchMode.TOUCHPAD_MODE);
            if (sm.getCurrentMode() != TouchModeStateMachine.TouchMode.TOUCHPAD_MODE || !sm.isManualLocked()) {
                System.out.println("FAILED (Manual lock to TOUCHPAD_MODE failed)");
                return 1;
            }

            // 3. Escape tracking change when manually locked should NOT change mode
            sm.onTerminalEscapeMouseTrackingChanged(false);
            if (sm.getCurrentMode() != TouchModeStateMachine.TouchMode.TOUCHPAD_MODE) {
                System.out.println("FAILED (Mode changed while manually locked)");
                return 1;
            }

            // 4. Unlock auto mode
            sm.unlockAutoMode();
            if (sm.isManualLocked() || sm.getCurrentMode() != TouchModeStateMachine.TouchMode.SHELL_MODE) {
                System.out.println("FAILED (Unlock auto mode failed to revert to SHELL_MODE)");
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

    private static int testSgrMouseProtocolAndTouchpadController() {
        System.out.print("[EMPIRICAL TEST 4] SgrMouseProtocolGenerator & TouchpadController... ");
        try {
            SgrMouseProtocolGenerator gen = new SgrMouseProtocolGenerator();
            gen.setMouseTrackingEnabled(true);

            // 1. Format SGR packet test
            String sgrPress = SgrMouseProtocolGenerator.formatSgrPacket(0, 10, 20, true);
            if (!"\033[<0;10;20M".equals(sgrPress)) {
                System.out.println("FAILED (SGR Press format mismatch: " + sgrPress + ")");
                return 1;
            }
            String sgrRelease = SgrMouseProtocolGenerator.formatSgrPacket(0, 10, 20, false);
            if (!"\033[<0;10;20m".equals(sgrRelease)) {
                System.out.println("FAILED (SGR Release format mismatch: " + sgrRelease + ")");
                return 1;
            }

            // 2. TouchpadController center grid initialization
            TouchpadController tc = new TouchpadController(80, 24, 20, 40);
            if (tc.getVirtualCursorCol() != 40 || tc.getVirtualCursorRow() != 12) {
                System.out.println("FAILED (Touchpad initial center position mismatch)");
                return 1;
            }

            // 3. Touchpad single tap
            byte[] tapPacket = tc.handleSingleTap();
            String tapStr = new String(tapPacket, StandardCharsets.US_ASCII);
            if (!"\033[<0;40;12M\033[<0;40;12m".equals(tapStr)) {
                System.out.println("FAILED (Touchpad single tap packet mismatch)");
                return 1;
            }

            // 4. Touchpad long press
            byte[] longPressPacket = tc.handleLongPress();
            String longPressStr = new String(longPressPacket, StandardCharsets.US_ASCII);
            if (!"\033[<2;40;12M\033[<2;40;12m".equals(longPressStr)) {
                System.out.println("FAILED (Touchpad long press packet mismatch)");
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

    private static int testVsockPtyFramerStreamParsing() {
        System.out.print("[EMPIRICAL TEST 5] VsockPtyFramer & StreamParser Protocol Validation... ");
        try {
            byte[] sessionId = "1234567890abcdef".getBytes(StandardCharsets.US_ASCII);

            // 1. Serialize RESIZE frame
            byte[] resizeFrame = VsockPtyFramer.serializeResizeFrame(sessionId, 120, 40);
            VsockPtyFramer.Frame parsedResize = VsockPtyFramer.parseFrameHeaderAndPayload(resizeFrame);
            if (parsedResize.type != VsockPtyFramer.PacketType.RESIZE) {
                System.out.println("FAILED (Parsed frame type is not RESIZE)");
                return 1;
            }
            int[] dims = VsockPtyFramer.parseResizePayload(parsedResize.payload);
            if (dims[0] != 120 || dims[1] != 40) {
                System.out.println("FAILED (Parsed resize dimensions mismatch: " + dims[0] + "x" + dims[1] + ")");
                return 1;
            }

            // 2. StreamParser fragmented header & concatenated frames test
            byte[] payload1 = "Hello Terminal".getBytes(StandardCharsets.UTF_8);
            byte[] frame1 = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, payload1);
            byte[] payload2 = "Second Packet".getBytes(StandardCharsets.UTF_8);
            byte[] frame2 = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, payload2);

            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            combined.write(frame1);
            combined.write(frame2);
            byte[] combinedBytes = combined.toByteArray();

            final List<VsockPtyFramer.Frame> parsedFrames = new ArrayList<>();
            VsockPtyFramer.StreamParser parser = new VsockPtyFramer.StreamParser();

            // Feed byte by byte (heavy fragmentation test)
            for (byte b : combinedBytes) {
                parser.appendAndParse(new byte[]{b}, 0, 1, sessionId, new VsockPtyFramer.OnFrameParsedListener() {
                    @Override
                    public void onFrameParsed(VsockPtyFramer.Frame frame) {
                        parsedFrames.add(frame);
                    }
                    @Override
                    public void onError(Exception e) {}
                });
            }

            if (parsedFrames.size() != 2) {
                System.out.println("FAILED (StreamParser expected 2 frames, got " + parsedFrames.size() + ")");
                return 1;
            }

            if (!Arrays.equals(payload1, parsedFrames.get(0).payload) || !Arrays.equals(payload2, parsedFrames.get(1).payload)) {
                System.out.println("FAILED (StreamParser payload mismatch)");
                return 1;
            }

            // 3. Payload size limit check (> 64KB should throw IllegalArgumentException)
            try {
                byte[] hugePayload = new byte[65537];
                VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, hugePayload);
                System.out.println("FAILED (Payload size > 64KB was not rejected)");
                return 1;
            } catch (IllegalArgumentException expected) {
                // Expected behaviour
            }

            System.out.println("PASS");
            return 0;
        } catch (Exception e) {
            System.out.println("FAILED with exception: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private static int testConcurrentStress() {
        System.out.print("[EMPIRICAL TEST 6] Concurrent Multi-Threaded Stress Test... ");
        try {
            final CjkComposingTextManager mgr = new CjkComposingTextManager();
            final VsockPtyFramer.StreamParser parser = new VsockPtyFramer.StreamParser();
            final byte[] sessionId = "0000111122223333".getBytes(StandardCharsets.US_ASCII);

            int numThreads = 8;
            int opsPerThread = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch latch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            // Concurrent composing manager ops
                            mgr.setComposingText("線程" + threadId + "_" + i, 1);
                            mgr.getComposingText();
                            mgr.deleteBeforeCursor(1);

                            // Concurrent parser ops
                            byte[] payload = ("T" + threadId + "I" + i).getBytes(StandardCharsets.UTF_8);
                            byte[] frame = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, payload);
                            parser.appendAndParse(frame, 0, frame.length, sessionId, null);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            if (!finished) {
                System.out.println("FAILED (Concurrent stress test timed out)");
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
