package android.database.sqlite;
public final class SQLiteDatabase {
    public static final int OPEN_READWRITE = 0x00000000;
    public static final int NO_LOCALIZED_COLLATORS = 0x00000010;
    public static final int ENABLE_WRITE_AHEAD_LOGGING = 0x20000000;
    public interface CursorFactory {}
    public static SQLiteDatabase openDatabase(String path, CursorFactory factory, int flags) { throw new RuntimeException("Stub"); }
    public SQLiteStatement compileStatement(String sql) { throw new RuntimeException("Stub"); }
    public void beginTransactionNonExclusive() { throw new RuntimeException("Stub"); }
    public void setTransactionSuccessful() { throw new RuntimeException("Stub"); }
    public void endTransaction() { throw new RuntimeException("Stub"); }
    public void close() { throw new RuntimeException("Stub"); }
}
