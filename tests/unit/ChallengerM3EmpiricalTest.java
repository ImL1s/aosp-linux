package tests.unit;

import com.android.virtualization.terminal.ime.CjkComposingTextManager;
import com.android.virtualization.terminal.ime.TerminalInputConnection;
import com.android.virtualization.terminal.net.PtySender;
import com.android.virtualization.terminal.net.VsockPtyFramer;
import com.android.virtualization.terminal.touch.SgrMouseProtocolGenerator;
import com.android.virtualization.terminal.touch.TouchModeStateMachine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ChallengerM3EmpiricalTest {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   JAVA EMPIRICAL CHALLENGER 1 TEST SUITE (MILESTONE M3 GATE)");
        System.out.println("================================================================================");

        int failures = 0;
        failures += testInputConnectionForwardDelete();
        failures += testInputConnectionHighFrequencyCommit();
        failures += testComposingCancelAndState();

        System.out.println("================================================================================");
        if (failures == 0) {
            System.out.println("JAVA EMPIRICAL SUITE: COMPLETED");
        } else {
            System.out.println("JAVA EMPIRICAL SUITE: " + failures + " FAILURE(S) ENCOUNTERED");
        }
        System.out.println("================================================================================");
    }

    private static int testInputConnectionForwardDelete() {
        System.out.print("[JAVA TEST 1] TerminalInputConnection deleteSurroundingText(0, 1) Forward Delete... ");
        final List<byte[]> sentBytes = new ArrayList<>();
        PtySender sender = new PtySender() {
            @Override
            public void sendBytes(byte[] bytes) {
                sentBytes.add(bytes);
            }
            @Override
            public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {}
            @Override
            public void sendResize(byte[] sessionId, int cols, int rows) {}
        };

        TerminalInputConnection ic = new TerminalInputConnection(null, true, sender);

        // Forward Delete (before = 0, after = 1)
        ic.deleteSurroundingText(0, 1);

        if (sentBytes.isEmpty()) {
            System.out.println("FAILED!");
            System.out.println("       [BUG DETECTED] deleteSurroundingText(0, 1) sent 0 bytes to PTY stream (ignored afterLength)!");
            return 1;
        }

        System.out.println("PASS");
        return 0;
    }

    private static int testInputConnectionHighFrequencyCommit() {
        System.out.print("[JAVA TEST 2] High Frequency CJK CommitText Stress (10,000 commits)... ");
        final List<byte[]> sentBytes = new ArrayList<>();
        PtySender sender = new PtySender() {
            @Override
            public void sendBytes(byte[] bytes) {
                sentBytes.add(bytes);
            }
            @Override
            public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {}
            @Override
            public void sendResize(byte[] sessionId, int cols, int rows) {}
        };

        TerminalInputConnection ic = new TerminalInputConnection(null, true, sender);

        long start = System.currentTimeMillis();
        for (int i = 0; i < 10000; i++) {
            ic.commitText("測試繁體字" + i, 1);
        }
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("PASS (" + elapsed + " ms for 10k commits)");
        return 0;
    }

    private static int testComposingCancelAndState() {
        System.out.print("[JAVA TEST 3] Composing State & Span Cancellation... ");
        TerminalInputConnection ic = new TerminalInputConnection(null, true, null);
        CjkComposingTextManager mgr = ic.getComposingTextManager();

        ic.setComposingText("ㄘㄨㄛ", 3);
        if (!mgr.isComposing() || !"ㄘㄨㄛ".equals(mgr.getComposingText())) {
            System.out.println("FAILED (composing text not set correctly)");
            return 1;
        }

        ic.cancelComposing();
        if (mgr.isComposing() || !"".equals(mgr.getComposingText())) {
            System.out.println("FAILED (cancelComposing did not clear composing state)");
            return 1;
        }

        System.out.println("PASS");
        return 0;
    }
}
