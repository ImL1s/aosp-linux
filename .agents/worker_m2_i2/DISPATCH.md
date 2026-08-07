## 2026-08-06T06:48:03Z
You are Worker 2 Iteration 2 (teamwork_preview_worker) for Milestone M2 (AVF Guest Setup & CE Storage Encryption).

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_i2
Workspace Root: /Users/iml1s/Documents/mine/aosp-linux

MANDATORY DOCUMENTS TO READ FIRST:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m2_1/handoff.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i2_1/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i2_2/handoff.md
6. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i2_3/handoff.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

YOUR MISSION:
Execute authentic remediation for all 5 defects identified in Milestone M2 Iteration 1:
1. **Rust Guest Agent Remediation (`guest/bridge-agent/src/`)**:
   - Delete fake XOR loop `compute_hmac_sha256()` in `guest/bridge-agent/src/main.rs`. Use `src/auth.rs` with `hmac` and `sha2` crates for authentic HMAC-SHA256 calculation.
   - Implement real `AF_VSOCK` stream socket connection (`CID_HOST=2`, `PORT_CONTROL=5000`) in `src/vsock.rs` & `main.rs`, sending/receiving 13-byte `VsockFrameHeader` + 64-byte `AuthHandshakePayload` over socket.
   - Apply volatile memory zeroing via `zeroize`.

2. **C++ Native Daemon Remediation (`system/linux_bridge/`)**:
   - Resolve compiler error: remove duplicate `struct AuthHandshakePayload` from `system/linux_bridge/hmac_auth.h` and add `#include "vsock_framing.h"`.
   - Remove fake XOR loop fallback in `hmac_auth.cpp`. Implement genuine RFC 2104 / FIPS 180-4 compliant C++ HMAC-SHA256 engine.
   - Implement real POSIX `AF_VSOCK` socket binding (`socket(AF_VSOCK, SOCK_STREAM, 0)`, `bind()`, `listen()`, `accept()`) for ports 5000, 5001, 5002 in `vsock_server.cpp` with mandatory `svm_cid == 3` verification.

3. **Android CE Master Key Remediation (`LinuxManagerService.java`)**:
   - Replace `new SecureRandom()` key generation on user unlock with persistent Android Keymaster / CE storage key derivation (`/data/system/linux_ce/user_<userId>.key`).
   - Zero out key memory on screen lock (`Arrays.fill`).

4. **Real Binary E2E Test Suite Remediation (`tests/e2e/`)**:
   - Refactor `runner.py` and test cases (`test_m2_tier1.py`, `test_m2_tier2.py`) to remove inline dummy Python functions and execute actual compiled binaries (`linux_bridge_test`, `launch_vm.sh`, compiled Rust agent) via `CommandRunner`.

VERIFICATION REQUIREMENTS:
- Run builds and compilation (`clang++`, `cargo build`, etc.).
- Run native tests `./build_out/bin/linux_bridge_test` and Python E2E runner `python3 tests/e2e/runner.py`.
- Document all changes, executed build commands, and test logs in `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_i2/handoff.md`. Send a message when complete.
