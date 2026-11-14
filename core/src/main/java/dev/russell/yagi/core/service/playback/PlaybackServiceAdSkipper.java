package dev.russell.yagi.core.service.playback;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.russell.yagi.core.adskip.AdAnalysisWorker;
import dev.russell.yagi.core.adskip.ChapterAdMatcher;
import dev.russell.yagi.core.feed.util.AdSkipUtils;
import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.storage.DBReader;
import dev.russell.yagi.event.adskip.AdSkipUndoEvent;
import dev.russell.yagi.event.adskip.AdSkippedEvent;
import dev.russell.yagi.model.feed.AdSegment;
import dev.russell.yagi.model.feed.FeedItem;
import dev.russell.yagi.model.feed.FeedMedia;
import dev.russell.yagi.model.feed.FeedPreferences;
import dev.russell.yagi.model.playback.Playable;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Skips the stored ad segments of the episode {@link PlaybackService} is playing.
 *
 * <p>It owns the segments of the current episode (loaded off the main thread whenever the
 * playable changes or the stored segments change), the set of segments that must not be skipped
 * again during this playback, and the decision the one-second ticker asks it for every second.
 *
 * <p>Skips leave a {@link #SKIP_BUFFER_MS} margin at both ends: the skip happens that far into
 * the segment and lands that far before its end, so a boundary that is a few seconds off (a
 * description timestamp, dynamically inserted ads, the detector's estimate) costs a little ad
 * rather than a little show. A segment too short to leave {@link #MIN_SKIP_MS} between the two
 * margins plays.
 *
 * <p>Two rules keep the feature from fighting the listener. A segment is only skipped when
 * playback <em>ran into</em> it, that is within {@link #ENTRY_WINDOW_MS} (scaled by the playback
 * speed) of the point where its skip is due; seeking into the middle of a segment is taken as
 * "I want to hear this" and suppresses it instead. And a skipped segment is suppressed
 * immediately, so undoing a skip cannot be undone again by the next tick.
 *
 * <p>It also keeps the episode's chapter ad segments in step with the chapters the service
 * loaded (the feed's, the file's, the Podcasting 2.0 chapter file's or the description's), so
 * labelled breaks are skipped in streamed episodes and episodes analysed before their chapters
 * were known.
 *
 * <p>This class never shows UI. It posts {@link AdSkippedEvent} and the app layer decides whether
 * to show a snackbar with an Undo action.
 */
class PlaybackServiceAdSkipper {
    private static final String TAG = "PlaybackService";

    /**
     * How far past the point where a segment's skip is due playback may be and still count as
     * having just run into it. Scaled by the playback speed because the ticker runs in wall-clock
     * seconds while positions advance in media time.
     */
    @VisibleForTesting
    static final long ENTRY_WINDOW_MS = 3000;

    /**
     * How much of each end of a segment is played: the skip happens this far into it and lands
     * this far before its end. In media time.
     */
    @VisibleForTesting
    static final long SKIP_BUFFER_MS = 3000;

    /** A jump shorter than this is not worth doing; such a segment simply plays. */
    @VisibleForTesting
    static final long MIN_SKIP_MS = 3000;

    /** Never seek to within this of the end of the episode; that is what skipping is for. */
    private static final long END_GUARD_MS = 1000;

    private final PlaybackService service;

    /** The segments of {@link #segmentsItemId}, earliest first. Never null. */
    @NonNull
    private List<AdSegment> segments = Collections.emptyList();

    /** The episode {@link #segments} belongs to, or 0 when nothing is loaded. */
    private long segmentsItemId = 0;

    /** Ids of segments that must not be skipped again while this episode plays. */
    private final Set<Long> suppressed = new HashSet<>();

    @Nullable
    private Disposable segmentLoader;

    @Nullable
    private Disposable chapterSegmentWriter;

    PlaybackServiceAdSkipper(PlaybackService service) {
        this.service = service;
    }

    /**
     * Loads the segments of a newly selected playable. Repeated calls for the episode that is
     * already loaded do nothing, so resuming playback does not forget which segments the user
     * asked to keep.
     */
    void onMediaChanged(@Nullable Playable playable) {
        long itemId = itemIdOf(playable);
        if (itemId == 0) {
            reset();
            return;
        }
        if (itemId == segmentsItemId) {
            return;
        }
        reset();
        segmentsItemId = itemId;
        load(itemId);
        enqueueAnalysisIfNeeded(playable);
        onChaptersLoaded(playable);
    }

    /**
     * Stores the ad segments the playable's loaded chapters call for (chapters titled "Sponsor",
     * "Ad break" and so on), replacing the episode's chapter segments when they differ. Does
     * nothing until the chapters are loaded, or when ads are off for the episode.
     */
    void onChaptersLoaded(@Nullable Playable playable) {
        long itemId = itemIdOf(playable);
        if (playable == null || itemId == 0 || playable.getChapters() == null
                || playable.getChapters().isEmpty() || !AdSkipUtils.isAdSkipEnabled(playable)) {
            return;
        }
        List<AdSegment> wanted = ChapterAdMatcher.match(new ArrayList<>(playable.getChapters()),
                playable.getDuration(), itemId);
        if (chapterSegmentWriter != null) {
            chapterSegmentWriter.dispose();
        }
        chapterSegmentWriter = Completable
                .fromAction(() -> AdAnalysisWorker.replaceChapterSegmentsIfChanged(itemId, wanted))
                .subscribeOn(Schedulers.io())
                .subscribe(() -> { }, error -> Log.e(TAG,
                        "Could not store the chapter ad segments of episode " + itemId, error));
        service.addServiceDisposable(chapterSegmentWriter);
    }

    /**
     * A downloaded episode that predates the feature (or slipped past the download hook, or whose
     * feed only just had detection switched on) is analysed while it plays; results arrive through
     * AdSegmentsChangedEvent. Does nothing when ads are off for the episode or it was analysed.
     */
    private void enqueueAnalysisIfNeeded(@Nullable Playable playable) {
        if (!(playable instanceof FeedMedia)) {
            return;
        }
        Disposable analysis = AdAnalysisWorker.enqueueOnPlayback(service, (FeedMedia) playable);
        if (analysis != null) {
            service.addServiceDisposable(analysis);
        }
    }

    /** Re-reads the segments of the current episode after they changed in the database. */
    void onSegmentsChanged(long feedItemId) {
        if (feedItemId != 0 && feedItemId == segmentsItemId) {
            load(feedItemId);
        }
    }

    /**
     * Seeks back to where the skip started. The segment stays suppressed (it was suppressed
     * before the skip was posted), so playback is not thrown forward again a second later.
     */
    void onUndo(@NonNull AdSkipUndoEvent event) {
        if (event.getFeedItemId() != segmentsItemId) {
            return;
        }
        suppressed.add(event.getSegmentId());
        service.seekTo((int) event.getFromMs());
    }

    /**
     * Re-evaluates ad skipping for the playing episode after a setting changed. A feed's change
     * refreshes the in-memory override of the playing feed from the database (the event only
     * says which feed changed; reading storage keeps this from drifting from what was stored).
     * Either way, when ads are now on for the episode and it is downloaded but never analysed,
     * the analysis is queued exactly as on first play.
     *
     * @param feedId the feed whose override changed, or 0 when the global default changed
     */
    void onAdSkipSettingChanged(long feedId) {
        Playable playable = service.getPlayable();
        if (feedId == 0) {
            // skipIfNecessary resolves the default on every tick; only the analysis may be due.
            enqueueAnalysisIfNeeded(playable);
            onChaptersLoaded(playable);
            return;
        }
        FeedPreferences preferences = feedPreferencesOf(playable);
        if (preferences == null || feedIdOf(playable) != feedId) {
            return;
        }
        service.addServiceDisposable(Single.fromCallable(() -> {
                    FeedPreferences stored = DBReader.getFeedPreferences(feedId);
                    // Single cannot emit null, so the nullable override travels in a one-element array.
                    return new Boolean[]{stored != null ? stored.getAdSkipOverride()
                            : preferences.getAdSkipOverride()};
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(override -> {
                            preferences.setAdSkipOverride(override[0]);
                            if (playable == service.getPlayable()) {
                                enqueueAnalysisIfNeeded(playable);
                                onChaptersLoaded(playable);
                            }
                        },
                        error -> Log.e(TAG, "Could not reload the ad-skip setting of feed "
                                + feedId, error)));
    }

    /**
     * Called once a second from the position observer: skips the segment playback just ran into,
     * if any.
     */
    void skipIfNecessary() {
        Playable playable = service.getPlayable();
        long itemId = itemIdOf(playable);
        if (itemId == 0 || itemId != segmentsItemId || segments.isEmpty()) {
            return;
        }
        if (!AdSkipUtils.isAdSkipEnabled(playable)) {
            return;
        }

        int position = service.getCurrentPosition();
        int duration = service.getDuration();
        if (position == PlaybackService.INVALID_TIME) {
            return;
        }
        AdSegment segment = findSegmentToSkip(segments, suppressed, position,
                service.getCurrentPlaybackSpeed(), Prefs.getAdSkipMinConfidence());
        if (segment == null) {
            return;
        }
        suppressed.add(segment.getId());

        if (runsToEndOfEpisode(segment, duration)) {
            Log.d(TAG, "Ad segment runs to the end of " + playable.getEpisodeTitle()
                    + ", skipping the episode");
            service.autoSkipper.markAutoSkipped(itemOf(playable));
            EventBus.getDefault().post(
                    new AdSkippedEvent(itemId, segment.getId(), position, segment.getEndMs()));
            service.mediaPlayer.skip();
            return;
        }

        long target = skipTarget(segment, duration);
        if (target - position < MIN_SKIP_MS) {
            return;
        }
        Log.d(TAG, "Skipping ad segment " + segment + " to " + target);
        service.seekTo((int) target);
        EventBus.getDefault().post(
                new AdSkippedEvent(itemId, segment.getId(), position, target));
    }

    /**
     * True while playback is inside an enabled segment of the playing episode that the "skip ad"
     * button can skip. Unlike the automatic skip this ignores where playback entered the segment
     * and whether it was suppressed: the button is the listener asking for it.
     */
    boolean isInSkippableSegment() {
        return currentSkippableSegment() != null;
    }

    /**
     * Skips the segment playback is in, exactly as the automatic skip would (including the Undo
     * offer). Does nothing outside a segment.
     */
    void skipCurrentSegment() {
        AdSegment segment = currentSkippableSegment();
        Playable playable = service.getPlayable();
        if (segment == null || playable == null) {
            return;
        }
        int position = service.getCurrentPosition();
        int duration = service.getDuration();
        suppressed.add(segment.getId());
        if (runsToEndOfEpisode(segment, duration)) {
            service.autoSkipper.markAutoSkipped(itemOf(playable));
            EventBus.getDefault().post(
                    new AdSkippedEvent(segmentsItemId, segment.getId(), position, segment.getEndMs()));
            service.mediaPlayer.skip();
            return;
        }
        long target = manualSkipTarget(segment, position, duration);
        if (target <= position) {
            return;
        }
        Log.d(TAG, "Skipping ad segment " + segment + " on request");
        service.seekTo((int) target);
        EventBus.getDefault().post(new AdSkippedEvent(segmentsItemId, segment.getId(), position, target));
    }

    @Nullable
    private AdSegment currentSkippableSegment() {
        Playable playable = service.getPlayable();
        long itemId = itemIdOf(playable);
        if (itemId == 0 || itemId != segmentsItemId || segments.isEmpty()
                || !AdSkipUtils.isAdSkipEnabled(playable)) {
            return null;
        }
        int position = service.getCurrentPosition();
        if (position == PlaybackService.INVALID_TIME) {
            return null;
        }
        return findSegmentAt(segments, position, Prefs.getAdSkipMinConfidence());
    }

    /**
     * The enabled segment containing {@code positionMs} that is confident enough to count as an
     * ad, or null. Suppression and the entry window do not matter here.
     */
    @Nullable
    @VisibleForTesting
    static AdSegment findSegmentAt(@NonNull List<AdSegment> segments, long positionMs,
                                   float minConfidence) {
        for (AdSegment segment : segments) {
            if (!segment.isEnabled() || !segment.contains(positionMs)) {
                continue;
            }
            if (!AdSkipUtils.meetsThreshold(segment, minConfidence)) {
                continue;
            }
            return segment;
        }
        return null;
    }

    /**
     * The whole decision of {@link #skipIfNecessary()}, without any player or database access.
     *
     * @param segments      the episode's segments
     * @param suppressed    ids that are never skipped again; a segment the listener seeked into
     *                      the middle of is <em>added</em> here and then left alone
     * @param positionMs    the current playback position
     * @param speed         the current playback speed; values that are not positive read as 1
     * @param minConfidence the confidence a detected segment needs, from the sensitivity setting
     * @return the segment to skip, or null when nothing should happen
     */
    @Nullable
    @VisibleForTesting
    static AdSegment findSegmentToSkip(@NonNull List<AdSegment> segments,
                                       @NonNull Set<Long> suppressed, long positionMs,
                                       float speed, float minConfidence) {
        for (AdSegment segment : segments) {
            if (!segment.isEnabled() || suppressed.contains(segment.getId())) {
                continue;
            }
            if (!segment.contains(positionMs)) {
                continue;
            }
            // Detected and transcript segments are guesses; chapter and manual ones are exact.
            if (!AdSkipUtils.meetsThreshold(segment, minConfidence)) {
                continue;
            }
            if (!isLongEnoughToSkip(segment)) {
                continue;
            }
            long skipFrom = segment.getStartMs() + SKIP_BUFFER_MS;
            if (positionMs < skipFrom) {
                // Still in the lead-in that is played on purpose; the skip comes once it is over.
                return null;
            }
            float effectiveSpeed = speed > 0 ? speed : 1f;
            long window = (long) (ENTRY_WINDOW_MS * effectiveSpeed);
            if (positionMs - skipFrom <= window) {
                return segment;
            }
            // The listener is in the middle of this segment without having played into it, so
            // they seeked here on purpose (or undid a skip). Let it play, this time.
            suppressed.add(segment.getId());
            return null;
        }
        return null;
    }

    /** True when the segment leaves at least {@link #MIN_SKIP_MS} between its two margins. */
    @VisibleForTesting
    static boolean isLongEnoughToSkip(@NonNull AdSegment segment) {
        return segment.getDurationMs() - 2 * SKIP_BUFFER_MS >= MIN_SKIP_MS;
    }

    /**
     * Where to seek to in order to leave a segment behind: {@link #SKIP_BUFFER_MS} before its end,
     * and never past the very end of the episode.
     */
    @VisibleForTesting
    static long skipTarget(@NonNull AdSegment segment, long durationMs) {
        return clampToEpisode(segment.getEndMs() - SKIP_BUFFER_MS, durationMs);
    }

    /**
     * Where the "skip ad" button lands: like {@link #skipTarget}, except that inside the closing
     * margin (or in a segment shorter than both margins) it goes to the segment's end, because
     * the listener asked to get past the ad.
     */
    @VisibleForTesting
    static long manualSkipTarget(@NonNull AdSegment segment, long positionMs, long durationMs) {
        long target = segment.getEndMs() - SKIP_BUFFER_MS;
        if (target <= positionMs) {
            target = segment.getEndMs();
        }
        return clampToEpisode(target, durationMs);
    }

    private static long clampToEpisode(long target, long durationMs) {
        return durationMs > 0 ? Math.min(target, durationMs - END_GUARD_MS) : target;
    }

    /** True when nothing but the tail of the episode is left after the segment. */
    @VisibleForTesting
    static boolean runsToEndOfEpisode(@NonNull AdSegment segment, long durationMs) {
        return durationMs > 0 && segment.getEndMs() >= durationMs - END_GUARD_MS;
    }

    private void load(long itemId) {
        if (segmentLoader != null) {
            segmentLoader.dispose();
        }
        segmentLoader = Single.fromCallable(() -> DBReader.loadAdSegmentsOfFeedItem(itemId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(loaded -> {
                    if (itemId == segmentsItemId) {
                        segments = loaded;
                        service.customActions.refreshIfChanged();
                    }
                }, error -> Log.e(TAG, "Could not load the ad segments of episode " + itemId, error));
        service.addServiceDisposable(segmentLoader);
    }

    private void reset() {
        if (segmentLoader != null) {
            segmentLoader.dispose();
            segmentLoader = null;
        }
        if (chapterSegmentWriter != null) {
            chapterSegmentWriter.dispose();
            chapterSegmentWriter = null;
        }
        segments = Collections.emptyList();
        segmentsItemId = 0;
        suppressed.clear();
    }

    @Nullable
    private static FeedItem itemOf(@Nullable Playable playable) {
        if (!(playable instanceof FeedMedia)) {
            return null;
        }
        return ((FeedMedia) playable).getItem();
    }

    /** The id of the episode behind a playable, or 0 when it is not a known episode. */
    private static long itemIdOf(@Nullable Playable playable) {
        FeedItem item = itemOf(playable);
        return item != null ? item.getId() : 0;
    }

    private static long feedIdOf(@Nullable Playable playable) {
        FeedItem item = itemOf(playable);
        return item != null && item.getFeed() != null ? item.getFeed().getId() : 0;
    }

    @Nullable
    private static FeedPreferences feedPreferencesOf(@Nullable Playable playable) {
        FeedItem item = itemOf(playable);
        if (item == null || item.getFeed() == null) {
            return null;
        }
        return item.getFeed().getPreferences();
    }
}
