## 2026-08-08T06:20:19Z
You are Reviewer 1 for Milestone M5 (Real System Hardware Portals - R5).
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m5_1

Mandatory context files:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_1/handoff.md

Target Files to Review:
- frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java

Objective:
Review LinuxPortalService.java implementation for correctness, robustness, and API compliance:
1. AppOpsManager integration (unsafeCheckOpRaw, noteOpNoThrow for OPSTR_CAMERA, OPSTR_RECORD_AUDIO, OPSTR_FINE_LOCATION, OPSTR_COARSE_LOCATION, and fallback handling when mContext is null).
2. Real system hardware APIs (CameraManager / Camera2 contention handling, AudioRecord PCM 16-bit zero-filling/downmixing, LocationManager updates & coarse location obfuscation).
3. Lifecycle hooks on VM stop/suspend.
4. Run verification script: ./scripts/run_m5_verification.sh and unit tests.

Write your review report to /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m5_1/handoff.md with your verdict (APPROVE or REQUEST_CHANGES) and send message.
