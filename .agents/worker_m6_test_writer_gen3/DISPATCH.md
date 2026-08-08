## 2026-08-08T06:34:55Z
<USER_REQUEST>
You are Worker 3 (worker_m6_test_writer_gen3) for Sub-Orchestrator M6.
Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m6_test_writer_gen3.

You MUST read:
- /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m6/SCOPE.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m6_concurrency_stress/handoff.md

Write ownership boundaries:
- .github/workflows/ci.yml
- tests/e2e_report.json
- tests/e2e/*

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Your Goal:
Fix the 2 concurrency and socket lifecycle issues reported by Challenger 2 in tests/e2e/framework/socket_harness.py:

1. Socket Teardown & Lifecycle Leak:
   - In SocketHarnessServer (socket_harness.py), track active client connections (self.active_clients) and worker threads (self.worker_threads).
   - In SocketHarnessServer.stop(), close all active client sockets, signal shutdown, and join worker threads so ports 5000, 5001, 5002 are completely freed in OS socket table upon stop_harness().

2. Concurrency & Stream Framing Desync:
   - In socket_harness.py, change socket backlog from listen(10) to listen(128).
   - Implement _recv_exact(conn, length) helper that loops until exactly length bytes are read.
   - Replace bare conn.recv(length) calls in _handle_port_conn and _handle_unix_conn with _recv_exact.

3. Verification:
   - Run python3 .agents/challenger_m6_concurrency_stress/stress_harness.py and confirm OVERALL VERDICT: PASS and Exit Code 0.
   - Run python3 tests/e2e/runner.py --tier 1 --tier 2 and confirm 370/370 Passed, Exit Code 0.
   - Run python3 tests/e2e/runner.py --tier 1 --tier 2 --tier 3 --tier 4 and confirm 430/430 Passed, Exit Code 0.

Write your handoff report to /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m6_test_writer_gen3/handoff.md with full remediation details and test logs, then notify sub_orch_m6.
</USER_REQUEST>
