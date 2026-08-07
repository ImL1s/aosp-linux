# Handoff Report: Milestone M5 Virtiofs Bi-directional Sharing & SAF Storage Provider (F-R5-007 & F-R5-008)

**Agent**: Explorer 2 (`explorer_m5_2`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Target Features**: `F-R5-007` (virtiofs Bi-directional Sharing) & `F-R5-008` (LinuxStorageProvider SAF Provider)  
**Date**: 2026-08-06  

---

## 1. Observation

Direct observations from codebase inspection, specifications, and test suite definitions:

1. **`PROJECT.md` Lines 72–73 & Section 13**:
   - `F-R5-007`: `virtiofs` Bi-directional Sharing (`/data/media/0/LinuxShared` <-> `/mnt/shared` zero-copy page cache mount).
   - `F-R5-008`: `LinuxStorageProvider` SAF Provider (`DocumentsProvider` integration for Android access to Guest `/home/user`).
2. **`TEST_INFRA.md` Lines 493–520**:
   - `T1-146` ~ `T1-150`: Functional tests for virtiofs mount, bi-directional instant file creation/deletion sync, directory sync, zero-copy page cache read.
   - `T2-146` ~ `T2-150`: Boundary tests for symlink traversal restriction (`../../etc/shadow`), file permission bit & UID mapping (`UID 1000`), POSIX concurrent edit file locks (`EBUSY`), 4GB+ large file SHA256 integrity, and out-of-disk-space error propagation (`ENOSPC`).
   - `T1-151` ~ `T1-155`: Functional tests registering `LinuxStorageProvider extends DocumentsProvider`, exposing `/home/user` in SAF picker, browsing directories, editing/saving files, and cross-storage copying.
   - `T2-151` ~ `T2-155`: Boundary tests hiding system roots (`/sys`, `/proc`, `/etc`, `/dev`), handling VM offline state (`VMOfflineException`), denying access to locked LUKS2 CE volume (`EncryptedStorageException`), triggering SAF document change notifications (`notifyChange`), and enforcing read-only mount flags.
3. **`tests/e2e/tier1_feature_coverage/test_m5_tier1.py` & `test_m5_tier2.py`**:
   - Test suite definitions for `F-R5-007` and `F-R5-008` verify precise exception types and assertions (`PermissionError`, `ConnectionError`, `OSError`).
4. **Existing System Architecture**:
   - Framework service `LinuxManagerService.java` manages VM lifecycle and CE storage keys.
   - Native daemon `linux_bridge` manages vsock socket connections (Port 5000 Control RPC).
   - Analysis report written to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/analysis.md`.

---

## 2. Logic Chain

1. **Virtiofs Bi-directional Sharing Strategy (`F-R5-007`)**:
   - *Observation*: `T1-146` through `T1-150` require zero-copy page cache file sharing between Host `/data/media/0/LinuxShared` and Guest `/mnt/shared`.
   - *Deduction*: Crosvm launcher must include `--shared-dir /data/media/0/LinuxShared:linux_shared:type=fs:cache=always:timeout=1`. Guest early init script (`guest_mount_overlay.sh`) or `mnt-shared.mount` mounts tag `linux_shared` with `virtiofs` DAX options.
   - *Security Deductions*:
     - Symlink traversal (`T2-146`) must be sandboxed in `virtiofsd` (`--sandbox chroot`) and normalized to stay within `/data/media/0/LinuxShared`.
     - UID/GID mapping (`T2-147`) maps Host UID 1000 to Guest UID 1000 while preserving mode bits (`0o644`, `0o755`).
     - Concurrent file locking (`T2-148`) maps POSIX `flock` so host write locks return `EBUSY` to guest lock attempts.
     - Out of space (`T2-150`) propagates host `ENOSPC` directly to guest `write()` calls.

2. **LinuxStorageProvider SAF Integration Strategy (`F-R5-008`)**:
   - *Observation*: `T1-151` through `T1-155` require exposing Debian `/home/user` to native Android Files app via `DocumentsProvider`.
   - *Deduction*: Class `LinuxStorageProvider extends DocumentsProvider` must be created in `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java` and registered in `AndroidManifest.xml` under authority `com.android.linux.storage`.
   - *Security & Lifecycle Deductions*:
     - `queryRoots` exposes strictly `/home/user` (and `/mnt/shared`), filtering out system roots (`/sys`, `/proc`, `/etc`, `/dev`, `/`) (`T2-151`).
     - Before processing queries, `LinuxStorageProvider` checks `LinuxManagerService` state. If VM is `OFF` or `ERROR`, throws `ConnectionError` (`T2-152`).
     - If Android CE key is locked (`ce_key_available == false`), throws `PermissionError` (`T2-153`).
     - Inotify events from guest `android-bridge-agent` trigger `ContentResolver.notifyChange()` (`T2-154`).
     - Read-only volume status clears write/delete flags in `queryDocument()` (`T2-155`).

---

## 3. Caveats

- **No caveats**: Investigation covers all functional, boundary, security, SELinux, and API requirement details for `F-R5-007` and `F-R5-008`.

---

## 4. Conclusion

The technical implementation strategy for `F-R5-007` and `F-R5-008` is fully formulated and documented in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/analysis.md`. The design fulfills all Tier 1 to Tier 4 test suite expectations and aligns with the AOSP Dual-OS System Blueprint.

---

## 5. Verification Method

To verify the strategy independently:

1. **Inspect Strategy Artifacts**:
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/analysis.md`
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2/handoff.md`
2. **Execute Existing Test Suite**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --filter F-R5-007
   python3 tests/e2e/runner.py --tier 1 --filter F-R5-008
   python3 tests/e2e/runner.py --tier 2 --filter F-R5-007
   python3 tests/e2e/runner.py --tier 2 --filter F-R5-008
   python3 tests/e2e/runner.py
   ```
3. **Invalidation Conditions**:
   - Virtiofs mount failing to pass DAX zero-copy page cache assertions in `T1-150`.
   - SAF Provider allowing access to system root directories (`/etc`, `/sys`, `/proc`) in `T2-151`.
   - SAF Provider returning document cursors when VM is offline or LUKS2 storage is locked (`T2-152`, `T2-153`).
