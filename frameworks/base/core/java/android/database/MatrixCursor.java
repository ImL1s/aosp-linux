package android.database;

public class MatrixCursor implements Cursor {
    private final String[] mColumnNames;

    public static class RowBuilder {
        public RowBuilder add(String column, Object value) {
            return this;
        }
    }

    public MatrixCursor(String[] columnNames) {
        mColumnNames = columnNames;
    }

    public RowBuilder newRow() {
        return new RowBuilder();
    }

    @Override
    public int getCount() { return 0; }
    @Override
    public int getPosition() { return 0; }
    @Override
    public boolean moveToNext() { return false; }
    @Override
    public void close() {}
}
