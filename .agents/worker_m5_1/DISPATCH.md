## 2026-08-08T06:15:07Z
You are Worker 1 for Milestone M5 (Real System Hardware Portals - R5).
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_1

Mandatory context files to read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1/handoff.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/handoff.md

Write Ownership:
- frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java
- frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java
(and related internal service helper interfaces in frameworks/base/services/core/java/com/android/server/linux/ if needed).

Task Objective:
Implement Milestone M5 requirements:
1. LinuxPortalService.java:
   - Replace mAppOpsStore ConcurrentHashMap with real AppOpsManager system calls (unsafeCheckOpRaw, noteOpNoThrow) using OPSTR_CAMERA, OPSTR_RECORD_AUDIO, OPSTR_FINE_LOCATION, OPSTR_COARSE_LOCATION. Include null-checks for mContext/mAppOpsManager for unit test compatibility.
   - Replace in-memory dummy models for Camera, Audio, Location with real system APIs:
     * Camera: CameraManager, AvailabilityCallback for contention, openCamera, ImageReader (YUV_420_888), streaming frames over vsock port 5000 to /dev/video0.
     * Audio: AudioRecord (PCM 16-bit), background thread, privacy zero-filling, channel downmix, streaming PCM audio over vsock port 5000 to virtio-snd.
     * Location: LocationManager.requestLocationUpdates(), GeoClue D-Bus JSON updates over vsock port 5000, coarse location obfuscation support.
     * Cleanup: Register lifecycle hooks with LinuxManagerService to release hardware on VM stop/suspend.

2. LinuxStorageProvider.java:
   - Remove manual boolean setters (setVmRunning, setCeKeyAvailable, setReadOnlyMount) and manual state fields.
   - Link SAF provider dynamically to LocalServices.getService(LinuxManagerInternal.class) VM state (STATE_RUNNING) and vold/LinuxCeKeyManager LUKS2 mount lifecycle (isCeKeyAvailable(), isReadOnlyMount()).
   - Trigger ContentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null) on VM state and storage unlock transitions.
   - Ensure unit tests (LinuxStorageProviderTest) work seamlessly via mock/fake LocalServices.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Verification Requirements:
After making edits, run the build and test verification script:
`./scripts/run_m5_verification.sh`
and any unit test runners. Document all build/test commands and exact output in your report.

Write your report to /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_1/handoff.md and notify orchestrator via send_message when done.
