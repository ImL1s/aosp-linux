package android.provider;

import android.content.ContentProvider;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;

public abstract class DocumentsProvider extends ContentProvider {
    public abstract Cursor queryRoots(String[] projection);
    public abstract Cursor queryDocument(String documentId, String[] projection);
    public abstract Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder);
    public abstract ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal);

    @Override
    public boolean onCreate() {
        return true;
    }
}


