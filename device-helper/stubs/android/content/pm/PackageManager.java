package android.content.pm;
public abstract class PackageManager {
    public static class NameNotFoundException extends Exception {}
    public abstract java.util.List<ApplicationInfo> getInstalledApplications(int flags);
    public abstract PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException;
    public abstract CharSequence getApplicationLabel(ApplicationInfo info);
    public abstract android.graphics.drawable.Drawable getApplicationIcon(ApplicationInfo info);
}
