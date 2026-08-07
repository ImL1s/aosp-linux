package android.provider;

public final class DocumentsContract {
    public static final class Root {
        public static final String COLUMN_ROOT_ID = "root_id";
        public static final String COLUMN_FLAGS = "flags";
        public static final String COLUMN_ICON = "icon";
        public static final String COLUMN_TITLE = "title";
        public static final String COLUMN_SUMMARY = "summary";
        public static final String COLUMN_DOCUMENT_ID = "document_id";
        public static final String COLUMN_AVAILABLE_BYTES = "available_bytes";

        public static final int FLAG_SUPPORTS_CREATE = 0x01;
        public static final int FLAG_SUPPORTS_RECENTS = 0x02;
        public static final int FLAG_SUPPORTS_SEARCH = 0x04;
    }

    public static final class Document {
        public static final String COLUMN_DOCUMENT_ID = "document_id";
        public static final String COLUMN_MIME_TYPE = "mime_type";
        public static final String COLUMN_DISPLAY_NAME = "_display_name";
        public static final String COLUMN_LAST_MODIFIED = "last_modified";
        public static final String COLUMN_FLAGS = "flags";
        public static final String COLUMN_SIZE = "_size";

        public static final String MIME_TYPE_DIR = "vnd.android.document/directory";
        public static final int FLAG_SUPPORTS_WRITE = 0x04;
        public static final int FLAG_SUPPORTS_DELETE = 0x08;
        public static final int FLAG_SUPPORTS_RENAME = 0x10;
        public static final int FLAG_DIR_SUPPORTS_CREATE = 0x08;
    }
}

