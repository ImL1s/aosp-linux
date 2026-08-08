## 2026-08-08T15:54:05Z
You are dispatched as the Master Audit Fix Worker (teamwork_preview_worker) for AOSP Dual-OS Remediation Project (aosp-linux).

Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_master_r4_audit_fix
Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Forensic Audit Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_r4_1/handoff.md
Explorer Fix Design Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r4_audit_fix/handoff.md
Project Blueprint: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md

Your task is to execute ALL 4 audit violation fixes exactly as designed by the Explorer:

1. FIX 1 (`guest/scripts/launch_vm.sh`):
   - Purge ALL occurrences of `exec sleep 3600` and `TEST_MODE` sleep logic from `guest/scripts/launch_vm.sh`.
   - Update KVM check to warn to stderr instead of exiting/sleeping.
   - Update execution block to try `crosvm` -> `qemu-system-aarch64` -> `qemu-system-x86_64`; if none found, print message and `exit 0` cleanly with zero orphaned sleep processes.

2. FIX 2 (`frameworks/base/` file count reduction to EXACTLY 20):
   - Purge all 93 unneeded SDK stub directories/files (annotation, app, content, database, graphics, hardware, location, media, net, os, provider, text, util, view, widget, org, res, LocalServices.java, SystemServer.java, SystemService.java, and duplicate ILinuxBridgeDaemon.aidl).
   - Verify `find frameworks/base -type f | wc -l` outputs EXACTLY 20 canonical files.

3. FIX 3 (`guest/bridge-agent/src/portal.rs` thread-safety and cargo test stability):
   - Add `reset_portal_state()` helper function to reset `GLOBAL_PORTAL_STATE`.
   - Update write lock acquiring in `handle_portal_session` to handle poison: `get_portal_state().write().unwrap_or_else(|e| e.into_inner())`.
   - In `mod tests`, acquire `TEST_LOCK` and invoke `reset_portal_state()` at the start of each unit test.
   - Verify `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml` passes 34/34 unit tests with 100% thread safety and exit code 0.

4. FIX 4 (Repository cleanliness):
   - Remove untracked files `tests/unit/challenger_r4_stress_harness.py` and `tests/unit/challenger_r4_concurrency_pty_stress.py`.
   - Verify `git status --porcelain` is clean (0 non-agent untracked files).

5. Dynamic Test Verification:
   - Run `python3 tests/e2e/runner.py` and verify 430/430 tests pass (100.0%, exit code 0).

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

After executing all 4 fixes, record test outputs and verification command results in /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_master_r4_audit_fix/handoff.md and send a completion message back.
