# Handoff Report: Challenger 2 — Milestone M5 (LinuxStorageProvider SAF Provider & Lifecycle Verification)

## VERDICT: APPROVE

---

## 1. Observation

### 1.1 SAF Rejection when VM is Stopped or CE Key Unavailable
- **File**: `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
- **Observed Lines 108–121**:
```java
    private void checkVmStateAndLock() {
        LinuxManagerInternal lmi = getLinuxManagerInternal();
        boolean isVmRunning = (lmi != null) && lmi.isVmRunning();
        if (!isVmRunning) {
            Slog.e(TAG, "VM is offline when SAF accessed");
            throw new ConnectionError("VMOfflineException: Cannot browse SAF documents while Linux VM is powered off");
        }

        boolean isCeKeyAvailable = (lmi != null) && lmi.isCeKeyAvailable();
        if (!isCeKeyAvailable) {
            Slog.e(TAG, "CE Key unavailable (locked) when SAF accessed");
            throw new PermissionError("EncryptedStorageException: CE storage volume is locked");
        }
    }
```
- **Entrypoints**:
  - `queryRoots(String[] projection)` — Line 191: calls `checkVmStateAndLock()`.
  - `queryDocument(String documentId, String[] projection)` — Line 218: calls `checkVmStateAndLock()`.
  - `queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)` — Line 228: calls `checkVmStateAndLock()`.
  - `openDocument(String documentId, String mode, CancellationSignal signal)` — Line 244: calls `checkVmStateAndLock()`.
- **Empirical Execution Output**:
  ```
  E/LinuxStorageProvider: VM is offline when SAF accessed
    PASS: queryRoots rejected as expected when VM stopped -> VMOfflineException: Cannot browse SAF documents while Linux VM is powered off
    PASS: queryChildDocuments rejected as expected when VM stopped -> VMOfflineException: Cannot browse SAF documents while Linux VM is powered off
  E/LinuxStorageProvider: CE Key unavailable (locked) when SAF accessed
    PASS: queryRoots rejected as expected when CE key locked -> EncryptedStorageException: CE storage volume is locked
    PASS: queryChildDocuments rejected as expected when CE key locked -> EncryptedStorageException: CE storage volume is locked
  ```

### 1.2 Read-Only vs Read-Write Mount Exposure under LUKS2 Mount States
- **File**: `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
- **Observed Lines 123–126 & 267–273 & 251–253**:
```java
    private boolean isReadOnlyMount() {
        LinuxManagerInternal lmi = getLinuxManagerInternal();
        return lmi != null && lmi.isReadOnlyMount();
    }
```
```java
        int flags = 0;
        if (!isReadOnlyMount()) {
            flags |= (Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME);
            if (file.isDirectory()) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            }
        }
```
```java
        if (isReadOnlyMount() && isWriteRequested) {
            throw new SecurityException("Cannot open document for writing: Storage is mounted read-only");
        }
```
- **Empirical Execution Output**:
  ```
  I/LinuxStorageProvider: openDocument: home/user/test_write.txt mode: w
    PASS: openDocument blocked write mode on read-only mount -> Cannot open document for writing: Storage is mounted read-only
    PASS: Read-only check in openDocument functions correctly.
  ```

### 1.3 ContentResolver Notification on State Change Listeners
- **File**: `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
- **Observed Lines 72–88 & 128–133**:
```java
    private final LinuxManagerInternal.StorageStateListener mStorageStateListener =
            new LinuxManagerInternal.StorageStateListener() {
                @Override
                public void onVmStateChanged(int newState, int oldState) {
                    notifyRootsChanged();
                }

                @Override
                public void onCeKeyStatusChanged(boolean available) {
                    notifyRootsChanged();
                }

                @Override
                public void onStorageMountChanged(boolean isReadOnly) {
                    notifyRootsChanged();
                }
            };
```
```java
    private void notifyRootsChanged() {
        if (getContext() != null) {
            getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null);
        }
        Slog.i(TAG, "Dispatched notifyChange for roots URI: content://" + AUTHORITY + "/root");
    }
