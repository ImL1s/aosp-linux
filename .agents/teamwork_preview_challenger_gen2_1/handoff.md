# Handoff Report — teamwork_preview_challenger_gen2_1

## Verdict
REJECT

## 1. Observation

- **Cargo Test Suite Verification**:
  - Command executed: `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml`
  - Output: `test result: ok. 34 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 10.00s`
  - Exit Code: 0 (34/34 PASS).

- **Python E2E Test Suite Verification (3 Consecutive Runs)**:
  - Command executed: `python3 tests/e2e/runner.py`
  - Run 1 Output: `RESULT: ALL TESTS PASSED SUCCESSFULLY (430/430 PASS, Exit Code 0)` (Duration: 38.86s).
  - Run 2 Output: `RESULT: ALL TESTS PASSED SUCCESSFULLY (430/430 PASS, Exit Code 0)` (Duration: ~39s).
  - Run 3 Output: `RESULT: ALL TESTS PASSED SUCCESSFULLY (430/430 PASS, Exit Code 0)` (Duration: ~39s).
  - Test Pass Consistency: 100.0% (430/430 PASS across all 3 iterations).

- **Process Leak & Orphaned Process Discovery**:
  - `guest/scripts/launch_vm.sh` lines 101-105:
    ```bash
    else
        echo "[Launch Script] crosvm binary not in PATH (Simulated execution mode)"
        if [ "${TEST_MODE:-0}" = "1" ]; then
            exec sleep 3600
        fi
    fi
    ```
  - `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` line 205:
    ```python
    res_launch1 = CommandRunner.run(f"TEST_MODE=1 bash '{launch_script}' '{config_file}'", cwd=PROJECT_ROOT)
    ```
  - `tests/e2e/framework/command_runner.py` line 23: `subprocess.run(..., timeout=30.0)`
  - Empirical Process Table Inspection (`ps -ef | grep sleep`):
    - After Run 1: PID 17008 (`sleep 3600`) orphaned under Python process wrapper PID 16829.
    - After Run 2: PID 17690, PID 17854 (`sleep 3600`) added to process table.
    - After Run 3: PID 17938, PID 18172 (`sleep 3600`) added to process table.
    - Total: 3 lingering orphaned `sleep 3600` processes remained active in the host system process table post-execution.

- **Socket / Thread Leak Verification**:
  - Socket ports 5000, 5001, 5002 checked via `lsof -i :5000 -i :5001 -i :5002`: Zero active sockets bound post-execution.

## 2. Logic Chain

1. **Cargo & E2E Pass Rate**: Running `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml` passed all 34 tests with Exit Code 0. Running `python3 tests/e2e/runner.py` 3 consecutive times achieved 430/430 PASS (100.0%) on every run without flakiness.
2. **Process Leak Mechanism**: In `test_m2_tier2.py` line 205, the test executes `TEST_MODE=1 bash guest/scripts/launch_vm.sh`.
3. **Simulation Trap In `launch_vm.sh`**: Because `crosvm` is absent, `launch_vm.sh` triggers line 103 `exec sleep 3600`.
4. **Command Timeout & Subprocess Orphan**: `CommandRunner.run` executes `subprocess.run(..., timeout=30.0)`. Since `sleep 3600` runs for 1 hour, `subprocess.run` times out after 30 seconds and raises `subprocess.TimeoutExpired`. `CommandRunner.run` catches the exception and returns `exit_code = -1`.
5. **Masked Failure in Test Assertions**: `test_m2_tier2.py` line 206 only asserts file sizes (`os.path.getsize(base_img)`) and does NOT check `res_launch1.exit_code == 0`. Consequently, the test suite reports `PASS` for T2-35 despite `CommandRunner.run` timing out after 30 seconds.
6. **System Defect Impact**: 
   - Every execution of `python3 tests/e2e/runner.py` stalls for 30 seconds during T2-35 waiting for the `sleep 3600` process timeout.
   - Each run leaves an orphaned `sleep 3600` background process in the system process table.
   - This violates Requirement 2 of `ORIGINAL_REQUEST.md` ("no TEST_MODE=1, no exec sleep 3600") and Challenger Task 3 ("Verify no socket leaks, thread leaks, or orphaned processes during runner execution").

## 3. Caveats

- socket ports 5000, 5001, 5002 clean up properly upon completion of individual socket harness tests.
- Rust unit tests (`cargo test`) run cleanly in ~10 seconds with 0 leaks.

## 4. Conclusion

While the Rust test suite (34/34 PASS) and Python test suite (430/430 PASS x3) pass cleanly without test flakiness, `guest/scripts/launch_vm.sh` still retains `exec sleep 3600` fallback simulation logic under `TEST_MODE=1`. When invoked in `test_m2_tier2.py`, it forces a 30-second `subprocess.run` timeout in `CommandRunner.run` and leaks an orphaned `sleep 3600` process on every run.

**Required Remediation**:
1. Remove `if [ "${TEST_MODE:-0}" = "1" ]; then exec sleep 3600; fi` from `guest/scripts/launch_vm.sh`.
2. Refactor `test_m2_tier2.py` (T2-35) to verify `launch_vm.sh` script execution without spawning background `sleep 3600` processes or causing 30-second timeout stalls.

## 5. Verification Method

To reproduce the process leak empirically:

1. Clean any existing `sleep 3600` processes:
   ```bash
   pkill -f "sleep 3600" || true
   ```
2. Execute one run of the Python E2E test suite:
   ```bash
   python3 tests/e2e/runner.py
   ```
3. Inspect system process table for leaked processes:
   ```bash
   ps -ef | grep "sleep 3600" | grep -v grep
   ```
   *Expected Result if Defect Exists*: 1 or more lingering `sleep 3600` processes listed in stdout.
