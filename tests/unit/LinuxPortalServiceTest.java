package tests.unit;

import com.android.server.linux.LinuxPortalService;

public class LinuxPortalServiceTest {
    public static void main(String[] args) {
        System.out.println("=== Running LinuxPortalServiceTest ===");

        LinuxPortalService portal = new LinuxPortalService(null);

        // AppOps mode check
        portal.setAppOp("org.gnome.Cheese", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);
        if (!portal.requestCameraAccess("org.gnome.Cheese")) {
            throw new RuntimeException("Test Failed: Camera access should be granted");
        }

        portal.setAppOp("org.gnome.Cheese", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_DENIED);
        if (portal.requestCameraAccess("org.gnome.Cheese")) {
            throw new RuntimeException("Test Failed: Camera access should be denied");
        }

        // Camera Contention
        portal.setAppOp("org.gnome.Cheese", LinuxPortalService.OP_CAMERA, LinuxPortalService.MODE_ALLOWED);
        portal.setAndroidAppActiveForCamera(true);
        if (portal.requestCameraAccess("org.gnome.Cheese")) {
            throw new RuntimeException("Test Failed: Camera access should be blocked during contention with native Android app");
        }
        portal.setAndroidAppActiveForCamera(false);

        // Resolution negotiation fallback
        LinuxPortalService.CameraSession session = portal.startCameraStream("org.gnome.Cheese", "s1", 3840, 2160, 120);
        if (session.width != 1920 || session.height != 1080 || session.fps != 30) {
            throw new RuntimeException("Test Failed: Resolution fallback failed: " + session.width + "x" + session.height + "@" + session.fps);
        }
        portal.stopCameraStream("s1");

        // Mic privacy toggle & stereo to mono
        portal.setAppOp("org.audacity.Audacity", LinuxPortalService.OP_RECORD_AUDIO, LinuxPortalService.MODE_ALLOWED);
        LinuxPortalService.MicSession micS = portal.startMicStream("org.audacity.Audacity", "m1", 48000, 2);
        byte[] inputPcm = new byte[1024];
        java.util.Arrays.fill(inputPcm, (byte) 0x12);

        portal.setMicPrivacyToggle(true);
        byte[] silentPcm = portal.processMicPcmFrame(micS, inputPcm);
        for (byte b : silentPcm) {
            if (b != 0) throw new RuntimeException("Test Failed: Privacy toggle failed to silence mic frame");
        }
        portal.setMicPrivacyToggle(false);

        short mono = portal.downmixStereoToMono((short) 1000, (short) 2000);
        if (mono != 1500) {
            throw new RuntimeException("Test Failed: Downmixing failed, expected 1500 got " + mono);
        }
        portal.stopMicStream("m1");

        // Test Mono mic session behavior under mono channel config
        LinuxPortalService.MicSession monoMicSession = new LinuxPortalService.MicSession("org.audacity.Audacity", "m2", 48000, 1);
        byte[] rawMonoInput = new byte[]{ 0x10, 0x00, 0x20, 0x00 }; // 2 mono 16-bit samples
        byte[] processedMono = portal.processMicPcmFrame(monoMicSession, rawMonoInput);
        if (processedMono[0] != 0x10 || processedMono[2] != 0x20) {
            throw new RuntimeException("Test Failed: Mono PCM was incorrectly downmixed under mono channel config");
        }

        // Location coarse rounding
        double[] obfuscated = portal.getObfuscatedLocation(25.0330123, 121.5654987, true);
        if (obfuscated[0] != 25.03 || obfuscated[1] != 121.57) {
            throw new RuntimeException("Test Failed: Coarse location obfuscation failed: " + obfuscated[0] + ", " + obfuscated[1]);
        }

        System.out.println("PASS: LinuxPortalServiceTest executed successfully.");
    }
}
