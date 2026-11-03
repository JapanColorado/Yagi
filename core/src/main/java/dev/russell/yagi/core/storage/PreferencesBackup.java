package dev.russell.yagi.core.storage;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import dev.russell.yagi.core.pref.Prefs;

/**
 * Carries the user's settings inside a database export.
 *
 * <p>An export is still a single SQLite file: {@link #writeTo} adds one table,
 * {@value #TABLE}, holding every value of every SharedPreferences file the app owns, except
 * logins and internal bookkeeping (see {@link #EXCLUDED_FILES} and {@link #EXCLUDED_KEYS}).
 * Builds that predate this table import such a file and simply ignore it, and a backup without
 * the table imports with the current settings left alone.</p>
 *
 * <p>Restoring cannot happen while the app runs: the settings screens, MainActivity and the
 * playback service listen to preference changes and would recreate or reconfigure themselves
 * halfway through the import. So {@link #takeFrom} pulls the rows out of the imported file,
 * {@link #savePendingRestore} parks them in a file, and {@link #applyPendingRestore} writes them
 * at the start of the next process (the import ends in a forced restart), before anything has
 * read a preference or registered a listener.</p>
 */
public final class PreferencesBackup {
    private static final String TAG = "PreferencesBackup";

    @VisibleForTesting
    static final String TABLE = "exported_preferences";
    private static final String COL_FILE = "file";
    private static final String COL_KEY = "key";
    private static final String COL_TYPE = "type";
    private static final String COL_VALUE = "value";

    /**
     * Stands for the default preferences file, whose real name contains the application id and so
     * differs between the release and debug builds.
     */
    @VisibleForTesting
    static final String DEFAULT_FILE = "default";

    private static final String PENDING_RESTORE_FILE = "pending-preferences-restore.json";

    /**
     * Preference files that are not exported, and are left alone on restore: the sync login and
     * sync state ({@code SynchronizationCredentials}, {@code SynchronizationSettings} and
     * {@code SynchronizationQueueStorage}), the version marker of {@code PreferenceUpgrader}, the
     * settings search history, usage counters, the last refresh time and the user's Anthropic API
     * key ({@code anthropic}, see {@code AdSkipPrefs}), which must never end up in an export file.
     */
    private static final Set<String> EXCLUDED_FILES = new HashSet<>(Arrays.asList(
            "gpodder.net", "synchronization", "app_version", "preferenceSearch",
            "UsageStatistics", "dbtasks", "anthropic"));

    /** Files written by libraries (WebView, AndroidX, ...) rather than by the app. */
    private static final List<String> EXCLUDED_FILE_PREFIXES = Arrays.asList(
            "androidx.", "com.", "org.", "io.", "WebView");

    /** Secrets in the default preferences file; the device keeps its own value on restore. */
    private static final Set<String> EXCLUDED_KEYS = Collections.singleton(Prefs.PREF_PROXY_PASSWORD);

    private static final String TYPE_STRING = "string";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_STRING_SET = "string_set";

    private PreferencesBackup() {
    }

    /** One preference value, encoded as text the same way in the table and the pending file. */
    @VisibleForTesting
    static final class Row {
        final String file;
        final String key;
        final String type;
        final String value;

        Row(String file, String key, String type, String value) {
            this.file = file;
            this.key = key;
            this.type = type;
            this.value = value;
        }
    }

