package dev.russell.yagi.core.adskip;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import dev.russell.yagi.core.ClientConfig;
import dev.russell.yagi.core.R;
import dev.russell.yagi.core.feed.util.AdSkipUtils;
import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.util.ChapterUtils;
import dev.russell.yagi.core.util.ui.NotificationUtils;
import dev.russell.yagi.core.storage.DBReader;
import dev.russell.yagi.core.storage.DBWriter;
import dev.russell.yagi.model.feed.AdSegment;
import dev.russell.yagi.model.feed.Chapter;
import dev.russell.yagi.model.feed.FeedItem;
import dev.russell.yagi.model.feed.FeedMedia;
import io.reactivex.Completable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Runs ad detection over one episode in the background and stores the result: the chapters, the
 * published transcript ({@link TranscriptAdAnalysis}) and, for a downloaded episode, the audio
 * ({@link AdAnalyzer}). A streamed episode with a transcript gets the first two.
 *
 * <p>This class owns the <em>scheduling</em> side of ad detection: when analysis is worth running
 * ({@link #enqueue}, {@link #enqueueOnPlayback}), how it is identified to WorkManager (one unique
 * work name per media id) and how its output is split by {@link AdSegment.Source} into the rows
 * the storage layer keeps separate. It owns none of the detection itself.
 *
 * <p>Detected segments are stored with whatever confidence the detector reported, deliberately
 * unfiltered: the user's sensitivity setting is applied at playback time, so lowering it does not
 * require a re-analysis. Analysis is best effort — a file that cannot be decoded, or a run that
 * WorkManager stops, fails without a retry and is picked up by the next download or by a manual
 * request from the UI.
 */
public class AdAnalysisWorker extends Worker {

    private static final String TAG = "AdAnalysisWorker";

    /** Input data key: the id of the {@link FeedMedia} to analyse. */
    public static final String PARAM_MEDIA_ID = "mediaId";

    /** Tag on every request, so the UI can observe or cancel all analysis work at once. */
    public static final String WORK_TAG = "ad-analysis";

    private static final String WORK_NAME_PREFIX = "ad-analysis-";

    /** Id of the foreground notification shown while an episode is being analysed. */
    private static final int NOTIFICATION_ID = 0xAD5C1F;

    public AdAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @Override
    @NonNull
    public Result doWork() {
        ClientConfig.ensureInitialized(getApplicationContext());

        long mediaId = getInputData().getLong(PARAM_MEDIA_ID, 0);
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null) {
            Log.e(TAG, "No media with id " + mediaId + ", nothing to analyse");
            return Result.failure();
        }
        FeedItem item = media.getItem();
        if (item == null) {
            Log.e(TAG, "Media " + mediaId + " has no episode, nothing to analyse");
            return Result.failure();
        }
        String filePath = media.getFile_url();
        boolean downloaded = media.isDownloaded() && filePath != null && new File(filePath).exists();

        // Publish whatever the chapters already say before spending minutes on the audio, so that
        // an episode played right after its download can skip labelled breaks immediately.
        List<Chapter> chapters = loadChapters(item);
        storeChapterSegments(item, chapters);
        boolean hasChapters = chapters != null && !chapters.isEmpty();

        // The transcript is quick to read (a download and at most one request to Claude), and for
        // a streamed episode it is all there is; its findings are published before the audio's.
        TranscriptReading transcript = TranscriptAdAnalysis.read(item, media.getDuration());
        if (transcript != null && !storeTranscriptSegments(item, transcript, chapters, media)) {
            return Result.failure();
        }
        if (!downloaded) {
            if (transcript == null) {
                Log.d(TAG, "Media " + mediaId + " is not downloaded and has no readable"
                        + " transcript, nothing to analyse");
                return Result.failure();
            }
            Prefs.setTranscriptAdMethod(mediaId, transcript.getMethod());
            Log.i(TAG, "Read the transcript of " + media.getEpisodeTitle() + " ("
                    + transcript.getMethod() + "): " + transcript.getSegments().size() + " segments");
            return Result.success();
        }

        // Decoding a two hour episode takes minutes of CPU. A plain background job is stopped by
        // the system after about ten minutes, and sooner on some devices when the app is not in
        // front, so promote the run to a foreground service for its duration.
        promoteToForeground(media);

        long started = System.currentTimeMillis();
        List<AdSegment> segments;
        try {
            segments = new AdAnalyzer().analyze(filePath, item.getId(), media.getDuration(),
                    chapters, transcript, this::isStopped);
        } catch (DecodeException e) {
            Log.e(TAG, "Could not analyse " + media.getEpisodeTitle(), e);
            return Result.failure();
        }
        long elapsedMs = System.currentTimeMillis() - started;

        if (isStopped()) {
            // Half the file was decoded at best. Dropping the run is better than storing segments
            // derived from a truncated episode; the next download or a manual request re-enqueues.
            Log.w(TAG, "Analysis of " + media.getEpisodeTitle() + " was stopped after "
                    + elapsedMs / 1000 + " s");
            return Result.failure();
        }

        AdSegment.Source[] stored = transcript != null ? ALL_ANALYSED_SOURCES : AUDIO_SOURCES;
        if (!hasChapters) {
            // Chapters this run could not see (read from a streamed file by the player) keep
            // the segments the player stored for them.
            stored = without(stored, AdSegment.Source.CHAPTER);
        }
        if (!store(item, segments, stored, media)) {
            return Result.failure();
        }
        Prefs.markAdAnalyzed(mediaId);
        // A transcript that could not be fetched is not tried again for this download: playing
        // the episode would otherwise repeat the whole audio analysis each time.
        if (transcript != null) {
            Prefs.setTranscriptAdMethod(mediaId, transcript.getMethod());
        } else if (TranscriptAdAnalysis.wantedMethod() != null) {
            Prefs.setTranscriptAdMethod(mediaId, TranscriptReading.METHOD_NONE);
        }
        Log.i(TAG, "Analysed " + media.getEpisodeTitle() + " in " + elapsedMs / 1000 + " s: "
                + segments.size() + " segments");
        return Result.success();
    }

    /** What an audio analysis without a transcript reading replaces. */
    private static final AdSegment.Source[] AUDIO_SOURCES = {
            AdSegment.Source.DETECTED, AdSegment.Source.CHAPTER};
    /** What an audio analysis with a transcript reading replaces. */
    private static final AdSegment.Source[] ALL_ANALYSED_SOURCES = {
            AdSegment.Source.DETECTED, AdSegment.Source.CHAPTER, AdSegment.Source.TRANSCRIPT,
            AdSegment.Source.SELF_PROMO};
    /** What a transcript reading replaces. */
    private static final AdSegment.Source[] TRANSCRIPT_SOURCES = {
            AdSegment.Source.TRANSCRIPT, AdSegment.Source.SELF_PROMO};

    @NonNull
    private static AdSegment.Source[] without(@NonNull AdSegment.Source[] sources,
                                              @NonNull AdSegment.Source dropped) {
        List<AdSegment.Source> kept = new ArrayList<>(sources.length);
        for (AdSegment.Source source : sources) {
            if (source != dropped) {
                kept.add(source);
            }
        }
        return kept.toArray(new AdSegment.Source[0]);
    }

    /** Stores the transcript's findings, trimmed where chapters overrule them. */
    private static boolean storeTranscriptSegments(@NonNull FeedItem item,
                                                   @NonNull TranscriptReading transcript,
                                                   @Nullable List<Chapter> chapters,
                                                   @NonNull FeedMedia media) {
        long durationMs = media.getDuration();
        List<AdSegment> merged = AdSegmentMerger.merge(null, transcript.getSegments(),
                ChapterAdMatcher.match(chapters, durationMs, item.getId()), null);
        return store(item, merged, TRANSCRIPT_SOURCES, media);
    }

    /** Replaces the episode's segments of each of {@code sources} with those in {@code segments}. */
    private static boolean store(@NonNull FeedItem item, @NonNull List<AdSegment> segments,
                                 @NonNull AdSegment.Source[] sources, @NonNull FeedMedia media) {
        try {
            for (AdSegment.Source source : sources) {
                DBWriter.replaceAdSegments(item.getId(), source, segmentsOf(segments, source)).get();
            }
            return true;
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while storing ad segments");
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            Log.e(TAG, "Could not store the ad segments of " + media.getEpisodeTitle(), e);
            return false;
        }
    }

    /**
     * Asks WorkManager to run the rest of this job inside its foreground service, with a quiet
     * progress notification. Best effort: on Android 12 and later the system refuses to start a
     * foreground service from a background app, and the analysis then simply runs as ordinary
     * background work with the limits that implies.
     */
    private void promoteToForeground(@NonNull FeedMedia media) {
        Context context = getApplicationContext();
        Notification notification = new NotificationCompat.Builder(context,
                NotificationUtils.CHANNEL_ID_DOWNLOADING)
                .setContentTitle(context.getString(R.string.ad_analysis_notification_title))
                .setContentText(media.getEpisodeTitle())
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true)
                .build();
        ForegroundInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Decoding audio is what mediaProcessing is for, and its daily time limit is separate
            // from dataSync's, so a long analysis cannot use up the time downloads need. When
            // the limit runs out, WorkManager stops this worker and isStopped() ends the run.
            info = new ForegroundInfo(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info = new ForegroundInfo(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            info = new ForegroundInfo(NOTIFICATION_ID, notification);
        }
        try {
            setForegroundAsync(info).get();
        } catch (ExecutionException | IllegalStateException e) {
            Log.w(TAG, "Could not run the ad analysis in the foreground; continuing anyway", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Queues an analysis for an episode that is starting to play: a downloaded one whose audio was
     * never analysed, or any episode with a transcript that the current settings want read and
     * that has not been read that way (a streamed episode, or a key added since), so that a library
     * from before ad skipping was switched on (globally or for its feed) still gets covered without
     * the user asking episode by episode. The first check uses the in-memory feed preferences of
     * {@code media}; the database check runs off the main thread. An episode analysed once, even
     * with nothing found, is not re-analysed by this path unless the settings now ask for more.
     *
     * @return the subscription doing the work, for the caller to own, or null when there was
     *         nothing to do.
     */
    @Nullable
    public static Disposable enqueueOnPlayback(@NonNull Context context,
                                               @Nullable FeedMedia media) {
        if (media == null || media.getId() <= 0 || !AdSkipUtils.isAdSkipEnabled(media)) {
            return null;
        }
        boolean audioPending = media.isDownloaded() && !Prefs.isAdAnalyzed(media.getId());
        if (!audioPending && !TranscriptAdAnalysis.needsReading(media.getItem(), media.getId())) {
            return null;
        }
        final Context applicationContext = context.getApplicationContext();
        final long mediaId = media.getId();
        return Completable.fromAction(() -> enqueue(applicationContext, mediaId, false))
                .subscribeOn(Schedulers.io())
                .subscribe(() -> { },
                        error -> Log.e(TAG, "Could not queue the ad analysis of media "
                                + mediaId, error));
    }

    /**
     * Stores the chapter-derived ad segments of one episode without decoding any audio.
     *
     * <p>This is the cheap half of detection and the only half a streamed episode can get. It
     * reads the chapters (from the item, or from the database when the item does not carry them,
     * merged with the Podcasting 2.0 chapter file, else the chapter list in the description) and
     * replaces the episode's {@link AdSegment.Source#CHAPTER} segments; detected and manual
     * segments are untouched. Both the database read and the write run on the caller's thread and
     * the shared database executor respectively, so call this off the main thread.
     */
    public static void applyChapterSegments(@Nullable FeedItem item) {
        if (item == null) {
            return;
        }
        storeChapterSegments(item, loadChapters(item));
    }

    private static void storeChapterSegments(@NonNull FeedItem item,
                                             @Nullable List<Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            // Nothing to go on here; the player stores what chapters it reads from the file.
            return;
        }
        long durationMs = item.getMedia() != null ? item.getMedia().getDuration() : 0;
        replaceChapterSegmentsIfChanged(item.getId(),
                ChapterAdMatcher.match(chapters, durationMs, item.getId()));
    }

    /**
     * Makes {@code wanted} the episode's {@link AdSegment.Source#CHAPTER} segments, unless they
     * already are (same starts and ends), so that playing an episode again does not rewrite its
     * rows and post a change. A segment the listener switched off stays off when the same range
     * comes back. Reads the database on the caller's thread: call it off the main thread.
     */
    public static void replaceChapterSegmentsIfChanged(long feedItemId,
                                                       @NonNull List<AdSegment> wanted) {
        List<AdSegment> current = new ArrayList<>();
        for (AdSegment segment : DBReader.loadAdSegmentsOfFeedItem(feedItemId)) {
            if (segment.getSource() == AdSegment.Source.CHAPTER) {
                current.add(segment);
            }
        }
        if (sameRanges(current, wanted)) {
            return;
        }
        for (AdSegment segment : wanted) {
            for (AdSegment old : current) {
                if (!old.isEnabled() && old.getStartMs() == segment.getStartMs()
                        && old.getEndMs() == segment.getEndMs()) {
                    segment.setEnabled(false);
                }
            }
        }
        DBWriter.replaceAdSegments(feedItemId, AdSegment.Source.CHAPTER, wanted);
    }

    /** True when both lists cover the same ranges, in any order. */
    static boolean sameRanges(@NonNull List<AdSegment> a, @NonNull List<AdSegment> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (AdSegment x : a) {
            boolean found = false;
            for (AdSegment y : b) {
                if (x.getStartMs() == y.getStartMs() && x.getEndMs() == y.getEndMs()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Queues an analysis run for one episode: the full analysis when it is downloaded, the
     * chapters and transcript only when it is not.
     *
     * <p>Unless {@code force} is set this first checks that ad skipping is on for the episode's
     * feed (its own choice, else the global default), which reads the episode from the database;
     * call it off the main thread in that case. A
     * forced request (the UI's "analyse this episode") skips both checks and replaces any run
     * already queued for the same media, so the user's tap is not swallowed by a pending job.
     */
    public static void enqueue(@NonNull Context context, long mediaId, boolean force) {
        if (!force && !shouldAnalyze(mediaId)) {
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AdAnalysisWorker.class)
                .setInputData(new Data.Builder().putLong(PARAM_MEDIA_ID, mediaId).build())
                .setConstraints(new Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build())
                .addTag(WORK_TAG)
                .build();
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(workName(mediaId),
                    force ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.KEEP, request);
        } catch (IllegalStateException e) {
            // WorkManager is not initialised: a unit test, or a process that never ran the
            // Application class. Ad analysis is optional, so this must not take the caller down.
            Log.e(TAG, "Could not enqueue the ad analysis of media " + mediaId, e);
        }
    }

    /** Cancels the analysis of one episode, if any is queued or running. */
    public static void cancel(@NonNull Context context, long mediaId) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(workName(mediaId));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Could not cancel the ad analysis of media " + mediaId, e);
        }
    }

    private static String workName(long mediaId) {
        return WORK_NAME_PREFIX + mediaId;
    }

    private static boolean shouldAnalyze(long mediaId) {
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        return media != null && AdSkipUtils.isAdSkipEnabled(media);
    }

    @Nullable
    private static List<Chapter> loadChapters(@NonNull FeedItem item) {
        long durationMs = item.getMedia() != null ? item.getMedia().getDuration() : 0;
        return ChapterUtils.loadChaptersWithoutMediaFile(item, durationMs);
    }

    @NonNull
    private static List<AdSegment> segmentsOf(@NonNull List<AdSegment> segments,
                                              @NonNull AdSegment.Source source) {
        List<AdSegment> out = new ArrayList<>();
        for (AdSegment segment : segments) {
            if (segment.getSource() == source) {
                out.add(segment);
            }
        }
        return out;
    }
}
