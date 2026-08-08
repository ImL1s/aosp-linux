## 2026-08-08T06:17:39Z
You are Worker 1 (worker_m6_test_writer) for Sub-Orchestrator M6.
Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m6_test_writer.

You MUST read:
- /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m6/SCOPE.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m6_ci_runner/handoff.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m6_framework/handoff.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m6_testcases/handoff.md

Write ownership boundaries:
- .github/workflows/ci.yml
- tests/e2e_report.json
- tests/e2e/*

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Your Tasks:
1. CI Workflow (.github/workflows/ci.yml):
   - Remove static tests/e2e_report.json assertion check at line 31-33.
   - Replace with real test runner invocation: python3 tests/e2e/runner.py --tier 1 --tier 2.

2. E2E Framework & Runner (tests/e2e/framework/, tests/e2e/runner.py):
   - Refactor runner.py: fix --tier argument parsing (support list of tiers e.g. --tier 1 --tier 2), fix DEFAULT_REPORT_PATH to dynamic relative path, ensure honest exit code (0 on success, 1 on fail/err).
   - Replace in-memory dummy objects (MockVsockBridge, MockSystemServer, MockSommelier, MockXdgPortal, MockEnvironment) in framework/ with real socket framing (socket_harness.py), real binary/system inspection (system_inspector.py), and real system environment (real_env.py / SystemEnvironment).

3. Test Cases (tests/e2e/tier1_feature_coverage/, tier2_boundary_corner/, tier3_cross_feature/, tier4_real_world/):
   - Replace tautological string assertions, in-memory variable checks, and fake CTS results with real functional assertions (AF_UNIX socket frames for /dev/socket/linux_bridge, AF_VSOCK socket framing for ports 5000-5002, checkpolicy binary execution for SELinux, avbtool signatures, real file I/O benchmarking, and IPC calls).

4. Run tests using python3 tests/e2e/runner.py --tier 1 --tier 2 and verify honest execution and pass status.

Write your handoff report to /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m6_test_writer/handoff.md with full implementation summary, test commands, and results, then notify sub_orch_m6.
