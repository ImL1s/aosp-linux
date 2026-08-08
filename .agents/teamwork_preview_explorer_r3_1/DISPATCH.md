## 2026-08-08T13:13:43Z
<USER_REQUEST>
You are teamwork_preview_explorer_r3_1. Your working directory is `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_r3_1`.

Your task is to investigate and design exact remediation fixes for Defect 1 and Defect 2 from the Round 3 Victory Audit report.

Read the following files before starting:
1. `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
3. Full audit report: `/Users/iml1s/Documents/mine/aosp-linux/.agents/victory_auditor_r3/handoff.md`

Focus Areas:
1. STAND-IN STUB CLASSES (Req 1 / Rule 3):
   - Purge duplicate/stub classes: `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java` (dummy `getState()` returning `STATE_STOPPED`), `packages/apps/LinuxTerminal/src/android/graphics/Rect.java` (64-line stub), and `frameworks/base/core/java/android/util/Slog.java`.
   - Inspect all places where these stubs are referenced or imported in `LinuxTerminal` and framework components.
   - Ensure genuine AOSP framework class imports and patches (`patches/aosp_frameworks_base.patch`).

2. AUTH & VSOCK CONTRACT MISMATCH (Req 3):
   - In `guest/bridge-agent/src/auth.rs`, wire actual RFC 2104 `HmacSha256` challenge/response verification in `perform_handshake`. Remove raw token byte equality `verify_token` and `#[allow(dead_code)]`.
   - Ensure Host C++ (`system/linux_bridge/hmac_auth.cpp`) and Guest Rust (`guest/bridge-agent/src/auth.rs`) match golden vectors and RFC 2104 HMAC-SHA256 challenge-response contract.
   - In `tests/e2e/framework/socket_harness.py`, remove IPv4 TCP `127.0.0.1` fallback sockets for ports 5000, 5001, 5002, 15000, 15001, 15002.

Deliverable:
Write a comprehensive design report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_r3_1/handoff.md` with:
- Exact line numbers and file paths needing changes
- Step-by-step code change recommendations
- Verification steps (build/test commands)
Send a completion message to the parent orchestrator (Conv ID: `20d6aa05-0e46-4016-818a-bbff71e44e71`).
</USER_REQUEST>