```
- **Empirical Execution Output**:
  ```
    Total ContentResolver.notifyChange calls received: 7
    PASS: All 7 state transition notifications (VM state x2, CE key x2, Mount state x2, Doc change x1) were received by ContentResolver!
  ```

### 1.4 Full Verification Suite and Java Unit Tests
- **Command**: `./scripts/run_m5_verification.sh`
- **Output**:
```
=== M5 Hardware Portals, Virtiofs, SELinux & OTA Verification Suite ===
[1/6] Checking Structural & File Compliance... PASS
[2/6] Compiling Java Framework & Service Modules... PASS
[3/6] Running Java Unit Test Suite... PASS (LinuxPortalServiceTest, LinuxAudioPolicyTest, LinuxStorageProviderTest)
[4/6] Compiling and Running C++ Watchdog & AVB Tests... PASS
[5/6] Compiling Rust Guest Agent (android-bridge-agent)... PASS
[6/6] Running Python E2E Test Suite for Milestone M5 Features F-R5-001..014... PASS
==================================================
M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY
```

---

## 2. Logic Chain

1. **SAF Call Rejection Logic**:
   - *Observation*: `checkVmStateAndLock()` is executed at the start of `queryRoots`, `queryChildDocuments`, `queryDocument`, and `openDocument`.
   - *Reasoning*: Querying `LinuxManagerInternal.isVmRunning()` and `isCeKeyAvailable()` dynamically prevents stale or unencrypted access attempts. When the VM is powered off, `ConnectionError` (`VMOfflineException`) is thrown. When CE key is locked, `PermissionError` (`EncryptedStorageException`) is thrown.
   - *Result*: Zero access is granted to unencrypted user storage or offline VM filesystems through SAF.

2. **LUKS2 Mount Exposure Logic**:
   - *Observation*: `isReadOnlyMount()` delegates to `LinuxManagerInternal.isReadOnlyMount()`.
   - *Reasoning*: When LUKS2 volume is mounted read-only, `includeFile` strips `FLAG_SUPPORTS_WRITE`, `FLAG_SUPPORTS_DELETE`, `FLAG_SUPPORTS_RENAME`, and `FLAG_DIR_SUPPORTS_CREATE`. Any `openDocument` request with write intent (`w`, `wt`, `wa`, `rw`, `rwt`) immediately fails with `SecurityException`.
   - *Result*: Storage permissions dynamically match the underlying LUKS2 mount mode.

3. **ContentResolver Dispatch Logic**:
   - *Observation*: `mStorageStateListener` hooks into `onVmStateChanged`, `onCeKeyStatusChanged`, and `onStorageMountChanged`.
   - *Reasoning*: Each of these callbacks invokes `notifyRootsChanged()`, which issues `ContentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)`.
   - *Result*: Android Files UI and SAF document pickers automatically refresh root views when VM state or storage key availability changes.

---

## 3. Caveats

- **Mock Framework Environment**: Unit tests operate against stubbed `ContentResolver` and `LocalServices` context in headless Java test runners. Live device execution uses full Android `SystemServer` runtime.

---

## 4. Conclusion

Empirical verification of `LinuxStorageProvider` SAF storage provider lifecycle confirms:
1. `queryRoots` and `queryChildDocuments` reject all calls when VM is stopped (`ConnectionError`) or CE key is locked (`PermissionError`).
2. LUKS2 read-only vs read-write mount modes are accurately exposed in document flags and write operations.
3. `ContentResolver.notifyChange` notifications fire correctly on all storage state listener events.
4. `./scripts/run_m5_verification.sh` passes 100% across all 14 features and all unit test suites.

Verdict: **APPROVE**.

---

## 5. Verification Method

### 1. Run M5 Verification Suite
```bash
./scripts/run_m5_verification.sh
```

### 2. Run Java Unit Tests
```bash
java -cp build_out/classes tests.unit.LinuxStorageProviderTest
```

### 3. Invalidation Conditions
- `queryRoots` or `queryChildDocuments` returning cursors when `isVmRunning()` or `isCeKeyAvailable()` returns `false`.
- Write requests allowed through `openDocument` when `isReadOnlyMount()` returns `true`.
- State transitions in `LinuxManagerService` failing to trigger `ContentResolver.notifyChange` on `buildRootsUri(AUTHORITY)`.
