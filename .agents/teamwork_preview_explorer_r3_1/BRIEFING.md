# BRIEFING — 2026-08-08T13:30:00Z

## Mission
Investigate and design exact remediation fixes for Defect 1 (Stand-in Stub Classes) and Defect 2 (Auth & VSOCK Contract Mismatch) from the Round 3 Victory Audit report.

## 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: Investigator / Remediation Fix Designer
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_r3_1
- Original parent: 7b9401b7-29a1-4c9f-99d0-c1920772f926 (Orchestrator Conv ID: 20d6aa05-0e46-4016-818a-bbff71e44e71)
- Milestone: Remediation Design for Defect 1 & Defect 2

## 🔒 Key Constraints
- Read-only investigation — do NOT implement changes in source tree directly
- Produce detailed handoff.md in working directory with exact line numbers, code recommendations, and verification steps
- Communicate in Traditional Chinese when applicable

## Current Parent
- Conversation ID: 7b9401b7-29a1-4c9f-99d0-c1920772f926
- Updated: 2026-08-08T13:30:00Z

## Investigation State
- **Explored paths**:
  - `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java` (dummy stub)
  - `packages/apps/LinuxTerminal/src/android/graphics/Rect.java` (stub)
  - `frameworks/base/core/java/android/util/Slog.java` (stub)
  - `frameworks/base/core/java/android/system/linux/LinuxManager.java` (canonical)
  - `packages/apps/LinuxTerminal/Android.bp` & root `Android.bp`
  - `patches/aosp_frameworks_base.patch`
  - `guest/bridge-agent/src/auth.rs`, `main.rs`, `pty.rs`, `empirical_tests.rs`
  - `system/linux_bridge/hmac_auth.h`, `hmac_auth.cpp`, `vsock_framing.h`, `vsock_server.cpp`
  - `tests/e2e/framework/socket_harness.py`, `vsock_helper.py`
- **Key findings**:
  - Identified 3 stub files to be purged: `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java`, `packages/apps/LinuxTerminal/src/android/graphics/Rect.java`, `frameworks/base/core/java/android/util/Slog.java`.
  - Identified `auth.rs` raw token equality `verify_token` vs C++ 64-byte `AuthHandshakePayload` mismatch. Designed RFC 2104 HMAC-SHA256 64-byte handshake wiring.
  - Identified PTY teardown deadlock in `pty.rs` causing `cargo test` failures.
  - Identified IPv4 TCP `127.0.0.1` fallback removal in `socket_harness.py`.
- **Unexplored areas**: None for Defect 1 & 2 scope.

## Key Decisions Made
- Prepared exact step-by-step remediation design with file paths, line numbers, code snippets, and build/test verification commands.

## Artifact Index
- handoff.md — Comprehensive Design & Remediation Report
- progress.md — Liveness Heartbeat
- DISPATCH.md — Received Dispatch Messages
