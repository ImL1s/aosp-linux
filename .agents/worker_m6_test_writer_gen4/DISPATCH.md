## 2026-08-08T10:35:06Z
<USER_REQUEST>
You are Worker 4 (worker_m6_test_writer_gen4) working on Milestone M6 (Clean & Honest E2E Test Suite - R6).
Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m6_test_writer_gen4

Please read original context files first:
- `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
- `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m6/SCOPE.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m6_concurrency_stress_gen2/handoff.md`

Objective:
Remediate the defects in `tests/e2e/framework/socket_harness.py` and any flaky tests in `tests/e2e/` identified during Challenger 2 stress testing:

Defects to fix:
1. Socket Teardown Race & Port Leak:
   Calling `env.stop_harness()` closes active FDs while worker threads execute `_handle_port_conn`, causing `OSError: [Errno 9] Bad file descriptor` at `conn.settimeout(5.0)` and leaving OS Port 5000 (and ports 5001/5002/UNIX socket) bound after teardown.
   Fix requirement:
   - Implement thread shutdown flags (e.g. `threading.Event()` or `self._running` boolean).
   - Track all connection handling threads and join them (`.join(timeout=...)`) in `stop_harness()`.
   - Call `shutdown(socket.SHUT_RDWR)` before `close()` on sockets.
   - Wrap socket operations in try/except blocks to gracefully catch `OSError`, `EBADF`, and `ConnectionResetError` when socket is closed during teardown.
   - Use timeout on socket accept/recv or non-blocking sockets so threads periodically check the shutdown flag.
   - Ensure OS ports (5000, 5001, 5002) are fully unbindable and reusable immediately after `stop_harness()`.

2. High Concurrency Request Dropping:
   Under 50-thread parallel hammer (2,000 ops), 1,938 ops failed (96.9% failure rate).
   Fix requirement:
   - Increase listen backlog (`socket.listen(128)` or `256`).
   - Optimize connection acceptance and concurrency handling in `socket_harness.py`. Ensure client requests under high concurrency (50 threads) are accepted, processed, and responded to reliably without dropping or timing out.

3. Flaky Test in Repeated Execution:
   Run 2 of 3 sequential runs of `python3 tests/e2e/runner.py` had 429/430 pass (1 failure), causing runner exit code 1.
   Fix requirement:
   - Investigate and fix the flaky test failure in `tests/e2e/` (ensure proper socket state reset, port cleanup, or test isolation between runs).

Verification required:
- Run `python3 tests/e2e/runner.py` (all tiers) and verify 430/430 pass.
- Run `python3 .agents/challenger_m6_concurrency_stress/stress_harness.py` and verify all 3 stress tests pass 100% with exit code 0 and OVERALL VERDICT: APPROVE.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work.

Write your handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m6_test_writer_gen4/handoff.md` upon completion.
</USER_REQUEST>
