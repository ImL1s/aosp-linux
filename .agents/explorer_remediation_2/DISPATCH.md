## 2026-08-08T20:05:29Z
You are teamwork_preview_explorer_remediation_2.
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2

Task: Investigate Phase B Audit Findings (Cheating, Simulated Executions, Dead HMAC Code & Facades)

Full Audit Findings File:
/Users/iml1s/Documents/mine/aosp-linux/.agents/victory_auditor/handoff.md
Original Request File:
/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md

Specific Audit Evidence to Investigate:
1. `guest/scripts/launch_vm.sh`:
   - Line 76: `if [ ! -c /dev/kvm ] && [ "${TEST_MODE:-0}" != "1" ]; then`
   - Lines 101-105: `echo "[Launch Script] crosvm binary not in PATH (Simulated execution mode)"; if [ "${TEST_MODE:-0}" = "1" ]; then exec sleep 3600; fi`
   - Utilizing `TEST_MODE=1` to `exec sleep 3600` simulating VM launch. Rule 7 specifies: Missing KVM, crosvm, hardware means BLOCKED/FAIL, NOT simulated success.
2. `guest/bridge-agent/src/auth.rs`:
   - `perform_handshake` uses raw byte equality `verify_token` (`token_buf == secret`), returning `AUTH_OK\n`.
   - `HmacSha256` struct and `compute_hmac_response` function are marked `#[allow(dead_code)]` and never called.
   - Requirement R3 & Phase 3 requires real HMAC-SHA256 challenge/response verification during handshake.
3. `tests/e2e/framework/socket_harness.py`:
   - Contains TCP 127.0.0.1 loopback fallback mechanism (ports 5000, 5001, 5002, 15000, 15001, 15002) pretending to be AF_VSOCK.
4. `tests/e2e/framework/real_env.py` and test suite:
   - `verify_vts_kernel_compliance`, `verify_cts_verifier_compatibility`, `cts_results`, `measure_cts_idle_power_drop`, `measure_zero_copy_latency`, `export_dma_buf`, `import_dma_buf`, `request_location_access`, `get_pcm_audio_stream_chunk` return hardcoded values/constants.
   - Tests in `tests/e2e/` write data to memory dicts/lists of `MockEnvironment` and self-assert them.
5. Facade implementations in `LinuxManagerService.java`:
   - `getInstalledApps()` returns fixed list (`org.gnome.Terminal`, `org.mozilla.firefox`).
   - `launchLinuxApp()` returns `true` directly if `mBridgeService` is null.
   - `installGuestImage()` returns `true` directly without descriptor handling.

Required Deliverable:
Write a comprehensive report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2/handoff.md` detailing:
1. Exact lines and code logic in `launch_vm.sh`, `auth.rs`, `socket_harness.py`, `real_env.py`, `LinuxManagerService.java` that constitute cheating / facade / fake implementation.
2. Detailed fix strategy and code modifications needed for each file to ensure real logic, real HMAC-SHA256 handshake, real non-simulated error handling, and clean environment checks.
