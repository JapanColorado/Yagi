package dev.russell.yagi.core.feed;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.documentfile.provider.DocumentFile;

import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import dev.russell.yagi.core.R;
import dev.russell.yagi.core.storage.DBReader;
import dev.russell.yagi.core.storage.DBTasks;
import dev.russell.yagi.core.storage.DBWriter;
import dev.russell.yagi.model.feed.Feed;
import dev.russell.yagi.model.feed.FeedPreferences;
import dev.russell.yagi.model.feed.SortOrder;

/**
 * Creates feeds from folders on the device's own storage, picked with the storage access
 * framework. It keeps read access to the picked folder across reboots, and then:
 *
 * <ul>
 * <li>A folder that holds one book (or one podcast) becomes one feed
 * ({@link Feed#PREFIX_LOCAL_FOLDER} plus the tree URI), as it always did.</li>
 * <li>A folder that holds several books is a library: {@link LocalLibrarySplitter} splits it and
 * every book becomes its own feed. A book folder's feed is {@link Feed#PREFIX_LOCAL_FOLDER} plus
 * the URI of that folder inside the picked tree, a single-file book's feed is
 * {@link Feed#PREFIX_LOCAL_FILE} plus the file's URI. The library is remembered in
 * {@link LocalLibraryStore}, and {@link #rescanLibraries} adds the books that appear in it
 * later.</li>
 * </ul>
 *
 * <p>Feeds are stored subscribed, sorted in {@link SortOrder#TRACK_ORDER} and marked as an
 * audiobook when asked to or when they look like one; {@link LocalFeedUpdater} then scans them in
 * the download service. A book that already has a feed is not added again.</p>
 *
 * <p>It blocks on database writes, so it must not run on the main thread.</p>
 */
public final class LocalFeedCreator {
    private static final String TAG = "LocalFeedCreator";

    private LocalFeedCreator() {
    }

    /** What {@link #addLocalFolder} did. */
    public static final class ImportResult {
        /** The feeds that were created. */
        @NonNull
        public final List<Feed> added = new ArrayList<>();
        /** The feeds that already existed for books in the folder. */
        @NonNull
        public final List<Feed> existing = new ArrayList<>();
        /** True if the folder was split into books and is now watched for new ones. */
        public final boolean library;

        ImportResult(boolean library) {
            this.library = library;
        }
    }

    /**
     * Adds a folder picked with {@code ActivityResultContracts.OpenDocumentTree}: as one feed, or
     * as one feed per book if it holds several.
     *
     * @param treeUri   the tree URI the picker returned
     * @param audiobook true or false to mark the new feeds as audiobooks or podcasts, null to
     *                  let {@link LocalFolderClassifier} guess from the files
     * @throws IllegalArgumentException if the folder cannot be resolved
     */
    @WorkerThread
    @NonNull
    public static synchronized ImportResult addLocalFolder(@NonNull Context context, @NonNull Uri treeUri,
                                                           @Nullable Boolean audiobook) {
        context.getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        DocumentFile documentFile = DocumentFile.fromTreeUri(context, treeUri);
        if (documentFile == null) {
            throw new IllegalArgumentException("Unable to retrieve document tree");
        }
        String title = documentFile.getName();
        if (title == null) {
            title = context.getString(R.string.local_folder);
        }

        LocalFolderScanner.Result scan = null;
        try {
            scan = LocalFolderScanner.scan(context, treeUri);
        } catch (IOException e) {
            // the first scan reports the problem on the feed; until then, treat it as one podcast
            Log.w(TAG, "unable to scan " + treeUri + ": " + e.getMessage());
        }
        List<LocalLibrarySplitter.Part> parts = scan == null ? Collections.emptyList()
                : LocalLibrarySplitter.split(paths(scan), albumReader(context, scan));
        if (scan != null && audiobook == null && isShelfOfLooseFiles(parts)
                && !LocalFolderClassifier.looksLikeAudiobook(context, scan.mediaFiles)) {
            // loose files that are not audiobooks: the episodes of one podcast
            parts = Collections.emptyList();
        }

        if (scan == null || parts.isEmpty() || LocalLibrarySplitter.isSingleFolder(parts)) {
            boolean isAudiobook = audiobook != null ? audiobook
                    : scan != null && LocalFolderClassifier.looksLikeAudiobook(context, scan.mediaFiles);
            ImportResult result = new ImportResult(false);
            String key = documentKey(Feed.PREFIX_LOCAL_FOLDER + treeUri);
            for (Feed feed : DBReader.getFeedList()) {
                if (key != null && key.equals(documentKey(feed.getDownload_url()))) {
                    // already there, perhaps as a book of a library folder: rescan and show it
                    DBTasks.forceRefreshFeed(context, feed, true);
                    result.existing.add(feed);
                    return result;
                }
            }
            result.added.add(addFeed(context, Feed.PREFIX_LOCAL_FOLDER + treeUri, title, isAudiobook, true));
            return result;
        }

        ImportResult result = new ImportResult(true);
        boolean audiobooks = addParts(context, treeUri, scan, parts, audiobook, true, result);
        for (LocalLibraryStore.Library library : LocalLibraryStore.getAll(context)) {
            // nothing new was added, so nothing was guessed: keep what the folder was
            audiobooks |= result.added.isEmpty() && library.audiobooks
                    && library.treeUri.equals(treeUri.toString());
        }
        // Picking the folder again is a fresh start: books removed earlier came back just now.
        LocalLibraryStore.put(context, new LocalLibraryStore.Library(treeUri.toString(), title,
                Boolean.TRUE.equals(audiobook) || audiobooks, keys(parts), fingerprint(scan)));
        return result;
    }