    /** Adds the {@value #TABLE} table with the current settings to an export copy. */
    static void writeTo(SQLiteDatabase db, Context context) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        db.execSQL("CREATE TABLE " + TABLE + " (" + COL_FILE + " TEXT NOT NULL, "
                + COL_KEY + " TEXT NOT NULL, " + COL_TYPE + " TEXT NOT NULL, "
                + COL_VALUE + " TEXT NOT NULL, PRIMARY KEY (" + COL_FILE + ", " + COL_KEY + "))");
        db.beginTransaction();
        try {
            for (Row row : capture(context)) {
                ContentValues values = new ContentValues();
                values.put(COL_FILE, row.file);
                values.put(COL_KEY, row.key);
                values.put(COL_TYPE, row.type);
                values.put(COL_VALUE, row.value);
                db.insertOrThrow(TABLE, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Reads the settings out of an imported file and drops the table, so it does not linger in
     * the live database.
     *
     * @return the settings, or null if the file was exported without them (an older build).
     */
    @Nullable
    static List<Row> takeFrom(SQLiteDatabase db) {
        try (Cursor tables = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{TABLE})) {
            if (!tables.moveToFirst()) {
                return null;
            }
        }
        List<Row> rows = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE, new String[]{COL_FILE, COL_KEY, COL_TYPE, COL_VALUE},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                rows.add(new Row(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3)));
            }
        }
        db.execSQL("DROP TABLE " + TABLE);
        return rows;
    }

    /** Parks imported settings until {@link #applyPendingRestore} runs in the next process. */
    static void savePendingRestore(Context context, List<Row> rows) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (Row row : rows) {
                array.put(new JSONObject()
                        .put(COL_FILE, row.file)
                        .put(COL_KEY, row.key)
                        .put(COL_TYPE, row.type)
                        .put(COL_VALUE, row.value));
            }
        } catch (JSONException e) {
            throw new IOException("Unable to encode the imported settings", e);
        }
        File target = pendingRestoreFile(context);
        File temp = new File(target.getPath() + ".tmp");
        FileUtils.writeStringToFile(temp, array.toString(), StandardCharsets.UTF_8);
        if (!temp.renameTo(target)) {
            FileUtils.deleteQuietly(temp);
            throw new IOException("Unable to store the imported settings");
        }
    }

    /**
     * Applies settings parked by a database import. Must run at the very start of
     * {@code Application.onCreate}, before any preference is read or listened to.
     */
    public static void applyPendingRestore(Context context) {
        File file = pendingRestoreFile(context);
        if (!file.exists()) {
            return;
        }
        try {
            JSONArray array = new JSONArray(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
            List<Row> rows = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                rows.add(new Row(object.getString(COL_FILE), object.getString(COL_KEY),
                        object.getString(COL_TYPE), object.getString(COL_VALUE)));
            }
            restore(context, rows);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not restore the imported settings", e);
        } finally {
            // Deleted even after a failure, so that a broken file cannot fail every start.
            FileUtils.deleteQuietly(file);
        }
    }

    /** Every exportable setting on this device. */
    @VisibleForTesting
    static List<Row> capture(Context context) {
        List<Row> rows = new ArrayList<>();
        for (String file : exportableFiles(context)) {
            for (Map.Entry<String, ?> entry : open(context, file).getAll().entrySet()) {
                if (!isExportable(file, entry.getKey())) {
                    continue;
                }
                Row row = encode(file, entry.getKey(), entry.getValue());
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * Makes the exportable settings equal to {@code rows}: values missing from the backup are
     * removed, so the device ends up with the backup's settings rather than a mix. Excluded files
     * and keys keep the device's values.
     */
    @VisibleForTesting
    static void restore(Context context, List<Row> rows) {
        Set<String> files = new TreeSet<>(exportableFiles(context));
        for (Row row : rows) {
            if (isExportable(row.file, row.key)) {
                files.add(row.file);
            }
        }
        for (String file : files) {
            SharedPreferences prefs = open(context, file);
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : prefs.getAll().keySet()) {
                if (isExportable(file, key)) {
                    editor.remove(key);
                }
            }
            for (Row row : rows) {
                if (row.file.equals(file) && isExportable(row.file, row.key)) {
                    put(editor, row);
                }
            }
            if (!editor.commit()) {
                Log.e(TAG, "Could not write the imported settings of " + file);
            }
        }
    }

    private static List<String> exportableFiles(Context context) {
        // The name PreferenceManager.getDefaultSharedPreferences uses
        String defaultName = context.getPackageName() + "_preferences";
        List<String> files = new ArrayList<>();
        files.add(DEFAULT_FILE);
        String[] names = new File(context.getDataDir(), "shared_prefs").list();
        if (names == null) {
            return files;
        }
        Arrays.sort(names);
        for (String name : names) {
            if (!name.endsWith(".xml")) {
                continue;
            }
            String file = name.substring(0, name.length() - ".xml".length());
            if (!file.equals(defaultName) && isExportable(file, null)) {
                files.add(file);
            }
        }
        return files;
    }

    private static boolean isExportable(String file, @Nullable String key) {
        if (DEFAULT_FILE.equals(file)) {
            return key == null || !EXCLUDED_KEYS.contains(key);
        }
        if (file.isEmpty() || file.contains("/") || EXCLUDED_FILES.contains(file)) {
            return false;
        }
        for (String prefix : EXCLUDED_FILE_PREFIXES) {
            if (file.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static SharedPreferences open(Context context, String file) {
        if (DEFAULT_FILE.equals(file)) {
            return PreferenceManager.getDefaultSharedPreferences(context);
        }
        return context.getSharedPreferences(file, Context.MODE_PRIVATE);
    }

    @Nullable
    private static Row encode(String file, String key, Object value) {
        if (value instanceof String) {
            return new Row(file, key, TYPE_STRING, (String) value);
        } else if (value instanceof Integer) {
            return new Row(file, key, TYPE_INT, value.toString());
        } else if (value instanceof Long) {
            return new Row(file, key, TYPE_LONG, value.toString());
        } else if (value instanceof Float) {
            return new Row(file, key, TYPE_FLOAT, value.toString());
        } else if (value instanceof Boolean) {
            return new Row(file, key, TYPE_BOOLEAN, value.toString());
        } else if (value instanceof Set) {
            return new Row(file, key, TYPE_STRING_SET, new JSONArray((Set<?>) value).toString());
        }
        Log.w(TAG, "Not exporting " + file + "/" + key + ": unsupported value type");
        return null;
    }

    private static void put(SharedPreferences.Editor editor, @NonNull Row row) {
        try {
            switch (row.type) {
                case TYPE_STRING:
                    editor.putString(row.key, row.value);
                    break;
                case TYPE_INT:
                    editor.putInt(row.key, Integer.parseInt(row.value));
                    break;
                case TYPE_LONG:
                    editor.putLong(row.key, Long.parseLong(row.value));
                    break;
                case TYPE_FLOAT:
                    editor.putFloat(row.key, Float.parseFloat(row.value));
                    break;
                case TYPE_BOOLEAN:
                    editor.putBoolean(row.key, Boolean.parseBoolean(row.value));
                    break;
                case TYPE_STRING_SET:
                    JSONArray array = new JSONArray(row.value);
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < array.length(); i++) {
                        set.add(array.getString(i));
                    }
                    editor.putStringSet(row.key, set);
                    break;
                default:
                    Log.w(TAG, "Skipping " + row.file + "/" + row.key + ": unknown type " + row.type);
                    break;
            }
        } catch (NumberFormatException | JSONException e) {
            Log.w(TAG, "Skipping " + row.file + "/" + row.key + ": malformed value", e);
        }
    }

    private static File pendingRestoreFile(Context context) {
        return new File(context.getFilesDir(), PENDING_RESTORE_FILE);
    }
}
