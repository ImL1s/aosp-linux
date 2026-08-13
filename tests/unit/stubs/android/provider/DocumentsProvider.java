package android.provider;

import android.content.ContentProvider;
import android.database.Cursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

public abstract class DocumentsProvider extends ContentProvider {
    public DocumentsProvider() {}
    public abstract Cursor queryRoots(String[] projection);
    public abstract Cursor queryDocument(String documentId, String[] projection);
    public abstract Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder);
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, Bundle queryArgs) {
        return queryChildDocuments(parentDocumentId, projection, (String) null);
    }
    public abstract ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal);
}
