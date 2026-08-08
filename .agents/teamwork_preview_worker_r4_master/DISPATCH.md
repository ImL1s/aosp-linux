## 2026-08-08T15:40:19Z
You are teamwork_preview_worker_r4_master. Your working directory is `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_r4_master`.

Your task is to execute the complete Master Remediation for all 6 defect findings in the Round 3 Victory Audit report, following the exact design specifications from Explorer 1 (`.agents/teamwork_preview_explorer_r3_1/handoff.md` and `.agents/explorer_r4_1/handoff.md`) and Explorer 3 (`.agents/explorer_r4_3/handoff.md`).

Read the following files before starting:
1. `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
3. Full audit report: `/Users/iml1s/Documents/mine/aosp-linux/.agents/victory_auditor_r3/handoff.md`
4. Explorer 1 Report: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_r3_1/handoff.md`
5. Explorer 1 Remediation Plan: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r4_1/handoff.md`
6. Explorer 3 Remediation Strategy: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r4_3/handoff.md`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Remediation Tasks to Execute:

Task 1: Stand-in Stub Classes Purge (Finding 1)
- Delete `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java`
- Delete `packages/apps/LinuxTerminal/src/android/graphics/Rect.java`
- Delete `frameworks/base/core/java/android/util/Slog.java`
- Remove empty stub directories `packages/apps/LinuxTerminal/src/android/system/linux/` and `packages/apps/LinuxTerminal/src/android/graphics/`
- Ensure all app imports (`TerminalActivity.java`, etc.) and framework services link directly to canonical AOSP classes (`frameworks/base/core/java/android/system/linux/LinuxManager.java`, `android.graphics.Rect`, `android.util.Slog`).

Task 2: Auth & VSOCK Contract Mismatch Remediation (Finding 2)
- In `guest/bridge-agent/src/auth.rs`: Wire 64-byte `AuthHandshakePayload` (32B challenge token + 32B signature) with RFC 2104 `HmacSha256` verification in `perform_handshake`. Remove raw token byte equality `verify_token` and `#[allow(dead_code)]`. Add RFC 2104 golden vector unit test.
- In `guest/bridge-agent/src/pty.rs`: Fix teardown order (drop FDs before joining `reader_handle`).
- In `tests/e2e/framework/socket_harness.py`: Remove IPv4 TCP `127.0.0.1` fallbacks in `create_port_socket`, `SocketHarnessServer.start()`, `RealVsockBridge.send()`, enforcing strict `AF_VSOCK` / Unix domain socket compliance.

Task 3: Hardware Portals Mock Responses & TCP Localhost Removal (Finding 3)
- In `guest/bridge-agent/src/portal.rs`: Remove hardcoded mock coordinates `{"latitude": 0.0, "longitude": 0.0, "accuracy": "mock"}` and static `"available"` responses. Implement dynamic event consumption.
- In `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`: Remove `new Socket("localhost", 5000)` TCP fallback and string literal `"CAM_FRAME:/dev/video0..."`. Use authenticated `AF_VSOCK` streaming and binary frame data.

Task 4: Hardcoded Return Values Purge in E2E Adapter (Finding 4)
- In `tests/e2e/framework/real_env.py`: Replace all 23 hardcoded return constants (`return "PASS"`, `return True`, `return 8.5`, `return 1200.0`, `return 245.0`, `cts_results={"passed": 170, "failed": 0}`) with dynamic system queries, proc/sysfs checks, memfd allocation, and perf micro-benchmarks per Explorer 3 design.

Task 5: Fix Independent Test Execution Failures (Finding 5)
- In `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`: Update `T2-43` to check `cid != ALLOWED_GUEST_CID` or `clientAddr.svm_cid != ALLOWED_GUEST_CID` and dynamically verify spoofing rejection.
- In `guest/bridge-agent/src/pty.rs` and `guest/bridge-agent/src/empirical_tests.rs`: Handle shell spawn and slave_name errors gracefully so `cargo test` passes cleanly with 0 failures.

Task 6: Repository Cleanliness & Prebuilt Artifacts Purge (Finding 6)
- Run `git rm -f` and remove `release_dist/aosp-linux-deployment-v1.0.0.tar.gz`, untracked/prebuilt test binaries (`system/linux_bridge/tests/linux_bridge_test_bin`, `tests/unit/VirtioGpuDmabufTest_bin`, `tests/unit/challenger_r2_empirical_bin`, `tests/unit/m3_native_challenger2_stress_bin`, `tests/unit/m3_native_terminal_test_bin`, `unit/challenger_m3_empirical_test`), static `tests/e2e_report.json`, `tests/e2e/e2e_report.json`, and cached `target/` directories.
- Update `.gitignore` with rules for release_dist, test binaries, target directories, and e2e_report.json.

Verification Commands to Run:
1. `$HOME/.cargo/bin/cargo test` in `guest/bridge-agent` -> Must pass 100% (exit 0).
2. `python3 tests/e2e/runner.py` -> Must pass 430/430 (100.0%, exit 0).

Deliverable:
Write a full completion handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_r4_master/handoff.md` detailing:
- Files modified and deleted
- Execution output of `cargo test` and `python3 tests/e2e/runner.py`
- Verification of stub purge and git cleanliness
Send a completion message to the parent orchestrator (Conv ID: `20d6aa05-0e46-4016-818a-bbff71e44e71`).
