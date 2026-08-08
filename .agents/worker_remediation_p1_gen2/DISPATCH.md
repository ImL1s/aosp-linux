## 2026-08-08T12:09:00Z
Task: Phase A Remediation — Timeline, Provenance & Miniature Stub Cleanup

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Context Files:
- Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- Victory Audit Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/victory_auditor/handoff.md
- Explorer 1 Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_1/handoff.md

Detailed Instructions (Execute Steps 1 to 6 from Explorer 1 report):
1. Git Purge Static JSON Reports & Prebuilts:
   `git rm -f tests/e2e/e2e_report.json tests/e2e_report.json` (if present)
   `git rm -f hmac_auth.o release_dist/aosp-linux-deployment-v1.0.0.tar.gz` (if present)
   `git rm -rf guest/bridge-agent/target/ guest/portal-agent/target/` (if present)
   `git rm -f system/linux_bridge/tests/linux_bridge_test_bin tests/unit/VirtioGpuDmabufTest_bin tests/unit/challenger_r2_empirical_bin tests/unit/m3_native_challenger2_stress_bin tests/unit/m3_native_terminal_test_bin unit/challenger_m3_empirical_test` (if present)
   `git rm -f scratch/bad_magic_vbmeta.img scratch/dummy.img scratch/truncated_vbmeta.img scratch/test_slot_metadata.json scratch/test_slot_metadata_hb.json` (if present)
   Remove `build_out/` directory if present on disk.

2. Git Purge 77 Miniature Stand-in Stub Classes under `frameworks/base/`:
   Remove all 77 fake stub folders/files listed in Explorer 1 report (`android/annotation/`, `android/app/`, `android/content/`, `android/database/`, `android/graphics/`, `android/hardware/`, `android/location/`, `android/media/`, `android/net/`, `android/os/`, `android/provider/`, `android/text/`, `android/util/`, `android/view/`, `android/widget/`, `org/`, `LocalServices.java`, `SystemService.java`, `SystemServer.java`, `core/res/AndroidManifest.xml`).
   CRITICAL: Retain ONLY the 20 genuine dual-OS framework files under `frameworks/base/core/java/android/system/linux/` and `frameworks/base/services/core/java/com/android/server/linux/`.

3. Update `.gitignore`:
   Ensure `build_out/`, `target/`, `guest/bridge-agent/target/`, `guest/portal-agent/target/`, `*.o`, `*.so`, `*.a`, `*.class`, `*.dex`, `*.apk`, `*.tar.gz`, `tests/e2e/e2e_report.json`, `tests/e2e_report.json` are listed in `.gitignore`.

4. Refactor `Android.bp`:
   Update root `Android.bp` so `srcs` only points to `frameworks/base/core/java/android/system/linux/**/*.java` and `frameworks/base/core/java/android/system/linux/**/*.aidl` (no wildcard `core/java/**/*.java`).

5. Create `patches/aosp_frameworks_base.patch`:
   Create directory `patches/` and file `patches/aosp_frameworks_base.patch` documenting canonical AOSP modifications for `Context.java`, `SystemServiceRegistry.java`, `SystemServer.java`, and `AndroidManifest.xml`.

6. Verify & Report:
   Run verification checks:
   - `git ls-files | grep -E '(e2e_report\.json|hmac_auth\.o|\.tar\.gz|_bin$|guest/bridge-agent/target)'` -> empty
   - `find frameworks/base -type f | wc -l` -> 20
   Write detailed handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_remediation_p1_gen2/handoff.md`.
