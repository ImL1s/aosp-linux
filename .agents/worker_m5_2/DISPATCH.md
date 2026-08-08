## 2026-08-08T06:24:06Z
<USER_REQUEST>
You are Worker 2 for Milestone M5 (Real System Hardware Portals - R5 Iteration 2).
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_2

Mandatory context files to read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m5_1/handoff.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m5_1/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_iter2/handoff.md

Write Ownership:
- frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java

Remediation Task Objective:
Implement the 7 fixes in LinuxPortalService.java:
1. Camera2 Real Hardware Binding: Validate dimensions (width > 0 && height > 0). Call mCameraManager.openCamera(...) and create CameraCaptureSession with ImageReader surface so real hardware frames flow to vsock port 5000 /dev/video0.
2. Coarse Location & Obfuscation: Wire requestLocationAccess() to accept OPSTR_COARSE_LOCATION. In onLocationChanged(), call getObfuscatedLocation(location) when coarse location permission is active before pushing GeoClue D-Bus JSON over vsock.
3. AppOps noteOpNoThrow: Add mAppOpsManager.noteOpNoThrow(...) calls on camera, audio, and location stream initialization to trigger Android system status bar privacy indicators and security auditing.
4. Camera Contention Recovery: Fix AvailabilityCallback.onCameraUnavailable() so it ignores callbacks for LinuxPortalService's own opened camera ID. When setAndroidAppActiveForCamera(false) is called, auto-resume active guest camera sessions.
5. Audio Multi-Session & Downmix: Fix mAudioRecordThread loop to iterate over all active mMicSessions.values() instead of hardcoding session ID "s1". Call downmixStereoToMono() in processMicPcmFrame() when session channels == 1 and PCM buffer is stereo.
6. Input Validation & USB Unplug: Validate camera dimensions (reject <= 0). In setHardwareCameraPluggedIn(false), close active CameraCaptureSession/ImageReader and set active camera sessions to isActive = false.
7. Socket Connection Reuse: Maintain persistent vsock/socket connection per streaming session rather than creating new TCP socket connections per 10ms PCM frame / location update.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Verification Requirements:
After making edits, run the build and test verification script:
`./scripts/run_m5_verification.sh`
and unit tests. Document all build/test commands and exact output in your report.

Write your report to /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_2/handoff.md and notify orchestrator via send_message when done.
</USER_REQUEST>
