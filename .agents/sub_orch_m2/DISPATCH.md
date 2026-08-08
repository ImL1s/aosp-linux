# DISPATCH — Sub-Orchestrator M2 (Production Guest Agent Loop - R2)

## Mission
Orchestrate the iteration loop (Explorer -> Worker -> Reviewer -> Challenger -> Auditor) to fix Defect R2: Production Guest Agent Loop.

## Scope & Requirements
1. `guest/bridge-agent/src/main.rs`: Replace `loop { sleep(5s); }` with multi-threaded server dispatch loop listening on Vsock Ports 5000 (Control/Portal), 5001 (PTY), and 5002 (Wayland).
2. `guest/bridge-agent/src/auth.rs` & `main.rs`: Remove hardcoded secrets (`b"shared_secret_key_32bytes_long!!"`) and all-zero token fallbacks (`vec![0u8; 32]`).
3. Handshake error handling: Require authentication failure to abort process immediately (`std::process::exit(1)`).
4. Implement RPC dispatchers for PTY allocation/I/O, Wayland socket proxying (`/run/user/1000/wayland-0`), and Portal RPC requests.

## Input Documents
- `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
- `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md`
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_1/handoff.md`

## Outputs
Write progress to `.agents/sub_orch_m2/progress.md` and handoff report to `.agents/sub_orch_m2/handoff.md`.

## 2026-08-08T06:36:19Z
You are Sub-Orchestrator M2 (Successor gen2). Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2.

Resume work at /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2. Read handoff.md, BRIEFING.md, ORIGINAL_REQUEST.md, DISPATCH.md, and progress.md for current state.
Your parent is e27b9395-c6bf-4764-91fe-af9e49f3aa80 — use this ID for all escalation and status reporting (send_message).

Current State & Immediate Next Action:
- Iteration 3 failed Gate due to Auditor 1 and Reviewer 1 flagging that `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/ota_rollback.rs` STILL EXISTS on physical disk.
- All technical code implementations in `guest/bridge-agent/src/` (main.rs, auth.rs, vsock.rs, pty.rs, wayland.rs, portal.rs) are APPROVED and 31/31 cargo tests pass.
- Immediate action for gen2: Dispatch Worker 4 to physically remove `rm -f /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/ota_rollback.rs` from disk and verify `cargo test`. Then run Iteration 4 verification (2 Reviewers, 2 Challengers, 1 Auditor) to pass the Gate and claim victory for Milestone M2.

