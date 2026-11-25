package com.robomori.device;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.util.Base64;
import java.io.FileInputStream;

/**
 * Changes an app's database as the app itself would: `DatabaseHelper <path> <statement in base64>…` runs the statements
 * through Android's own SQLite, under the same locks as the app, in one transaction, so either all of them change the
 * database or none do. It prints `ROBOMORI_CHANGED` with how many rows they changed, or `ROBOMORI_ERROR` with why they
 * couldn't. Runs as the app, through run-as.
 */
public final class DatabaseHelper {
    public static void main(String[] args) {
        try {
            String path = args[0];
            int flags = SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS;
            // Opened in the journal mode the app uses, so the app's own connection carries on undisturbed.
            if (writeAheadLogged(path)) flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
            SQLiteDatabase database = SQLiteDatabase.openDatabase(path, null, flags);
            try {
                long changed = 0;
                database.beginTransactionNonExclusive();
                try {
                    for (int i = 1; i < args.length; i++) {
                        changed += run(database, new String(Base64.decode(args[i], Base64.NO_WRAP), "UTF-8"));
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
                System.out.println("ROBOMORI_CHANGED\t" + changed);
            } finally {
                database.close();
            }
        } catch (Throwable failure) {
            System.out.println("ROBOMORI_ERROR\t" + String.valueOf(failure.getMessage()).replace('\n', ' '));
        }
    }

    /** Runs one statement and returns how many rows it changed itself, not counting what its triggers did. */
    private static int run(SQLiteDatabase database, String sql) {
        long before = totalChanges(database);
        SQLiteStatement statement = database.compileStatement(sql);
        try {
            int changed = statement.executeUpdateDelete();
            // SQLite reports the count of the last statement that changed rows, so one that changes none, such as
            // CREATE TABLE, would count that one's rows again.
            return totalChanges(database) > before ? changed : 0;
        } catch (SQLiteException failure) {
            // A statement that returns rows, such as a SELECT among the others, changes nothing, but Android refuses to
            // run it here, after stepping it once; its rows are read from the copy taken afterwards.
            if (String.valueOf(failure.getMessage()).contains("rawQuery")) return 0;
            throw failure;
        } finally {
            statement.close();
        }
    }

    private static long totalChanges(SQLiteDatabase database) {
        SQLiteStatement statement = database.compileStatement("SELECT total_changes()");
        try {
            return statement.simpleQueryForLong();
        } finally {
            statement.close();
        }
    }

    /** Whether the database at [path] keeps a write-ahead log, which its header says in its 19th byte. */
    private static boolean writeAheadLogged(String path) throws Exception {
        byte[] header = new byte[20];
        FileInputStream input = new FileInputStream(path);
        try {
            return input.read(header) == header.length && header[18] == 2;
        } finally {
            input.close();
        }
    }
}
