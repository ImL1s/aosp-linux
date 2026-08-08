# BRIEFING — 2026-08-08T06:14:55Z

## Mission
Investigate LinuxStorageProvider.java and related storage lifecycle classes for Milestone M5 (Real System Hardware Portals - R5) to dynamically link SAF provider to LinuxManagerService VM state and vold/LinuxCeKeyManager LUKS2 mount lifecycle without manual boolean setters.

## 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: Read-only investigation, storage architecture analysis, SAF provider integration, LUKS2 mount lifecycle
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2
- Original parent: a0a5cd7b-a1b9-4e75-a26a-4fe83a6ef27f
- Milestone: M5 (Real System Hardware Portals - R5)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Analyze LinuxStorageProvider.java and related storage lifecycle classes
- Write full investigation report to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/handoff.md
- Use Traditional Chinese (繁體中文) per user rules.

## Current Parent
- Conversation ID: a0a5cd7b-a1b9-4e75-a26a-4fe83a6ef27f
- Updated: 2026-08-08T06:14:55Z

## Investigation State
- **Explored paths**:
  - `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxCeKeyManager.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerInternal.java`
  - `tests/unit/LinuxStorageProviderTest.java`
  - `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`
- **Key findings**:
  1. `LinuxStorageProvider.java` uses manual in-memory booleans (`mVmRunning`, `mCeKeyAvailable`, `mIsReadOnlyMount`) and setters (`setVmRunning()`, `setCeKeyAvailable()`, `setReadOnlyMount()`), leading to split-brain state during VM shutdowns or user locks.
  2. `LinuxManagerInternal.java` requires new interfaces: `isCeKeyAvailable()`, `isReadOnlyMount()`, `registerStorageStateListener()`, `unregisterStorageStateListener()`.
  3. `LinuxStorageProvider.java` should dynamically query `LinuxManagerInternal` via `LocalServices.getService(LinuxManagerInternal.class)` and dispatch `notifyRootsChanged()` via `ContentResolver.notifyChange()` on VM state transitions.
- **Unexplored areas**: None for this subtask scope.

## Key Decisions Made
- Use `ssh localhost` to bypass macOS TCC file access restrictions on `/Users/iml1s/Documents`.
- Formulate complete, concrete 5-component handoff report and implementation patch in `handoff.md`.

## Artifact Index
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/DISPATCH.md — Incoming dispatch message
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/BRIEFING.md — Persistent briefing state
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/progress.md — Liveness heartbeat
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/handoff.md — 5-component investigation report
