package dev.russell.yagi.core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;

import dev.russell.yagi.core.pref.PlaybackPreferences;
import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.pref.UsageStatistics;
import dev.russell.yagi.core.sync.SynchronizationCredentials;
import dev.russell.yagi.core.sync.SynchronizationSettings;
import dev.russell.yagi.storage.db.Db;

/**
 * Settings travelling inside a database export: written into the exported copy only, pulled out
 * and dropped on import, and applied by {@link PreferencesBackup#applyPendingRestore} on the next
 * start, with sync logins and the proxy password kept from the device.
 */
@RunWith(RobolectricTestRunner.class)
public class PreferencesBackupTest {

    private static final String UI_FILE = "SwipeActionsPrefs";
    private static final String SYNC_FILE = "gpodder.net";
    private static final String CLAUDE_KEY_FILE = "anthropic";

    private Application context;
    private File backup;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        defaults().edit().clear().commit();
        prefs(CLAUDE_KEY_FILE).edit().clear().commit();
        Prefs.resetForTests();
        Prefs.init(context);
        PlaybackPreferences.init(context);
        UsageStatistics.init(context);
        SynchronizationCredentials.init(context);
        SynchronizationSettings.init(context);
        Db.init(context);
        Db.getInstance().open();
        backup = new File(context.getCacheDir(), "backup.db");
    }

    @After
    public void tearDown() {
        DBWriter.tearDownTests();
        Db.tearDownTests();
        context.deleteDatabase(Db.DATABASE_NAME);
        backup.delete();
        Prefs.resetForTests();
    }

    @Test
    public void exportImportRestoresSettingsOnNextStart() throws Exception {
        defaults().edit()
                .putString("pref_string", "exported")
                .putInt("pref_int", 7)
                .putLong("pref_long", 1L << 40)
                .putFloat("pref_float", 1.25f)
                .putBoolean("pref_boolean", true)
                .putStringSet("pref_set", new HashSet<>(Arrays.asList("a", "b")))
                .putString(Prefs.PREF_PROXY_PASSWORD, "exported secret")
                .commit();
        prefs(UI_FILE).edit().putString("swipe", "exported").commit();
        prefs(SYNC_FILE).edit().putString("username", "exported user").commit();
        Prefs.setClaudeApiKey("exported key");

        try (FileOutputStream out = new FileOutputStream(backup)) {
            DatabaseExporter.exportToStream(out, context);
        }
        assertFalse("the live database never gets the table", hasTable(Db.getInstance().getDb()));
        assertExportedRowsExcludeSecrets();

        defaults().edit()
                .putString("pref_string", "changed")
                .putInt("pref_int", 8)
                .putString("pref_added_later", "gone after restore")
                .putString(Prefs.PREF_PROXY_PASSWORD, "device secret")
                .commit();
        prefs(UI_FILE).edit().putString("swipe", "changed").commit();
        prefs(SYNC_FILE).edit().putString("username", "device user").commit();
        Prefs.setClaudeApiKey("device key");

        DatabaseExporter.importBackup(Uri.fromFile(backup), context);
        assertEquals("nothing changes before the restart", "changed",
                defaults().getString("pref_string", null));

        PreferencesBackup.applyPendingRestore(context);

        assertEquals("exported", defaults().getString("pref_string", null));
        assertEquals(7, defaults().getInt("pref_int", 0));
        assertEquals(1L << 40, defaults().getLong("pref_long", 0));
        assertEquals(1.25f, defaults().getFloat("pref_float", 0), 0f);
        assertTrue(defaults().getBoolean("pref_boolean", false));
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), defaults().getStringSet("pref_set", null));
        assertFalse(defaults().contains("pref_added_later"));
        assertEquals("device secret", defaults().getString(Prefs.PREF_PROXY_PASSWORD, null));
        assertEquals("exported", prefs(UI_FILE).getString("swipe", null));
        assertEquals("device user", prefs(SYNC_FILE).getString("username", null));
        assertEquals("device key", Prefs.getClaudeApiKey());

        SQLiteDatabase imported = SQLiteDatabase.openDatabase(
                context.getDatabasePath(Db.DATABASE_NAME).getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertFalse("the import drops the table", hasTable(imported));
        } finally {
            imported.close();
        }

        defaults().edit().putString("pref_string", "after restart").commit();
        PreferencesBackup.applyPendingRestore(context);
        assertEquals("a restore is applied only once", "after restart",
                defaults().getString("pref_string", null));
    }

    @Test
    public void backupWithoutSettingsLeavesSettingsAlone() throws Exception {
        try (FileOutputStream out = new FileOutputStream(backup)) {
            DatabaseExporter.exportToStream(out, context);
        }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(backup.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            db.execSQL("DROP TABLE " + PreferencesBackup.TABLE);
            assertNull(PreferencesBackup.takeFrom(db));
        } finally {
            db.close();
        }
        defaults().edit().putString("pref_string", "kept").commit();

        DatabaseExporter.importBackup(Uri.fromFile(backup), context);
        PreferencesBackup.applyPendingRestore(context);

        assertEquals("kept", defaults().getString("pref_string", null));
    }

    private void assertExportedRowsExcludeSecrets() {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(backup.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertEquals(1, countRows(db, "file = ? AND key = ?", PreferencesBackup.DEFAULT_FILE, "pref_string"));
            assertEquals(1, countRows(db, "file = ?", UI_FILE));
            assertEquals(0, countRows(db, "file = ?", SYNC_FILE));
            assertEquals(0, countRows(db, "file = ?", CLAUDE_KEY_FILE));
            assertEquals(0, countRows(db, "key = ?", Prefs.PREF_PROXY_PASSWORD));
        } finally {
            db.close();
        }
    }

    private static int countRows(SQLiteDatabase db, String where, String... args) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + PreferencesBackup.TABLE
                + " WHERE " + where, args)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private static boolean hasTable(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{PreferencesBackup.TABLE})) {
            return cursor.moveToFirst();
        }
    }

    private SharedPreferences defaults() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private SharedPreferences prefs(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
