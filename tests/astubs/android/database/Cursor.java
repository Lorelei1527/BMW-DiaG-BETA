package android.database;
public interface Cursor extends java.io.Closeable { boolean moveToNext(); String getString(int i); void close(); }
