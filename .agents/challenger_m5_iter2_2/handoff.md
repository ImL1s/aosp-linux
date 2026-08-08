# Handoff Report: Challenger 2 — Milestone M5 Iteration 2 (LinuxStorageProvider Verification)

## 1. Observation

Empirical verification of `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java` was conducted with the following verbatim command executions and outputs:

1. **Compilation and Execution of Dedicated Empirical Test Suite**:
   ```bash
   javac -d build_out/classes @build_out/m5_sources.txt tests/unit/ChallengerM5Iter2LinuxStorageProviderTest.java
   java -cp build_out/classes tests.unit.ChallengerM5Iter2LinuxStorageProviderTest
   ```
   *Output*:
   ```
   ==========================================================
    CHALLENGER 2 EMPIRICAL TEST: LinuxStorageProvider (R5)   
   ==========================================================
   I/LinuxStorageProvider: LinuxStorageProvider created under authority: com.android.linux.storage

   [TEST 1.1] Verifying SAF query rejection when Linux VM is powered off...
   E/LinuxStorageProvider: VM is offline when SAF accessed
   E/LinuxStorageProvider: VM is offline when SAF accessed
   E/LinuxStorageProvider: VM is offline when SAF accessed
   E/LinuxStorageProvider: VM is offline when SAF accessed
     [PASS] All 4 SAF methods correctly rejected query with ConnectionError when VM is offline.

   [TEST 1.2] Verifying SAF query rejection when LUKS2 CE key is locked...
   E/LinuxStorageProvider: CE Key unavailable (locked) when SAF accessed
   E/LinuxStorageProvider: CE Key unavailable (locked) when SAF accessed
   E/LinuxStorageProvider: CE Key unavailable (locked) when SAF accessed
   E/LinuxStorageProvider: CE Key unavailable (locked) when SAF accessed
     [PASS] All 4 SAF methods correctly rejected query with PermissionError when LUKS2 CE volume is locked.

   [TEST 2.1] Verifying Read-Only mount flag enforcement in SAF openDocument write modes...
   I/LinuxStorageProvider: openDocument: home/user/test.txt mode: w
   I/LinuxStorageProvider: openDocument: home/user/test.txt mode: wt
   I/LinuxStorageProvider: openDocument: home/user/test.txt mode: wa
   I/LinuxStorageProvider: openDocument: home/user/test.txt mode: rw
   I/LinuxStorageProvider: openDocument: home/user/test.txt mode: rwt
     [PASS] All 5 write modes correctly blocked with SecurityException on read-only mount.

   [TEST 2.2] Verifying queryDocument & queryChildDocuments return valid cursors under Read-Only vs Read-Write...
     [PASS] queryDocument and queryChildDocuments executed successfully in read-only and read-write modes.

   [TEST 3.1] Verifying ContentResolver root URI notification on VM/CE/Mount state transitions...
   I/LinuxStorageProvider: Dispatched notifyChange for roots URI: content://com.android.linux.storage/root
   I/LinuxStorageProvider: Dispatched notifyChange for roots URI: content://com.android.linux.storage/root
   I/LinuxStorageProvider: Dispatched notifyChange for roots URI: content://com.android.linux.storage/root
   I/LinuxStorageProvider: Dispatched notifyChange for URI: content://com.android.linux.storage/document/home/user/sample.txt
     [PASS] ContentResolver notifyChange triggered correctly for all 4 state transition & document update events.

   [TEST 4.1] Verifying Path Traversal and System Root Security Exceptions...
     [PASS] All 8 system root & path traversal attempts blocked with SecurityException.

   ==========================================================
      CHALLENGER 2 SUMMARY: 6 PASSED, 0 FAILED out of 6 TESTS.
   ==========================================================
   ```

2. **Execution of Full M5 Verification Script**:
   ```bash
   ./scripts/run_m5_verification.sh
   ```
   *Output*:
   ```
   ==================================================
   M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY
   ```

3. **Execution of Challenger M5 Stress Test Suite**:
   ```bash
   java -cp build_out/classes tests.unit.ChallengerM5EmpiricalStressTest
   ```
   *Output*:
   ```
   ==================================================
      STRESS TEST SUMMARY: 6 PASSED, 0 FAILED out of 6 TESTS.
   ==================================================
   ```

---

## 2. Logic Chain

1. **Rejection of Queries when VM is Stopped or LUKS2 CE Key Locked**:
   - `checkVmStateAndLock()` is invoked at the entry point of all four standard SAF query methods: `queryRoots`, `queryDocument`, `queryChildDocuments`, and `openDocument`.
   - When `lmi.isVmRunning()` returns `false`, `checkVmStateAndLock()` throws `LinuxStorageProvider.ConnectionError` ("VMOfflineException: Cannot browse SAF documents while Linux VM is powered off").
   - When `lmi.isCeKeyAvailable()` returns `false`, `checkVmStateAndLock()` throws `LinuxStorageProvider.PermissionError` ("EncryptedStorageException: CE storage volume is locked").
   - Empirical test `ChallengerM5Iter2LinuxStorageProviderTest` verified that calling any SAF method under stopped or locked state consistently triggers the expected exception.

