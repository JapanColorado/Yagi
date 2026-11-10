package dev.russell.yagi.core.storage;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.documentfile.provider.DocumentFile;

import dev.russell.yagi.core.feed.LocalFeedCreator;
import dev.russell.yagi.core.util.StorageUtils;

import dev.russell.yagi.core.service.download.DownloadRequest;
import dev.russell.yagi.core.service.download.DownloadRequestCreator;
import dev.russell.yagi.core.service.download.DownloadService;
import dev.russell.yagi.storage.db.Db;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import dev.russell.yagi.event.FeedItemEvent;
import dev.russell.yagi.event.FeedListUpdateEvent;
import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.model.download.DownloadStatus;
import dev.russell.yagi.core.sync.SyncService;
import dev.russell.yagi.core.sync.queue.SynchronizationQueueSink;
import dev.russell.yagi.model.download.DownloadError;
import dev.russell.yagi.core.util.LongList;
import dev.russell.yagi.core.util.comparator.FeedItemPubdateComparator;
import dev.russell.yagi.model.feed.Feed;
import dev.russell.yagi.model.feed.EpisodeSearchOptions;
import dev.russell.yagi.model.feed.FeedItem;
import dev.russell.yagi.model.feed.FeedMedia;
import dev.russell.yagi.sync.model.EpisodeAction;

/**
 * Provides methods for doing common tasks that use DBReader and DBWriter.
 */
public final class DBTasks {
    private static final String TAG = "DBTasks";

    private static final String PREF_NAME = "dbtasks";
    private static final String PREF_LAST_REFRESH = "last_refresh";

    /**
     * Executor service used by the autodownloadUndownloadedEpisodes method.
     */
    private static final ExecutorService autodownloadExec;

    private static AutomaticDownloadAlgorithm downloadAlgorithm = new AutomaticDownloadAlgorithm();

