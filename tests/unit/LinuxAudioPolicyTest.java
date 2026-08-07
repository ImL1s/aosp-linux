package tests.unit;

import com.android.server.linux.LinuxAudioPolicyHandler;

public class LinuxAudioPolicyTest {
    public static void main(String[] args) {
        System.out.println("=== Running LinuxAudioPolicyTest ===");

        LinuxAudioPolicyHandler handler = new LinuxAudioPolicyHandler(null);

        // Background focus rejection
        boolean focusGranted = handler.requestAudioFocus(true, false);
        if (focusGranted) {
            throw new RuntimeException("Test Failed: Audio focus should be rejected for background app without foreground service");
        }

        // Phone call ducking
        handler.setFocusState("LOSS_TRANSIENT_CAN_DUCK");
        if (Math.abs(handler.getVolumeFactor() - 0.2f) > 0.001) {
            throw new RuntimeException("Test Failed: Volume factor should be 0.2 during call ducking");
        }

        // Alarm clock pause
        handler.setFocusState("LOSS_TRANSIENT");
        if (!handler.isPaused()) {
            throw new RuntimeException("Test Failed: Playback should be paused during alarm clock loss transient");
        }

        // Restore GAIN
        handler.setFocusState("GAIN");
        if (handler.isPaused() || Math.abs(handler.getVolumeFactor() - 1.0f) > 0.001) {
            throw new RuntimeException("Test Failed: Focus GAIN should restore volume 1.0 and unpause");
        }

        // Format conversion & mixing
        float f = handler.convertInt16ToFloat32((short) 32767);
        if (f < 0.99f || f > 1.0f) {
            throw new RuntimeException("Test Failed: INT16 to FLOAT32 conversion error: " + f);
        }

        float mixed = handler.mixAudioSamples(0.7f, 0.5f);
        if (Math.abs(mixed - 1.0f) > 0.001) {
            throw new RuntimeException("Test Failed: Sample mixing clipping failed: " + mixed);
        }

        // Buffer overflow dropping
        for (int i = 0; i < 150; i++) {
            handler.enqueueFrame("frame_" + i);
        }
        if (handler.getQueueSize() != 100) {
            throw new RuntimeException("Test Failed: Audio queue max size should be capped at 100, got " + handler.getQueueSize());
        }

        System.out.println("PASS: LinuxAudioPolicyTest executed successfully.");
    }
}
