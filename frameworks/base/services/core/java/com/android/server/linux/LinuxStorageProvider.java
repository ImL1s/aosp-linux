/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.linux;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Slog;

import com.android.server.LocalServices;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Storage Access Framework (SAF) DocumentsProvider exposing Debian Guest storage (/home/user & /mnt/shared).
 * Integrates VM state checking, LUKS2 CE encryption lock validation, inotify change notifications, and read-only flag enforcement.
 * {@hide}
 */
public class LinuxStorageProvider extends DocumentsProvider {
    private static final String TAG = "LinuxStorageProvider";
    public static final String AUTHORITY = "com.android.linux.storage";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
    };

    private static final List<String> SYSTEM_ROOTS = Arrays.asList("/sys", "/proc", "/etc", "/dev");
    private final List<String> mExposedRoots = new ArrayList<>(Arrays.asList("/home/user", "/mnt/shared"));
    private final List<String> mNotificationUris = new ArrayList<>();

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

    @Override
    public boolean onCreate() {
        Slog.i(TAG, "LinuxStorageProvider created under authority: " + AUTHORITY);
        LinuxManagerInternal lmi = getLinuxManagerInternal();
        if (lmi != null) {
            lmi.registerStorageStateListener(mStorageStateListener);
        }
        return true;
    }

    private LinuxManagerInternal getLinuxManagerInternal() {
        return LocalServices.getService(LinuxManagerInternal.class);
    }

    public List<String> getExposedRoots() {
        return mExposedRoots;
    }

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

    private boolean isReadOnlyMount() {
        LinuxManagerInternal lmi = getLinuxManagerInternal();
        return lmi != null && lmi.isReadOnlyMount();
    }

    private void notifyRootsChanged() {
        if (getContext() != null) {
            getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null);
        }
        Slog.i(TAG, "Dispatched notifyChange for roots URI: content://" + AUTHORITY + "/root");
    }

    private File getFileForDocId(String documentId) throws SecurityException {
        if (documentId == null) {
            throw new SecurityException("Null document ID");
        }

        // Direct check against system root strings
        for (String sysRoot : SYSTEM_ROOTS) {
            if (documentId.equals(sysRoot) || documentId.startsWith(sysRoot + "/") || documentId.equals(sysRoot.substring(1)) || documentId.startsWith(sysRoot.substring(1) + "/")) {
                throw new SecurityException("Access to system root path denied: " + documentId);
            }
        }

        File baseDir;
        String relativePath;

        if (documentId.startsWith("home/user") || documentId.startsWith("/home/user")) {
            baseDir = new File("/data/linux/home/user");
            String strip = documentId.startsWith("/") ? documentId.substring(1) : documentId;
            relativePath = strip.length() >= 9 ? strip.substring(9) : "";
        } else if (documentId.startsWith("mnt/shared") || documentId.startsWith("/mnt/shared")) {
            baseDir = new File("/data/media/0/LinuxShared");
            String strip = documentId.startsWith("/") ? documentId.substring(1) : documentId;
            relativePath = strip.length() >= 10 ? strip.substring(10) : "";
        } else {
            throw new SecurityException("Unauthorized root document ID: " + documentId);
        }

        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        File targetFile = new File(baseDir, relativePath);
        try {
            String canonicalTarget = targetFile.getCanonicalPath();
            String canonicalBase = baseDir.getCanonicalPath();

            // Rule 1: Canonical target MUST stay within canonical base boundary
            if (!canonicalTarget.equals(canonicalBase) && !canonicalTarget.startsWith(canonicalBase + File.separator)) {
                throw new SecurityException("Path traversal attempt blocked: " + documentId);
            }

            // Rule 2: Re-verify canonical target against system roots
            for (String sysRoot : SYSTEM_ROOTS) {
                if (canonicalTarget.equals(sysRoot) || canonicalTarget.startsWith(sysRoot + "/")) {
                    throw new SecurityException("Access to system root path denied: " + canonicalTarget);
                }
            }

            return targetFile;
        } catch (IOException e) {
            throw new SecurityException("Failed to resolve canonical path for: " + documentId, e);
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        checkVmStateAndLock();
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);

        File homeDir = new File("/data/linux/home/user");
        File sharedDir = new File("/data/media/0/LinuxShared");

        MatrixCursor.RowBuilder rowHome = result.newRow();
        rowHome.add(Root.COLUMN_ROOT_ID, "home_user");
        rowHome.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_RECENTS | Root.FLAG_SUPPORTS_SEARCH);
        rowHome.add(Root.COLUMN_TITLE, "Linux Home Directory");
        rowHome.add(Root.COLUMN_SUMMARY, "Debian User Storage (/home/user)");
        rowHome.add(Root.COLUMN_DOCUMENT_ID, "home/user");
        rowHome.add(Root.COLUMN_AVAILABLE_BYTES, homeDir.exists() ? homeDir.getUsableSpace() : 10737418240L);

        MatrixCursor.RowBuilder rowShared = result.newRow();
        rowShared.add(Root.COLUMN_ROOT_ID, "mnt_shared");
        rowShared.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH);
        rowShared.add(Root.COLUMN_TITLE, "Linux Shared Storage");
        rowShared.add(Root.COLUMN_SUMMARY, "Virtiofs Bi-directional Shared Folder");
        rowShared.add(Root.COLUMN_DOCUMENT_ID, "mnt/shared");
        rowShared.add(Root.COLUMN_AVAILABLE_BYTES, sharedDir.exists() ? sharedDir.getUsableSpace() : 21474836480L);

        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        checkVmStateAndLock();
        File file = getFileForDocId(documentId);

        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, file);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        checkVmStateAndLock();
        File parentFile = getFileForDocId(parentDocumentId);

        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File[] children = parentFile.listFiles();
        if (children != null) {
            for (File child : children) {
                String childDocId = parentDocumentId.endsWith("/") ? parentDocumentId + child.getName() : parentDocumentId + "/" + child.getName();
                includeFile(result, childDocId, child);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) {
        checkVmStateAndLock();
        Slog.i(TAG, "openDocument: " + documentId + " mode: " + mode);

        File targetFile = getFileForDocId(documentId);
        int pfdMode = parseMode(mode);
        boolean isWriteRequested = (pfdMode & (ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_READ_WRITE)) != 0;

        if (isReadOnlyMount() && isWriteRequested) {
            throw new SecurityException("Cannot open document for writing: Storage is mounted read-only");
        }

        if (isWriteRequested && targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
        }

        try {
            return ParcelFileDescriptor.open(targetFile, pfdMode);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("File not found: " + documentId, e);
        }
    }

    private void includeFile(MatrixCursor result, String docId, File file) {
        int flags = 0;
        if (!isReadOnlyMount()) {
            flags |= (Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME);
            if (file.isDirectory()) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            }
        }

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_MIME_TYPE, file.isDirectory() ? Document.MIME_TYPE_DIR : getTypeForFile(file));
        row.add(Document.COLUMN_DISPLAY_NAME, file.exists() ? file.getName() : getDisplayNameFromId(docId));
        row.add(Document.COLUMN_LAST_MODIFIED, file.exists() ? file.lastModified() : System.currentTimeMillis());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, file.isDirectory() ? 0L : (file.exists() ? file.length() : 1024L));
    }

    private int parseMode(String mode) {
        if ("r".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_ONLY;
        } else if ("w".equals(mode) || "wt".equals(mode)) {
            return ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("wa".equals(mode)) {
            return ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_APPEND;
        } else if ("rw".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        } else if ("rwt".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        } else {
            return ParcelFileDescriptor.MODE_READ_ONLY;
        }
    }

    private String getTypeForFile(File file) {
        String name = file.getName();
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    public void notifyDocumentChanged(String uri) {
        mNotificationUris.add(uri);
        if (getContext() != null) {
            getContext().getContentResolver().notifyChange(Uri.parse(uri), null);
        }
        Slog.i(TAG, "Dispatched notifyChange for URI: " + uri);
    }

    public List<String> getNotificationUris() {
        return mNotificationUris;
    }

    private String getDisplayNameFromId(String docId) {
        if (docId == null || docId.isEmpty()) return "root";
        int lastSlash = docId.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < docId.length() - 1) {
            return docId.substring(lastSlash + 1);
        }
        return docId;
    }

    public static class ConnectionError extends RuntimeException {
        public ConnectionError(String msg) {
            super(msg);
        }
    }

    public static class PermissionError extends RuntimeException {
        public PermissionError(String msg) {
            super(msg);
        }
    }
}