    static {
        autodownloadExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    private DBTasks() {
    }

    /**
     * Removes the feed with the given download url. This method should NOT be executed on the GUI thread.
     *
     * @param context     Used for accessing the db
     * @param downloadUrl URL of the feed.
     */
    public static void removeFeedWithDownloadUrl(Context context, String downloadUrl) {
        Db adapter = Db.getInstance();
        adapter.open();
        Cursor cursor = adapter.getFeedCursorDownloadUrls();
        long feedID = 0;
        if (cursor.moveToFirst()) {
            do {
                if (cursor.getString(1).equals(downloadUrl)) {
                    feedID = cursor.getLong(0);
                }
            } while (cursor.moveToNext());
        }
        cursor.close();
        adapter.close();

        if (feedID != 0) {
            try {
                DBWriter.deleteFeed(context, feedID).get();
            } catch (InterruptedException | ExecutionException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Safe to continue: the feed is simply left in the database and the next refresh
                // or manual removal can try again. There is no caller state to unwind here.
                Log.e(TAG, "Failed to delete feed " + feedID, e);
            }
        } else {
            Log.w(TAG, "removeFeedWithDownloadUrl: Could not find feed with url: " + downloadUrl);
        }
    }

    /**
     * Refreshes all feeds.
     * It must not be from the main thread.
     * This method might ignore subsequent calls if it is still
     * enqueuing Feeds for download from a previous call
     *
     * @param context  Might be used for accessing the database
     * @param initiatedByUser a boolean indicating if the refresh was triggered by user action.
     */
    /** @return false if the refresh could not be started (see {@link DownloadService#refreshAllFeeds}). */
    public static boolean refreshAllFeeds(final Context context, boolean initiatedByUser) {
        // first, so that books that appeared in a library folder are scanned by this refresh
        LocalFeedCreator.rescanLibraries(context);
        if (!DownloadService.refreshAllFeeds(context, initiatedByUser)) {
            return false;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        prefs.edit().putLong(PREF_LAST_REFRESH, System.currentTimeMillis()).apply();

        SyncService.sync(context);
        // Note: automatic download of episodes will be done but not here.
        // Instead it is done after all feeds have been refreshed (asynchronously),
        // in DownloadService.onDestroy()
        // See Issue #2577 for the details of the rationale
        return true;
    }



    /**
     * Queues the next page of this Feed for download. The given Feed has to be a paged
     * Feed (isPaged()=true) and must contain a nextPageLink.
     *
     * @param context      Used for requesting the download.
     * @param feed         The feed whose next page should be loaded.
     * @param loadAllPages True if any subsequent pages should also be loaded, false otherwise.
     */
    public static void loadNextPageOfFeed(final Context context, Feed feed, boolean loadAllPages) {
        if (feed.isPaged() && feed.getNextPageLink() != null) {
            int pageNr = feed.getPageNr() + 1;
            Feed nextFeed = new Feed(feed.getNextPageLink(), null, feed.getTitle() + "(" + pageNr + ")");
            nextFeed.setPageNr(pageNr);
            nextFeed.setPaged(true);
            nextFeed.setId(feed.getId());

            DownloadRequest.Builder builder = DownloadRequestCreator.create(nextFeed);
            builder.loadAllPages(loadAllPages);
            DownloadService.download(context, false, builder.build());
        } else {
            Log.e(TAG, "loadNextPageOfFeed: Feed was either not paged or contained no nextPageLink");
        }
    }

    public static void forceRefreshFeed(Context context, Feed feed, boolean initiatedByUser) {
        forceRefreshFeed(context, feed, false, initiatedByUser);
    }

    public static void forceRefreshCompleteFeed(final Context context, final Feed feed) {
        forceRefreshFeed(context, feed, true, true);
    }

    private static void forceRefreshFeed(Context context, Feed feed, boolean loadAllPages, boolean initiatedByUser) {
        DownloadRequest.Builder builder = DownloadRequestCreator.create(feed);
        builder.withInitiatedByUser(initiatedByUser);
        builder.setForce(true);
        builder.loadAllPages(loadAllPages);
        DownloadService.download(context, false, builder.build());
    }

    /**
     * Notifies the database about a missing FeedMedia file. This method will correct the FeedMedia object's
     * values in the DB and send a FeedItemEvent.
     */
    public static void notifyMissingFeedMediaFile(final Context context, final FeedMedia media) {
        Log.i(TAG, "The feedmanager was notified about a missing episode. It will update its database now.");
        media.setDownloaded(false);
        media.setFile_url(null);
        DBWriter.setFeedMediaDownloadState(media);
        EventBus.getDefault().post(FeedItemEvent.updated(media.getItem()));
    }

    /**
     * Answers whether a media file exists. Used by {@link #findMissingMediaFiles} so the pure
     * decision logic can be tested without touching the filesystem.
     */
    public interface MediaFileChecker {
        boolean exists(@NonNull FeedMedia media);
    }

    /**
     * Returns true if the file referenced by the media's file_url exists. Handles both plain
     * filesystem paths and content:// URIs (as used by local folder feeds).
     */
    public static boolean mediaFileExists(@NonNull Context context, @NonNull FeedMedia media) {
        if (media.getFile_url() == null) {
            return false;
        }
        if (media.isContentUri()) {
            try {
                DocumentFile file = DocumentFile.fromSingleUri(context, Uri.parse(media.getFile_url()));
                return file != null && file.exists();
            } catch (Exception e) {
                // A revoked permission or a broken provider must not wipe the flag.
                Log.w(TAG, "Unable to check " + media.getFile_url() + ": " + e.getMessage());
                return true;
            }
        }
        File file = new File(media.getFile_url());
        if (file.exists()) {
            return true;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            // The whole directory is gone: an unmounted card or a previous data folder that is
            // currently unreachable, not a deleted episode. Leave the row alone.
            return true;
        }
        return false;
    }

    /**
     * Pure selection logic for {@link #checkMissingMediaFiles}: from a list of items, returns
     * the media that are flagged as downloaded but whose file does not exist. Items of local
     * folder feeds are skipped because {@code LocalFeedUpdater} owns their files.
     */
    @NonNull
    public static List<FeedMedia> findMissingMediaFiles(@NonNull List<FeedItem> items,
                                                        @NonNull MediaFileChecker checker) {
        List<FeedMedia> missing = new ArrayList<>();
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media == null || !media.isDownloaded()) {
                continue;
            }
            if (item.getFeed() != null && item.getFeed().isLocalFeed()) {
                continue;
            }
            if (!checker.exists(media)) {
                missing.add(media);
            }
        }
        return missing;
    }

