# BRIEFING — 2026-08-13T17:21:08Z

## Mission
Investigate the codebase for Auth & VSOCK Handshake protocol, token/secret generation, Guest agent cmdline parsing, Host C++ daemon listening server, HMAC-SHA256 handshake, VM state transition, and cargo workspace verification.

## 🔒 My Identity
- Archetype: survey_explorer
- Roles: Auth Protocol & Handshake Explorer
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2
- Original parent: 9bf4ed43-7f01-40fa-acc0-13647ab4d92d
- Milestone: Survey Phase

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Must use 繁體中文
- Document findings in survey_report.md and handoff.md
- Send completion message to parent when done

## Current Parent
- Conversation ID: 9bf4ed43-7f01-40fa-acc0-13647ab4d92d
- Updated: 2026-08-13T17:22:00Z

## Investigation State
- **Explored paths**:
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`
  - `system/linux_bridge/main.cpp`
  - `system/linux_bridge/socket_server.cpp` & `socket_server.h`
  - `system/linux_bridge/vsock_server.cpp` & `vsock_server.h`
  - `system/linux_bridge/hmac_auth.cpp` & `hmac_auth.h`
  - `system/linux_bridge/vsock_framing.h`
  - `guest/scripts/launch_vm.sh`
  - `guest/bridge-agent/src/main.rs`, `auth.rs`, `vsock.rs`, `empirical_tests.rs`
  - `guest/bridge-agent/Cargo.toml`
  - `guest/portal-agent/Cargo.toml`
- **Key findings**:
  1. Located all Host Java, Host C++, Guest Rust and Script source files for Auth & VSOCK Handshake.
  2. Identified Token/Secret mismatch defect: Host Java generates 32B token, C++ daemon expects 64B payload and generates a separate random secret when payload < 64B.
  3. Analyzed Guest agent `/proc/cmdline` parsing: `android_bridge.token=<hex>` is parsed and decoded into 32-byte binary secret in `guest/bridge-agent/src/auth.rs`.
  4. Analyzed Host C++ `VsockServer` listening on AF_VSOCK port 5000: checks CID 3, enforces 5s timeout, constant-time token comparison, replay protection (`markTokenUsed`), and RFC 2104 HMAC-SHA256 signature verification.
  5. Analyzed VM state transition chain: Vsock handshake success triggers `onVsockHandshakeSuccess` -> UDS `CMD_HANDSHAKE_COMPLETE` -> `LinuxBridgeService` -> `LinuxManagerService.notifyVmStarted()` (`STATE_RUNNING`). Identified Guest agent role reversal defect: Guest agent is currently listening on port 5000 instead of initiating connection to Host CID 2 Port 5000.
  6. Verified Cargo compilation: `$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu` passed cleanly (Exit Code 0) for both `bridge-agent` and `portal-agent`.
- **Unexplored areas**: None (all 6 tasks completed).

## Key Decisions Made
- Completed read-only investigation and synthesized findings in survey_report.md and handoff.md.

## Artifact Index
- /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/DISPATCH.md — Dispatch history
- /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/BRIEFING.md — Working memory index
- /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/progress.md — Liveness heartbeat & task status
- /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/survey_report.md — Detailed survey report
- /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/handoff.md — 5-Component handoff report