    /**
     * Looks into every watched library folder and adds the books that appeared since the last
     * look. Books the user removed from the app are not added again. Called before all feeds are
     * refreshed, which then scans the new books' files; a folder that cannot be read is skipped.
     *
     * @return the number of books added
     */
    @WorkerThread
    public static synchronized int rescanLibraries(@NonNull Context context) {
        int added = 0;
        for (LocalLibraryStore.Library library : LocalLibraryStore.getAll(context)) {
            Uri treeUri = Uri.parse(library.treeUri);
            try {
                LocalFolderScanner.Result scan = LocalFolderScanner.scan(context, treeUri);
                String fingerprint = fingerprint(scan);
                if (fingerprint.equals(library.fingerprint)) {
                    continue;
                }
                List<LocalLibrarySplitter.Part> parts =
                        LocalLibrarySplitter.split(paths(scan), albumReader(context, scan));
                List<LocalLibrarySplitter.Part> newParts = newParts(parts, library.knownParts);
                ImportResult result = new ImportResult(true);
                addParts(context, treeUri, scan, newParts, library.audiobooks ? Boolean.TRUE : null,
                        false, result);
                added += result.added.size();
                Set<String> known = new LinkedHashSet<>(library.knownParts);
                known.addAll(keys(parts));
                LocalLibraryStore.put(context, new LocalLibraryStore.Library(library.treeUri, library.name,
                        library.audiobooks, known, fingerprint));
            } catch (IOException | RuntimeException e) {
                // Folder gone, permission revoked, provider misbehaving: try again next time.
                Log.w(TAG, "unable to rescan the library " + library.treeUri + ": " + e.getMessage());
            }
        }
        return added;
    }

    /**
     * The parts that are not known yet. A part inside a known book folder, or a folder around
     * known books, is left out too: its files already belong to existing feeds, and the folder
     * only splits differently now that it has more files.
     */
    @NonNull
    static List<LocalLibrarySplitter.Part> newParts(@NonNull List<LocalLibrarySplitter.Part> parts,
                                                    @NonNull Set<String> knownKeys) {
        List<LocalLibrarySplitter.Part> newParts = new ArrayList<>();
        for (LocalLibrarySplitter.Part part : parts) {
            if (knownKeys.contains(part.key())) {
                continue;
            }
            boolean overlaps = false;
            for (String key : knownKeys) {
                String knownPath = key.substring(2);
                boolean knownIsFolder = key.startsWith("D:");
                if ((knownIsFolder && isBelow(part.path, knownPath))
                        || (!part.file && isBelow(knownPath, part.path))) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                newParts.add(part);
            }
        }
        return newParts;
    }

    private static boolean isBelow(String path, String folder) {
        return folder.isEmpty() || path.startsWith(folder + "/");
    }

    /** True for several books that all are single files directly in the picked folder. */
    private static boolean isShelfOfLooseFiles(List<LocalLibrarySplitter.Part> parts) {
        if (parts.size() < 2) {
            return false;
        }
        for (LocalLibrarySplitter.Part part : parts) {
            if (!part.file || part.path.contains("/")) {
                return false;
            }
        }
        return true;
    }

    /** @return true if the added feeds were (all) marked as audiobooks */
    private static boolean addParts(Context context, Uri treeUri, LocalFolderScanner.Result scan,
                                 List<LocalLibrarySplitter.Part> parts, @Nullable Boolean audiobook,
                                 boolean refresh, ImportResult result) {
        if (parts.isEmpty()) {
            return false;
        }
        Map<String, LocalFolderScanner.LocalFile> byPath = new HashMap<>();
        for (LocalFolderScanner.LocalFile file : scan.mediaFiles) {
            byPath.put(file.relativePath, file);
        }
        Map<String, Feed> existing = new HashMap<>();
        for (Feed feed : DBReader.getFeedList()) {
            String key = documentKey(feed.getDownload_url());
            if (key != null) {
                existing.put(key, feed);
            }
        }
        List<String> urls = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<Boolean> guesses = new ArrayList<>();
        int guessedAudiobooks = 0;
        for (LocalLibrarySplitter.Part part : parts) {
            String url;
            String title;
            if (part.file) {
                LocalFolderScanner.LocalFile file = byPath.get(part.path);
                if (file == null) {
                    continue;
                }
                url = Feed.PREFIX_LOCAL_FILE + file.uri;
                title = FilenameUtils.getBaseName(file.name);
            } else {
                String documentId = scan.folderIds.get(part.path);
                if (documentId == null) {
                    continue;
                }
                url = Feed.PREFIX_LOCAL_FOLDER + DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                title = part.name();
            }
            Feed existingFeed = existing.get(documentKey(url));
            if (existingFeed != null) {
                result.existing.add(existingFeed);
                continue;
            }
            boolean isAudiobook;
            if (audiobook != null) {
                isAudiobook = audiobook;
            } else {
                List<LocalFolderScanner.LocalFile> files = new ArrayList<>();
                for (LocalFolderScanner.LocalFile file : scan.mediaFiles) {
                    if (part.contains(file.relativePath)) {
                        files.add(file);
                    }
                }
                isAudiobook = LocalFolderClassifier.looksLikeAudiobook(context, files);
            }
            urls.add(url);
            titles.add(title);
            guesses.add(isAudiobook);
            guessedAudiobooks += isAudiobook ? 1 : 0;
        }
        // Books and podcasts are rarely kept in one folder, and a book without tags looks like
        // a podcast: when at least half of the folder is audiobooks, all of it is.
        boolean mostlyAudiobooks = !urls.isEmpty() && guessedAudiobooks * 2 >= urls.size();
        for (int i = 0; i < urls.size(); i++) {
            result.added.add(addFeed(context, urls.get(i), titles.get(i),
                    mostlyAudiobooks || guesses.get(i), refresh));
        }
        return mostlyAudiobooks;
    }

