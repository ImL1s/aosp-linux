# M5 Technical Analysis & Strategy Report: Virtiofs Bi-directional Sharing & SAF Storage Provider

**Author**: Explorer 2 (`explorer_m5_2`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Target Features**: `F-R5-007` (virtiofs Bi-directional Sharing) & `F-R5-008` (LinuxStorageProvider SAF Provider)  
**Date**: 2026-08-06  

---

## 1. Executive Summary

This report provides a comprehensive technical investigation and implementation strategy for features **F-R5-007** (`virtiofs` Bi-directional Sharing) and **F-R5-008** (`LinuxStorageProvider` SAF Provider) within Milestone M5 of the AOSP Dual-OS System Blueprint.

- **F-R5-007 (virtiofs Bi-directional Sharing)**: Establishes a zero-copy page cache file mount bridging Host Android storage at `/data/media/0/LinuxShared` and Guest Linux Debian storage at `/mnt/shared`. Utilizing `virtiofs` with DAX / `cache=always`, file modifications synchronize instantly across Host and Guest with low-latency page cache sharing, while enforcing strict symlink traversal sandboxing, UID/GID mapping, POSIX file locking, and host storage space error propagation.
- **F-R5-008 (LinuxStorageProvider SAF Provider)**: Implements `LinuxStorageProvider extends DocumentsProvider` under `com.android.server.linux.storage`, enabling native Android Files app and system document pickers to browse, read, edit, copy, and delete files inside Debian Guest `/home/user`. This provider integrates with `LinuxManagerService` and vsock RPC channels to handle VM lifecycle states, LUKS2 CE encryption lock boundaries, automatic document change notifications via `inotify`, and read-only mount flag enforcement.

---

## 2. Feature F-R5-007: virtiofs Bi-directional Sharing

### 2.1 Architecture & Daemon Configuration

```
+-----------------------------------------------------------------------------------+
| Host: Android 15/16 System                                                        |
| Path: /data/media/0/LinuxShared (media_rw_data_file)                               |
|                                                                                   |
|  [crosvm runner / virtiofsd]                                                      |
|        | DAX Shared Memory Region / Virtqueue                                      |
+--------|--------------------------------------------------------------------------+
         | virtio-fs (Tag: linux_shared)
         v
+-----------------------------------------------------------------------------------+
| Guest: Debian 12 ARM64 VM                                                         |
| Mount Point: /mnt/shared (virtiofs rw,noatime,cache=always,dax)                   |
| UID/GID: 1000:1000 (debian/user)                                                  |
+-----------------------------------------------------------------------------------+
```

#### 2.1.1 Crosvm Launcher & Daemon Flags
- **crosvm Launch Parameter**:
  ```bash
  crosvm run \
    --shared-dir /data/media/0/LinuxShared:linux_shared:type=fs:cache=always:timeout=1 \
    ...
  ```
- **Directory Initialization**:
  Host `LinuxManagerService` verifies and creates `/data/media/0/LinuxShared` during initialization:
  ```java
  File sharedDir = new File("/data/media/0/LinuxShared");
  if (!sharedDir.exists()) {
      sharedDir.mkdirs();
      FileUtils.setPermissions(sharedDir, 0775, 1000, 1023); // UID 1000, GID media_rw
  }
  ```

#### 2.1.2 Guest Mount Setup
- **Guest `/etc/fstab` Entry**:
  ```fstab
  linux_shared /mnt/shared virtiofs rw,noatime,cache=always,dax,g_uid=1000,g_gid=1000 0 0
  ```
- **Automated systemd Mount Unit (`mnt-shared.mount`)**:
  ```ini
  [Unit]
  Description=Virtiofs LinuxShared Mount
  Before=default.target

  [Mount]
  What=linux_shared
  Where=/mnt/shared
  Type=virtiofs
  Options=rw,noatime,cache=always,dax

  [Install]
  WantedBy=multi-user.target
  ```

### 2.2 Functional Requirements & Data Sync Mechanics (`T1-146` ~ `T1-150`)

1. **Mount Verification (`T1-146`)**: Host `/data/media/0/LinuxShared` mounts to Guest `/mnt/shared` via virtiofs DAX protocol.
2. **Host-to-Guest Instant Sync (`T1-147`)**: File created in Host `/data/media/0/LinuxShared/doc.txt` is visible immediately in Guest `/mnt/shared/doc.txt`.
3. **Guest-to-Host Instant Sync (`T1-148`)**: File created in Guest `/mnt/shared/output.log` is visible immediately in Host `/data/media/0/LinuxShared/output.log`.
4. **Directory & Deletion Sync (`T1-149`)**: Subdirectory creation (`mkdir`) and file deletion (`unlink`) propagate bi-directionally without caching delay.
5. **Zero-Copy Page Cache Read (`T1-150`)**: Virtiofs Direct Access (DAX) maps host file system page cache directly into guest memory space, avoiding VM memory copy overhead.

### 2.3 Boundary & Security Edge Cases (`T2-146` ~ `T2-150`)

1. **Symlink Traversal Restriction (`T2-146`)**:
   - **Requirement**: Prevent symlinks created inside `/mnt/shared` from resolving to host sensitive files (e.g., `../../etc/shadow` or `/data/system`).
   - **Enforcement**: `virtiofsd` running on host enforces `--sandbox chroot` and `safe_traversal` resolution logic. Path normalization checks ensure target stays within `/data/media/0/LinuxShared`:
     ```python
     def resolve_shared_symlink(shared_root: str, target_path: str):
         normalized = os.path.normpath(os.path.join(shared_root, target_path))
         if not normalized.startswith(shared_root):
             raise PermissionError("SecurityException: Symlink traversal escapes shared folder boundary")
         return normalized
     ```
2. **File Permission Bit & UID/GID Mapping (`T2-147`)**:
   - **Mapping**: Host Android primary user UID `1000` maps to Guest Linux user `debian` UID `1000`.
   - **Mode Bit Preservation**: File mode bits (e.g., `0o644`, `0o755`) are preserved across the virtiofs boundary.
3. **Concurrent File Lock Conflict Resolution (`T2-148`)**:
   - **Mechanism**: POSIX file locks (`flock` / `fcntl`) are forwarded by virtiofs daemon.
   - **Conflict Handling**: When a Host process holds an exclusive write lock on `document.txt`, guest attempts to acquire write lock raise `OSError("EBUSY: File is locked by host process")`.
4. **Large File Transfer (>4GB) Integrity (`T2-149`)**:
   - **LFS Support**: Full 64-bit Large File Support (LFS) over virtiofs queue.
   - **Integrity**: Verified via SHA-256 checksum matching across 4GB+ payloads without chunk truncation.
5. **Out of Disk Space Error Propagation (`T2-150`)**:
   - **Error Handling**: When Host volume `/data/media/0` is out of disk space (`free_bytes == 0`), guest `write()` operations immediately fail with `OSError("ENOSPC: Virtiofs host storage volume is full")`.

---

## 3. Feature F-R5-008: LinuxStorageProvider SAF Provider

### 3.1 SAF Provider Architecture

`LinuxStorageProvider` extends Android's native `DocumentsProvider` to expose the Guest Linux filesystem (specifically `/home/user`) to the Android Storage Access Framework (SAF).

```
+-----------------------------------------------------------------------------------+
| Android Storage Access Framework (SAF) / Files App                                |
|                                                                                   |
|  [LinuxStorageProvider (com.android.server.linux.storage.LinuxStorageProvider)]   |
|        |                                                                          |
|  [LinuxBridgeService (Unix Domain Socket)]                                        |
+--------|--------------------------------------------------------------------------+
         | AF_VSOCK Port 5000 (File RPC Channel)
         v
+-----------------------------------------------------------------------------------+
| Guest: Debian 12 ARM64 VM                                                         |
|  [android-bridge-agent (Storage Handler)]                                         |
|  Path: /home/user (LUKS2 Decrypted CE Storage)                                    |
+-----------------------------------------------------------------------------------+
```

### 3.2 Java Implementation & Manifest Registration

#### 3.2.1 File Location
`frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java`

#### 3.2.2 AndroidManifest.xml Entry
```xml
<provider
    android:name="com.android.server.linux.storage.LinuxStorageProvider"
    android:authorities="com.android.linux.storage"
    android:exported="true"
    android:grantUriPermissions="true"
    android:permission="android.permission.MANAGE_DOCUMENTS">
    <intent-filter>
        <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
    </intent-filter>
</provider>
```

#### 3.2.3 Class Interface Pseudocode
```java
package com.android.server.linux.storage;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import com.android.server.linux.LinuxManagerService;

public class LinuxStorageProvider extends DocumentsProvider {
    private static final String AUTHORITY = "com.android.linux.storage";
    private static final String ROOT_ID_HOME = "home_user";
    
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        checkVmStateAndLock();
        MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID_HOME);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_RECENTS | Root.FLAG_SUPPORTS_SEARCH);
        row.add(Root.COLUMN_TITLE, "Linux Home Directory");
        row.add(Root.COLUMN_DOCUMENT_ID, "home/user");
        row.add(Root.COLUMN_INTENT_FOR_NAME, "android.intent.action.VIEW");
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        checkVmStateAndLock();
        // Disallow system roots access
        if (isSystemRoot(parentDocumentId)) {
            throw new SecurityException("Access to system root path denied");
        }
        return fetchChildDocumentsFromGuestRpc(parentDocumentId, projection);
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        checkVmStateAndLock();
        return fetchDocumentMetaFromGuestRpc(documentId, projection);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) {
        checkVmStateAndLock();
        return openDocumentFdFromGuestRpc(documentId, mode);
    }

    private void checkVmStateAndLock() {
        LinuxManagerService service = LinuxManagerService.getInstance();
        if (service == null || service.getVmState() == LinuxManagerService.STATE_OFF) {
            throw new ConnectionError("VMOfflineException: Cannot browse SAF documents while Linux VM is powered off");
        }
        if (!service.isCeKeyAvailable()) {
            throw new SecurityException("EncryptedStorageException: CE storage volume is locked");
        }
    }
}
```

### 3.3 Functional Requirements (`T1-151` ~ `T1-155`)

1. **Provider Registration (`T1-151`)**: Register `LinuxStorageProvider extends DocumentsProvider` in system server.
2. **Expose Guest Home (`T1-152`)**: Expose Guest `/home/user` in Android Files app system picker.
3. **Directory Navigation (`T1-153`)**: Browse subdirectories and list file trees via SAF APIs.
4. **File Edit & Save (`T1-154`)**: Open, edit, and save guest files using native Android editor apps.
5. **Cross-Storage File Copy (`T1-155`)**: Copy files from Android local storage (`/sdcard/Download`) into Guest `/home/user`.

### 3.4 Boundary & Security Edge Cases (`T2-151` ~ `T2-155`)

1. **Hide System Root Directories (`T2-151`)**:
   - Exposed SAF roots are strictly restricted to `/home/user` (and `/mnt/shared`).
   - System directories (`/sys`, `/proc`, `/etc`, `/dev`, `/`) are omitted from SAF roots and blocked during child document queries.
2. **Handle Guest VM Offline State (`T2-152`)**:
   - When Linux VM state is `OFF` or `ERROR`, attempting to access `LinuxStorageProvider` raises `ConnectionError("VMOfflineException: Cannot browse SAF documents while Linux VM is powered off")`.
3. **Deny Access to Locked LUKS2 Volume (`T2-153`)**:
   - Prior to Android user credential unlock (`ce_key_available == false`), accessing guest files raises `PermissionError("EncryptedStorageException: CE storage volume is locked")`.
4. **SAF Document Notification Change Trigger (`T2-154`)**:
   - Guest `portal-agent` / `android-bridge-agent` monitors `/home/user` using `inotify`.
   - On file change, guest sends `CMD_STORAGE_NOTIFY_CHANGE` to Host.
   - Host `LinuxStorageProvider` triggers `getContext().getContentResolver().notifyChange(documentUri, null)`.
5. **Enforce Read-Only SAF Flags (`T2-155`)**:
   - If Guest home volume is mounted read-only (`opts: ro`), `queryDocument` clears `Document.FLAG_SUPPORTS_WRITE`, `Document.FLAG_SUPPORTS_DELETE`, `Document.FLAG_SUPPORTS_RENAME`, enforcing read-only behavior in the SAF UI picker.

---

## 4. Permission Model & SELinux Policy Integration

### 4.1 Permission Declarations
- **Framework Permission**: `android.permission.MANAGE_DOCUMENTS` & `android.permission.MANAGE_LINUX_ENVIRONMENT`.

### 4.2 SELinux Contexts (`system/sepolicy/private/linux_manager.te`)
```sepolicy
# Storage domain permissions for linux_manager & linux_bridge
type linux_shared_data_file, file_type, data_file_type, core_data_file_type;

# Allow linux_manager & linux_bridge to access shared directory
allow linux_manager linux_shared_data_file:dir create_dir_perms;
allow linux_manager linux_shared_data_file:file create_file_perms;
allow linux_bridge linux_shared_data_file:dir create_dir_perms;
allow linux_bridge linux_shared_data_file:file create_file_perms;

# Neverallow safeguards
neverallow linux_bridge efs_file:file *;
neverallow linux_manager system_file:file write;
```

---

## 5. Verification & Test Plan

### 5.1 Verification Commands

1. **Unit & E2E Coverage Check**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --filter F-R5-007
   python3 tests/e2e/runner.py --tier 1 --filter F-R5-008
   python3 tests/e2e/runner.py --tier 2 --filter F-R5-007
   python3 tests/e2e/runner.py --tier 2 --filter F-R5-008
   ```

2. **Cross-Feature & Scenario Checks**:
   ```bash
   python3 tests/e2e/runner.py --tier 3
   python3 tests/e2e/runner.py --tier 4
   ```

3. **Full Test Suite Execution**:
   ```bash
   python3 tests/e2e/runner.py
   ```

---

## 6. Implementation Strategy & File Inventory

| Feature | Target Component / File | Purpose |
|---------|-------------------------|---------|
| `F-R5-007` | `guest/scripts/launch_vm.sh` | Crosvm `--shared-dir` virtiofs daemon launch options |
| `F-R5-007` | `guest/scripts/guest_mount_overlay.sh` | Mount `/mnt/shared` via virtiofs DAX |
| `F-R5-007` | `system/sepolicy/private/file_contexts` | SELinux label `/data/media/0/LinuxShared` |
| `F-R5-008` | `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java` | SAF `DocumentsProvider` implementation |
| `F-R5-008` | `frameworks/base/core/res/AndroidManifest.xml` | Provider manifest registration |
| `F-R5-008` | `system/linux_bridge/vsock_server.cpp` | Storage Vsock RPC packet handling |

---

## 7. Conclusion

The technical strategy for **F-R5-007** and **F-R5-008** provides zero-copy, low-latency bi-directional file sharing and complete Android SAF integration while guaranteeing strict data isolation, encryption boundaries, and SELinux hardening.
