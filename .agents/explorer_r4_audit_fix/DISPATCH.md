## 2026-08-08T15:52:19Z
You are dispatched as Explorer (teamwork_preview_explorer) for Audit Evidence Remediation of the AOSP Dual-OS Project (aosp-linux).

Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r4_audit_fix
Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Full Forensic Audit Evidence Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_r4_1/handoff.md
Dead Ends Log: /Users/iml1s/Documents/mine/aosp-linux/DEAD_ENDS.md
Project Blueprint: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md

The Forensic Auditor has issued an INTEGRITY VIOLATION VETO. You must inspect the full evidence report (/Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_r4_1/handoff.md) and formulate exact, step-by-step remediation plans for all 4 integrity violations:

1. VIOLATION 1: `guest/scripts/launch_vm.sh` lines 76, 102, 103 contains prohibited `exec sleep 3600` under `TEST_MODE=1`.
   - Inspect `launch_vm.sh`. Formulate exact purge of `exec sleep 3600` and `TEST_MODE` sleep logic. Ensure real crosvm/qemu invocation or clean exit without orphaned sleep processes.

2. VIOLATION 2: `frameworks/base/` file count is 113 (specification requires EXACTLY 20 canonical files).
   - Inspect `frameworks/base/`. Identify all 92 unneeded/stub SDK files (e.g. Activity.java, Context.java, Canvas.java) to purge, leaving EXACTLY 20 canonical dual-OS AIDL/service Java files.

3. VIOLATION 3: `cargo test` flakiness and race condition in `guest/bridge-agent/src/portal.rs`.
   - Inspect `portal.rs` (line 379, `test_dispatch_location_with_host_event`). Formulate mutex lock or per-test `GLOBAL_PORTAL_STATE` reset so multi-threaded `cargo test` runs 100% reliably with exit code 0.

4. VIOLATION 4: Repository status dirty due to `tests/unit/challenger_r4_stress_harness.py`.
   - Formulate purge of `tests/unit/challenger_r4_stress_harness.py` so `git status --porcelain` is 100% clean.

Instructions:
- Perform read-only investigation.
- Do NOT recommend strategies that circumvent the audit.
- Write your findings, evidence chain, and exact remediation strategy into /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r4_audit_fix/handoff.md.
- Send a completion message back when done.
