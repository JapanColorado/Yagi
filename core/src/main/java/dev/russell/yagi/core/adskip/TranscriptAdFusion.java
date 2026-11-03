package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import dev.russell.yagi.model.feed.AdSegment;

/**
 * Combines what the transcript says with what the audio sounds like.
 *
 * <p>The two detectors fail differently. The audio detector hears a music bed: it misses ads read
 * over silence and fires on theme music. The transcript detectors read the words: they see every
 * sponsor read, but only where the publisher's transcript exists and is aligned with the file. So:
 *
 * <ul>
 * <li>A transcript ad that the audio also flagged is more certain, and keeps the transcript's
 *     edges, which follow the words rather than a smoothed bed level.</li>
 * <li>An audio detection inside the stretch the transcript covers, with no transcript ad near it,
 *     is most likely music that is part of the show, and loses confidence. How much depends on how
 *     well the transcript was read ({@link Config#claude} or {@link Config#offline}).</li>
 * <li>An audio detection outside the transcript (an ad stitched in after the transcript was made,
 *     often before the first or after the last line) is left as it is.</li>
 * <li>An audio detection over the show's own promotion ({@link AdSegment.Source#SELF_PROMO}) is
 *     treated like one the transcript does not support: the transcript says what it is, and it
 *     is not an ad.</li>
 * </ul>
 *
 * <p>The audio detection overlapping a transcript ad is dropped rather than left to
 * {@link AdSegmentMerger}'s subtraction, whose remnants at either side of the transcript ad would
 * mostly be the bed fading in and out.
 */
public final class TranscriptAdFusion {

    /** Tunables; the defaults were measured as described in docs/AD_SKIP_TUNING.md. */
    public static final class Config {
        /** Confidence factor for audio detections the transcript does not support. */
        public final float unsupportedFactor;
        /** Weight of the audio's confidence when it supports a transcript ad. */
        public final float supportWeight;
        /**
         * An audio detection that overhangs a transcript ad by more than this on one side keeps
         * that part: it is probably an inserted spot next to the read the transcript knows.
         */
        public final long keepOverhangMs;

        public Config(float unsupportedFactor, float supportWeight, long keepOverhangMs) {
            this.unsupportedFactor = unsupportedFactor;
            this.supportWeight = supportWeight;
            this.keepOverhangMs = keepOverhangMs;
        }

        /**
         * For ads found by Claude, which reads a transcript about as well as a person: an audio
         * detection inside a transcript Claude read and found no ad in drops below every
         * sensitivity threshold.
         */
        @NonNull
        public static Config claude() {
            return new Config(0.3f, 0.5f, 45_000);
        }

        /**
         * For ads found by {@link TranscriptAdHeuristic}, which misses more: unsupported audio
         * detections are only discounted.
         */
        @NonNull
        public static Config offline() {
            return new Config(0.85f, 0.5f, 45_000);
        }
    }

    /**
     * The transcript is taken to cover this much beyond its first and last words: the theme
     * music before the host speaks and after the goodbyes is part of the show.
     */
    static final long EDGE_MUSIC_MS = 30_000;

    private TranscriptAdFusion() {
    }

    /**
     * Fuses one episode's detections.
     *
     * @param detected   the audio detector's segments ({@link AdSegment.Source#DETECTED})
     * @param transcript the transcript detector's segments ({@link AdSegment.Source#TRANSCRIPT}
     *                   and {@link AdSegment.Source#SELF_PROMO})
     * @param span       the stretch the transcript covers, {start, end} in ms; null when no
     *                   transcript was read, in which case {@code detected} is returned as is
     * @return transcript segments followed by detected segments, each possibly adjusted; not
     *         merged, see {@link AdSegmentMerger}
     */
    @NonNull
    public static List<AdSegment> fuse(@NonNull List<AdSegment> detected,
                                       @NonNull List<AdSegment> transcript,
                                       @Nullable long[] span, @NonNull Config config) {
        List<AdSegment> out = new ArrayList<>(detected.size() + transcript.size());
        if (span == null) {
            out.addAll(transcript);
            out.addAll(detected);
            return out;
        }
        List<AdSegment> ads = new ArrayList<>(transcript.size());
        for (AdSegment ad : transcript) {
            if (ad.getSource() == AdSegment.Source.SELF_PROMO) {
                out.add(ad);
            } else {
                ads.add(ad);
            }
        }
        for (AdSegment ad : ads) {
            float support = 0;
            for (AdSegment heard : detected) {
                if (overlaps(ad, heard)) {
                    support = Math.max(support, heard.getConfidence());
                }
            }
            float confidence = ad.getConfidence();
            if (support > 0) {
                confidence = 1 - (1 - confidence) * (1 - config.supportWeight * support);
            }
            out.add(withConfidence(ad, confidence, ad.getStartMs(), ad.getEndMs()));
        }
        long spanStart = span[0] - EDGE_MUSIC_MS;
        long spanEnd = span[1] + EDGE_MUSIC_MS;
        for (AdSegment heard : detected) {
            List<AdSegment> pieces = new ArrayList<>(2);
            boolean supported = false;
            for (AdSegment ad : ads) {
                if (overlaps(ad, heard)) {
                    supported = true;
                    // Keep only a long overhang on either side, as a detection of its own.
                    if (ad.getStartMs() - heard.getStartMs() > config.keepOverhangMs) {
                        pieces.add(withConfidence(heard, heard.getConfidence(), heard.getStartMs(),
                                ad.getStartMs()));
                    }
                    if (heard.getEndMs() - ad.getEndMs() > config.keepOverhangMs) {
                        pieces.add(withConfidence(heard, heard.getConfidence(), ad.getEndMs(),
                                heard.getEndMs()));
                    }
                }
            }
            if (!supported) {
                pieces.add(heard);
            }
            for (AdSegment piece : pieces) {
                // Mostly inside the transcript: the words around it are known, and none was an ad.
                long inside = Math.min(piece.getEndMs(), spanEnd)
                        - Math.max(piece.getStartMs(), spanStart);
                boolean covered = inside * 2 >= piece.getDurationMs();
                out.add(covered
                        ? withConfidence(piece, piece.getConfidence() * config.unsupportedFactor,
                                piece.getStartMs(), piece.getEndMs())
                        : piece);
            }
        }
        return out;
    }

    private static boolean overlaps(AdSegment a, AdSegment b) {
        return a.getStartMs() < b.getEndMs() && b.getStartMs() < a.getEndMs();
    }

    private static AdSegment withConfidence(AdSegment segment, float confidence, long start,
                                            long end) {
        return new AdSegment(segment.getId(), segment.getFeedItemId(), start, end,
                segment.getSource(), Math.max(0f, Math.min(1f, confidence)), segment.isEnabled());
    }
}
