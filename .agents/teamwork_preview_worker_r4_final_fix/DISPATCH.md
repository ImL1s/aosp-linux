## 2026-08-08T15:56:48Z
You are teamwork_preview_worker_r4_final_fix. Your working directory is `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_r4_final_fix`.

Your task is to execute the complete process leak remediation and fail-fast exit code fix in `guest/scripts/launch_vm.sh`, `tests/e2e/runner.py`, `tests/e2e/framework/base_test.py`, and `test_m2_tier2.py`, following the specifications from Explorer `teamwork_preview_explorer_r4_orphan_fix` (`.agents/teamwork_preview_explorer_r4_orphan_fix/handoff.md`).

Read the following files before starting:
1. `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. Explorer Fix Design Report: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_r4_orphan_fix/handoff.md`
3. Challenger 1 Report: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_r4_1/handoff.md`
4. `guest/scripts/launch_vm.sh`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Remediation Tasks to Execute:

Task 1: `guest/scripts/launch_vm.sh` Process Leak & Fail-Fast Fix
- Completely remove `exec sleep 3600`.
- In `TEST_MODE=1` mode when `crosvm`/`qemu` is absent, implement a trap-based parent-monitored finite loop (`trap cleanup SIGTERM SIGINT SIGHUP EXIT`, monitoring `$PPID` with `kill -0 $PPID`) so that when the parent process exits or receives SIGTERM, `launch_vm.sh` cleans up and exits within <=0.5s.
- In non-test mode (when `TEST_MODE` is not 1) when neither `crosvm` nor `qemu` binary is found in PATH, exit with fail-fast exit code 1 (`echo "[Launch Script] Neither crosvm nor qemu binary found in PATH." >&2; exit 1`).

Task 2: `tests/e2e/runner.py` Subprocess & Process Leak Teardown
- Implement `cleanup_orphaned_processes()` function inside `tests/e2e/runner.py`.
- In `main()`, call `cleanup_orphaned_processes()` in the `finally:` block.
- `cleanup_orphaned_processes()` queries PIDs for target strings `["sleep 3600", "linux_bridge_test"]` and issues `SIGTERM` followed by `SIGKILL` to clean up any orphaned processes from the host OS process table upon test suite exit.

Task 3: Per-Test Teardown in `tests/e2e/framework/base_test.py`
- Update `BaseTestCase.teardown()` to ensure per-test mock environment reset and socket cleanup.

Task 4: `test_m2_tier2.py` File Lock Teardown
- Ensure `TestR2_001_T2_35_MultiProcessMountLock.run_test` explicitly unlocks and closes file handles in `finally:` blocks.

Verification Commands to Run:
1. Kill any existing leaked processes: `pkill -f "sleep 3600|linux_bridge_test" || true`.
2. `$HOME/.cargo/bin/cargo test` in `guest/bridge-agent` -> Must pass 100% (34/34 PASS, exit 0).
3. `python3 tests/e2e/runner.py` -> Must pass 430/430 (100.0%, exit 0).
4. Verify process table: `ps aux | grep -E "sleep 3600|linux_bridge_test" | grep -v grep` -> Must be completely empty (0 leaked processes).

Deliverable:
Write a full completion handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_r4_final_fix/handoff.md` detailing:
- Files modified
- Execution output of `cargo test` and `python3 tests/e2e/runner.py`
- Verification of zero process leaks (`ps aux`)
Send a completion message to the parent orchestrator (Conv ID: `20d6aa05-0e46-4016-818a-bbff71e44e71`).
