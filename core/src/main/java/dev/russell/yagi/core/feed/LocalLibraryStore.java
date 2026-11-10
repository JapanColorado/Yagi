package dev.russell.yagi.core.feed;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The library folders the app watches: folders that were added and split into several books (see
 * {@link LocalLibrarySplitter}). Each refresh of all feeds looks into them again and adds the
 * books that appeared since ({@link LocalFeedCreator#rescanLibraries}).
 *
 * <p>Kept in its own SharedPreferences file as one JSON array, because the folder URIs only mean
 * something on this device and so do not belong in the settings export.</p>
 */
public final class LocalLibraryStore {
    private static final String TAG = "LocalLibraryStore";
    private static final String FILE = "local_libraries";
    private static final String KEY_LIBRARIES = "libraries";

    private LocalLibraryStore() {
    }

    /** One watched folder. */
    public static final class Library {
        /** The tree URI the folder picker returned. */
        @NonNull
        public final String treeUri;
        /** The folder's name, for the list of watched folders. */
        @NonNull
        public final String name;
        /** True if everything in it is an audiobook, false to guess for each book. */
        public final boolean audiobooks;
        /**
         * {@link LocalLibrarySplitter.Part#key()} of every book that was ever added from this
         * folder. A book that is in here but no longer in the app was removed by the user and is
         * not added again.
         */
        @NonNull
        public final Set<String> knownParts;
        /** Identifies the set of files of the last scan, so an unchanged folder is not split again. */
        @Nullable
        public final String fingerprint;

        public Library(@NonNull String treeUri, @NonNull String name, boolean audiobooks,
                       @NonNull Set<String> knownParts, @Nullable String fingerprint) {
            this.treeUri = treeUri;
            this.name = name;
            this.audiobooks = audiobooks;
            this.knownParts = knownParts;
            this.fingerprint = fingerprint;
        }
    }

    @NonNull
    public static synchronized List<Library> getAll(@NonNull Context context) {
        List<Library> libraries = new ArrayList<>();
        String json = prefs(context).getString(KEY_LIBRARIES, null);
        if (json == null) {
            return libraries;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Set<String> known = new LinkedHashSet<>();
                JSONArray knownArray = object.optJSONArray("known");
                for (int j = 0; knownArray != null && j < knownArray.length(); j++) {
                    known.add(knownArray.getString(j));
                }
                libraries.add(new Library(object.getString("uri"), object.optString("name", ""),
                        object.optBoolean("audiobooks", true), known,
                        object.isNull("fingerprint") ? null : object.getString("fingerprint")));
            }
        } catch (JSONException e) {
            Log.e(TAG, "The list of library folders is unreadable: " + e.getMessage());
        }
        return libraries;
    }

    /** Stores the library, replacing the entry for the same folder. */
    public static synchronized void put(@NonNull Context context, @NonNull Library library) {
        List<Library> libraries = getAll(context);
        boolean replaced = false;
        for (int i = 0; i < libraries.size(); i++) {
            if (libraries.get(i).treeUri.equals(library.treeUri)) {
                libraries.set(i, library);
                replaced = true;
            }
        }
        if (!replaced) {
            libraries.add(library);
        }
        save(context, libraries);
    }

    /** Stops watching the folder. The books that were added from it stay. */
    public static synchronized void remove(@NonNull Context context, @NonNull String treeUri) {
        List<Library> libraries = getAll(context);
        for (int i = libraries.size() - 1; i >= 0; i--) {
            if (libraries.get(i).treeUri.equals(treeUri)) {
                libraries.remove(i);
            }
        }
        save(context, libraries);
    }

    private static void save(Context context, List<Library> libraries) {
        JSONArray array = new JSONArray();
        try {
            for (Library library : libraries) {
                JSONObject object = new JSONObject();
                object.put("uri", library.treeUri);
                object.put("name", library.name);
                object.put("audiobooks", library.audiobooks);
                object.put("known", new JSONArray(library.knownParts));
                if (library.fingerprint != null) {
                    object.put("fingerprint", library.fingerprint);
                }
                array.put(object);
            }
        } catch (JSONException e) {
            // only thrown for NaN or a null key, neither of which is put here
            Log.e(TAG, "Unable to store the library folders: " + e.getMessage());
            return;
        }
        prefs(context).edit().putString(KEY_LIBRARIES, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
