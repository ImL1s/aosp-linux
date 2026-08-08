# BRIEFING — 2026-08-08T15:56:51Z

## Mission
Execute process leak remediation and fail-fast exit code fix in `guest/scripts/launch_vm.sh`, `tests/e2e/runner.py`, `tests/e2e/framework/base_test.py`, and `test_m2_tier2.py`.

## 🔒 My Identity
- Archetype: implementer, qa, specialist
- Roles: implementer, qa, specialist
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_r4_final_fix
- Original parent: 20d6aa05-0e46-4016-818a-bbff71e44e71
- Milestone: Process Leak & Fail-Fast Exit Code Fix

## 🔒 Key Constraints
- Completely remove `exec sleep 3600`.
- In `TEST_MODE=1` mode when `crosvm`/`qemu` is absent, implement trap-based parent-monitored loop in `launch_vm.sh`.
- In non-test mode when `crosvm`/`qemu` absent, exit fail-fast with code 1.
- `runner.py`: implement `cleanup_orphaned_processes()` for `["sleep 3600", "linux_bridge_test"]` and call in `finally:`.
- `base_test.py`: update `BaseTestCase.teardown()` for per-test mock reset and socket cleanup.
- `test_m2_tier2.py`: explicit unlock and close file handles in `finally:` blocks for `TestR2_001_T2_35_MultiProcessMountLock.run_test`.

## Current Parent
- Conversation ID: 20d6aa05-0e46-4016-818a-bbff71e44e71
- Updated: not yet

## Task Summary
- **What to build**: Fix process leaks, teardown, file lock release, and fail-fast handling.
- **Success criteria**: cargo test pass 34/34, runner.py pass 430/430, 0 leaked processes in ps aux.

## Change Tracker
- **Files modified**: none yet
- **Build status**: pending
- **Pending issues**: none

## Quality Status
- **Build/test result**: pending
- **Lint status**: pending
- **Tests added/modified**: pending