    private static volatile boolean missingMediaFilesChecked = false;

    /**
     * Reconciles the "downloaded" flag with the filesystem: every media that is flagged as
     * downloaded but whose file is gone (deleted externally, restored from a backup made on
     * another device, storage folder changed, ...) is reset so that it can be downloaded
     * again and no longer shows up as downloaded.
     * <p>
     * Must NOT be called on the main thread. Does nothing when the storage is not available,
     * because an unmounted SD card would otherwise look like every file was deleted.
     *
     * @param force run even if a check already ran in this process
     * @return the number of media entries that were reset
     */
    /** Threshold above which a missing-file sweep is treated as a storage outage, not deletions. */
    static boolean looksLikeStorageOutage(int missing, int total) {
        return total >= 10 && missing * 2 > total;
    }

    public static int checkMissingMediaFiles(@NonNull Context context, boolean force) {
        if (!force && missingMediaFilesChecked) {
            return 0;
        }
        if (!StorageUtils.storageAvailable()) {
            Log.w(TAG, "Storage not available, skipping missing media file check");
            return 0;
        }
        final Context appContext = context.getApplicationContext();
        List<FeedItem> downloadedItems = DBReader.getDownloadedItems();
        List<FeedMedia> missing = findMissingMediaFiles(downloadedItems, media -> mediaFileExists(appContext, media));
        missingMediaFilesChecked = true;
        if (missing.isEmpty()) {
            Log.d(TAG, "Missing media file check: all " + downloadedItems.size() + " files present");
            return 0;
        }
        if (looksLikeStorageOutage(missing.size(), downloadedItems.size())) {
            // Most files gone at once means an unmounted card / unreachable old folder rather
            // than individual deletions; wiping the flags would make them unrecoverable.
            Log.w(TAG, "Missing media file check: " + missing.size() + " of " + downloadedItems.size()
                    + " files missing, assuming storage is unreachable and skipping");
            return 0;
        }

        List<FeedItem> changedItems = new ArrayList<>();
        List<Future<?>> writes = new ArrayList<>(missing.size());
        for (FeedMedia media : missing) {
            Log.i(TAG, "Downloaded file missing, resetting: " + media.getFile_url());
            media.setDownloaded(false);
            media.setFile_url(null);
            writes.add(DBWriter.setFeedMediaDownloadState(media));
            if (media.getItem() != null) {
                changedItems.add(media.getItem());
            }
        }
        for (Future<?> write : writes) {
            try {
                write.get();
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "missing media file update failed", e);
            }
        }
        if (!changedItems.isEmpty()) {
            EventBus.getDefault().post(FeedItemEvent.updated(changedItems));
        }
        return missing.size();
    }

    public static List<FeedItem> enqueueFeedItemsToDownload(final Context context,
                       List<FeedItem> items) throws InterruptedException, ExecutionException {
        List<FeedItem> itemsToEnqueue = new ArrayList<>();
        if (Prefs.enqueueDownloadedEpisodes()) {
            LongList queueIDList = DBReader.getQueueIDList();
            for (FeedItem item : items) {
                if (!queueIDList.contains(item.getId())) {
                    itemsToEnqueue.add(item);
                }
            }
            DBWriter.addQueueItem(context, false, itemsToEnqueue.toArray(new FeedItem[0])).get();
        }
        return itemsToEnqueue;
    }

    /**
     * Looks for non-downloaded episodes in the queue or list of unread items and request a download if
     * 1. Network is available
     * 2. The device is charging or the user allows auto download on battery
     * 3. There is free space in the episode cache
     * This method is executed on an internal single thread executor.
     *
     * @param context  Used for accessing the DB.
     * @return A Future that can be used for waiting for the methods completion.
     */
    public static Future<?> autodownloadUndownloadedItems(final Context context) {
        Log.d(TAG, "autodownloadUndownloadedItems");
        return autodownloadExec.submit(downloadAlgorithm.autoDownloadUndownloadedItems(context));
    }

    /**
     * For testing purpose only.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setDownloadAlgorithm(AutomaticDownloadAlgorithm newDownloadAlgorithm) {
        downloadAlgorithm = newDownloadAlgorithm;
    }

    /**
     * Removed downloaded episodes outside of the queue if the episode cache is full. Episodes with a smaller
     * 'playbackCompletionDate'-value will be deleted first.
     * <p/>
     * This method should NOT be executed on the GUI thread.
     *
     * @param context Used for accessing the DB.
     */
    public static void performAutoCleanup(final Context context) {
        Prefs.getEpisodeCleanupAlgorithm().performCleanup(context);
    }

    private static Feed searchFeedByIdentifyingValueOrID(Feed feed) {
        if (feed.getId() != 0) {
            return DBReader.getFeed(feed.getId());
        } else {
            List<Feed> feeds = DBReader.getFeedList();
            for (Feed f : feeds) {
                if (f.getIdentifyingValue().equals(feed.getIdentifyingValue())) {
                    f.setItems(DBReader.getFeedItemList(f));
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Get a FeedItem by its identifying value.
     */
    private static FeedItem searchFeedItemByIdentifyingValue(List<FeedItem> items, FeedItem searchItem) {
        for (FeedItem item : items) {
            if (TextUtils.equals(item.getIdentifyingValue(), searchItem.getIdentifyingValue())) {
                return item;
            }
        }
        return null;
    }

    /**
     * Index of identifying value -> first item with that value, for O(1) lookups in the
     * merge loop of {@link #updateFeed} (which used to be O(n*m) per refresh).
     */
    @NonNull
    static Map<String, FeedItem> indexByIdentifyingValue(@NonNull List<FeedItem> items) {
        Map<String, FeedItem> index = new HashMap<>(items.size() * 2);
        for (FeedItem item : items) {
            String key = item.getIdentifyingValue();
            if (key != null && !index.containsKey(key)) {
                index.put(key, item);
            }
        }
        return index;
    }

    /**
     * Guess if one of the items could actually mean the searched item, even if it uses another identifying value.
     * This is to work around podcasters breaking their GUIDs.
     */
    private static FeedItem searchFeedItemGuessDuplicate(List<FeedItem> items, FeedItem searchItem) {
        for (FeedItem item : items) {
            if (FeedItemDuplicateGuesser.seemDuplicates(item, searchItem)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Adds new Feeds to the database or updates the old versions if they already exists. If another Feed with the same
     * identifying value already exists, this method will add new FeedItems from the new Feed to the existing Feed.
     * These FeedItems will be marked as unread with the exception of the most recent FeedItem.
     * <p/>
     * This method can update multiple feeds at once. Submitting a feed twice in the same method call can result in undefined behavior.
     * <p/>
     * This method should NOT be executed on the GUI thread.
     *
     * @param context Used for accessing the DB.
     * @param newFeed The new Feed object.
     * @param removeUnlistedItems The item list in the new Feed object is considered to be exhaustive.
     *                            I.e. items are removed from the database if they are not in this item list.
     * @return The updated Feed from the database if it already existed, or the new Feed from the parameters otherwise.
     */
    public static synchronized Feed updateFeed(Context context, Feed newFeed, boolean removeUnlistedItems) {
        Feed resultFeed;
        List<FeedItem> unlistedItems = new ArrayList<>();

        Db adapter = Db.getInstance();
        adapter.open();

        // Look up feed in the feedslist
        final Feed savedFeed = searchFeedByIdentifyingValueOrID(newFeed);
        if (savedFeed == null) {
            Log.d(TAG, "Found no existing Feed with title "
                            + newFeed.getTitle() + ". Adding as new one.");

            // Add a new Feed
            // all new feeds will have the most recent item marked as unplayed
            FeedItem mostRecent = newFeed.getMostRecentItem();
            if (mostRecent != null && !newFeed.isAudiobook()) {
                mostRecent.setNew();
            }

            resultFeed = newFeed;
        } else {
            Log.d(TAG, "Feed with title " + newFeed.getTitle()
                        + " already exists. Syncing new with existing one.");

            Collections.sort(newFeed.getItems(), new FeedItemPubdateComparator());

            if (newFeed.getPageNr() == savedFeed.getPageNr()) {
                if (savedFeed.compareWithOther(newFeed)) {
                    Log.d(TAG, "Feed has updated attribute values. Updating old feed's attributes");
                    savedFeed.updateFromOther(newFeed);
                }
            } else {
                Log.d(TAG, "New feed has a higher page number.");
                savedFeed.setNextPageLink(newFeed.getNextPageLink());
            }
            if (savedFeed.getPreferences().compareWithOther(newFeed.getPreferences())) {
                Log.d(TAG, "Feed has updated preferences. Updating old feed's preferences");
                savedFeed.getPreferences().updateFromOther(newFeed.getPreferences());
            }

            // get the most recent date now, before we start changing the list
            FeedItem priorMostRecent = savedFeed.getMostRecentItem();
            Date priorMostRecentDate = null;
            if (priorMostRecent != null) {
                priorMostRecentDate = priorMostRecent.getPubDate();
            }

            // Look for new or updated Items
            final Map<String, FeedItem> savedIndex = indexByIdentifyingValue(savedFeed.getItems());
            for (int idx = 0; idx < newFeed.getItems().size(); idx++) {
                final FeedItem item = newFeed.getItems().get(idx);

                FeedItem possibleDuplicate = searchFeedItemGuessDuplicate(newFeed.getItems(), item);
                if (!newFeed.isLocalFeed() && possibleDuplicate != null && item != possibleDuplicate) {
                    // Canonical episode is the first one returned (usually oldest)
                    DownloadStatus warning = new DownloadStatus(savedFeed,
                            item.getTitle(), DownloadError.ERROR_PARSER_EXCEPTION_DUPLICATE, false,
                            "The podcast host appears to have added the same episode twice. "
                                    + "The app still refreshed the feed and attempted to repair it."
                                    + "\n\nOriginal episode:\n" + duplicateEpisodeDetails(item)
                                    + "\n\nSecond episode that is also in the feed:\n"
                                    + duplicateEpisodeDetails(possibleDuplicate), false);
                    warning.setWarningKey(FeedWarningKeys.duplicateEpisodes(
                            item.getIdentifyingValue(), possibleDuplicate.getIdentifyingValue()));
                    DBWriter.addDownloadStatus(warning);
                    continue;
                }

                FeedItem oldItem = savedIndex.get(item.getIdentifyingValue());
                if (!newFeed.isLocalFeed() && oldItem == null) {
                    oldItem = searchFeedItemGuessDuplicate(savedFeed.getItems(), item);
                    if (oldItem != null) {
                        Log.d(TAG, "Repaired duplicate: " + oldItem + ", " + item);
                        DownloadStatus warning = new DownloadStatus(savedFeed,
                                item.getTitle(), DownloadError.ERROR_PARSER_EXCEPTION_DUPLICATE, false,
                                "The podcast host changed the ID of an existing episode instead of just "
                                        + "updating the episode itself. The app still refreshed the feed and "
                                        + "attempted to repair it."
                                        + "\n\nOriginal episode:\n" + duplicateEpisodeDetails(oldItem)
                                        + "\n\nNow the feed contains:\n" + duplicateEpisodeDetails(item), false);
                        warning.setWarningKey(FeedWarningKeys.changedEpisodeId(oldItem.getId()));
                        DBWriter.addDownloadStatus(warning);
                        oldItem.setItemIdentifier(item.getItemIdentifier());

                        if (oldItem.isPlayed() && oldItem.getMedia() != null) {
                            EpisodeAction action = new EpisodeAction.Builder(oldItem, EpisodeAction.PLAY)
                                    .currentTimestamp()
                                    .started(oldItem.getMedia().getDuration() / 1000)
                                    .position(oldItem.getMedia().getDuration() / 1000)
                                    .total(oldItem.getMedia().getDuration() / 1000)
                                    .build();
                            SynchronizationQueueSink.enqueueEpisodeActionIfSynchronizationIsActive(context, action);
                        }
                    }
                }

                if (oldItem != null) {
                    oldItem.updateFromOther(item);
                } else {
                    Log.d(TAG, "Found new item: " + item.getTitle());
                    item.setFeed(savedFeed);
                    if (item.getIdentifyingValue() != null) {
                        savedIndex.put(item.getIdentifyingValue(), item);
                    }

                    if (idx >= savedFeed.getItems().size()) {
                        savedFeed.getItems().add(item);
                    } else {
                        savedFeed.getItems().add(idx, item);
                    }

                    // only mark the item new if it was published after or at the same time
                    // as the most recent item
                    // (if the most recent date is null then we can assume there are no items
                    // and this is the first, hence 'new')
                    // New items that do not have a pubDate set are always marked as new
                    // Audiobook tracks are never NEW: they stay out of the inbox and the
                    // new-episode notifications and are stored as UNPLAYED.
                    if (savedFeed.isAudiobook()) {
                        item.setPlayed(false);
                    } else if (item.getPubDate() == null || priorMostRecentDate == null
                            || priorMostRecentDate.before(item.getPubDate())
                            || priorMostRecentDate.equals(item.getPubDate())) {
                        Log.d(TAG, "Marking item published on " + item.getPubDate()
                                + " new, prior most recent date = " + priorMostRecentDate);
                        item.setNew();
                    }
                }
            }

            if (savedFeed.isAudiobook()) {
                // also covers an existing item that FeedItem.updateFromOther flagged NEW
                for (FeedItem feedItem : savedFeed.getItems()) {
                    if (feedItem.isNew()) {
                        feedItem.setPlayed(false);
                    }
                }
            }

            // identify items to be removed
            if (removeUnlistedItems) {
                final Map<String, FeedItem> newIndex = indexByIdentifyingValue(newFeed.getItems());
                Iterator<FeedItem> it = savedFeed.getItems().iterator();
                while (it.hasNext()) {
                    FeedItem feedItem = it.next();
                    if (!newIndex.containsKey(feedItem.getIdentifyingValue())) {
                        unlistedItems.add(feedItem);
                        it.remove();
                    }
                }
            }

            // update attributes. A paged response (page > 0) carries the Last-Modified/ETag of
            // that page, not of the feed itself, so it must not replace the value used for the
            // next conditional request.
            if (newFeed.getPageNr() == savedFeed.getPageNr()) {
                savedFeed.setLastUpdate(newFeed.getLastUpdate());
            }
            savedFeed.setType(newFeed.getType());
            savedFeed.setLastUpdateFailed(false);

            resultFeed = savedFeed;
        }

        try {
            if (savedFeed == null) {
                DBWriter.addNewFeed(context, newFeed).get();
                // Update with default values that are set in database
                resultFeed = searchFeedByIdentifyingValueOrID(newFeed);
            } else {
                DBWriter.setCompleteFeed(savedFeed).get();
            }
            if (removeUnlistedItems) {
                DBWriter.deleteFeedItems(context, unlistedItems).get();
            }
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Safe to continue: the in-memory feed is still returned to the caller so that the
            // refresh finishes; the database write is retried on the next refresh.
            Log.e(TAG, "Failed to persist updated feed " + newFeed.getTitle(), e);
        }

        adapter.close();

        if (savedFeed != null) {
            EventBus.getDefault().post(new FeedListUpdateEvent(savedFeed));
        } else {
            EventBus.getDefault().post(new FeedListUpdateEvent(Collections.emptyList()));
        }

        return resultFeed;
    }

    private static String duplicateEpisodeDetails(FeedItem item) {
        return "Title: " + item.getTitle()
                + "\nID: " + item.getItemIdentifier()
                + ((item.getMedia() == null) ? "" : "\nURL: " + item.getMedia().getDownload_url());
    }

    /**
     * Searches the FeedItems of a specific Feed for a given string.
     *
     * @param feedID  The id of the feed whose items should be searched.
     * @param query   The search string.
     * @return A FutureTask object that executes the search request
     *         and returns the search result as a List of FeedItems.
     */
    public static FutureTask<List<FeedItem>> searchFeedItems(final long feedID, final String query) {
        return new FutureTask<>(new QueryTask<List<FeedItem>>() {
            @Override
            public void execute(Db adapter) {
                Cursor searchResult = adapter.searchItems(feedID, query);
                List<FeedItem> items = DBReader.extractItemlistFromCursor(searchResult);
                DBReader.loadAdditionalFeedItemListData(items);
                setResult(items);
                searchResult.close();
            }
        });
    }

    /**
     * Searches the FeedItems for a given string, filtered, scoped and sorted by {@code options}.
     *
     * @return A FutureTask object that executes the search request
     *         and returns the search result as a List of FeedItems.
     */
    public static FutureTask<List<FeedItem>> searchFeedItems(final String query,
                                                             final EpisodeSearchOptions options) {
        final long now = System.currentTimeMillis();
        return new FutureTask<>(new QueryTask<List<FeedItem>>() {
            @Override
            public void execute(Db adapter) {
                try (Cursor searchResult = adapter.searchItems(query, options, now)) {
                    List<FeedItem> items = DBReader.extractItemlistFromCursor(searchResult);
                    DBReader.loadAdditionalFeedItemListData(items);
                    setResult(items);
                }
            }
        });
    }

    /** How many items {@link #searchFeedItems(String, EpisodeSearchOptions)} finds. */
    public static FutureTask<Integer> countFeedItems(final String query, final EpisodeSearchOptions options) {
        final long now = System.currentTimeMillis();
        return new FutureTask<>(new QueryTask<Integer>() {
            @Override
            public void execute(Db adapter) {
                setResult(adapter.countSearchItems(query, options, now));
            }
        });
    }

    public static FutureTask<List<Feed>> searchFeeds(final String query) {
        return new FutureTask<>(new QueryTask<List<Feed>>() {
            @Override
            public void execute(Db adapter) {
                Cursor cursor = adapter.searchFeeds(query);
                List<Feed> items = new ArrayList<>();
                if (cursor.moveToFirst()) {
                    do {
                        items.add(DBReader.extractFeedFromCursorRow(cursor));
                    } while (cursor.moveToNext());
                }
                setResult(items);
                cursor.close();
            }
        });
    }

    /**
     * A runnable which should be used for database queries. The onCompletion
     * method is executed on the database executor to handle Cursors correctly.
     * This class automatically creates a PodDBAdapter object and closes it when
     * it is no longer in use.
     */
    abstract static class QueryTask<T> implements Callable<T> {
        private T result;

        public QueryTask() {
        }

        @Override
        public T call() throws Exception {
            Db adapter = Db.getInstance();
            adapter.open();
            execute(adapter);
            adapter.close();
            return result;
        }

        public abstract void execute(Db adapter);

        void setResult(T result) {
            this.result = result;
        }
    }
}
