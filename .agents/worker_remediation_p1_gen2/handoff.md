# Phase A Remediation Handoff Report — Timeline, Provenance & Miniature Stub Cleanup

## 1. Observation
Executing Phase A remediation based on `DISPATCH.md` and `explorer_remediation_1/handoff.md`:

1. **Static JSON Reports & Binary Artifact Purge**:
   Ran python script invoking `git rm -f --ignore-unmatch`:
   - `tests/e2e/e2e_report.json`, `tests/e2e_report.json`
   - `hmac_auth.o`, `release_dist/aosp-linux-deployment-v1.0.0.tar.gz`
   - `guest/bridge-agent/target/`, `guest/portal-agent/target/` (566 tracked Rust target files removed)
   - `system/linux_bridge/tests/linux_bridge_test_bin`, `tests/unit/VirtioGpuDmabufTest_bin`, `tests/unit/challenger_r2_empirical_bin`, `tests/unit/m3_native_challenger2_stress_bin`, `tests/unit/m3_native_terminal_test_bin`, `unit/challenger_m3_empirical_test`
   - `scratch/bad_magic_vbmeta.img`, `scratch/dummy.img`, `scratch/truncated_vbmeta.img`, `scratch/test_slot_metadata.json`, `scratch/test_slot_metadata_hb.json`
   - Removed untracked build output directory `build_out/` from disk via `rm -rf build_out`.

2. **Miniature Stand-in Stub Classes Purge (77 files)**:
   Executed `git rm -rf --ignore-unmatch` on all 77 miniature stand-in classes/directories under `frameworks/base/`:
   - `frameworks/base/core/java/android/annotation/`
   - `frameworks/base/core/java/android/app/`
   - `frameworks/base/core/java/android/content/`
   - `frameworks/base/core/java/android/database/`
   - `frameworks/base/core/java/android/graphics/`
   - `frameworks/base/core/java/android/hardware/`
   - `frameworks/base/core/java/android/location/`
   - `frameworks/base/core/java/android/media/`
   - `frameworks/base/core/java/android/net/`
   - `frameworks/base/core/java/android/os/`
   - `frameworks/base/core/java/android/provider/`
   - `frameworks/base/core/java/android/text/`
   - `frameworks/base/core/java/android/util/`
   - `frameworks/base/core/java/android/view/`
   - `frameworks/base/core/java/android/widget/`
   - `frameworks/base/core/java/org/`
   - `frameworks/base/services/core/java/com/android/server/LocalServices.java`
   - `frameworks/base/services/core/java/com/android/server/SystemService.java`
   - `frameworks/base/services/core/java/com/android/server/SystemServer.java`
   - `frameworks/base/core/res/AndroidManifest.xml`

   Ran `find frameworks/base -type f | wc -l`:
   Output: `20` (Only the genuine Dual-OS framework files remain under `android/system/linux/` and `com/android/server/linux/`).

3. **.gitignore Verification**:
   Inspected `/Users/iml1s/Documents/mine/aosp-linux/.gitignore`:
   Contains `build_out/`, `target/`, `guest/bridge-agent/target/`, `guest/portal-agent/target/`, `*.o`, `*.so`, `*.a`, `*.class`, `*.dex`, `*.apk`, `*.tar.gz`, `tests/e2e/e2e_report.json`, `tests/e2e_report.json`.

4. **Android.bp Refactoring**:
   Inspected `/Users/iml1s/Documents/mine/aosp-linux/Android.bp`:
   ```bp
   java_sdk_library {
       name: "android.system.linux",
       srcs: [
           "frameworks/base/core/java/android/system/linux/**/*.java",
           "frameworks/base/core/java/android/system/linux/**/*.aidl",
       ],
       api_packages: ["android.system.linux"],
       platform_apis: true,
       installable: true,
   }
   ```
   No wildcard `core/java/**/*.java` pattern is used.

