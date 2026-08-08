## 2026-08-08T06:22:59Z
<USER_REQUEST>
You are Challenger 2 for Milestone M1 (Real AVF VM Launch - R1).
Your working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_m1_2

Mandatory files to read:
- /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/DISPATCH.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_m1_1/handoff.md

Task:
1. Conduct adversarial stress testing on `guest/scripts/launch_vm.sh` and VM process lifecycle management.
2. Test edge cases: missing VM config files, malformed JSON configs, empty or invalid security tokens, `flock` lock contention across concurrent launches, `TEST_MODE=1` vs `TEST_MODE=0` behavior, and child PID termination/cleanup on SIGTERM/SIGKILL.
3. Run builds and verification commands:
   - Native C++ unit tests:
     `mkdir -p build_out/bin && clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test`
   - Python E2E tests:
     `python3 tests/e2e/runner.py --filter F-R1`
4. State your verdict clearly as **APPROVE** or **REJECT** in `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_m1_2/handoff.md`. Include test evidence.
</USER_REQUEST>
