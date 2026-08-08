## 2026-08-08T23:44:30Z
You are dispatched as teamwork_preview_auditor_gen2_1 for Forensic Integrity Audit (Round 4 Final Gate Check).

Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_auditor_gen2_1
Create your working directory `.agents/teamwork_preview_auditor_gen2_1` if it doesn't exist.

Context and Key Files:
- ORIGINAL_REQUEST: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- PREVIOUS AUDITOR REPORT: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_auditor_r3_1/handoff.md
- WORKER HANDOFF: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_gen2_2/handoff.md

Forensic Audit Instructions:
Execute strict forensic integrity checks across the entire codebase:
1. Host Portal & Guest Portal Integrity:
   - Check `LinuxPortalService.java` & `VsockPortalClient.java` for AF_VSOCK port 5000 usage, binary headers (`CAMF` 0x43414D46 / `VSOK` 0x56534F4B), HMAC verification. Confirm ZERO matches for `localhost` TCP fallback or string payloads.
   - Check `guest/bridge-agent/src/portal.rs` for active `GLOBAL_PORTAL_STATE` event updates. Confirm ZERO matches for mock coordinates or hardcoded "available" responses.
2. Test Framework & Fallback Integrity:
   - Inspect `tests/e2e/framework/real_env.py`. Confirm that `validate_sepolicy_boards`, `verify_gsi_boot_compatibility`, `measure_cts_idle_power_drop`, and `measure_erofs_read_throughput` use dynamic host fallbacks (os.walk, platform.uname(), process CPU time delta, tempfile I/O benchmark) without hardcoded constants or EnvironmentError traps.
   - Inspect `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`. Confirm removal of try-except override traps for T2-165, T2-168, T2-170, T2-174.
3. Test Execution Verification:
   - Run `python3 tests/e2e/runner.py`. Verify output: 430/430 PASS (100.0% Pass Rate, Exit Code 0).
   - Run `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml`. Verify output: 34/34 PASS (Exit Code 0).
4. Repository Cleanliness:
   - Run `git status --porcelain`. Verify ZERO untracked binary executables or report JSON artifacts.
5. Verification of Claims:
   - Verify that worker claims match exact empirical execution.

Deliver your handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_auditor_gen2_1/handoff.md`.
Specify your final verdict clearly on line 5: `CLEAN` or `INTEGRITY VIOLATION`.
Send a message to the orchestrator with your report path when complete.
