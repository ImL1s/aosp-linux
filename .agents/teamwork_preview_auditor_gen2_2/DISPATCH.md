## 2026-08-08T15:54:24Z
You are dispatched as teamwork_preview_auditor_gen2_2 for Forensic Integrity Audit (Round 4 Final Verification Gate Check).

Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_auditor_gen2_2
Create your working directory `.agents/teamwork_preview_auditor_gen2_2` if it doesn't exist.

Context and Key Files:
- ORIGINAL_REQUEST: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- WORKER 3 HANDOFF: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_gen2_3/handoff.md

Forensic Audit Instructions:
Execute strict forensic integrity checks across the codebase:
1. Script & Process Integrity:
   - Check `guest/scripts/launch_vm.sh` for zero occurrences of `exec sleep 3600` or `TEST_MODE`. Confirm fail-fast behavior (`exit 1` on missing binary/hardware).
2. Host Portal & Guest Portal Integrity:
   - Check `LinuxPortalService.java` & `VsockPortalClient.java` for AF_VSOCK port 5000 usage, binary headers (`CAMF` 0x43414D46 / `VSOK` 0x56534F4B), HMAC verification. Confirm ZERO matches for `localhost` TCP fallback or string payloads.
   - Check `guest/bridge-agent/src/portal.rs` for active `GLOBAL_PORTAL_STATE` event updates. Confirm ZERO matches for mock coordinates or hardcoded "available" responses.
3. Test Framework & Fallback Integrity:
   - Inspect `tests/e2e/framework/real_env.py`. Confirm that `validate_sepolicy_boards`, `verify_gsi_boot_compatibility`, `measure_cts_idle_power_drop`, and `measure_erofs_read_throughput` use dynamic host fallbacks (os.walk, platform.uname(), process CPU time delta, tempfile I/O benchmark) without hardcoded constants or EnvironmentError traps.
   - Inspect `tests/e2e/tier2_boundary_corner/test_m5_tier2.py` and `test_m2_tier2.py`. Confirm removal of try-except override traps and `TEST_MODE` flags.
4. Test Execution & Performance Verification:
   - Run `python3 tests/e2e/runner.py`. Verify output: 430/430 PASS (100.0% Pass Rate, Exit Code 0, duration < 10s).
   - Run `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml`. Verify output: 34/34 PASS (Exit Code 0).
   - Check `ps -ef | grep "sleep 3600" | grep -v grep`. Verify 0 processes.
5. Repository Cleanliness:
   - Run `git status --porcelain`. Verify ZERO untracked binary executables or report JSON artifacts.

Deliver your handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_auditor_gen2_2/handoff.md`.
Specify your final verdict clearly on line 5: `CLEAN` or `INTEGRITY VIOLATION`.
Send a message to the orchestrator with your report path when complete.
