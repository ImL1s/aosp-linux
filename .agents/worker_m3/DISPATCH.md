## 2026-08-13T17:38:38Z
You are worker_m3 (M3 Auth Protocol & Handshake Initiator Worker).
Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3
Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Survey Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/survey_report.md
Project Plan: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md

Scope & Tasks for Milestone 3 (R3):
1. Read ORIGINAL_REQUEST.md (R3 requirement) and survey_report.md from survey_explorer_2.
2. Fix Single-Secret HMAC Agreement across Host Java, Host C++, and Guest Agent:
   - In `LinuxManagerService.java` and `LinuxBridgeService.java`: Generate a 32-byte binary token AND a 32-byte binary secret (total 64-byte payload). Send 64-byte payload over Unix Domain Socket (`CMD_VM_START`).
   - In `system/linux_bridge/socket_server.cpp`: Receive the 64-byte payload containing token (bytes 0..31) and secret (bytes 32..63). Pass `secret` to `mVsockServer->setAuthToken(token, secret)` and hex-encode `secret` (or token/secret agreement) to pass as `AUTH_TOKEN` to `launch_vm.sh`.
   - In `guest/scripts/launch_vm.sh`: Pass `android_bridge.token=${AUTH_TOKEN}` (where `AUTH_TOKEN` is hex string of the exact 32-byte secret).
   - In `guest/bridge-agent/src/auth.rs`: Ensure `parse_secret_from_cmdline` parses `android_bridge.token=` from `/proc/cmdline` and decodes the hex string into the exact 32-byte binary secret.
3. Fix Guest Agent Startup Initiator:
   - Host C++ `vsock_server.cpp` remains Server listening on AF_VSOCK port 5000.
   - In `guest/bridge-agent/src/main.rs`: Implement startup initiator logic! Upon agent boot, connect to Host `CID_HOST = 2` port 5000 via AF_VSOCK socket. Construct and send 64-byte `AuthHandshakePayload` (32-byte token + 32-byte RFC 2104 HMAC-SHA256 signature).
   - Ensure upon successful handshake, Host C++ sends `CMD_HANDSHAKE_COMPLETE` (0x0003) to Host Java, transitioning VM state to `STATE_RUNNING`.
4. Rust ARM64 Compilation Verification:
   - Run `$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu` inside `guest/bridge-agent` and `guest/portal-agent`. Fix any warnings so build passes cleanly with zero warnings or errors.
5. Host C++ & Java Compilation Verification:
   - Verify C++ bridge daemon and Java services compile cleanly.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Document changes and build/test commands in:
- /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3/handoff.md

Send a completion message when done.
