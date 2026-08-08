## 2026-08-08T12:53:01Z
You are teamwork_preview_worker_r2_p1.
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r2_p1

Task: Implement Round 2 Remediation Work Package 1 — Host Portal AF_VSOCK & Frame Payload, `real_env.py` Hardcoded Constant Purge & Repository Cleanliness

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Context Files:
- Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- Explorer 1 Report (Host Portal): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_1/handoff.md
- Explorer 3 Report (real_env.py & Cleanliness): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_3/handoff.md

Detailed Remediation Instructions:
1. **Host Portal AF_VSOCK & Frame Payload (`LinuxPortalService.java`)**:
   - Remove ALL `new Socket("localhost", 5000)` TCP fallback instances (lines 713, 724, 747).
   - Implement POSIX AF_VSOCK socket communication in `VsockPortalClient.java` (using `Os.socket(40, SOCK_STREAM, 0)` and `VmSocketAddress(5000, guestCid)`).
   - In `openHardwareCamera()`: Do NOT discard `ImageReader` buffers. Extract YUV_420_888 planes and convert to NV21 byte array in `convertYuv420ToNv21()`.
   - In `sendVsockCameraFramePayload()`: Pack 13-byte Big-Endian VSOK header (`0x56534F4B`) with `CAMF` binary payload (`width`, `height`, `format`, `timestampNs`, `payloadSizeBytes`, `nv21Bytes`), removing dummy string `"CAM_FRAME:/dev/video0"`.
   - Route Audio PCM payload and Location GeoClue updates through `VsockPortalClient` AF_VSOCK with binary/JSON payloads.

2. **Test Framework `real_env.py` Hardcoded Constant Purge**:
   - Refactor lines 134, 137, 140, 234, 331, 502, 526, 529 in `tests/e2e/framework/real_env.py`:
     - `verify_cts_verifier_compatibility()`: Inspect `pm list packages` or `cts_results.json`; raise `EnvironmentError` if unavailable (remove `"PASS"`).
     - `measure_cts_idle_power_drop()`: Read `/sys/class/power_supply/battery/` or `dumpsys battery`; raise `EnvironmentError` if unavailable (remove `1.4`).
     - `verify_gsi_boot_compatibility()`: Inspect `getprop ro.gsi.version`; raise `EnvironmentError` if unavailable (remove `True`).
     - `measure_zero_copy_latency()`: Time actual `export_dma_buf()`/`import_dma_buf()` calls; raise `EnvironmentError` if dma-heap is missing (remove `8.5`).
     - `measure_audio_buffer_delay()`: Time actual `get_pcm_audio_stream_chunk()` call; raise `EnvironmentError` if audio device missing (remove `10.5`).
     - `measure_virtiofs_read_speed()`: Calculate actual MB/s `(2.0 / dt)`; raise `EnvironmentError` if I/O fails (remove `1200.0`).
     - `validate_sepolicy_boards()`: Count real board policy files in `/system/etc/selinux/`; raise `EnvironmentError` if missing (remove `2`).
     - `measure_erofs_read_throughput()`: Read actual `erofs` mount throughput from `/proc/mounts`; raise `EnvironmentError` if missing (remove `245.0`).

3. **Repository Cleanliness & `.gitignore` Update**:
   - Remove untracked test binaries in `tests/unit/`: `m3_native_challenger2_stress_bin`, `m3_native_terminal_test_bin`, and any `*_bin` files.
   - Update `.gitignore` to include: `*_bin`, `scratch/`, `release_dist/`, `patches/`, `e2e_report.json`, `tests/e2e_report.json`, `tests/e2e/e2e_report.json`, `__pycache__/`, `.pytest_cache/`.
   - Verify `git status --porcelain` is completely clean after running `python3 tests/e2e/runner.py`.

4. **Verification**:
   - Check `LinuxPortalService.java` for `new Socket("localhost"` -> 0 matches.
   - Run `python3 tests/e2e/runner.py` -> 430/430 PASS (100.0%), Exit Code 0.
   - Check `git status --porcelain` -> empty.
Write detailed handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r2_p1/handoff.md`.
