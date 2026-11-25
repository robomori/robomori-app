package com.robomori.device;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;

/**
 * Lists the debuggable apps the user installed, or with `all`, every app on the device that has code, one `ROBOMORI_APP`
 * line each: package, name, version name, version code, launcher icon as a base64 PNG, user id, then 1 or 0 for whether
 * it's debuggable and whether it's part of the system, tab separated, then `ROBOMORI_DONE`. Runs as Android's shell
 * user; no application installation or root is required.
 */
public final class AppsHelper {
    private static final int ICON_SIZE = 96;

    public static void main(String[] args) {
        try {
            // The system context needs a looper on this thread.
            Looper.prepareMainLooper();
            Object activityThread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getClass().getMethod("getSystemContext").invoke(activityThread);
            PackageManager packages = system.getPackageManager();
            boolean all = args.length > 0 && args[0].equals("all");
            for (ApplicationInfo app : packages.getInstalledApplications(0)) {
                boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean debuggable = (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                if (!all && (isSystem || !debuggable)) continue;
                // Without code, such as a theme's overlay, an app never keeps data of its own.
                if ((app.flags & ApplicationInfo.FLAG_HAS_CODE) == 0) continue;
                PackageInfo info = packages.getPackageInfo(app.packageName, 0);
                System.out.println("ROBOMORI_APP\t" + app.packageName + "\t" + clean(packages.getApplicationLabel(app))
                    + "\t" + clean(info.versionName) + "\t" + info.versionCode + "\t" + icon(packages, app)
                    + "\t" + app.uid + "\t" + (debuggable ? 1 : 0) + "\t" + (isSystem ? 1 : 0));
            }
            System.out.println("ROBOMORI_DONE");
        } catch (Throwable failure) {
            while (failure instanceof InvocationTargetException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            System.out.println("ROBOMORI_ERROR\t" + (failure instanceof SecurityException ? "DENIED" : "UNSUPPORTED"));
        }
        System.exit(0);
    }

    /** The app's launcher icon as Android draws it, adaptive icons included, or empty when it can't be drawn. */
    private static String icon(PackageManager packages, ApplicationInfo app) {
        try {
            Drawable drawable = packages.getApplicationIcon(app);
            Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
            drawable.draw(new Canvas(bitmap));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            return Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable unavailable) {
            return "";
        }
    }

    private static String clean(CharSequence text) {
        return text == null ? "" : text.toString().replaceAll("[\\t\\r\\n]+", " ").trim();
    }
}
