## 2026-08-08T15:51:53Z
You are teamwork_preview_worker_r4_audit_remediation.
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r4_audit_remediation

Task: Remediate Forensic Auditor Integrity Violations in Round 4 Audit

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Context Files:
- Auditor Evidence Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_r4_1/handoff.md
- Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md

Detailed Remediation Instructions:

1. **`guest/bridge-agent/src/portal.rs` (0.0 literal removal)**:
   - Line 253: Replace `if loc.latitude != 0.0 || loc.longitude != 0.0` with `if loc.latitude.abs() > f64::EPSILON || loc.longitude.abs() > f64::EPSILON`.
   - Verify `grep -rn '0\.0' guest/bridge-agent/src/portal.rs` returns EXACTLY 0 matches.
   - Run `cargo test --manifest-path guest/bridge-agent/Cargo.toml` -> Verify 34/34 PASS.

2. **`frameworks/base/` File Count Cleanup (Must be EXACTLY 20)**:
   - Inspect all files under `frameworks/base/`.
   - Keep ONLY the 20 genuine dual-OS files:
     - `frameworks/base/core/java/android/system/linux/` (5 AIDL files: `ILinuxManager.aidl`, `ILinuxWindowBridge.aidl`, `ILinuxPortalService.aidl`, `ILinuxStorageProvider.aidl`, `ILinuxBridge.aidl` + 2 Java files: `LinuxManager.java`, `LinuxWindowBridge.java`)
     - `frameworks/base/services/core/java/com/android/server/linux/` (13 Java files: `LinuxManagerService.java`, `LinuxBridgeService.java`, `LinuxPortalService.java`, `VsockPortalClient.java`, `LinuxStorageProvider.java`, `LinuxWindowBridgeService.java`, `LinuxAppProxyActivity.java`, `LinuxAppOpsPolicy.java`, `LinuxCameraPolicy.java`, `LinuxAudioPolicy.java`, `LinuxLocationPolicy.java`, `LinuxLuksProvider.java`, `LinuxVirtiofsService.java`)
   - `git rm -rf` or delete any non-canonical files under `frameworks/base/` (e.g. stub apps or mock classes).
   - Verify `find frameworks/base -type f | wc -l` returns EXACTLY 20!

3. **`guest/scripts/launch_vm.sh` (`TEST_MODE` & `sleep 3600` Removal)**:
   - Completely remove `TEST_MODE` logic and `exec sleep 3600` from `guest/scripts/launch_vm.sh`.
   - Ensure fail-fast error checks: exit code 1 if `/dev/kvm` is missing, exit code 4 if `crosvm` binary is missing.
   - Verify `grep -E 'TEST_MODE|exec sleep 3600' guest/scripts/launch_vm.sh` returns EXACTLY 0 matches!

4. **Repository Cleanliness & `.gitignore`**:
   - Clean up untracked binaries/harnesses in `tests/unit/` (`challenger_r4_stress_harness.py`, `*_bin`).
   - Update `.gitignore` to include: `tests/unit/challenger_r4_stress_harness.py`, `*_bin`, `scratch/`, `release_dist/`, `patches/`, `e2e_report.json`, `tests/e2e_report.json`, `tests/e2e/e2e_report.json`, `__pycache__/`, `.pytest_cache/`.

5. **Verification**:
   - `cargo test --manifest-path guest/bridge-agent/Cargo.toml` -> 34/34 PASS (100.0%).
   - `python3 tests/e2e/runner.py` -> 430/430 PASS (100.0%), Exit Code 0.

Write detailed handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r4_audit_remediation/handoff.md`.
