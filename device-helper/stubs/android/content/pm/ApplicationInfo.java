package android.content.pm;
public class ApplicationInfo {
    public static final int FLAG_SYSTEM = 1;
    public static final int FLAG_DEBUGGABLE = 2;
    public static final int FLAG_HAS_CODE = 4;
    public String packageName;
    public int flags;
    public int uid;
}
