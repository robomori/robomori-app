package com.robomori.device;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.TreeSet;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Runs as Android's shell user; no application installation or root is required. */
public final class LocaleHelper {
    public static void main(String[] args) {
        try {
            Class<?> activityManager = Class.forName("android.app.ActivityManager");
            Object manager;
            try {
                manager = activityManager.getMethod("getService").invoke(null);
            } catch (NoSuchMethodException androidNougat) {
                manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            }
            Class<?> managerInterface = Class.forName("android.app.IActivityManager");
            Class<?> configuration = Class.forName("android.content.res.Configuration");
            Class<?> localeList = Class.forName("android.os.LocaleList");
            if (args.length == 2 && "set".equals(args[0])) {
                for (String tag : args[1].split(",", -1)) {
                    Locale locale = Locale.forLanguageTag(tag);
                    if (locale.getLanguage().isEmpty() || "und".equals(locale.toLanguageTag())) {
                        throw new IllegalArgumentException("Invalid locale");
                    }
                }
                Object config = configuration.getConstructor().newInstance();
                Object locales = localeList.getMethod("forLanguageTags", String.class).invoke(null, args[1]);
                configuration.getMethod("setLocales", localeList).invoke(config, locales);
                configuration.getField("userSetLocale").setBoolean(config, true);
                try {
                    Method update = managerInterface.getMethod("updatePersistentConfigurationWithAttribution",
                        configuration, String.class, String.class);
                    update.invoke(manager, config, "com.android.shell", null);
                } catch (NoSuchMethodException olderAndroid) {
                    managerInterface.getMethod("updatePersistentConfiguration", configuration).invoke(manager, config);
                }
            } else if (args.length < 1 || args.length > 2 || !"describe".equals(args[0])) {
                throw new IllegalArgumentException("Unknown action");
            }

            Object config = managerInterface.getMethod("getConfiguration").invoke(manager);
            Object locales = configuration.getMethod("getLocales").invoke(config);
            System.out.println("ROBOMORI_CURRENT\t" + localeList.getMethod("toLanguageTags").invoke(locales));
            if ("describe".equals(args[0])) {
                String build = (String) Class.forName("android.os.Build").getField("FINGERPRINT").get(null);
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(build.getBytes(StandardCharsets.UTF_8));
                StringBuilder fingerprint = new StringBuilder();
                for (byte value : digest) fingerprint.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                System.out.println("ROBOMORI_BUILD\t" + fingerprint);
                if (args.length == 2 && fingerprint.toString().equals(args[1])) return;
                Class<?> resources = Class.forName("android.content.res.Resources");
                Object system = resources.getMethod("getSystem").invoke(null);
                Object assets = resources.getMethod("getAssets").invoke(system);
                String[] tags = (String[]) Class.forName("android.content.res.AssetManager")
                    .getMethod("getLocales").invoke(assets);
                TreeSet<String> available = new TreeSet<String>();
                for (String tag : tags) {
                    String canonical = Locale.forLanguageTag(tag.replace('_', '-')).toLanguageTag();
                    if (!"und".equals(canonical) && !"en-XA".equals(canonical) && !"ar-XB".equals(canonical)) {
                        available.add(canonical);
                    }
                }
                for (String tag : available) System.out.println("ROBOMORI_LOCALE\t" + tag);
            }
        } catch (Throwable failure) {
            while (failure instanceof InvocationTargetException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            System.out.println("ROBOMORI_ERROR\t" + (failure instanceof SecurityException ? "DENIED" : "UNSUPPORTED"));
        }
    }
}
