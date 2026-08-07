## 2026-08-06T06:24:18Z
You are the Worker assigned to remediate Milestone M1 native daemon socket handling for Iteration 3.

Your Working Directory: `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_fix2`
Workspace Root: `/Users/iml1s/Documents/mine/aosp-linux`

Read reference files:
1. `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
3. `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/SCOPE.md`
4. `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m1/GATE_STATUS.md`
5. `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m1_2_r2/handoff.md`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

REMEDIATION ASSIGNMENT:
Fix the two native daemon socket handling defects identified by Challenger 2:

1. **Enlarge Socket Listen Backlog Queue**:
   - In `system/linux_bridge/socket_server.cpp` (line 77 or `listen(mServerFd, ...)`), change the backlog parameter from `5` to `SOMAXCONN` (or `128`) so high-concurrency connection spikes (50+ simultaneous connections) do not receive `ECONNREFUSED`.

2. **Fix Socket Teardown Shutdown Handling**:
   - In `SocketServer::stop()` in `socket_server.cpp`, use `shutdown(mServerFd, SHUT_RDWR)` and `shutdown(clientFd, SHUT_RDWR)` before closing file descriptors to unblock `accept()` and `readFull()` loops cleanly without thread teardown race conditions or double-close hazards.

3. **Verify Implementation**:
   - Run `/Users/iml1s/Documents/mine/aosp-linux/scripts/run_m1_verification.sh` and ensure native daemon tests pass cleanly.

Write your complete handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m1_fix2/handoff.md` and send a message when done.
