# BRIEFING — 2026-08-08T14:13:10Z

## Mission
Investigate M5 integration boundaries (LinuxPortalService, LinuxStorageProvider) and build/test targets for AOSP Linux.

## 🔒 My Identity
- Archetype: Teamwork Explorer
- Roles: Read-only investigation, boundary analysis, test verification mapping
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3
- Original parent: a0a5cd7b-a1b9-4e75-a26a-4fe83a6ef27f
- Milestone: M5 (Real System Hardware Portals - R5)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Report output to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/handoff.md
- Communicate findings via send_message to parent agent

## Current Parent
- Conversation ID: a0a5cd7b-a1b9-4e75-a26a-4fe83a6ef27f
- Updated: 2026-08-08T14:13:10Z

## Investigation State
- **Explored paths**:
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
  - `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxCeKeyManager.java`
  - `Android.bp`, `system/linux_bridge/Android.bp`
  - `scripts/run_m5_verification.sh`
  - `tests/unit/LinuxPortalServiceTest.java`
  - `tests/unit/LinuxStorageProviderTest.java`
  - `tests/e2e/tier1_feature_coverage/test_m5_tier1.py`
  - `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`
- **Key findings**:
  1. `LinuxPortalService.java` relies on `mAppOpsStore` in-memory map instead of system `AppOpsManager`. `CameraManager`, `AudioRecord`, and `LocationManager` integration are completely simulated in memory and must be replaced with real system service calls and vsock port 5000 frame streaming to guest `v4l2loopback`, `virtio-snd`, and GeoClue.
  2. `LinuxStorageProvider.java` uses manual boolean setters (`setVmRunning`, `setCeKeyAvailable`) instead of querying `LinuxManagerInternal` local service (`getVmState()`, `isCeKeyAvailable()`). It must bind to LUKS2 CE volume mount state derived via HKDF-SHA256 from user CE key by `LinuxCeKeyManager` / `vold`.
  3. Build targets exist in `Android.bp` (`services.linux`, `linux_bridge`), and local unit/E2E test suite targets are executed via `scripts/run_m5_verification.sh` using `javac`, `clang++`, `cargo`, and `python3 tests/e2e/runner.py`.
- **Unexplored areas**: None for M5 investigation scope.

## Key Decisions Made
- Used mirrored directory `/tmp/aosp-linux-work/aosp-linux/` to bypass local macOS TCC restrictions on `~/Documents` and synced back to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/` via helper script.

## Artifact Index
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/DISPATCH.md` — Dispatch log
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/BRIEFING.md` — State briefing
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/progress.md` — Progress heartbeat
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/handoff.md` — Final investigation report
