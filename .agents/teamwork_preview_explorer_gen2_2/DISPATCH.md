## 2026-08-08T23:48:45Z
<USER_REQUEST>
You are dispatched as teamwork_preview_explorer_gen2_2 to analyze the orphan process leak defect in `guest/scripts/launch_vm.sh` and `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` identified by Challenger 1.

Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_gen2_2
Create your working directory `.agents/teamwork_preview_explorer_gen2_2` if it doesn't exist.

Context and Key Files to Read:
- ORIGINAL_REQUEST: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- DEAD_ENDS: /Users/iml1s/Documents/mine/aosp-linux/DEAD_ENDS.md
- CHALLENGER 1 REPORT: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_gen2_1/handoff.md
- TARGET FILES:
  - `guest/scripts/launch_vm.sh`
  - `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`

Defect Details:
- In `guest/scripts/launch_vm.sh` (lines 101-105), when `crosvm` is missing from PATH and `TEST_MODE=1` is set, the script executes `exec sleep 3600`.
- In `test_m2_tier2.py` line 205 (T2-35), the test invokes `TEST_MODE=1 bash guest/scripts/launch_vm.sh`.
- `CommandRunner.run` times out after 30 seconds waiting for `launch_vm.sh` to finish, leaving an orphaned `sleep 3600` process running in the background on every run of `python3 tests/e2e/runner.py`.
- Furthermore, `ORIGINAL_REQUEST.md` Rule 4 explicitly states: "No TEST_MODE, simulated-success path, or exec sleep 3600".

Tasks:
1. Examine `guest/scripts/launch_vm.sh` and `test_m2_tier2.py`.
2. Formulate a clean fix strategy that:
   - Removes `if [ "${TEST_MODE:-0}" = "1" ]; then exec sleep 3600; fi` completely from `launch_vm.sh`. If `crosvm` is missing, `launch_vm.sh` must print an error message and exit immediately with a non-zero exit code (e.g. exit code 1 or 2).
   - Refactors T2-35 in `test_m2_tier2.py` to validate `launch_vm.sh` configuration file generation, parameter checking, and error handling without spawning long-lived background processes or causing 30-second `subprocess.run` timeout stalls in `CommandRunner.run`.
   - Ensures `python3 tests/e2e/runner.py` achieves 430/430 PASS (100.0%, Exit Code 0) in under 10 seconds total duration, leaving zero `sleep 3600` processes in `ps -ef | grep sleep`.
3. Deliver your fix design report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_gen2_2/handoff.md`.
4. Send a message to the orchestrator with your report path when complete.
</USER_REQUEST>
