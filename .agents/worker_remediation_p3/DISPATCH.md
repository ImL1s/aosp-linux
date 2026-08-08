## 2026-08-08T12:07:59Z

Task: Phase B & C Remediation — Socket Harness, Real Env Hardcoded Constants Cleanup & T2-43 Fix

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Context Files:
- Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- Victory Audit Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/victory_auditor/handoff.md
- Explorer 2 Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2/handoff.md
- Explorer 3 Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_3/handoff.md

Detailed Remediation Instructions:
1. `tests/e2e/framework/socket_harness.py`:
   - Remove TCP `127.0.0.1` IPv4 loopback socket fallback mechanism inside `create_port_socket`.
   - If `AF_VSOCK` is missing or fails on non-AVF host environments, raise `VsockUnavailableError` or handle properly per Rule 7 (return BLOCKED / failure, do NOT simulate AF_VSOCK over TCP loopback).

2. `tests/e2e/framework/real_env.py` and test suite:
   - Clean up hardcoded return constants in `real_env.py`:
     - `verify_vts_kernel_compliance()`: Perform real check of `/proc/config.gz` or kernel parameters rather than returning hardcoded `True`.
     - `export_dma_buf()` / `import_dma_buf()`: Perform actual dma-heap / graphic buffer operations; raise `EnvironmentError` if dma-heap is missing (do NOT return hardcoded FD 42 or dict).
     - `request_location_access()`: Perform real Android Location API / binder / portal check; raise error if provider unavailable (do NOT return hardcoded Taipei GPS coordinates).
     - `get_pcm_audio_stream_chunk()`: Read real audio stream or handle error (do NOT return hardcoded byte string).
     - `cts_results`: Query real CTS result data or raise error (do NOT return hardcoded dict `{"passed": 170, "failed": 0}`).
     - Clean up self-certifying tests in `tests/e2e/` that write expected values into local python memory dictionaries and assert them immediately. Replace with genuine socket/IPC assertions.

3. Fix `T2-43` in `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`:
   - In `run_test()`, update `CustomAssertions.assert_in` to check for `"cid != ALLOWED_GUEST_CID"` matching the actual refactored parameter in `system/linux_bridge/vsock_server.cpp`.

4. Dynamic Test Runner Execution:
   - Run `python3 tests/e2e/runner.py`.
   - Ensure all 430 tests pass dynamically with exit code 0.
   - Do NOT commit any static `e2e_report.json` files.

5. Verify & Report:
   Run `python3 tests/e2e/runner.py` and verify all tests pass with exit code 0.
   Write detailed handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_remediation_p3/handoff.md`.
