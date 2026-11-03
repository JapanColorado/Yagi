package dev.russell.yagi.core.feed.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.storage.DBWriter;
import dev.russell.yagi.event.settings.AdSkipChangedEvent;
import dev.russell.yagi.model.feed.AdSegment;
import dev.russell.yagi.model.feed.Feed;
import dev.russell.yagi.model.feed.FeedItem;
import dev.russell.yagi.model.feed.FeedMedia;
import dev.russell.yagi.model.feed.FeedPreferences;
import dev.russell.yagi.model.playback.Playable;

/**
 * Resolves whether ad detection and skipping is on for a feed, and changes it for one feed.
 *
 * <p>The global preference ({@link Prefs#isAdSkipEnabled()}) is only the default. A feed follows
 * it until the user makes a choice for that feed, which is stored as
 * {@link FeedPreferences#getAdSkipOverride()}; this class is the one place that combines the two,
 * so every gate (analysis, skipping, drawing segments) agrees.
 */
public final class AdSkipUtils {

    private AdSkipUtils() {
    }

    /** The feed's own choice if it has one, otherwise the global default. */
    public static boolean isAdSkipEnabled(@Nullable FeedPreferences preferences) {
        Boolean override = preferences != null ? preferences.getAdSkipOverride() : null;
        return override != null ? override : Prefs.isAdSkipEnabled();
    }

    /**
     * Whether ads are detected and skipped for this media. Anything that is not an episode of a
     * feed with loaded preferences follows the global default.
     */
    public static boolean isAdSkipEnabled(@Nullable Playable media) {
        return isAdSkipEnabled(preferencesOf(media));
    }

    /**
     * Whether a segment is confident enough to count as an ad. Detected and transcript segments
     * are guesses and need {@code minConfidence}; chapter and manual ones are exact and always
     * count. The show's own promotions count only when the user chose to skip them
     * ({@link Prefs#isAdSkipSelfPromo()}), and then like any other guess. Whether the segment is
     * enabled does not matter here.
     */
    public static boolean meetsThreshold(@NonNull AdSegment segment, float minConfidence) {
        boolean skipSelfPromo = segment.getSource() == AdSegment.Source.SELF_PROMO
                && Prefs.isAdSkipSelfPromo();
        return meetsThreshold(segment, minConfidence, skipSelfPromo);
    }

    /** {@link #meetsThreshold(AdSegment, float)} with the self-promotion setting passed in. */
    public static boolean meetsThreshold(@NonNull AdSegment segment, float minConfidence,
                                         boolean skipSelfPromo) {
        switch (segment.getSource()) {
            case CHAPTER:
            case MANUAL:
                return true;
            case SELF_PROMO:
                return skipSelfPromo && segment.getConfidence() >= minConfidence;
            case DETECTED:
            case TRANSCRIPT:
            default:
                return segment.getConfidence() >= minConfidence;
        }
    }

    /**
     * The segments that clear the current sensitivity setting ({@link Prefs#getAdSkipMinConfidence()}),
     * i.e. the ones that would be skipped if enabled. Weaker detections stay in the database so a
     * change of sensitivity needs no re-analysis.
     */
    @NonNull
    public static List<AdSegment> segmentsMeetingThreshold(@NonNull List<AdSegment> segments) {
        float minConfidence = Prefs.getAdSkipMinConfidence();
        List<AdSegment> result = new ArrayList<>(segments.size());
        for (AdSegment segment : segments) {
            if (meetsThreshold(segment, minConfidence)) {
                result.add(segment);
            }
        }
        return result;
    }

    /**
     * Stores an explicit choice for one feed, even when it equals the current default: the user
     * picked it for this feed, so a later change of the default must not move it. Posts
     * {@link AdSkipChangedEvent} once the change is in the database.
     */
    public static void setAdSkipForFeed(@NonNull Feed feed, boolean enabled) {
        store(feed, enabled);
    }

    /** Makes the feed follow the global default again. Posts {@link AdSkipChangedEvent}. */
    public static void resetAdSkipForFeed(@NonNull Feed feed) {
        store(feed, null);
    }

    /**
     * "Set as default": makes the feed's own choice the global default, posts
     * {@link AdSkipChangedEvent} for the default (feed id 0), and makes the feed follow the
     * default again. Does nothing when the feed has no choice of its own.
     */
    public static void makeDefault(@NonNull Feed feed) {
        FeedPreferences preferences = feed.getPreferences();
        Boolean override = preferences != null ? preferences.getAdSkipOverride() : null;
        if (override == null) {
            return;
        }
        Prefs.setAdSkipEnabled(override);
        EventBus.getDefault().post(new AdSkipChangedEvent(0));
        resetAdSkipForFeed(feed);
    }

    private static void store(@NonNull Feed feed, @Nullable Boolean override) {
        FeedPreferences preferences = feed.getPreferences();
        if (preferences == null) {
            return;
        }
        preferences.setAdSkipOverride(override);
        // Only this field is written: the caller's object may be older than the stored row.
        DBWriter.updateFeedPreferences(feed.getId(), stored -> stored.setAdSkipOverride(override),
                new AdSkipChangedEvent(feed.getId()));
    }

    @Nullable
    private static FeedPreferences preferencesOf(@Nullable Playable media) {
        if (!(media instanceof FeedMedia)) {
            return null;
        }
        FeedItem item = ((FeedMedia) media).getItem();
        Feed feed = item != null ? item.getFeed() : null;
        return feed != null ? feed.getPreferences() : null;
    }
}
