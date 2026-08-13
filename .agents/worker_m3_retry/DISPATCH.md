## 2026-08-14T01:54:05Z
You are worker_m3_retry (M3 HMACSHA256 Constant Fix Worker).
Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_retry
Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Challenger Handoff: /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m3_2/handoff.md

Tasks for Milestone 3 Retry:
1. Fix cryptographic constant typo in `system/linux_bridge/hmac_auth.cpp`:
   Line 87: Change SHA-256 constant `K[62]` from `0xbef4a3f7` to standard `0xbef9a3f7`.
2. Run C++ build and test: compile `linux_bridge_test` and run unit tests validating RFC 4231 test vectors pass with 100% accuracy.
3. Run Rust check: `$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu` in `guest/bridge-agent` and verify 0 warnings, 0 errors.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Document changes and test output in:
- /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3_retry/handoff.md

Send a completion message when done.
