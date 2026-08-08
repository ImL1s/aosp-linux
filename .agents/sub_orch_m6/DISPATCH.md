# DISPATCH — Sub-Orchestrator M6 (E2E Testing Track Orchestrator)

## Mission
Execute the iteration loop (Explorer -> Worker -> Reviewer -> Challenger -> Auditor -> Gate check) for Milestone M6: Clean & Honest E2E Test Suite - R6.

## Objective & Scope
- CI Workflow (`.github/workflows/ci.yml`): Remove static `tests/e2e_report.json` assertion check at line 33. Replace with real test runner invocation (`python3 tests/e2e/runner.py --tier 1 --tier 2`).
- E2E Framework & Runner (`tests/e2e/framework/mock_env.py`, `tests/e2e/runner.py`): Replace in-memory dummy objects and hardcoded CTS/AVB results with real LocalSocket, Vsock, IPC, binary checks, and system service assertions.
- Test Cases (`tests/e2e/tier1_feature_coverage/`, `tier2_boundary_corner/`, `tier3_cross_feature/`, `tier4_real_world/`): Eliminate tautological string assertions. Replace with real IPC binary frame sends, socket responses, and binary return code checks.

## Write Ownership
- `.github/workflows/ci.yml`
- `tests/e2e_report.json`
- `tests/e2e/*`

## 2026-08-08T10:34:26Z
Iteration 4 Execution Request:
1. Dispatch a Worker (teamwork_preview_worker) with instructions to fix tests/e2e/framework/socket_harness.py and any flaky test in tests/e2e/.
   The Worker MUST implement proper thread shutdown flags, thread .join(), socket shutdown(SHUT_RDWR) before close(), error catching on closed sockets, non-blocking socket loops or thread synchronization, increased listen backlog, and ensure python3 .agents/challenger_m6_concurrency_stress/stress_harness.py passes 100%.
   Include mandatory integrity warning.
2. Dispatch 2 Reviewers (teamwork_preview_reviewer) to independently review code quality, socket safety, and honest test execution.
3. Dispatch 2 Challengers (teamwork_preview_challenger) to run stress verification.
4. Dispatch 1 Forensic Auditor (teamwork_preview_auditor) to perform integrity verification.
5. Evaluate Gate Verdict in GATE_STATUS.md.
6. Once Gate Result is PASS:
   - Publish TEST_READY.md at project root.
   - Mark Milestone M6 as DONE in SCOPE.md and progress.md.
   - Send handoff report to parent orchestrator.

## 2026-08-08T10:41:36Z
Task for Iteration 5 Execution:
1. Dispatch Worker 5 (`teamwork_preview_worker`) with instructions to execute complete remediation:
   - Fix `tests/e2e/framework/socket_harness.py` ThreadPoolExecutor teardown deadlock: ensure worker threads do not block `sys.exit()` (e.g., set worker threads as daemon threads or call `executor.shutdown(wait=False, cancel_futures=True)` and explicitly close listener/client sockets).
   - Fix Port 5001 vsock bridge socket request failures under 50-worker concurrency so 2,000/2,000 operations pass cleanly.
   - Rewrite all 35+ hardcoded / tautological test cases across `test_m5_tier1.py`, `test_m4_tier1.py`, `test_m1_tier1.py`, `test_m5_tier2.py` identified by Forensic Auditor (`.agents/auditor_m6_integrity_gen1/handoff.md`) and Reviewer 2 (`.agents/reviewer_m6_honest_execution_gen4/handoff.md`). Make real calls to `self.mock_env` adapters, binary inspectors (`BinaryInspector.compile_and_verify_selinux()`), socket harness IPC, or actual file I/O.
   - Include mandatory integrity warning: "DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work."
2. Dispatch 2 Reviewers (`teamwork_preview_reviewer`), 2 Challengers (`teamwork_preview_challenger`), and 1 Forensic Auditor (`teamwork_preview_auditor`) for Iteration 5 Gate check.
3. Evaluate Gate Verdict in `GATE_STATUS.md`.
4. Once Gate Result is PASS (ALL APPROVE & Auditor CLEAN):
   - Publish `TEST_READY.md` at project root (`/Users/iml1s/Documents/mine/aosp-linux/TEST_READY.md`).
   - Mark Milestone M6 as DONE in `SCOPE.md` and `progress.md`.
   - Send handoff report to parent orchestrator (`341e1391-c11c-4495-a47e-96c41635ffc2`).
