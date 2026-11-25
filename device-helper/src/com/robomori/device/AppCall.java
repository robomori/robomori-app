package com.robomori.device;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Changes what a running app holds in memory, from inside its own process, so the app sees an edit made in Robomori at
 * once. Robomori's agent loads this class into the app and calls [run], since an app keeps its preferences and its
 * Room observers to itself: a change made to its files from outside goes unnoticed until it restarts.
 *
 * A request is fields in base64 joined by dots: an ID, a command and its arguments.
 * - `prefs`, a SharedPreferences name, then edits: `set` with a key, a type and a value, or `remove` with a key. They
 *   go through the app's own SharedPreferences, so its copy in memory, its listeners and its file all change together.
 * - `room`, then table names: tells every Room database in the app that those tables changed, so its LiveData, Flows
 *   and observers query them again.
 *
 * The outcome goes to `robomori-call-<ID>` in the app's code cache, for Robomori to read: `ok` and what was done, or
 * `error` and why not.
 */
public final class AppCall {
    /** Every live object that is a [type], which Robomori's agent finds by walking the app's heap. */
    private static native Object[] instances(Class<?> type);

    public static void run(Context context, String request) {
        String[] fields = request.split("\\.");
        String outcome;
        try {
            String command = text(fields[1]);
            if (command.equals("prefs")) outcome = "ok\t" + editPreferences(context, fields);
            else if (command.equals("room")) outcome = "ok\t" + invalidateRoom(context, fields);
            else throw new IllegalArgumentException("Unknown command " + command);
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            outcome = "error\t" + String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause).replace('\n', ' ');
        }
        report(context, text(fields[0]), outcome);
    }

    private static String editPreferences(Context context, String[] fields) {
        SharedPreferences.Editor editor = context.getSharedPreferences(text(fields[2]), Context.MODE_PRIVATE).edit();
        int edits = 0;
        for (int i = 3; i < fields.length; edits++) {
            String operation = text(fields[i++]);
            String key = text(fields[i++]);
            if (operation.equals("remove")) { editor.remove(key); continue; }
            String type = text(fields[i++]);
            String value = text(fields[i++]);
            if (type.equals("String")) editor.putString(key, value);
            else if (type.equals("Null")) editor.putString(key, null);
            else if (type.equals("Int")) editor.putInt(key, Integer.parseInt(value));
            else if (type.equals("Long")) editor.putLong(key, Long.parseLong(value));
            else if (type.equals("Float")) editor.putFloat(key, Float.parseFloat(value));
            else if (type.equals("Boolean")) editor.putBoolean(key, Boolean.parseBoolean(value));
            else throw new IllegalArgumentException("SharedPreferences can't hold a " + type);
        }
        if (!editor.commit()) throw new IllegalStateException("The app couldn't save its preferences.");
        return edits + " edits";
    }

    /** Tells each Room database in the app that the tables named in [fields] changed; returns how many it told. */
    private static String invalidateRoom(Context context, String[] fields) throws Exception {
        String[] tables = new String[fields.length - 2];
        for (int i = 2; i < fields.length; i++) tables[i - 2] = text(fields[i]);
        int told = 0;
        // Room 3 moved to its own package; an app may hold either, or neither.
        for (String name : new String[] {"androidx.room.RoomDatabase", "androidx.room3.RoomDatabase"}) {
            Class<?> type;
            try {
                type = Class.forName(name, false, context.getClassLoader());
            } catch (ClassNotFoundException absent) {
                continue;
            }
            for (Object database : instances(type)) {
                Object tracker = type.getMethod("getInvalidationTracker").invoke(database);
                if (tracker != null && invalidate(tracker, tables)) told++;
            }
        }
        return told + " databases";
    }

    /**
     * Marks [tables] changed in one Room InvalidationTracker, as Room itself does once it sees a write. Room only notices
     * writes made on its own connection, through triggers that log them there, so one made by another process needs this.
     */
    private static boolean invalidate(Object tracker, String[] tables) throws Exception {
        // Room 2.6 and earlier tell each observer, which LiveData, Flow and RxJava results all go through.
        Method byNames = method(tracker.getClass(), "notifyObserversByTableNames", String[].class);
        if (byNames != null) {
            byNames.invoke(tracker, (Object) tables);
            return true;
        }
        // Room 2.7 and later track tables by ID: Flows follow the tables' versions, and observers are told separately.
        Object implementation = field(tracker, "implementation");
        @SuppressWarnings("unchecked")
        Map<String, Integer> ids = (Map<String, Integer>) field(implementation, "tableIdLookup");
        Set<Integer> changed = new HashSet<>();
        for (String table : tables) {
            Integer id = ids.get(table.toLowerCase(Locale.ROOT));
            if (id != null) changed.add(id);
        }
        if (changed.isEmpty()) return false;
        Object versions = field(implementation, "observedTableVersions");
        method(versions.getClass(), "increment", Set.class).invoke(versions, changed);
        // Room 3 has no observers, only Flows.
        Object observers = optionalField(implementation, "onInvalidatedTablesIds");
        if (observers != null) method(observers.getClass(), "invoke", Object.class).invoke(observers, changed);
        return true;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Method found = at.getDeclaredMethod(name, parameters);
                found.setAccessible(true);
                return found;
            } catch (NoSuchMethodException next) {
                // Declared further up, if anywhere.
            }
        }
        return null;
    }

    private static Object field(Object owner, String name) throws Exception {
        Object value = optionalField(owner, name);
        if (value == null) throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
        return value;
    }

    /** The value of the field [name] of [owner], or null when it has none. */
    private static Object optionalField(Object owner, String name) throws Exception {
        for (Class<?> at = owner.getClass(); at != null; at = at.getSuperclass()) {
            try {
                Field found = at.getDeclaredField(name);
                found.setAccessible(true);
                return found.get(owner);
            } catch (NoSuchFieldException next) {
                // Declared further up, if anywhere.
            }
        }
        return null;
    }

    private static String text(String field) {
        return new String(Base64.getDecoder().decode(field), StandardCharsets.UTF_8);
    }

    /** Writes [outcome] where Robomori reads it, whole: written beside it first, then moved into place. */
    private static void report(Context context, String id, String outcome) {
        try {
            File partial = new File(context.getCodeCacheDir(), "robomori-call-" + id + ".partial");
            FileOutputStream output = new FileOutputStream(partial);
            try {
                output.write(outcome.getBytes(StandardCharsets.UTF_8));
            } finally {
                output.close();
            }
            partial.renameTo(new File(context.getCodeCacheDir(), "robomori-call-" + id));
        } catch (Exception ignored) {
            // Robomori stops waiting for the outcome after a while, and treats it as a failure.
        }
    }
}
