## 2026-08-08T12:38:20Z

<USER_REQUEST>
You are teamwork_preview_worker_remediation_p3_gen2.
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_remediation_p3_gen2

Task: Implement Phase C Audit Fixes — C++ Thread Lifecycle Fix in `socket_server.cpp`, Recompile `./build_out/bin/linux_bridge_test`, and Fix `socket_harness.py` Port Collisions

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Context Files:
- Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- Forensic Audit Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_remediation_1/handoff.md
- Explorer Audit Fix Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_iter2_1/handoff.md

Detailed Instructions (Execute Strategies 1, 2, and 3 from Explorer report):

1. `system/linux_bridge/socket_server.h` & `socket_server.cpp`:
   - In `socket_server.h`: Add `std::mutex mClientThreadsMutex;` and `std::vector<std::thread> mClientThreads;`.
   - In `socket_server.cpp` (`listenLoop`): Remove `clientThread.detach()`. Push client threads into `mClientThreads`.
   - In `socket_server.cpp` (`stop`):
     - Close active client FDs (`shutdown` + `close`) to unblock client threads.
     - Join `mListenThread`.
     - Move and `join()` all `mClientThreads` so no detached threads outlive the `SocketServer` object.

2. Recompile `./build_out/bin/linux_bridge_test`:
   - Run compilation:
     `clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test`
   - Run 50-iteration C++ stress check:
     `bash -c 'for i in $(seq 1 50); do ./build_out/bin/linux_bridge_test > /dev/null 2>&1 || { echo "FAILED AT RUN $i"; exit 1; }; done; echo "50 RUNS ALL PASSED CLEANLY"'`
     Verify zero `SIGABRT` / exit code 134.

3. `tests/e2e/framework/socket_harness.py`:
   - Add `SO_REUSEADDR` and `SO_REUSEPORT` to all socket creations in `SocketHarnessServer.start()` and `RealVsockBridge.send`.
   - In `SocketHarnessServer.stop()`: Remove `SO_LINGER` call on listening sockets. Shutdown and close client sockets first, then server sockets. Unlink stale sockets on start and stop.

4. Full Test Suite Verification:
   - Run `python3 tests/e2e/runner.py`.
   - Verify output: 430/430 PASS (100.0%), 0 FAILED, Exit Code 0.

Write detailed handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_remediation_p3_gen2/handoff.md`.
</USER_REQUEST>
