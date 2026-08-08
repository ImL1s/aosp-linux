# BRIEFING — 2026-08-08T20:07:15Z

## Mission
Investigate Phase B audit findings regarding cheating, simulated executions, dead HMAC code, fake/mock test framework methods, and facade implementations in launch_vm.sh, auth.rs, socket_harness.py, real_env.py, and LinuxManagerService.java. Produce a detailed remediation report with exact evidence and concrete fix strategies.

## 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: Read-only investigator, synthesis, analysis report author
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2
- Original parent: a1e94585-26d4-4319-8c0d-e99ee7bac2e0
- Milestone: Phase B Remediation Investigation

## 🔒 Key Constraints
- Read-only investigation — do NOT implement code changes to project source (only produce handoff.md, BRIEFING.md, DISPATCH.md, progress.md in working directory).
- All evidence must cite exact file paths, line numbers, and verbatim code snippets.
- Fix strategies must address root causes strictly per system requirements (Rule 7, Requirement R3/Phase 3, real hardware check/error handling, real HMAC-SHA256, real VSOCK binding, real app enumeration/launch/image installation logic).

## Current Parent
- Conversation ID: a1e94585-26d4-4319-8c0d-e99ee7bac2e0
- Updated: 2026-08-08T20:07:15Z

## Investigation State
- **Explored paths**:
  - `guest/scripts/launch_vm.sh`
  - `guest/bridge-agent/src/auth.rs`
  - `guest/bridge-agent/src/main.rs`
  - `tests/e2e/framework/socket_harness.py`
  - `tests/e2e/framework/real_env.py`
  - `tests/e2e/framework/vsock_helper.py`
  - `tests/e2e/tier1_feature_coverage/test_m1_tier1.py`
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- **Key findings**:
  - `launch_vm.sh`: TEST_MODE=1 bypasses /dev/kvm and executes `sleep 3600` or exits 0 when crosvm missing.
  - `auth.rs`: Raw byte equality comparison `token_buf == secret` used in `perform_handshake`, while `HmacSha256` struct & `compute_hmac_response` are marked `#[allow(dead_code)]`.
  - `socket_harness.py`: Falls back to TCP `127.0.0.1` sockets on ports 5000, 5001, 5002, 15000, 15001, 15002 pretending to be AF_VSOCK.
  - `real_env.py` & `tests/e2e/`: Hardcoded constants returned for VTS compliance, CTS compatibility ("PASS"), idle power drop (1.4), dma_buf (42 / dict), location access (GPS coords), PCM chunk, virtiofs/erofs throughput; tests in `tests/e2e/` self-assert local python dicts.
  - `LinuxManagerService.java`: `getInstalledApps()` returns hardcoded app list when bridge is null; `launchLinuxApp()` returns `true` when disconnected; `installGuestImage()` ignores `imageFd` and returns `true` unconditionally.
- **Unexplored areas**: None. Complete coverage of all 5 requested items.

## Key Decisions Made
- Fully documented exact observations, logic chains, caveats, conclusions, and fix strategies in `handoff.md`.

## Artifact Index
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2/DISPATCH.md — Dispatch log
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2/BRIEFING.md — Working memory index
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2/progress.md — Liveness heartbeat
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2/handoff.md — Final deliverable report
