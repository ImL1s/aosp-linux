## 2026-08-08T12:53:26Z
You are dispatched as worker_r2_1 (Host Portal AF_VSOCK & Image Payload Developer).
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r2_1

Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Explorer Report File: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_1/handoff.md
Target File: /Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Your objective:
1. Read ORIGINAL_REQUEST.md and /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_1/handoff.md.
2. In LinuxPortalService.java:
   - PURGE ALL occurrences of `new Socket("localhost", ...)` (lines 713, 724, 747).
   - Implement authenticated `VsockPortalClient` using `android.system.Os.socket(AF_VSOCK=40, SOCK_STREAM, 0)` and `VmSocketAddress(5000, guestCid)`.
   - Pack 13-byte Big-Endian VSOK framing header (`VSOK_MAGIC = 0x56534F4B`, frameType=0x01, payloadLen, sequenceId).
   - In `openHardwareCamera`, convert YUV_420_888 pixels from `android.media.Image` into NV21 byte array (`convertYuv420ToNv21`), construct binary camera frame header (`subType = 0x43414D46` "CAMF", width, height, format=NV21, timestampNs, payloadSizeBytes) followed by NV21 bytes, and send over VsockPortalClient.
   - Refactor `sendVsockAudioPayload` and `sendGeoClueLocationUpdate` to use binary subTypes (`0x4155444F` "AUDO" and `0x47454F43` "GEOC") over VsockPortalClient.
3. Verify changes.
4. Write handoff.md in /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r2_1/handoff.md detailing your edits, verification commands, and results. Then report completion via send_message.
