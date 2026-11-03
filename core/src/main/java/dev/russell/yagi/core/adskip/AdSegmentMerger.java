package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import dev.russell.yagi.model.feed.AdSegment;

/**
 * Reconciles ad segments coming from different sources into one sorted, non-overlapping list.
 *
 * <p>Four sources disagree in practice: the user's own marks, the publisher's chapter titles, the
 * reading of the transcript and the audio analysis. They are ranked MANUAL &gt; CHAPTER &gt;
 * TRANSCRIPT &gt; DETECTED, on the principle that a statement beats a label, a label beats what
 * was said, and what was said beats what it sounded like. The show's own promotions
 * (SELF_PROMO) come last: they are not ads, and only fill gaps.
 *
 * <p>Within one source, overlapping or touching segments are unioned and keep the highest
 * confidence. Across sources, a lower-ranked segment has the higher-ranked ranges *subtracted* from
 * it rather than being thrown away, so a two minute detected break that a one minute chapter
 * overlaps still contributes its remaining minute. Remnants shorter than
 * {@link #MIN_REMNANT_MS} are dropped as noise.
 */
public final class AdSegmentMerger {

    /** Shortest piece worth keeping after a higher-ranked segment has been subtracted. */
    public static final long MIN_REMNANT_MS = 1000;

    private AdSegmentMerger() {
    }

    /**
     * Convenience overload for the usual three inputs; any argument may be null or empty.
     */
    @NonNull
    public static List<AdSegment> merge(@Nullable List<AdSegment> detected,
                                        @Nullable List<AdSegment> chapter,
                                        @Nullable List<AdSegment> manual) {
        return merge(detected, null, chapter, manual);
    }

    /** Convenience overload with transcript segments; any argument may be null or empty. */
    @NonNull
    public static List<AdSegment> merge(@Nullable List<AdSegment> detected,
                                        @Nullable List<AdSegment> transcript,
                                        @Nullable List<AdSegment> chapter,
                                        @Nullable List<AdSegment> manual) {
        List<AdSegment> all = new ArrayList<>();
        if (detected != null) {
            all.addAll(detected);
        }
        if (transcript != null) {
            all.addAll(transcript);
        }
        if (chapter != null) {
            all.addAll(chapter);
        }
        if (manual != null) {
            all.addAll(manual);
        }
        return merge(all);
    }

    /**
     * Merges a mixed list.
     *
     * @return a new list sorted by start time with no two segments overlapping. Input segments are
     *         never mutated; trimmed ones are replaced by new instances that keep the original
     *         source, confidence, enabled flag and database id.
     */
    @NonNull
    public static List<AdSegment> merge(@Nullable List<AdSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        List<AdSegment> accepted = new ArrayList<>();
        // Highest rank first, so everything already accepted outranks what comes next.
        for (AdSegment.Source source : new AdSegment.Source[]{
                AdSegment.Source.MANUAL, AdSegment.Source.CHAPTER, AdSegment.Source.TRANSCRIPT,
                AdSegment.Source.DETECTED, AdSegment.Source.SELF_PROMO}) {
            List<AdSegment> tier = unionWithinSource(segments, source);
            for (AdSegment segment : tier) {
                accepted.addAll(subtract(segment, accepted));
            }
        }
        Collections.sort(accepted, new Comparator<AdSegment>() {
            @Override
            public int compare(AdSegment a, AdSegment b) {
                return Long.compare(a.getStartMs(), b.getStartMs());
            }
        });
        return accepted;
    }

    /** Unions overlapping or touching segments of one source, keeping the highest confidence. */
    private static List<AdSegment> unionWithinSource(List<AdSegment> segments,
                                                     AdSegment.Source source) {
        List<AdSegment> tier = new ArrayList<>();
        for (AdSegment segment : segments) {
            if (segment != null && segment.getSource() == source
                    && segment.getEndMs() > segment.getStartMs()) {
                tier.add(segment);
            }
        }
        Collections.sort(tier, new Comparator<AdSegment>() {
            @Override
            public int compare(AdSegment a, AdSegment b) {
                return Long.compare(a.getStartMs(), b.getStartMs());
            }
        });
        List<AdSegment> out = new ArrayList<>();
        for (AdSegment segment : tier) {
            if (out.isEmpty()) {
                out.add(segment);
                continue;
            }
            AdSegment last = out.get(out.size() - 1);
            if (segment.getStartMs() <= last.getEndMs()) {
                out.set(out.size() - 1, new AdSegment(
                        last.getId(), last.getFeedItemId(), last.getStartMs(),
                        Math.max(last.getEndMs(), segment.getEndMs()), source,
                        Math.max(last.getConfidence(), segment.getConfidence()),
                        last.isEnabled() || segment.isEnabled()));
            } else {
                out.add(segment);
            }
        }
        return out;
    }

    /** Returns the parts of {@code segment} not covered by any already accepted segment. */
    private static List<AdSegment> subtract(AdSegment segment, List<AdSegment> accepted) {
        List<long[]> pieces = new ArrayList<>();
        pieces.add(new long[]{segment.getStartMs(), segment.getEndMs()});
        for (AdSegment blocker : accepted) {
            List<long[]> next = new ArrayList<>(pieces.size() + 1);
            for (long[] piece : pieces) {
                long start = piece[0];
                long end = piece[1];
                if (blocker.getEndMs() <= start || blocker.getStartMs() >= end) {
                    next.add(piece);
                    continue;
                }
                if (blocker.getStartMs() > start) {
                    next.add(new long[]{start, blocker.getStartMs()});
                }
                if (blocker.getEndMs() < end) {
                    next.add(new long[]{blocker.getEndMs(), end});
                }
            }
            pieces = next;
            if (pieces.isEmpty()) {
                break;
            }
        }
        List<AdSegment> out = new ArrayList<>(pieces.size());
        for (long[] piece : pieces) {
            if (piece[1] - piece[0] < MIN_REMNANT_MS) {
                continue;
            }
            if (piece[0] == segment.getStartMs() && piece[1] == segment.getEndMs()) {
                out.add(segment);
            } else {
                out.add(new AdSegment(segment.getId(), segment.getFeedItemId(), piece[0], piece[1],
                        segment.getSource(), segment.getConfidence(), segment.isEnabled()));
            }
        }
        return out;
    }
}
