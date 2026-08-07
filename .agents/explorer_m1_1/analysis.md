# E2E Test Execution Strategy Analysis Report

## Overview
This document analyzes the execution strategy for the 430 E2E tests (covering Tiers 1–4) via `tests/e2e/runner.py` and `tests/e2e/run_tests.sh` in the `aosp-linux` repository.

---

## Key Findings & Answers to Specific Questions

### Question 1: How to run `runner.py` to execute all Tiers 1–4 with verbose mode and output report to `tests/e2e_report.json`

`runner.py` is the main CLI test runner. When invoked without `--tier`, it dynamically discovers and executes all test classes across all four tiers:
- **Tier 1 (Feature Coverage)**: 185 test cases
- **Tier 2 (Boundary & Corner Cases)**: 185 test cases
- **Tier 3 (Cross-Feature Pairwise)**: 40 test cases
- **Tier 4 (Real-World E2E Scenarios)**: 20 test cases
- **Total**: 430 test cases

#### Exact Command Using `runner.py`:
```bash
python3 tests/e2e/runner.py --verbose --report tests/e2e_report.json
```

#### Exact Command Using Wrapper `run_tests.sh`:
```bash
./tests/e2e/run_tests.sh --verbose --report tests/e2e_report.json
```

#### Report Output:
- The runner outputs a formatted JSON report to `tests/e2e_report.json`.
- Default report path in `runner.py` (line 31) is `/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json`. Passing `--report tests/e2e_report.json` or `--output-json tests/e2e_report.json` explicitly targets this file.
- The generated report includes execution timestamp, summary statistics (`total`, `passed`, `failed`, `errored`, `skipped`, `pass_rate_percent`, `duration_seconds`), and individual test results with status, duration, error messages, and stack traces.

---

### Question 2: Are there any specific environment variables or flags needed?

#### Environment Variables:
- **`PYTHONPATH`**: Not strictly required. `runner.py` automatically injects its parent directory into `sys.path` (`BASE_DIR = os.path.dirname(os.path.abspath(__file__))`).
- **`TEST_MODE`**: Used internally by shell scripts (e.g. `TEST_MODE=1 bash guest/scripts/launch_vm.sh`) during individual tier tests to prevent actual QEMU/crosvm execution, but handled automatically within test cases using `CommandRunner`.
- **System Dependencies**: Standard `python3` (Python 3.8+) and C++ compilation toolchain (`clang++`) for building test binaries.

#### Flags Breakdown:
- `--verbose`: Enables printing detailed failure messages and Python stack traces upon test failure or error.
- `--report <PATH>` / `--output-json <PATH>`: Destination path for writing the structured JSON report.
- `--tier {1,2,3,4}`: (Optional) Restricts execution to a specific tier (1, 2, 3, or 4). Omitting this flag executes all Tiers 1–4.
- `--feature <FEATURE_ID>`: (Optional) Filters test cases by Feature ID (e.g. `F-R1-001`).
- `--filter <PATTERN>`: (Optional) Substring search across test ID, feature ID, or title.
- `--list`: (Optional) Lists all discovered tests in tabular format without running them.

---

### Question 3: What command should Worker run to execute the full suite cleanly?

#### Pre-requisite Build Requirement:
Several Tier 1 & Tier 2 tests (such as `T1-17`..`T1-19`, `T2-36`, `T2-41`, `T2-44`, `T2-46`..`T2-48`) invoke compiled C++ test executables in `build_out/bin/` (`linux_bridge_test`, `challenger_m2_framing_test`, `challenger_m2_hmac_test`, `challenger_m2_empirical_test`). If `build_out/bin/` binaries are missing (e.g., after `rm -rf build_out`), these sub-process tests fail with exit code 127 (`No such file or directory`).

#### Clean Full Execution Command Sequence for Worker:

```bash
# Step 1: Ensure native C++ test binaries are compiled into build_out/bin/
mkdir -p build_out/bin && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_framing_test.cpp -o build_out/bin/challenger_m2_framing_test && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_hmac_test.cpp -o build_out/bin/challenger_m2_hmac_test && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/challenger_m2_empirical_test.cpp -o build_out/bin/challenger_m2_empirical_test

# Step 2: Execute all 430 E2E test cases cleanly
python3 tests/e2e/runner.py --verbose --report tests/e2e_report.json
```

#### Execution Verification Results:
- **Total Tests Discovered & Executed**: 430 / 430
- **Passed**: 430 (100.0%)
- **Failed**: 0
- **Errors**: 0
- **Duration**: ~11 seconds
- **Exit Code**: 0
- **JSON Report Path**: `tests/e2e_report.json`
