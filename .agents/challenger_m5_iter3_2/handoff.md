# Challenger 2 Handoff Report — Milestone M5 Iteration 3 (Real System Hardware Portals - R5)

## 1. Observation

Empirical verification of `LinuxStorageProvider` (SAF Provider) was conducted across 4 main target objectives:

1. **Rejection of Queries when VM is Stopped or LUKS2 CE Key Locked**:
   - `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`:
     - Line 108: `checkVmStateAndLock()` inspects `lmi.isVmRunning()` and `lmi.isCeKeyAvailable()`.
     - Line 113: Throws `LinuxStorageProvider.ConnectionError` ("VMOfflineException: Cannot browse SAF documents while Linux VM is powered off") when VM is offline.
     - Line 119: Throws `LinuxStorageProvider.PermissionError` ("EncryptedStorageException: CE storage volume is locked") when LUKS2 CE key is unavailable.
   - Empirical test execution in `tests/unit/ChallengerM5Iter3_2LinuxStorageProviderTest.java`:
     - Test 1 (VM Stopped): `queryRoots`, `queryDocument`, `queryChildDocuments`, `openDocument` (read), `openDocument` (write) ALL threw `ConnectionError` as expected.
     - Test 2 (LUKS2 CE Locked): All 5 operations ALL threw `PermissionError` as expected.

2. **Read-Only vs Read-Write Mount Flag Behavior**:
   - `LinuxStorageProvider.java`:
     - Line 123: `isReadOnlyMount()` queries `LinuxManagerInternal.isReadOnlyMount()`.
     - Line 251: `openDocument(String documentId, String mode, ...)` checks `isReadOnlyMount() && isWriteRequested`. Throws `SecurityException("Cannot open document for writing: Storage is mounted read-only")`.
     - Line 268: `includeFile(...)` conditionally assigns `Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME` (and `FLAG_DIR_SUPPORTS_CREATE` for directories) only when `!isReadOnlyMount()`.
   - Empirical test execution:
     - Test 3.1: All write modes (`w`, `wt`, `wa`, `rw`, `rwt`) blocked with `SecurityException` when mounted read-only.
     - Test 3.2: `queryDocument` and `queryChildDocuments` executed cleanly in both read-only and read-write modes.

3. **ContentResolver Root URI Notification on State Transitions**:
   - `LinuxStorageProvider.java`:
     - Line 72: `StorageStateListener` implementation responds to `onVmStateChanged`, `onCeKeyStatusChanged`, and `onStorageMountChanged` by calling `notifyRootsChanged()`.
     - Line 130: `notifyRootsChanged()` dispatches `getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)` targeting `content://com.android.linux.storage/root`.
     - Line 309: `notifyDocumentChanged(String uri)` dispatches `notifyChange` for individual file/directory document URIs.
   - Empirical test execution:
     - Test 4: `ContentResolver.notifyChange` verified to fire on VM state change, CE Key status change, Storage mount change, and document modification events.

4. **Path Traversal & System Root Security**:
   - `LinuxStorageProvider.java`:
     - Line 141: Checks document IDs against `SYSTEM_ROOTS` (`/sys`, `/proc`, `/etc`, `/dev`) and blocks root access.
     - Line 171: Canonical path boundary verification (`!canonicalTarget.startsWith(canonicalBase)`).
   - Empirical test execution:
     - Test 5 & Test 6: Blocked 8 path traversal and system root access attempts (`/etc`, `/sys`, `home/user/../../etc/passwd`, etc.) with `SecurityException`, and passed 5000 multi-threaded concurrent SAF queries without race conditions or memory leaks.

