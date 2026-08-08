# SCOPE — Milestone M6: Clean & Honest E2E Test Suite (R6)

## Objective
Clean up and replace all fake, hardcoded, and tautological E2E test checks across the repository with honest, real E2E test assertions and runner execution.

## Requirements
1. **CI Workflow (`.github/workflows/ci.yml`)**:
   - Remove static `tests/e2e_report.json` assertion check at line 33.
   - Replace with real test runner invocation (`python3 tests/e2e/runner.py --tier 1 --tier 2`).

2. **E2E Framework & Runner (`tests/e2e/framework/mock_env.py`, `tests/e2e/runner.py`)**:
   - Replace in-memory dummy objects and hardcoded CTS/AVB results with real LocalSocket, Vsock, IPC, binary checks, and system service assertions.

3. **Test Cases (`tests/e2e/tier1_feature_coverage/`, `tier2_boundary_corner/`, `tier3_cross_feature/`, `tier4_real_world/`)**:
   - Eliminate tautological string assertions (e.g. `assert "/dev/video0" == "/dev/video0"`, `assert 5 > 0`, `assert read_speed_mbps > 500` without I/O).
   - Replace with real IPC binary frame sends, socket responses, and binary return code checks (e.g. `checkpolicy` for SELinux).

## Write Ownership Boundaries
- `.github/workflows/ci.yml`
- `tests/e2e_report.json`
- `tests/e2e/*`

## Key Deliverables
- Fully working E2E test runner and test cases that genuinely exercise system components.
- Verification output showing test execution results.
- `TEST_READY.md` published to `/Users/iml1s/Documents/mine/aosp-linux/TEST_READY.md` upon gate approval.
