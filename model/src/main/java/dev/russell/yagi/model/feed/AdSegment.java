package dev.russell.yagi.model.feed;

import androidx.annotation.NonNull;

/**
 * A time range inside an episode that the app treats as an advertisement and skips during
 * playback. Segments come from four sources: the offline audio analysis run after a download
 * ({@link Source#DETECTED}), chapter titles that look like sponsor breaks ({@link Source#CHAPTER}),
 * a reading of the episode's published transcript ({@link Source#TRANSCRIPT}), or the user marking
 * a range by hand in the player ({@link Source#MANUAL}). Manual segments always have a confidence
 * of 1 and take precedence over overlapping detected ones. The transcript reading also finds the
 * show's own promotions ({@link Source#SELF_PROMO}), which are stored the same way but skipped
 * only on request.
 */
public class AdSegment {

    public enum Source {
        DETECTED(0), CHAPTER(1), MANUAL(2), TRANSCRIPT(3),
        /**
         * Not an ad: the show asking for support for itself (its membership, merchandise,
         * newsletter), found in the transcript. Skipped only when the user asks for it.
         */
        SELF_PROMO(4);

        private final int code;

        Source(int code) {
            this.code = code;
        }

        public int toInteger() {
            return code;
        }

        @NonNull
        public static Source fromInteger(int code) {
            for (Source source : values()) {
                if (source.code == code) {
                    return source;
                }
            }
            return DETECTED;
        }
    }

    private long id;
    private long feedItemId;
    private long startMs;
    private long endMs;
    @NonNull
    private Source source;
    private float confidence;
    private boolean enabled;

    public AdSegment(long feedItemId, long startMs, long endMs, @NonNull Source source, float confidence) {
        this(0, feedItemId, startMs, endMs, source, confidence, true);
    }

    public AdSegment(long id, long feedItemId, long startMs, long endMs, @NonNull Source source,
                     float confidence, boolean enabled) {
        this.id = id;
        this.feedItemId = feedItemId;
        this.startMs = startMs;
        this.endMs = endMs;
        this.source = source;
        this.confidence = confidence;
        this.enabled = enabled;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getFeedItemId() {
        return feedItemId;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public long getDurationMs() {
        return endMs - startMs;
    }

    @NonNull
    public Source getSource() {
        return source;
    }

    /** 0..1; how sure the detector is. Chapter and manual segments report 1. */
    public float getConfidence() {
        return confidence;
    }

    /** False when the user switched this segment off; it is then never skipped. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean contains(long positionMs) {
        return positionMs >= startMs && positionMs < endMs;
    }

    @NonNull
    @Override
    public String toString() {
        return "AdSegment{" + source + " " + startMs + "-" + endMs + " conf=" + confidence
                + (enabled ? "" : " disabled") + "}";
    }
}
