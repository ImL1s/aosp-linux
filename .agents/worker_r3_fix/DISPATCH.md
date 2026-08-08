## 2026-08-08T13:02:21Z

You are dispatched as worker_r3_fix (Test Environment Overrides Purge & PTY Test Fix Developer).
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r3_fix

Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Reviewer Handoff Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_r3_1/handoff.md
Challenger Handoff Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_r3_1/handoff.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Your objective:
1. Read ORIGINAL_REQUEST.md, reviewer_r3_1/handoff.md, and challenger_r3_1/handoff.md.
2. In tests/e2e/framework/real_env.py:
   - Remove default attribute overrides in `RealSystemServerAdapter.__init__`: delete `self.cts_verifier_status = "PASS"`, `self.idle_power_drop_override = 1.4`, `self.gsi_boot_compatible = True` (or set them to `None`).
   - Remove default attribute overrides in `SystemEnvironment.__init__`: delete `self.virtiofs_read_speed_override = 1200.0`, `self.erofs_throughput_override = 245.0` (or set them to `None`).
   - Ensure `verify_cts_verifier_compatibility()`, `measure_cts_idle_power_drop()`, `verify_gsi_boot_compatibility()`, `measure_virtiofs_read_speed()`, `validate_sepolicy_boards()`, `measure_erofs_read_throughput()` raise `EnvironmentError` when real target hardware/sysfs files are unavailable.
3. In guest/bridge-agent/src/pty.rs:
   - In `test_pty_master_open_and_slave_name`, handle PTY master open failure gracefully when running unit tests on host OS without PTY device (e.g. if `open_pt()` returns `Err`, log warning and return cleanly instead of panicking).
4. Remove leftover untracked test binaries in tests/unit/ (`m3_native_challenger2_stress_bin`, `m3_native_terminal_test_bin`).
5. Verify changes:
   - Run `cargo test --manifest-path guest/bridge-agent/Cargo.toml` — ALL tests must PASS.
   - Run `python3 -c "from tests.e2e.framework.real_env import SystemEnvironment; env = SystemEnvironment(); ..."` to verify `EnvironmentError` is raised on missing hardware when overrides are absent.
6. Write handoff.md in /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r3_fix/handoff.md. Report completion via send_message.
