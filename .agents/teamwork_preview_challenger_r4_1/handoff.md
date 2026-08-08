# Empirical Stress Test Report — Teamwork Preview Challenger R4 1

## 1. Observation

### Observation 1: Task 1 — Rust Guest Agent Unit Tests & RFC 2104 HMAC Golden Vector
- Command: `$HOME/.cargo/bin/cargo test` executed in `guest/bridge-agent`.
- Output summary:
  `test result: ok. 34 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 10.00s` (exit code 0).
- Golden Vector Compliance Verified:
  - `auth::tests::test_rfc2104_golden_vector` passed cleanly.
  - Golden Vector: HMAC-SHA256 with key `"Jefe"` and data `"what do ya want for nothing?"` yielded `5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843` (RFC 4231 Test Vector 2).
- PTY unit tests verified:
  - `pty::tests::test_pty_master_open_and_slave_name ... ok`
  - `pty::tests::test_pty_resize ... ok`
  - `empirical_tests::empirical_tests::test_pty_payload_overflow_rejection ... ok`
  - `empirical_tests::empirical_tests::test_pty_disconnect_no_sigabrt_stress ... ok`

### Observation 2: Task 2 — Process Leak & Orphan Daemon Audit (FAIL)
- Direct empirical process table inspection (`ps aux`, `pgrep`):
  1. **Orphan `sleep 3600` processes**: When E2E tests (`python3 tests/e2e/runner.py` / `test_m2_tier2.py`) execute `TestR2_001_T2_35_MultiProcessMountLock`, `launch_vm.sh` is invoked with `TEST_MODE=1`. Because `crosvm` is not present in PATH, `launch_vm.sh` (lines 101-105) executes `exec sleep 3600`. `CommandRunner.run` times out after 30 seconds, leaving orphaned `sleep 3600` processes running in the background indefinitely (observed PIDs: 21310, 21905, 21990).
  2. **Leaked `./build_out/bin/linux_bridge_test` daemons**: Prior test runner commands (`./build_out/bin/linux_bridge_test && python3 tests/e2e/runner.py...`) spawned background socket server instances that do not perform clean daemon shutdown on test exit, leaving leaked processes running indefinitely in the OS process table (observed PIDs: 63629, 65006, 65489, 66006, 66172, 66234, 22126).

### Observation 3: Task 3 — Concurrency Stress & PTY Payload Boundaries
- Implemented and executed `tests/unit/challenger_r4_concurrency_pty_stress.py`.
- Output summary:
  `Ran 4 tests in 1.446s - OK` (exit code 0).
- Concurrency Verification:
  - 100 parallel worker threads executed 1,000 IPC requests (`CMD_VM_START`) against `SocketHarnessServer` on UNIX domain socket `/dev/socket/linux_bridge` (resolved path). All 1,000 requests succeeded with 100% header integrity (`magic == 0x414F`).
- PTY Payload Boundaries:
  - Exact 64KB (65,536 bytes) payload framing correctly parsed (`VsockFramingHelper.parse_header` length 65536).
  - Oversized payload (70,000 bytes > 64KB MAX_PAYLOAD_SIZE) correctly identified and parsed.
  - Abrupt socket disconnects (50 rapid partial frame teardowns) handled cleanly with zero socket FD leaks or crashes.

---

## 2. Logic Chain

1. **Task 1 Verification**: `cargo test` in `guest/bridge-agent` exits with code 0 and all 34 unit tests pass, confirming that RFC 2104 HMAC authentication and PTY master/slave handling function correctly in isolation.
2. **Task 2 Verification Failure**: Inspection of the system process table proves that running E2E tests (`runner.py` / `test_m2_tier2.py`) leaks `sleep 3600` processes because `launch_vm.sh` under `TEST_MODE=1` replaces bash with `sleep 3600`, which `CommandRunner.run` fails to kill upon timeout. Additionally, `linux_bridge_test` daemons remain running indefinitely in background. This directly violates Task 2 requirement ("Verify zero orphan/leaked background processes (e.g. `sleep 3600`, zombie daemons) are left running during or after test execution").
3. **Task 3 Verification**: Socket harness concurrency testing demonstrates that `SocketHarnessServer` handles 100 concurrent connection threads and 1,000 IPC requests without deadlock or socket drop, and PTY 64KB payload boundary framing operates as specified.
4. **Final Assessment**: Despite Tasks 1 and 3 passing, Task 2 fails process leak isolation. Therefore, the Round 4 Remediation codebase cannot be approved.

---

## 3. Caveats

No caveats. All findings were established through direct empirical command execution and process table auditing on the live workspace environment.

---

## 4. Conclusion

**REJECT**

Task 2 failed: Orphan `sleep 3600` processes (from `launch_vm.sh` under `TEST_MODE=1`) and leaked `./build_out/bin/linux_bridge_test` daemon processes accumulate in the OS process table during and after test execution.

---

## 5. Verification Method

To independently verify this empirical report:

1. **Verify Cargo Unit Tests (Task 1)**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent
   $HOME/.cargo/bin/cargo test
   ```
   *Result*: 34 passed; 0 failed (exit code 0).

2. **Verify Process Leak Accumulation (Task 2)**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux
   python3 tests/e2e/runner.py --tier 2
   ps aux | grep -E "sleep 3600|linux_bridge_test" | grep -v grep
   ```
   *Result*: Shows orphaned `sleep 3600` and leaked `linux_bridge_test` processes active in process table after test completion.

3. **Verify Concurrency & PTY Payload Boundary Stress Test (Task 3)**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux
   python3 tests/unit/challenger_r4_concurrency_pty_stress.py
   ```
   *Result*: 4 tests passed in 1.446s (exit code 0).
