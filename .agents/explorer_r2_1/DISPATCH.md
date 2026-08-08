## 2026-08-08T12:50:05Z
You are teamwork_preview_explorer_r2_1.
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_1

Task: Investigate Defect 1 — Host Portal TCP Fallback & Payload Format in `LinuxPortalService.java`

Original Request File:
/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md

Specific Audit Evidence to Investigate:
1. `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java` (lines 712, 723, 747):
   - Uses `new Socket("localhost", 5000)` fallback to TCP localhost socket instead of authenticated AF_VSOCK / VsockPortalClient.
2. Line 714 (or surrounding `sendVsockFrame` calls):
   - Transmits dummy string `"CAM_FRAME:/dev/video0:..."` instead of real camera image data / buffer metadata.
3. Phase 6 & Rule 5 Requirements:
   - Host portal communication must use authenticated AF_VSOCK (cid=guestCid, port=5000).
   - Camera portal must send actual image bytes/buffer metadata (not `"CAM_FRAME:/dev/video0"` string).

Required Deliverable:
Write a detailed investigation report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_1/handoff.md` detailing:
1. Exact lines in `LinuxPortalService.java` where TCP `new Socket("localhost", ...)` fallback and string payload `"CAM_FRAME:/dev/video0"` are used.
2. Precise code refactoring strategy for `LinuxPortalService.java` to route portal frames through authenticated AF_VSOCK / VsockPortalClient and send genuine image buffer metadata / NV21/YUV frame payloads.
