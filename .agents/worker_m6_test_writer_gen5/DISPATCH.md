## 2026-08-08T10:42:48Z

<USER_REQUEST>
You are Worker 5 (worker_m6_test_writer_gen5) working on Milestone M6 (Clean & Honest E2E Test Suite - R6).
Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m6_test_writer_gen5

Please read:
- `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
- `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m6/SCOPE.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m6/handoff.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m6_integrity_gen1/handoff.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m6_honest_execution_gen4/handoff.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m6_code_quality_gen5/handoff.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m6_concurrency_stress_gen3/handoff.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m6_runner_verification_gen1/handoff.md`

Objective:
Perform complete, honest remediation of all 4 defect categories identified across Reviewers, Challengers, and Forensic Auditor:

Defect 1: macOS System Port Collision on TCP Port 5000 (AirPlay Receiver)
- Issue: macOS AirPlay Receiver (`ControlCenter`) listens on `*:5000`. Binding `127.0.0.1:5000` causes kernel load-balancing collisions and port leaks.
- Remediation: Shift loopback ports from `5000, 5001, 5002` to high non-system ports `15000, 15001, 15002` (or `55000, 55001, 55002`) across `tests/e2e/framework/socket_harness.py`, `real_env.py`, test cases, and `.agents/challenger_m6_concurrency_stress/stress_harness.py`.

Defect 2: Non-Daemon ThreadPoolExecutor Deadlock on `sys.exit()`
- Issue: `SocketHarnessServer` uses `ThreadPoolExecutor` whose threads default to non-daemon (`t.daemon = False`). When `runner.py` finishes and calls `sys.exit()`, Python's `threading._shutdown()` blocks on joining worker threads.
- Remediation: Custom thread factory with `daemon=True` or explicit `executor.shutdown(wait=False, cancel_futures=True)` plus closing all client/listener sockets in `stop_harness()`.

Defect 3: Unconditional C++/Java Re-compilation Overhead
- Issue: `ensure_binaries_built()` in `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` re-compiles `g++` and `javac` binaries on every runner process invocation, taking 25+ seconds per run.
- Remediation: Add a file existence check (`if os.path.exists("./tests/unit/m3_native_challenger2_stress_bin"): return`) so compilation is skipped if binaries already exist.

Defect 4: Eliminate 35+ Hardcoded & Tautological Test Cases (INTEGRITY VIOLATION)
- Issue: Over 35 test cases in `test_m5_tier1.py`, `test_m4_tier1.py`, `test_m1_tier1.py`, `test_m5_tier2.py` hardcode local variables / literals and assert on local variables without executing real logic or system checks.
- Remediation:
  - `test_m5_tier1.py`:
    - T1-150 (`ZeroCopyPageCacheReadPerformance`): Perform actual file I/O read throughput measurement instead of `read_speed_mbps = 1200`.
    - T1-165 (`PolicyCompilationVerificationCheckpolicy`): Call `BinaryInspector.compile_and_verify_selinux()` or invoke `checkpolicy` binary instead of `checkpolicy_exit_code = 0`.
    - T1-119, T1-120, T1-124, T1-125, T1-136, T1-140, T1-160, T1-164, T1-168, T1-169, T1-170, T1-174, T1-183: Replace local variable self-assertions with real calls to `self.mock_env`, `socket_harness`, or `BinaryInspector`.
  - `test_m4_tier1.py`:
    - Rewrite T1-86, T1-89, T1-91..T1-95, T1-97, T1-99, T1-101..T1-108, T1-110, T1-111, T1-113, T1-114 to use real IPC sends/recvs or inspect `SystemEnvironment` state instead of declaring local dictionaries.
  - `test_m1_tier1.py`:
    - Rewrite T1-01, T1-03, T1-12, T1-16, T1-18, T1-20 to query real process credentials, socket files, or `SystemEnvironment` state instead of local string split/int checks.

Verification Required:
- Run `python3 tests/e2e/runner.py --tier 1 --tier 2 --tier 3 --tier 4` and verify 430/430 (100.0%) pass with clean process exit code 0 (no hanging sleeping processes or open ports).
- Run `python3 .agents/challenger_m6_concurrency_stress/stress_harness.py` and verify all 3 stress dimensions pass 100% with exit code 0 and `OVERALL VERDICT: APPROVE`.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work.

Write your handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m6_test_writer_gen5/handoff.md` upon completion.
</USER_REQUEST>
