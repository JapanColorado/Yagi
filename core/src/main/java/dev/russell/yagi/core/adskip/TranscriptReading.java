package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import dev.russell.yagi.model.feed.AdSegment;

/**
 * What reading one episode's transcript produced: the ads and self-promotions found, the
 * stretch of the episode the transcript covers, and how it was read, which decides how much the
 * audio analysis is overruled ({@link TranscriptAdFusion}).
 */
public final class TranscriptReading {

    /** Read by Claude with the user's API key. */
    public static final String METHOD_CLAUDE = "claude";
    /** Read on the phone by {@link TranscriptAdHeuristic}. */
    public static final String METHOD_OFFLINE = "offline";
    /** The episode has no transcript that can place an ad (none, untimed, or another cut). */
    public static final String METHOD_NONE = "none";

    @NonNull
    private final String method;
    @NonNull
    private final List<AdSegment> segments;
    @Nullable
    private final long[] span;

    public TranscriptReading(@NonNull String method, @NonNull List<AdSegment> segments,
                             @Nullable long[] span) {
        this.method = method;
        this.segments = Collections.unmodifiableList(segments);
        this.span = span;
    }

    /** No usable transcript: nothing found, and the audio analysis stands alone. */
    @NonNull
    public static TranscriptReading none() {
        return new TranscriptReading(METHOD_NONE, Collections.<AdSegment>emptyList(), null);
    }

    @NonNull
    public String getMethod() {
        return method;
    }

    /** {@link AdSegment.Source#TRANSCRIPT} and {@link AdSegment.Source#SELF_PROMO} segments. */
    @NonNull
    public List<AdSegment> getSegments() {
        return segments;
    }

    /** {start, end} of the transcript in ms, or null when there was none to read. */
    @Nullable
    public long[] getSpan() {
        return span == null ? null : span.clone();
    }

    /** How the audio analysis is combined with this reading. */
    @NonNull
    public TranscriptAdFusion.Config fusionConfig() {
        return METHOD_CLAUDE.equals(method)
                ? TranscriptAdFusion.Config.claude()
                : TranscriptAdFusion.Config.offline();
    }

    /**
     * Whether an episode read with {@code done} needs no new reading when {@code wanted} is the
     * method the settings ask for now: a missing transcript stays missing, and Claude's reading
     * is at least as good as the phone's.
     */
    public static boolean satisfies(@Nullable String done, @Nullable String wanted) {
        if (wanted == null) {
            return true;
        }
        if (done == null) {
            return false;
        }
        return done.equals(wanted) || METHOD_NONE.equals(done)
                || (METHOD_OFFLINE.equals(wanted) && METHOD_CLAUDE.equals(done));
    }
}
