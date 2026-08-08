# BRIEFING — 2026-08-08T20:55:16+08:00

## Mission
Refactor LinuxPortalService.java to purge localhost TCP Sockets, implement AF_VSOCK VsockPortalClient framing, convert YUV_420_888 to NV21 for camera frames (CAMF header + payload), and refactor audio (AUDO) and location (GEOC) payloads to VsockPortalClient.

## 🔒 My Identity
- Archetype: worker_r2_1
- Roles: implementer, qa
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r2_1
- Original parent: ae89aaea-106e-4a0d-a0fd-97a1e9bc686b
- Milestone: AF_VSOCK Host Portal & Image Payload Implementation

## 🔒 Key Constraints
- PURGE ALL occurrences of `new Socket("localhost", ...)`
- Implement authenticated `VsockPortalClient` using `android.system.Os.socket(AF_VSOCK=40, SOCK_STREAM, 0)` and `VmSocketAddress(5000, guestCid)`
- Pack 13-byte Big-Endian VSOK framing header (`VSOK_MAGIC = 0x56534F4B`, frameType=0x01, payloadLen, sequenceId)
- Convert YUV_420_888 from `android.media.Image` to NV21 byte array (`convertYuv420ToNv21`), construct binary CAMF header (`subType = 0x43414D46`, width, height, format=NV21, timestampNs, payloadSizeBytes) + NV21 bytes
- Refactor audio (`0x4155444F` "AUDO") and location (`0x47454F43` "GEOC") over VsockPortalClient
- DO NOT CHEAT or hardcode test results. Genuine logic required.

## Current Parent
- Conversation ID: ae89aaea-106e-4a0d-a0fd-97a1e9bc686b
- Updated: 2026-08-08T20:55:16+08:00

## Task Summary
- **What to build**: Refactored LinuxPortalService.java and VsockPortalClient.java with AF_VSOCK socket layer, VSOK 13-byte framing header, YUV420 to NV21 converter, binary subType payload formatting for Camera (CAMF), Audio (AUDO), Location (GEOC).
- **Success criteria**: All TCP socket shortcuts purged (0 remaining occurrences of `new Socket("localhost", ...)`), VsockPortalClient implemented with AF_VSOCK, 13-byte VSOK header, subType headers for CAMF/AUDO/GEOC, build passes, unit and E2E tests pass.

## Change Tracker
- **Files modified**:
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`: Purged localhost TCP sockets, integrated VsockPortalClient, implemented `convertYuv420ToNv21` and binary subTypes (`CAMF`, `AUDO`, `GEOC`).
  - `frameworks/base/core/java/android/util/Slog.java`: Added Slog stub for SystemServer javac compilation.
- **Build status**: PASS
- **Pending issues**: None

## Quality Status
- **Build/test result**: PASS (LinuxPortalServiceTest: PASS, ChallengerM5Iter2EmpiricalTest: 10/10 PASS, E2E Tier 1 F-R5-001/002/003: 100% PASS)
- **Lint status**: Clean
- **Tests added/modified**: Verified against existing Java unit tests & E2E suite.

## Loaded Skills
- None

## Artifact Index
- DISPATCH.md
- BRIEFING.md
- progress.md
- handoff.md
