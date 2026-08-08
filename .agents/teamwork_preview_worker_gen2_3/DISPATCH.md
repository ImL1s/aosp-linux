## 2026-08-08T15:52:07Z
<USER_REQUEST>
You are dispatched as teamwork_preview_worker_gen2_3 to implement the orphan process leak remediation in `guest/scripts/launch_vm.sh` and `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`.

Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_gen2_3
Create your working directory `.agents/teamwork_preview_worker_gen2_3` if it doesn't exist.

Context and Key Files to Read:
- ORIGINAL_REQUEST: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- EXPLORER FIX REPORT: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_gen2_2/handoff.md
- EXPLORER PATCH 1: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_gen2_2/proposed_launch_vm.sh.patch
- EXPLORER PATCH 2: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_gen2_2/proposed_test_m2_tier2.py.patch
- TARGET FILES:
  - `guest/scripts/launch_vm.sh`
  - `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Detailed Tasks:
1. In `guest/scripts/launch_vm.sh`:
   - Completely remove `TEST_MODE` checks and `exec sleep 3600` (lines 76 and 101–105).
   - When `/dev/kvm` is missing (line 76) or `crosvm` is not found in PATH (line 100), print error message to `stderr` and `exit 1` immediately (fail fast).
2. In `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` (T2-35):
   - Remove `TEST_MODE=1` environment variables from the `CommandRunner.run` command strings on lines 205 and 213.
3. Verification Commands:
   - `pkill -f "sleep 3600" || true`
   - `python3 tests/e2e/runner.py` -> Must achieve 430/430 PASS (100.0% Pass Rate, Exit Code 0) in < 10 seconds total duration.
   - `ps -ef | grep "sleep 3600" | grep -v grep` -> Must return 0 processes.
   - `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml` -> Must achieve 34/34 PASS (Exit Code 0).
   - `git status --porcelain` -> Verify 0 untracked binaries or reports.
4. Write your completion handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_gen2_3/handoff.md`.
5. Send a message to the orchestrator with your report path when complete.
</USER_REQUEST>
