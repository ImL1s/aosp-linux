# Investigation Report & Handoff — Explorer 2 (Milestone M5)

## 1. Observation

### 1.1 In-Memory Manual Boolean Setters in `LinuxStorageProvider.java`
- **File Location**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`
- **Verbatim Code (Lines 61–85)**:
  ```java
  private boolean mVmRunning = true;
  private boolean mCeKeyAvailable = true;
  private boolean mIsReadOnlyMount = false;

  public void setVmRunning(boolean running) {
      mVmRunning = running;
  }

  public void setCeKeyAvailable(boolean available) {
      mCeKeyAvailable = available;
  }

  public void setReadOnlyMount(boolean readOnly) {
      mIsReadOnlyMount = readOnly;
  }
  ```
- **Observation Details**:
  `LinuxStorageProvider` (which extends `DocumentsProvider`) relies entirely on external callers manually calling `setVmRunning()`, `setCeKeyAvailable()`, and `setReadOnlyMount()`. When system events occur (VM state changes, user lock/unlock, LUKS2 container mount/unmount via `LinuxCeKeyManager`/`vold`), `LinuxStorageProvider`'s state is NOT automatically updated.

### 1.2 Access Checking in `checkVmStateAndLock()`
- **Verbatim Code (Lines 87–98)**:
  ```java
  private void checkVmStateAndLock() {
      if (!mVmRunning) {
          Slog.e(TAG, "VM is offline when SAF accessed");
          throw new ConnectionError("VMOfflineException: Cannot browse SAF documents while Linux VM is powered off");
      }
      if (!mCeKeyAvailable) {
          Slog.e(TAG, "CE Key unavailable (locked) when SAF accessed");
          throw new PermissionError("EncryptedStorageException: CE storage volume is locked");
      }
  }
  ```
- **Observation Details**:
  Because `checkVmStateAndLock()` checks in-memory `mVmRunning` and `mCeKeyAvailable` fields, if the VM stops or crashes, or if the user locks their profile, `LinuxStorageProvider` continues to allow SAF access if `setVmRunning(false)` was never explicitly called.

### 1.3 `LinuxManagerInternal.java` Interface Gaps
- **File Location**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerInternal.java`
- **Verbatim Code (Lines 22–33)**:
  ```java
  public abstract class LinuxManagerInternal {
      public abstract boolean isVmRunning();
      public abstract int getVmState();
      public abstract void onUserUnlocked(int userId);
  }
  ```
- **Observation Details**:
  `LinuxManagerInternal` currently lacks methods to query CE storage status (`isCeKeyAvailable()`), read-only mount status (`isReadOnlyMount()`), or register storage state change listeners (`registerStorageStateListener()`).

### 1.4 Hardcoded Unit Test Assertions in `LinuxStorageProviderTest.java`
- **File Location**: `/Users/iml1s/Documents/mine/aosp-linux/tests/unit/LinuxStorageProviderTest.java`
- **Verbatim Code (Lines 16–33)**:
  ```java
  provider.setVmRunning(false);
  // ...
  provider.setVmRunning(true);
  provider.setCeKeyAvailable(false);
  // ...
  provider.setCeKeyAvailable(true);
  ```
- **Observation Details**:
  The unit test explicitly relies on the manual boolean setters `setVmRunning()` and `setCeKeyAvailable()`, confirming that testing previously bypassed system service linkage.

---

## 2. Logic Chain

1. **Root Cause of Split-Brain State**:
   - *Observation 1.1 & 1.2*: `LinuxStorageProvider` reads local boolean fields (`mVmRunning`, `mCeKeyAvailable`, `mIsReadOnlyMount`) during SAF queries (`queryRoots`, `queryDocument`, `queryChildDocuments`, `openDocument`).
   - *Reasoning*: Because `DocumentsProvider` instances are managed by Android ContentProvider lifecycle, system storage events (VM stop, LUKS2 unmount, CE lock) do not automatically trigger method calls on `LinuxStorageProvider`. This causes a split-brain state where SAF queries succeed on offline or locked storage.

