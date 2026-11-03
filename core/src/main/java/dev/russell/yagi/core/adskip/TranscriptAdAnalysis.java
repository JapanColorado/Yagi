package dev.russell.yagi.core.adskip;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.service.download.PodcastHttpClient;
import dev.russell.yagi.core.util.TranscriptUtils;
import dev.russell.yagi.model.feed.AdSegment;
import dev.russell.yagi.model.feed.Feed;
import dev.russell.yagi.model.feed.FeedItem;
import dev.russell.yagi.model.transcript.Transcript;

/**
 * Reads an episode's published transcript for ads, the way the settings ask: by Claude when the
 * user has stored an Anthropic API key, otherwise on the phone. A failed call to Claude falls back
 * to the phone, so a busy service or an expired key never leaves an episode unread.
 */
public final class TranscriptAdAnalysis {

    private static final String TAG = "TranscriptAdAnalysis";

    private TranscriptAdAnalysis() {
    }

    /**
     * The reading method the settings ask for now, or null when transcripts are not to be read.
     */
    @Nullable
    public static String wantedMethod() {
        if (!Prefs.isAdSkipUseTranscripts()) {
            return null;
        }
        return Prefs.hasClaudeApiKey() ? TranscriptReading.METHOD_CLAUDE
                : TranscriptReading.METHOD_OFFLINE;
    }

    /**
     * Whether {@code item} has a transcript the current settings want read that has not been read
     * that way yet. Reads only preferences and the item, so it is cheap enough for playback start.
     */
    public static boolean needsReading(@Nullable FeedItem item, long mediaId) {
        if (item == null || !item.hasTranscript()) {
            return false;
        }
        return !TranscriptReading.satisfies(Prefs.getTranscriptAdMethod(mediaId), wantedMethod());
    }

    /**
     * Reads the transcript of {@code item}. Blocks on the network.
     *
     * @return the reading; {@link TranscriptReading#none()} when the episode has no usable
     *         transcript; null when transcripts are switched off, or the transcript could not be
     *         fetched (so that a later run tries again)
     */
    @WorkerThread
    @Nullable
    public static TranscriptReading read(@NonNull FeedItem item, long durationMs) {
        String wanted = wantedMethod();
        if (wanted == null) {
            return null;
        }
        if (!item.hasTranscript()) {
            return TranscriptReading.none();
        }
        Transcript transcript;
        try {
            transcript = TranscriptUtils.load(item);
        } catch (IOException e) {
            Log.w(TAG, "Could not load the transcript of " + item.getTitle() + ": " + e.getMessage());
            return null;
        }
        List<TranscriptLine> lines = TranscriptLines.of(transcript);
        if (lines.isEmpty()) {
            return TranscriptReading.none();
        }
        if (!TranscriptLines.fitsEpisode(lines, durationMs)) {
            Log.i(TAG, "The transcript of " + item.getTitle() + " does not match the audio's length;"
                    + " not reading it for ads");
            return TranscriptReading.none();
        }
        long[] span = TranscriptLines.span(lines);
        Feed feed = item.getFeed();
        String podcastTitle = feed != null ? feed.getTitle() : null;

        String key = Prefs.getClaudeApiKey();
        if (TranscriptReading.METHOD_CLAUDE.equals(wanted) && key != null) {
            try {
                ClaudeAdClient client = new ClaudeAdClient(PodcastHttpClient.getHttpClient(), key);
                List<AdSegment> found = client.findAds(lines, podcastTitle, item.getTitle(),
                        item.getId());
                Log.i(TAG, "Claude found " + found.size() + " segments in " + item.getTitle()
                        + " (" + client.getInputTokens() + " input, " + client.getOutputTokens()
                        + " output tokens)");
                return new TranscriptReading(TranscriptReading.METHOD_CLAUDE, found, span);
            } catch (ClaudeException e) {
                Log.w(TAG, "Claude could not read the transcript of " + item.getTitle()
                        + (e.isKeyRejected() ? " (the API key was rejected)" : "")
                        + "; reading it on the phone instead: " + e.getMessage());
            } catch (RuntimeException e) {
                // An answer in a shape nobody foresaw must not cost the episode its reading.
                Log.e(TAG, "Unexpected failure reading the transcript of " + item.getTitle()
                        + " with Claude; reading it on the phone instead", e);
            }
        }
        Set<String> selfNames = TranscriptAdHeuristic.selfNames(podcastTitle,
                feed != null ? hostOf(feed.getLink()) : null);
        List<AdSegment> found = new TranscriptAdHeuristic().detect(lines, selfNames, item.getId());
        return new TranscriptReading(TranscriptReading.METHOD_OFFLINE, found, span);
    }

    @Nullable
    private static String hostOf(@Nullable String link) {
        if (link == null || link.isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(link).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
