# Explorer 2 Handoff Report: Remediation Strategy for Storage SAF Provider & Tier-1 E2E Tests

**Agent**: Explorer 2 (`explorer_m5_2_r2`)  
**Target Scope**: Milestone M5 Iteration 2 — `LinuxStorageProvider.java` & `test_m5_tier1.py`  
**Date**: 2026-08-06  

---

## 1. Observation

1. **`LinuxStorageProvider.java` Null PFD Return**:
   - Location: `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java:174-178`
   - Exact code observed:
     ```java
     @Override
     public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) {
         checkVmStateAndLock();
         Slog.i(TAG, "openDocument: " + documentId + " mode: " + mode);
         return null;
     }
     ```
   - Impact: Clients invoking SAF file open received `null`, causing `NullPointerException` or file open failure in host Android apps.

2. **`LinuxStorageProvider.java` Path Traversal Vulnerability**:
   - Location: `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java:151-154`
   - Exact code observed:
     ```java
     if (SYSTEM_ROOTS.contains("/" + parentDocumentId) || SYSTEM_ROOTS.contains(parentDocumentId)) {
         Slog.w(TAG, "Access to system root path denied: " + parentDocumentId);
         throw new SecurityException("Access to system root path denied");
     }
     ```
   - Empirical test result from `challenger_m5_1` (ST-04): 6 malicious traversal paths (`/etc/shadow`, `/home/user/../../etc/shadow`, `sys/kernel`, `/dev/mem`, etc.) bypassed the exact string check because `SYSTEM_ROOTS.contains(...)` only performed exact equality against `["/sys", "/proc", "/etc", "/dev"]`.

3. **`LinuxStorageProvider.java` Hardcoded Mock Files**:
   - Location: `LinuxStorageProvider.java:114,122,143,163`
   - Observed values: Fixed disk space (`10GB`/`20GB`), fixed file size (`1024L`/`2048L`), hardcoded file name (`"doc.txt"`).

4. **`test_m5_tier1.py` Fake Assertion Generator**:
   - Location: `tests/e2e/tier1_feature_coverage/test_m5_tier1.py:120-122`
   - Exact code observed:
     ```python
     def _create_t1_m5_class(test_id_str, feat_id, title_str):
         class T1M5Test(BaseTestCase):
             test_id = test_id_str
             feature_id = feat_id
             title = title_str
             tier = 1

             def run_test(self):
                 CustomAssertions.assert_true(True)
     ```
   - Impact: All 70 Tier-1 test cases (T1-116 through T1-185, covering F-R5-001 through F-R5-014) executed `assert_true(True)`, bypassing real test logic and fabricating a 100% pass rate.

---

## 2. Logic Chain

1. **Premise 1 (SAF Provider Functional Requirement)**: Android Storage Access Framework (`DocumentsProvider`) requires `openDocument()` to return a valid `ParcelFileDescriptor` representing the target file handle. Returning `null` (Observation 1) breaks file read/write operations for Android applications accessing guest storage.
2. **Premise 2 (SAF Provider Security Requirement)**: Guest home storage (`/home/user`) and shared storage (`/mnt/shared`) exposed via SAF must restrict client access to those base directories. Because `SYSTEM_ROOTS.contains(...)` only checks exact string matches against `["/sys", "/proc", "/etc", "/dev"]` (Observation 2), relative path traversal (`/home/user/../../etc/shadow`) and subpath queries (`/etc/shadow`) bypass security checks. Using `File.getCanonicalPath()` and checking `canonicalTarget.startsWith(canonicalBase)` prevents path traversal attacks.
3. **Premise 3 (Directory Query Realism)**: Hardcoded metadata (Observation 3) prevents Android file managers from seeing actual guest files. Dynamically traversing `File.listFiles()` and querying real file metadata (`length()`, `lastModified()`) restores full SAF directory browsing capabilities.
4. **Premise 4 (E2E Test Suite Integrity)**: Hardcoded `assert_true(True)` assertions (Observation 4) fabricate test results without verifying IPC, virtiofs, XDG portal D-Bus requests, SELinux policy rules, or OTA watchdog rollback behaviors. Replacing `_create_t1_m5_class` with 70 explicit `BaseTestCase` classes that inspect `self.mock_env` and perform real assertions guarantees genuine test coverage.

**Conclusion**: Refactoring `LinuxStorageProvider.java` to use canonical path validation and real `ParcelFileDescriptor.open()` calls, alongside rewriting `test_m5_tier1.py` into 70 genuine `BaseTestCase` classes, eliminates the security vulnerability, fixes SAF document access, and restores E2E test integrity.

---

## 3. Caveats

- **Host Storage Directories**: This remediation strategy assumes default host base directories for SAF storage are `/data/linux/home/user` (for `/home/user`) and `/data/media/0/LinuxShared` (for `/mnt/shared`). Implementers should ensure these parent directories exist or create them via `mkdirs()` on file write.
- **Environment Isolation**: E2E tests in `test_m5_tier1.py` rely on `MockEnvironment` to isolate tests from real hardware/kernel devices during automated test runs.

---

## 4. Conclusion

The remediation strategy designed by Explorer 2 provides a complete, step-by-step engineering roadmap for resolving issues F-R5-007, F-R5-008, and the fake Tier-1 test suite in Milestone M5 Iteration 2:
1. `LinuxStorageProvider.java` will become a secure, fully functional SAF provider with zero path traversal vulnerabilities and real file handle support via `ParcelFileDescriptor.open()`.
2. `test_m5_tier1.py` will contain 70 authentic, verifiable E2E test cases covering features F-R5-001 through F-R5-014.

---

## 5. Verification Method

To independently verify the proposed remediation plan:

1. **Inspect Analysis Report**:
   - Check `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2_r2/analysis.md` for complete fix specifications.
2. **Compile Java Storage Provider**:
   - Execute:
     ```bash
     javac -d build_out/classes frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java
     ```
3. **Run Stress Test for Path Traversal Fix**:
   - Execute:
     ```bash
     javac -cp build_out/classes -d build_out/classes tests/unit/ChallengerM5EmpiricalStressTest.java
     java -cp build_out/classes tests.unit.ChallengerM5EmpiricalStressTest
     ```
   - Confirm test `ST-04` outputs `[PASSED]`.
4. **Execute Tier-1 E2E Test Suite**:
   - Execute:
     ```bash
     python3 tests/e2e/runner.py --tier 1
     ```
   - Confirm 70 tests pass with real assertions and zero `assert_true(True)` dummy shortcuts.