2. **Need for Dynamic System Lookup**:
   - *Observation 1.3*: `LinuxManagerService` publishes `LinuxManagerInternal` to `LocalServices` (`publishLocalService(LinuxManagerInternal.class, mLocalService)`).
   - *Reasoning*: `LinuxStorageProvider` runs inside `system_server`. By replacing local boolean fields with dynamic calls to `LocalServices.getService(LinuxManagerInternal.class)`, `LinuxStorageProvider` will evaluate real-time VM state (`STATE_RUNNING`) and LUKS2 CE unlock state on every query, eliminating manual setters.

3. **Need for Root Refresh Notifications**:
   - *Observation 1.1*: When VM state transitions (`STATE_STOPPED` <-> `STATE_RUNNING`) or CE storage locks/unlocks, SAF UI components (e.g. Files app / DocumentsUI) must refresh available roots.
   - *Reasoning*: Registering a `StorageStateListener` on `LinuxManagerInternal` during `LinuxStorageProvider.onCreate()` allows the provider to invoke `getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)`. This immediately updates Android Storage Access Framework UI whenever the Linux VM boots or shuts down.

---

## 3. Caveats

- **SystemServer Runtime Dependency**: In unit test environments where `LocalServices` is not initialized, `LocalServices.getService(LinuxManagerInternal.class)` will return `null`. `LinuxStorageProvider` must handle `null` gracefully (or allow a test injection fallback in `LinuxStorageProviderTest`).
- **Permissions Context**: `LinuxStorageProvider` MUST ensure system path traversal checks (`SYSTEM_ROOTS` rejection and `canonicalTarget.startsWith(canonicalBase)`) remain strictly enforced regardless of VM state.

---

## 4. Conclusion

### Summary of Required Changes:
1. **Remove Manual Setters in `LinuxStorageProvider.java`**:
   - Delete `setVmRunning()`, `setCeKeyAvailable()`, and `setReadOnlyMount()`.
   - Delete private fields `mVmRunning`, `mCeKeyAvailable`, and `mIsReadOnlyMount`.
2. **Extend `LinuxManagerInternal.java`**:
   - Add `isCeKeyAvailable()`, `isReadOnlyMount()`, `registerStorageStateListener()`, and `unregisterStorageStateListener()`.
   - Define `StorageStateListener` interface:
     ```java
     public interface StorageStateListener {
         void onVmStateChanged(int newState, int oldState);
         void onCeKeyStatusChanged(boolean available);
         void onStorageMountChanged(boolean isReadOnly);
     }
     ```
3. **Implement Dynamic Queries in `LinuxStorageProvider.java`**:
   - Query `LinuxManagerInternal` dynamically inside `checkVmStateAndLock()`:
     - `lmi.isVmRunning()` (or `lmi.getVmState() == LinuxManager.STATE_RUNNING`)
     - `lmi.isCeKeyAvailable()`
   - Query `lmi.isReadOnlyMount()` inside `openDocument()` and `includeFile()`.
4. **SAF Notification Linking**:
   - In `onCreate()`, register `StorageStateListener` with `LinuxManagerInternal`.
   - Trigger `notifyRootsChanged()` (`ContentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)`).
5. **Update Unit Tests (`LinuxStorageProviderTest.java`)**:
   - Refactor unit test to publish a mock/fake `LinuxManagerInternal` to `LocalServices` (or test harness) instead of calling deleted setters.

---

## 5. Verification Method

1. **Compilation Verification**:
   ```bash
   javac -d build_out/classes $(find frameworks/base/services/core/java/com/android/server/linux -name "*.java")
   ```
2. **Unit Test Verification**:
   ```bash
   javac -cp build_out/classes -d build_out/classes tests/unit/LinuxStorageProviderTest.java
   java -cp build_out/classes tests.unit.LinuxStorageProviderTest
   ```
3. **E2E Test Verification**:
   ```bash
   python3 tests/e2e/runner.py --tier 1
   python3 tests/e2e/runner.py --tier 2
   ```
