## 2026-08-08T06:23:21Z

You are Explorer for Milestone M5 Iteration 2.
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_iter2

Mandatory context files to read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m5_1/handoff.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m5_1/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_1/handoff.md

Objective:
Formulate a detailed, concrete fix strategy for frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java addressing all 7 defects identified by Reviewer 1 and Challenger 1:
1. Camera2 Real Hardware Binding: Wire mCameraManager.openCamera(...) and CameraCaptureSession inside startCameraStream() so ImageReader receives real frames and sends them over vsock port 5000.
2. Coarse Location & Obfuscation: Wire requestLocationAccess() to accept OP_COARSE_LOCATION, and call getObfuscatedLocation() inside onLocationChanged() for coarse location permissions.
3. AppOps noteOpNoThrow: Add AppOpsManager.noteOpNoThrow(...) calls for camera, audio, and location stream starts to trigger Android privacy indicators.
4. Camera Contention Recovery: Fix AvailabilityCallback self-cancellation loop by checking if mActiveCameraDevice owns the cameraId, and allow auto-resuming guest sessions when setAndroidAppActiveForCamera(false) is called.
5. Audio Multi-Session & Downmix: Fix mAudioRecordThread loop to iterate over all active mMicSessions instead of hardcoding "s1". Wire downmixStereoToMono() into PCM frame processing.
6. Input Validation & USB Unplug: Validate width > 0 && height > 0 in startCameraStream(). Deactivate sessions and close ImageReader when setHardwareCameraPluggedIn(false) is called.
7. Socket / Vsock Connection Reuse: Keep persistent vsock/socket connection per streaming session instead of reconnecting on every PCM buffer.

Write your fix strategy report to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_iter2/handoff.md and report back via send_message.
