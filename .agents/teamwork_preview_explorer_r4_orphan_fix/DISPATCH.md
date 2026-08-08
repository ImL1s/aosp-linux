## 2026-08-08T23:53:06Z

You are teamwork_preview_explorer_r4_orphan_fix. Your working directory is `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_r4_orphan_fix`.

Your task is to investigate and design exact remediation fixes for the process leak defect identified by Challenger 1 (`.agents/teamwork_preview_challenger_r4_1/handoff.md`).

Read the following files before starting:
1. `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
3. `/Users/iml1s/Documents/mine/aosp-linux/DEAD_ENDS.md`
4. Challenger 1 Defect Report: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_r4_1/handoff.md`
5. `guest/scripts/launch_vm.sh` (lines 70-110)
6. `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` (T2-35 and teardown logic)

Defect to Fix:
1. In `guest/scripts/launch_vm.sh` (lines 101-105), under `TEST_MODE=1` when `crosvm` is absent, it executes `exec sleep 3600`. When tests invoke `launch_vm.sh`, `sleep 3600` replaces bash and continues running indefinitely in the background, leaking orphan processes into the host process table (PIDs observed: 21310, 21905, 21990).
2. In E2E tests (`test_m2_tier2.py` and test teardown logic), daemons such as `./build_out/bin/linux_bridge_test` and mock processes are spawned without clean teardown/kill upon test completion, accumulating background processes.

Design Requirements:
- Completely eliminate `exec sleep 3600` from `guest/scripts/launch_vm.sh`. Design a clean, finite execution mode or trap-based process lifecycle that terminates immediately when the parent process exits or receives SIGTERM.
- Update test runner teardown in `tests/e2e/runner.py` / `test_m2_tier2.py` to ensure all spawned subprocesses and daemons are explicitly terminated (`SIGTERM` / `SIGKILL`) in `tearDown` or `finally` blocks.
- Ensure all 430 E2E tests pass 100% (exit 0) and zero orphan processes (`sleep 3600`, `linux_bridge_test`) remain in `ps aux` after test completion.

Deliverable:
Write a detailed fix design report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_r4_orphan_fix/handoff.md` detailing:
- Exact line numbers and file edits for `launch_vm.sh`, `test_m2_tier2.py`, and teardown logic
- Verification command to verify zero process leaks (`ps aux | grep -E "sleep 3600|linux_bridge_test"`)
Send a completion message to parent orchestrator (Conv ID: `20d6aa05-0e46-4016-818a-bbff71e44e71`).
