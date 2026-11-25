package android.content;
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract android.content.pm.PackageManager getPackageManager();
    public abstract ClassLoader getClassLoader();
    public abstract java.io.File getCodeCacheDir();
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
