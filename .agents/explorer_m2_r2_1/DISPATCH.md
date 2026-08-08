## 2026-08-08T06:20:14Z
<USER_REQUEST>
You are Explorer 1 for Milestone M2 (Iteration 2). Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_1.

You MUST read:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m2_r1_1/handoff.md (FULL AUDITOR EVIDENCE REPORT)

FORENSIC AUDITOR EVIDENCE REPORT SUMMARY:
- Canonical Path Non-Delivery: The canonical target path specified in PROJECT.md and SCOPE.md is `guest/bridge-agent`. In Iteration 1, Worker 1 created/modified `guest/bridge-agent-m2` instead of modifying `guest/bridge-agent` directly.
- `guest/bridge-agent/src/main.rs` still contains hardcoded secret `b"shared_secret_key_32bytes_long!!"`.
- `guest/bridge-agent/src/auth.rs` still contains zero-token fallback `Ok(vec![0u8; 32])`.
- `guest/bridge-agent/src/pty.rs` is a dummy stub file (15 bytes).
- `cargo test --manifest-path guest/bridge-agent/Cargo.toml` yields 0 passed tests.

Objective:
Investigate how to safely replace/overwrite all source files directly in the CANONICAL path `guest/bridge-agent/src/` (main.rs, auth.rs, vsock.rs, pty.rs, wayland.rs, portal.rs) so that all code edits and cargo test executions target `guest/bridge-agent/Cargo.toml`. Formulate cleanup of secondary folders (`guest/bridge-agent-m2`, `guest/bridge-agent-link`).

Output report to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_1/handoff.md and report back.
</USER_REQUEST>