    /**
     * Identifies the folder or file a local feed is made from, however its URL spells it: a book
     * folder that was picked directly and the same folder found inside a library have different
     * URLs but the same document. Null for feeds that are not local.
     */
    @Nullable
    static String documentKey(@Nullable String downloadUrl) {
        if (downloadUrl == null) {
            return null;
        }
        String prefix;
        if (downloadUrl.startsWith(Feed.PREFIX_LOCAL_FILE)) {
            prefix = Feed.PREFIX_LOCAL_FILE;
        } else if (downloadUrl.startsWith(Feed.PREFIX_LOCAL_FOLDER)) {
            prefix = Feed.PREFIX_LOCAL_FOLDER;
        } else {
            return null;
        }
        try {
            Uri uri = Uri.parse(downloadUrl.substring(prefix.length()));
            return prefix + uri.getAuthority() + "|" + LocalFolderScanner.documentIdOf(uri);
        } catch (RuntimeException e) {
            // not a storage access framework URI: only equal to itself
            return downloadUrl;
        }
    }

    private static List<String> paths(LocalFolderScanner.Result scan) {
        List<String> paths = new ArrayList<>(scan.mediaFiles.size());
        for (LocalFolderScanner.LocalFile file : scan.mediaFiles) {
            paths.add(file.relativePath);
        }
        return paths;
    }

    private static Set<String> keys(List<LocalLibrarySplitter.Part> parts) {
        Set<String> keys = new LinkedHashSet<>();
        for (LocalLibrarySplitter.Part part : parts) {
            keys.add(part.key());
        }
        return keys;
    }

    /** Changes whenever a media file appears in, disappears from or moves within the folder. */
    private static String fingerprint(LocalFolderScanner.Result scan) {
        List<String> paths = paths(scan);
        Collections.sort(paths);
        return paths.size() + ":" + Integer.toHexString(paths.hashCode());
    }

    private static LocalLibrarySplitter.AlbumReader albumReader(Context context, LocalFolderScanner.Result scan) {
        Map<String, Uri> uris = new HashMap<>();
        for (LocalFolderScanner.LocalFile file : scan.mediaFiles) {
            uris.put(file.relativePath, file.uri);
        }
        return path -> {
            Uri uri = uris.get(path);
            if (uri == null) {
                return null;
            }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            } catch (RuntimeException e) {
                // unreadable file: it has no say
                Log.w(TAG, "unable to read the album of " + uri + ": " + e.getMessage());
                return null;
            } finally {
                try {
                    retriever.release();
                } catch (IOException e) {
                    Log.w(TAG, "unable to release MediaMetadataRetriever: " + e.getMessage());
                }
            }
        };
    }

    @NonNull
    private static Feed addFeed(@NonNull Context context, @NonNull String downloadUrl, @NonNull String title,
                                boolean audiobook, boolean refresh) {
        Feed feed = new Feed(downloadUrl, null, title);
        feed.setItems(Collections.emptyList());
        feed.setSortOrder(SortOrder.TRACK_ORDER);
        feed.setSubscribed(true);

        Feed fromDatabase = DBTasks.updateFeed(context, feed, false);
        FeedPreferences preferences = fromDatabase.getPreferences();
        if (audiobook && preferences != null && !preferences.isAudiobook()) {
            try {
                // before the first scan, so that its tracks are stored as UNPLAYED, not NEW
                DBWriter.setFeedAudiobook(fromDatabase.getId(), true).get();
                preferences.setAudiobook(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while adding " + downloadUrl, e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Unable to mark " + downloadUrl + " as an audiobook", e);
            }
        }
        if (refresh) {
            DBTasks.forceRefreshFeed(context, fromDatabase, true);
        }
        return fromDatabase;
    }
}