2. **Read-Only vs Read-Write Mount Flag Behavior**:
   - `isReadOnlyMount()` queries `LinuxManagerInternal.isReadOnlyMount()`.
   - In `openDocument()`, mode flags are parsed into bitmask `pfdMode`. If `isReadOnlyMount()` is `true` and write flags (`MODE_WRITE_ONLY` or `MODE_READ_WRITE`) are requested, a `SecurityException` with message `"Cannot open document for writing: Storage is mounted read-only"` is thrown.
   - In `includeFile()`, document flags (`Document.COLUMN_FLAGS`) are populated only when `!isReadOnlyMount()`. On read-only mounts, flags are set to `0` (denying write, delete, rename, and directory creation in DocumentsUI).
   - Empirical test `ChallengerM5Iter2LinuxStorageProviderTest` verified write modes (`"w"`, `"wt"`, `"wa"`, `"rw"`, `"rwt"`) throw `SecurityException` when read-only, while read mode (`"r"`) is accepted.

3. **ContentResolver Root URI Notification on State Transitions**:
   - `LinuxStorageProvider.onCreate()` registers `mStorageStateListener` with `LinuxManagerInternal`.
   - On `onVmStateChanged`, `onCeKeyStatusChanged`, and `onStorageMountChanged` events, `notifyRootsChanged()` is invoked, which calls `getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)`.
   - On `notifyDocumentChanged(uri)`, `getContext().getContentResolver().notifyChange(Uri.parse(uri), null)` is called and the URI is tracked in `mNotificationUris`.
   - Empirical test `ChallengerM5Iter2LinuxStorageProviderTest` attached a custom `TestContext` and verified `ContentResolver.notifyChange` is dispatched on all state transitions and document updates.

4. **Security & Path Traversal Protections**:
   - `getFileForDocId()` checks requested IDs against `SYSTEM_ROOTS` (`/sys`, `/proc`, `/etc`, `/dev`) both before and after canonical path resolution (`getCanonicalPath()`).
   - Empirical test `ChallengerM5Iter2LinuxStorageProviderTest` confirmed that attempts to query `/etc`, `/sys`, `/proc`, `/dev`, or path traversal strings (`home/user/../../etc/shadow`) are cleanly rejected with `SecurityException`.

---

## 3. Caveats

1. **Framework Stubs in Unit Environment**:
   - `android.net.Uri.parse()` in the build environment stub returns `null`. `ContentResolver.notifyChange` invocation was verified by tracking method call counts on a custom `TestContentResolver` passed via `attachInfo()`.
2. **Mount Operations**:
   - Real ext4/virtiofs mount operations are governed by host system kernel policy and `vold`. `LinuxStorageProvider` correctly reflects the logical state reported by `LinuxManagerInternal`.

---

## 4. Conclusion

**Verdict: APPROVE**

`LinuxStorageProvider` satisfies all R5 SAF provider requirements:
1. Rejects queries with `ConnectionError` when Linux VM is powered off and `PermissionError` when LUKS2 CE volume is locked.
2. Correctly enforces read-only vs read-write mount flags in `openDocument` and document query columns.
3. Notifies `ContentResolver` on state transitions (`onVmStateChanged`, `onCeKeyStatusChanged`, `onStorageMountChanged`).
4. Successfully passes `./scripts/run_m5_verification.sh` (100% of 14 features pass) and all empirical stress tests.

---

## 5. Verification Method

To independently verify the implementation and empirical test results:

1. **Compile & Run Challenger 2 Empirical Test**:
   ```bash
   javac -d build_out/classes @build_out/m5_sources.txt tests/unit/ChallengerM5Iter2LinuxStorageProviderTest.java
   java -cp build_out/classes tests.unit.ChallengerM5Iter2LinuxStorageProviderTest
   ```
   *Expected Output*: `CHALLENGER 2 SUMMARY: 6 PASSED, 0 FAILED out of 6 TESTS.`

2. **Execute Full M5 Verification Suite**:
   ```bash
   ./scripts/run_m5_verification.sh
   ```
   *Expected Output*: `M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY`

3. **Run M5 Stress Test Suite**:
   ```bash
   java -cp build_out/classes tests.unit.ChallengerM5EmpiricalStressTest
   ```
   *Expected Output*: `STRESS TEST SUMMARY: 6 PASSED, 0 FAILED out of 6 TESTS.`