5. **AOSP Patch Creation**:
   Inspected `/Users/iml1s/Documents/mine/aosp-linux/patches/aosp_frameworks_base.patch`:
   Contains canonical unified diff modifications for AOSP core files:
   - `Context.java`: Adds `LINUX_SERVICE = "linux"` constant
   - `SystemServiceRegistry.java`: Registers `LinuxManager` service fetcher
   - `SystemServer.java`: Starts `LinuxManagerService` in `startOtherServices()`
   - `AndroidManifest.xml`: Declares `MANAGE_LINUX_ENVIRONMENT` and `USE_LINUX_TERMINAL` permissions

6. **Verification Checks Output**:
   - `git ls-files | grep -E '(e2e_report\.json|hmac_auth\.o|\.tar\.gz|_bin$|guest/bridge-agent/target)'`: Exit code 1 (Empty output).
   - `find frameworks/base -type f | wc -l`: `20`.

---

## 2. Logic Chain
1. **Static JSON Reports & Binary Purge Logic**:
   - `ORIGINAL_REQUEST.md` non-negotiable rules forbid static JSON test reports and committed prebuilt binaries.
   - Executing `git rm` on all static reports (`e2e_report.json`), object/archive files (`hmac_auth.o`, `*.tar.gz`), build target directories (`guest/bridge-agent/target/`, `guest/portal-agent/target/`), binary executables (`*_bin`), and scratch images guarantees clean repository provenance.
2. **Miniature Stand-in Stub Classes Purge Logic**:
   - `ORIGINAL_REQUEST.md` Rule 3 mandates: "Do not replace canonical AOSP Context.java, SystemServer.java, or SystemServiceRegistry.java with miniature stand-ins."
   - Replacing 77 fake stub classes with canonical AOSP patch documentation (`patches/aosp_frameworks_base.patch`) ensures that when merged into a real AOSP source tree, original core classes (`Context.java`, `SystemServer.java`, etc.) are modified via git patch rather than overwritten with 60-line fake stubs.
3. **Genuine Dual-OS Retained Files Logic**:
   - The remaining 20 files under `frameworks/base/core/java/android/system/linux/` and `frameworks/base/services/core/java/com/android/server/linux/` represent genuine dual-OS framework interfaces, AIDLs, and system services (e.g. `LinuxManager.java`, `LinuxManagerService.java`, `LinuxBridgeService.java`).
4. **Android.bp & .gitignore Logic**:
   - Restricting `srcs` in `Android.bp` strictly to `android.system.linux` prevents unintended wildcards.
   - Updating `.gitignore` prevents future accidental commits of build artifacts and test reports.

---

## 3. Caveats
No caveats. All steps 1 to 6 have been completed and verified.

---

## 4. Conclusion
Phase A Remediation is complete:
- All static JSON reports, prebuilt binaries, Rust target dirs, test binaries, and scratch files are purged from git tracking.
- All 77 miniature stand-in stub classes under `frameworks/base/` are purged; exactly 20 genuine dual-OS framework files remain.
- `.gitignore` and `Android.bp` are properly updated and verified.
- `patches/aosp_frameworks_base.patch` documents standard AOSP core class modifications cleanly.

---

## 5. Verification Method
To independently verify the remediation:

1. **Verify Binary & Static Report Purge**:
   ```bash
   git ls-files | grep -E '(e2e_report\.json|hmac_auth\.o|\.tar\.gz|_bin$|guest/bridge-agent/target)'
   ```
   *Expected Output*: Empty (0 lines, exit code 1 from grep).

2. **Verify frameworks/base File Count**:
   ```bash
   find frameworks/base -type f | wc -l
   ```
   *Expected Output*: Exactly `20`.

3. **Verify Absence of Fake Stub Classes**:
   ```bash
   find frameworks/base -name "Context.java" -o -name "SystemServer.java" -o -name "SystemServiceRegistry.java" -o -name "ActivityManager.java"
   ```
   *Expected Output*: Empty.

4. **Verify Android.bp Sources Scope**:
   ```bash
   grep "core/java/\*\*/\*\.java" Android.bp
   ```
   *Expected Output*: Empty.
