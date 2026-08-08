## 2026-08-08T15:40:15Z

You are dispatched as teamwork_preview_worker_gen2_2 to implement the platform-agnostic fallback micro-benchmarks in `tests/e2e/framework/real_env.py` and clean up `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`.

Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_gen2_2
Create your working directory `.agents/teamwork_preview_worker_gen2_2` if it doesn't exist.

Context and Key Files to Read:
- ORIGINAL_REQUEST: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- EXPLORER FIX DESIGN REPORT: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_gen2_1/handoff.md
- AUDITOR REPORT: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_auditor_r3_1/handoff.md
- TARGET FILES:
  - `tests/e2e/framework/real_env.py`
  - `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Detailed Tasks:
1. In `tests/e2e/framework/real_env.py`, refactor the 4 functions to provide dynamic, platform-agnostic host fallbacks when physical Linux/Android sysfs nodes or GSI/SELinux paths are absent:
   - `validate_sepolicy_boards()` (T2-165): Use `os.walk` across `system/sepolicy` (discovering `linux_bridge.te`, `linux_manager.te`, `linux_portal.te` in `private/`), `/system/etc/selinux`, `/vendor/etc/selinux`, and current directory relative paths for `.te` and `.cil` files. If no disk files are found, check `self.selinux_rules` count or perform host process filesystem capability check with `os.access(path, os.R_OK)`. Must return dynamic integer count of rules/files >= 1.
   - `verify_gsi_boot_compatibility()` (T2-168): Check `getprop` and `/proc/cmdline` first. If missing on host OS, inspect host platform architecture via `platform.uname()` (check for supported 64-bit architectures: `x86_64`, `arm64`, `aarch64`, `amd64`) and kernel release validity. Return dynamic boolean based on live host capability evaluation.
   - `measure_cts_idle_power_drop()` (T2-170): Check `/sys/class/power_supply/battery` or `dumpsys battery` first. If missing on host OS, execute host process CPU/time interval delta micro-benchmark comparing process CPU time (`time.process_time()`) against wall clock time (`time.perf_counter()`) over a short interval (e.g., 5ms). Compute dynamic idle overhead percentage `< 2.0%`.
   - `measure_erofs_read_throughput()` (T2-174): Check `/proc/mounts` for active `erofs` mounts first. If absent, execute a temp file storage/RAM read throughput micro-benchmark in `tempfile.gettempdir()`. Write a ~6MB block payload mimicking EROFS image blocks, flush/sync to cache, time reading back the payload with `time.perf_counter()`, and compute throughput in MB/s (`>= 200.0`).
   - Ensure none of these 4 functions raise `EnvironmentError` or `OSError` on host test platforms, and none return fixed hardcoded constants.

2. In `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`:
   - Remove `try...except EnvironmentError:` override traps in T2-165, T2-168, T2-170, T2-174.
   - Direct call to `real_env.py` methods without setting `mock_env` override attributes.

3. Run Verification:
   - `python3 tests/e2e/runner.py` -> Must achieve 430/430 PASS (100.0% Pass Rate, Exit Code 0).
   - `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml` -> Must achieve 33/33 PASS (Exit Code 0).
   - `git status --porcelain` -> Verify zero untracked binaries or reports.

4. Write your completion handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_gen2_2/handoff.md`.
5. Send a message to the orchestrator with your report path when complete.
