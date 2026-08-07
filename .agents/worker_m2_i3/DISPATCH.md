## 2026-08-06T07:01:20Z
<USER_REQUEST>
You are Worker for Iteration 3 of Milestone M2 (AVF Guest Setup & CE Storage Encryption).
Your Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_i3

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

MANDATORY REFERENCES:
- Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- Master Blueprint: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Scope File: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md
- Challenger 1 Rejection Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_i2_1/handoff.md
- Explorer 1 (Iter 3) Fix Blueprint: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i3_1/handoff.md

Mission:
Implement authentic fixes for shell scripts and Tier 2 E2E tests:

1. **`guest/scripts/launch_vm.sh`**:
   - Fix file locking redirection: Change `exec 200>"$BASE_IMG"` and `exec 201>"$OVERLAY_IMG"` to read mode `exec 200<"$BASE_IMG"` and `exec 201<"$OVERLAY_IMG"` (or `exec 200<>"$BASE_IMG"`), preventing file truncation (`O_TRUNC`).
   - Implement dynamic JSON config parsing for `$CONFIG_FILE` (`vm_config.json`) using inline Python (`python3 -c "import json; ..."`), extracting RAM, CPU, CID, kernel, initrd, and image paths dynamically.

2. **`guest/scripts/init_storage_layout.sh`**:
   - Change `[ ! -f "$BASE_IMG" ]`, `[ ! -f "$OVERLAY_IMG" ]`, `[ ! -f "$HOME_IMG" ]` checks to `[ ! -f "$IMG" ] || [ ! -s "$IMG" ]` so truncated/0-byte/corrupted image files are detected and automatically re-initialized.

3. **`guest/scripts/guest_mount_overlay.sh`**:
   - Add ENOSPC space pre-checks and upperdir/workdir cleanup & mount retry logic on OverlayFS mount failure.

4. **`tests/e2e/tier2_boundary_corner/test_m2_tier2.py`**:
   - Refactor T2-32, T2-33, T2-35 to perform real subprocess execution, verifying image file size non-zero after `launch_vm.sh` execution and successful 0-byte image recovery by `init_storage_layout.sh`.

5. Verification & Testing:
   - Execute `./scripts/run_m2_verification.sh` and `python3 tests/e2e/runner.py`.
   - Ensure 100% pass rates across unit and E2E test suites.

6. Write completion report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_i3/handoff.md` and send summary message back to parent.
</USER_REQUEST>
