package android.database;

public interface Cursor {
    int getCount();
    int getPosition();
    boolean moveToNext();
    void close();
}
