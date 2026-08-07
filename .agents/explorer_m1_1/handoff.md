# Handoff Report — E2E Test Suite Execution Strategy (M1 / R1)

## 1. Observation

- **Runner Script**: `tests/e2e/runner.py`
  - Line 24-29: `TIER_DIRS = { 1: ["tier1_feature_coverage", "tier1"], 2: ["tier2_boundary_corner", "tier2"], 3: ["tier3_cross_feature", "tier3"], 4: ["tier4_real_world", "tier4"] }`
  - Line 31: `DEFAULT_REPORT_PATH = "/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json"`
  - Line 42: `target_tiers = [tier_filter] if tier_filter in TIER_DIRS else sorted(TIER_DIRS.keys())` (omitting `--tier` executes Tiers 1 through 4).
  - Line 93-104: CLI flags `--tier`, `--feature`, `--filter`, `--report`, `--output-json`, `--verbose`, `--list`.
- **Launcher Script**: `tests/e2e/run_tests.sh`
  - Line 15: `exec "$PYTHON_EXEC" "$RUNNER_SCRIPT" "$@"` (forwards all CLI options directly to `runner.py`).
- **Test Inventory Count**: `python3 tests/e2e/runner.py --list` output:
  - Total Discovered Tests: 430
  - Breakdown: Tier 1: 185 tests, Tier 2: 185 tests, Tier 3: 40 tests, Tier 4: 20 tests.
- **Dependency Execution Result**:
  - Direct execution of `python3 tests/e2e/runner.py --verbose --report tests/e2e_report.json` without native binaries in `build_out/bin/` resulted in 4 failure errors (exit code 127: `./build_out/bin/linux_bridge_test: No such file or directory` on tests `T1-17`, `T1-18`, `T1-19`, `T2-41`).
  - Pre-compiling `linux_bridge_test`, `challenger_m2_framing_test`, `challenger_m2_hmac_test`, and `challenger_m2_empirical_test` into `build_out/bin/` via `clang++` prior to running `runner.py` produced 100% PASS rate (430/430 passed, 0 failures, 0 errors, 0 skipped, duration 10.90 seconds, exit code 0).
  - `tests/e2e_report.json` was generated and verified with `"total": 430`, `"passed": 430`, `"pass_rate_percent": 100.0`.

---

## 2. Logic Chain

1. **Test Discovery**: `runner.py` dynamically scans subdirectories `tier1_feature_coverage`, `tier2_boundary_corner`, `tier3_cross_feature`, and `tier4_real_world`. When no `--tier` parameter is specified, `target_tiers` defaults to `[1, 2, 3, 4]`, discovering all 430 test classes.
2. **Flag Behavior**:
   - Passing `--verbose` sets `args.verbose = True`, causing lines 157–160 to print failure details and full Python stack traces whenever a test fails or errors out.
   - Passing `--report tests/e2e_report.json` overrides `args.report` and writes the output JSON via `ReportFormatter.generate_json_report`.
3. **Subprocess Test Dependencies**:
   - Tests `T1-17`, `T1-18`, `T1-19`, `T2-36`, `T2-41`, `T2-44`, `T2-46`, `T2-47`, `T2-48` execute C++ binary executables located at `./build_out/bin/linux_bridge_test` and `./build_out/bin/challenger_m2_hmac_test` using `CommandRunner.run(...)`.
   - If `build_out/bin/` does not exist or lacks these compiled executables, `CommandRunner` receives exit code 127 from the shell, causing assertion failures.
   - Therefore, a clean test execution requires compiling native test executables into `build_out/bin/` beforehand.
4. **Clean Execution**: Executing the native C++ compilation command followed by `python3 tests/e2e/runner.py --verbose --report tests/e2e_report.json` executes all 430 tests without error, producing a 100% pass rate.

---

## 3. Caveats

- **No mandatory environment variables required**: `sys.path` is automatically patched inside `runner.py`.
- **Pre-build step dependency**: Running `runner.py` on a completely clean repository where `build_out/` has been removed (e.g. by `rm -rf build_out`) will cause 4 native subprocess tests to fail with exit code 127. Worker must run the native compilation step first.
- **Python Version**: Requires Python 3.8+ (standard on system).

---

## 4. Conclusion

To execute all 430 E2E tests (Tiers 1–4) with verbose output and produce `tests/e2e_report.json` cleanly, the exact command sequence for Worker is:

```bash
# 1. Compile required native test binaries
mkdir -p build_out/bin && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_framing_test.cpp -o build_out/bin/challenger_m2_framing_test && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp tests/unit/challenger_m2_hmac_test.cpp -o build_out/bin/challenger_m2_hmac_test && \
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/challenger_m2_empirical_test.cpp -o build_out/bin/challenger_m2_empirical_test

# 2. Run full E2E test suite
python3 tests/e2e/runner.py --verbose --report tests/e2e_report.json
```

Or using `run_tests.sh`:
```bash
./tests/e2e/run_tests.sh --verbose --report tests/e2e_report.json
```

This guarantees 430/430 (100%) test pass rate and clean JSON report generation.

---

## 5. Verification Method

To verify this finding independently:

1. **List inventory**:
   ```bash
   python3 tests/e2e/runner.py --list
   ```
   *Expected result*: Displays 430 test cases across Tiers 1-4.

2. **Execute Full Suite**:
   ```bash
   python3 tests/e2e/runner.py --verbose --report tests/e2e_report.json
   ```
   *Expected result*:
   - Exit code: 0
   - Console summary: `TOTAL TESTS: 430`, `PASSED: 430`, `PASS RATE: 100.0%`
   - Report file created at `tests/e2e_report.json` with `"total": 430`, `"passed": 430`.
