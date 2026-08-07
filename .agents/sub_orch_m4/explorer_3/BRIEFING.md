# BRIEFING — 2026-08-06T19:34:55+08:00

## Mission
Investigate F-R4-005 (.desktop Inotify Monitor Daemon in guest portal-agent) and F-R4-006 (Launcher3 Synthetic Shortcuts & Vsock 5000 metadata sync via LinuxAppTracker.java).

## 🔒 My Identity
- Archetype: Explorer
- Roles: Explorer 3 for M4 Focus Area 3
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m4/explorer_3
- Original parent: 201d1ff6-41a2-48c5-9802-5c9a8ccd985e
- Milestone: M4

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Focus Area 3: F-R4-005 (.desktop Inotify) and F-R4-006 (Launcher3 Synthetic Shortcuts)

## Current Parent
- Conversation ID: 201d1ff6-41a2-48c5-9802-5c9a8ccd985e
- Updated: 2026-08-06T19:34:55+08:00

## Investigation State
- **Explored paths**:
  - `frameworks/base/core/java/android/system/linux/LinuxAppInfo.java` (Parcelable metadata container)
  - `frameworks/base/core/java/android/system/linux/LinuxManager.java` (Facade API)
  - `frameworks/base/core/java/android/system/linux/ILinuxManager.aidl` (Binder interface)
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` (SystemServer service)
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java` (IPC bridge service, CMD_APP_SYNC definition)
  - `system/linux_bridge/` (`main.cpp`, `vsock_framing.h`, `vsock_server.h`)
  - `guest/bridge-agent/` (`src/main.rs`)
  - `tests/e2e/tier1_feature_coverage/test_m4_tier1.py` (T1-106..T1-115)
  - `tests/e2e/tier2_boundary_corner/test_m4_tier2.py` (T2-106..T2-115)
  - `tests/e2e/runner.py` (E2E Test Runner)

- **Key findings**:
  1. `LinuxAppInfo.java`, `ILinuxManager.aidl`, `LinuxManager.java`, `LinuxManagerService.java`, and `LinuxBridgeService.java` already define `getInstalledApps()`, `launchLinuxApp()`, and command `CMD_APP_SYNC` (0x0200).
  2. `LinuxBridgeService.java` currently hardcodes default apps (`org.gnome.Terminal`, `org.mozilla.firefox`) when cache is empty and lacks an active `CMD_APP_SYNC` incoming packet handler.
  3. `guest/portal-agent` (Rust inotify watcher daemon) and `packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java` do not exist in the codebase yet and must be created by the Worker.
  4. Full test specification exists in E2E tests (T1-106..110 & T2-106..110 for F-R4-005, T1-111..115 & T2-111..115 for F-R4-006).

- **Unexplored areas**: None remaining for Focus Area 3 scope.

## Key Decisions Made
- Formulated concrete implementation design for `portal-agent` (Guest inotify watcher daemon), `LinuxBridgeService` app sync handling, and `LinuxAppTracker.java` (Launcher3 synthetic shortcut generator).

## Artifact Index
- DISPATCH.md — Initial dispatch message
- BRIEFING.md — Working briefing index
- progress.md — Liveness heartbeat and progress tracking
- handoff.md — Final investigation and handoff report
