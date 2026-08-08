## 2026-08-08T06:13:30Z

You are Worker 1 for Milestone M1 (Real AVF VM Launch - R1).
Your working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_m1_1

Mandatory files to read:
- /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/DISPATCH.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_1/handoff.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_2/handoff.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_3/handoff.md

Write ownership files (You exclusively own these files):
- frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java
- frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java
- system/linux_bridge/socket_server.cpp (and system/linux_bridge/socket_server.h)
- guest/scripts/launch_vm.sh

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Implementation Tasks:
1. Native Host Daemon (system/linux_bridge/socket_server.cpp & socket_server.h):
   - Remove fake immediate CMD_HANDSHAKE_COMPLETE (0x0003) response on CMD_VM_START (0x0001).
   - In CMD_VM_START: Generate 32-byte security token and secret via HmacAuth::generateRandomToken(). Call mVsockServer->setAuthToken(token, secret). Save mPendingClientFd = clientFd and mPendingTransactionId = header.transactionId. State becomes VmState::STARTING.
   - Spawn guest/scripts/launch_vm.sh via posix_spawn or fork/exec passing token hex string. Track child PID (mVmPid) and watch child process status.
   - Defer sending CMD_HANDSHAKE_COMPLETE response.
   - Implement onVsockHandshakeSuccess callback in SocketServer to send CMD_HANDSHAKE_COMPLETE (0x0003) to framework upon Vsock HMAC authentication success.
   - In CMD_VM_STOP (0x0002): Terminate child process cleanly (SIGTERM -> SIGKILL after 2s), call vsockServer.resetSession(), reset state to STOPPED.

2. Shell Script (guest/scripts/launch_vm.sh):
   - Accept $1 (CONFIG_FILE) and $2 (AUTH_TOKEN).
   - Pass android_bridge.token=${AUTH_TOKEN} into kernel parameters CMDLINE.
   - Add exec prefix to crosvm run so crosvm replaces the shell process image for accurate child PID tracking.
   - In TEST_MODE=1 when crosvm binary is absent, run exec sleep 3600 so test harnesses can test process spawning and PID management without /dev/kvm.

3. Java Framework Services (LinuxManagerService.java & LinuxBridgeService.java):
   - LinuxManagerService.java: Generate 32-byte auth token in startVm() and pass to mBridgeService.notifyVmStarting(token). Implement onVmStartFailed in callback to handle native launch failure and transition state to STATE_ERROR. Keep 15-second boot timeout.
   - LinuxBridgeService.java: Update notifyVmStarting(byte[] authToken) to send 32-byte authToken in CMD_VM_START payload. Add CMD_VM_START_FAILED (0x0004) packet handling and callback onVmStartFailed(errorCode, message).

4. Build & Test Verification:
   - Build native C++ unit tests:
     mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test
   - Run native C++ unit tests:
     ./build_out/bin/linux_bridge_test
   - Verify launch_vm.sh:
     TEST_MODE=1 bash guest/scripts/launch_vm.sh /data/misc/linux/vm_config.json testtoken123
   - Run Python E2E tests:
     python3 tests/e2e/runner.py --filter F-R1

Write your complete implementation report and test results to /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_m1_1/handoff.md.