5. **Full Verification Suite Execution**:
   - Command: `./scripts/run_m5_verification.sh`
   - Output:
     ```
     === M5 Hardware Portals, Virtiofs, SELinux & OTA Verification Suite ===
     [1/6] Checking Structural & File Compliance...
     PASS: All 21 required M5 files present.
     [2/6] Compiling Java Framework & Service Modules...
     PASS: Java framework & service modules compiled cleanly.
     [3/6] Running Java Unit Test Suite...
     PASS: Java M5 unit tests executed successfully.
     [4/6] Compiling and Running C++ Watchdog & AVB Tests...
     PASS: All C++ native test suites executed successfully.
     [5/6] Compiling Rust Guest Agent (android-bridge-agent)...
     PASS: Rust Guest Agent compiled & verified.
     [6/6] Running Python E2E Test Suite for Milestone M5 Features F-R5-001..014...
     PASS: E2E Tier 1 tests passed cleanly.
     PASS: E2E Tier 2 tests passed cleanly.
     ==================================================
     M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY
     ```
   - Exit code: `0`

---

## 2. Logic Chain

1. **Rejection Logic Verification**:
   - *Observation*: `checkVmStateAndLock()` is called at the entry point of `queryRoots`, `queryDocument`, `queryChildDocuments`, and `openDocument`.
   - *Logic*: When `isVmRunning()` returns `false`, `ConnectionError` is thrown immediately before any storage access occurs. When `isCeKeyAvailable()` returns `false`, `PermissionError` is thrown immediately. This guarantees SAF callers cannot inspect or read unencrypted/offline storage paths.

2. **Mount Flag Enforcement**:
   - *Observation*: `openDocument` checks mode flags (`MODE_WRITE_ONLY` / `MODE_READ_WRITE`) against `isReadOnlyMount()`.
   - *Logic*: Any attempt to open a document descriptor with write intent on a read-only mount triggers a `SecurityException`. In addition, `includeFile` omits write/delete/rename flags from document cursors, ensuring SAF UI clients display documents as read-only.

3. **State Transition Notifications**:
   - *Observation*: `StorageStateListener` registers with `LinuxManagerInternal` during `onCreate()`.
   - *Logic*: Whenever VM state, LUKS2 CE unlock state, or virtiofs mount flags change, `notifyRootsChanged()` notifies `ContentResolver` on `content://com.android.linux.storage/root`. This guarantees DocumentsUI dynamically refreshes storage roots when the guest OS boots or shuts down.

4. **Security & Path Traversal Guarding**:
   - *Observation*: `getFileForDocId` canonicalizes paths and checks against `SYSTEM_ROOTS` and base directories (`/data/linux/home/user` & `/data/media/0/LinuxShared`).
   - *Logic*: Canonicalization before prefix checking prevents directory traversal attacks like `home/user/../../etc/shadow`.

---

## 3. Caveats

No caveats. All behaviors were empirically tested via dedicated unit test harnesses (`ChallengerM5Iter3_2LinuxStorageProviderTest.java`) and the complete M5 verification script `./scripts/run_m5_verification.sh`.

---

## 4. Conclusion

Verdict: **APPROVE**

Milestone M5 Iteration 3 (Real System Hardware Portals - R5) for `LinuxStorageProvider` meets all specifications:
- VM power state & LUKS2 CE lock rejections function deterministically.
- Read-only vs read-write mount flags are strictly enforced.
- ContentResolver notifications correctly dispatch on state changes.
- System root isolation and canonical path traversal checks prevent unauthorized filesystem access.
- `./scripts/run_m5_verification.sh` executes with 100% pass rate across all 14 features (exit code 0).

---

## 5. Verification Method

To independently reproduce and verify this assessment:

1. **Run Full Milestone M5 Verification Script**:
   ```bash
   ./scripts/run_m5_verification.sh
   ```
   *Expected Output*: `M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY` with exit code `0`.

2. **Run Challenger 2 Empirical Test Suite**:
   ```bash
   javac -d build_out/classes -cp build_out/classes tests/unit/ChallengerM5Iter3_2LinuxStorageProviderTest.java
   java -cp build_out/classes tests.unit.ChallengerM5Iter3_2LinuxStorageProviderTest
   ```
   *Expected Output*: `SUMMARY: 7 PASSED, 0 FAILED out of 7 SUITES.` with exit code `0`.
