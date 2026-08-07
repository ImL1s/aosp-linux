## 2026-08-06T06:28:02Z
You are worker_m1_fix1, assigned to remediate and fix issues identified in Milestone M1 Iteration 1 Gate Verification.

Your Working Directory: `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_fix1`
Workspace Root: `/Users/iml1s/Documents/mine/aosp-linux`

MANDATORY READS:
1. `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
3. `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md`
4. `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/GATE_STATUS.md`
5. `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m1_2/handoff.md`
6. `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m1_2/handoff.md`

REMEDIATION TASKS TO FIX:
1. Socket Stream Partial Read Helper (`socket_server.cpp`, `vsock_framing.cpp`):
   - Implement `readFull(int fd, void* buf, size_t count)` to loop `read()` until `count` bytes are read or connection closes/errors.
   - Use `readFull` for reading binary headers (`sizeof(header)`) and payloads (`header.length`) to prevent framing corruption on TCP/socket fragmentation.
2. Max Payload Size Guard & Integer Overflow Prevention:
   - Define `constexpr size_t MAX_PAYLOAD_SIZE = 16 * 1024 * 1024; // 16MB`.
   - Reject frames with `header.length > MAX_PAYLOAD_SIZE`.
   - Add overflow check before allocation: check if `sizeof(header) + header.length` overflows `size_t`.
3. Socket Backlog & High Concurrency Handling:
   - Increase `listen(server_fd, SOMAXCONN)` or 128 (instead of 5).
   - Ensure `clientLoop` handles client socket teardown cleanly without dropping connections under load.
4. Double Close Race Condition:
   - Prevent double `close(fd)` when `stop()` is called concurrently while a `clientLoop` is exiting or closing socket descriptors.
5. SELinux & Android.bp Cleanup:
   - Add `/data/system/linux(/.*)? u:object_r:linux_vm_data_file:s0` to `system/sepolicy/private/file_contexts`.
   - Clean up dead obsolete files (`system/linux_bridge/linux_bridge_daemon.h/.cpp` if unused).
   - Clean up root `Android.bp` deprecated Soong properties if any.

VERIFICATION TO RUN:
1. Java unit tests:
   `javac -d /tmp/aosp_test_classes $(find frameworks/base/core/java frameworks/base/services/core/java tests/unit -name "*.java") && java -cp /tmp/aosp_test_classes tests.unit.LinuxManagerServiceTest`
2. Native C++ daemon unit tests & stress tests:
   `clang++ -std=c++20 -Wall -Wextra -I. tests/unit/linux_bridge_test.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_framing.cpp -o /tmp/linux_bridge_unittest && /tmp/linux_bridge_unittest`
3. Full M1 E2E Test Suite:
   `python3 tests/e2e/runner.py --filter F-R1`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

OUTPUT DELIVERABLES:
1. Write `changes.md` in `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_fix1/changes.md`.
2. Write a 5-component `handoff.md` in `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_fix1/handoff.md`.
3. Send a completion message back to parent when done.
