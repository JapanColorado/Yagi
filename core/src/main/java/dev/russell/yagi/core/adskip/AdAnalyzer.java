package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.russell.yagi.model.feed.AdSegment;
import dev.russell.yagi.model.feed.Chapter;

/**
 * The entry point of the ad-skip analysis: file in, {@link AdSegment}s out.
 *
 * <p>It owns only the wiring. {@link PcmDecoder} streams mono 16 kHz PCM into
 * {@link AudioFeatureExtractor}, which builds the episode's feature profile; {@link AdDetector}
 * turns that profile into candidate segments; {@link ChapterAdMatcher} reads whatever the publisher
 * labelled; when the transcript was read ({@link TranscriptReading}), {@link TranscriptAdFusion}
 * weighs the audio against it; {@link AdSegmentMerger} reconciles the lot. A background worker
 * calls {@link #analyze} after a download completes and hands the result to the storage layer.
 *
 * <p>Everything here is best effort. Analysis of a long episode takes minutes of CPU, so the
 * {@link Cancellation} hook is polled throughout; when it fires, analysis returns whatever the
 * chapters alone provide rather than segments derived from a half-decoded file.
 */
public final class AdAnalyzer {

    @NonNull
    private final AdDetector detector;

    public AdAnalyzer() {
        this(new AdDetector());
    }

    public AdAnalyzer(@NonNull AdDetector.Config config) {
        this(new AdDetector(config));
    }

    public AdAnalyzer(@NonNull AdDetector detector) {
        this.detector = detector;
    }

    /**
     * Analyses one downloaded episode.
     *
     * @param filePath   local path of the downloaded media file.
     * @param feedItemId stamped onto every returned segment.
     * @param durationMs episode duration; if not positive it is inferred from the decoded audio.
     * @param chapters   the episode's chapters, may be null.
     * @param cancelled  polled during decoding; may be null for "never cancel".
     * @return a sorted, non-overlapping list of segments. Detected segments carry a confidence from
     *         {@link AdDetector.Config#minConfidence} upwards; the caller is expected to filter by
     *         the user's sensitivity setting before acting on them.
     * @throws DecodeException if the media file cannot be decoded at all.
     */
    @NonNull
    public List<AdSegment> analyze(@Nullable String filePath, long feedItemId, long durationMs,
                                   @Nullable List<Chapter> chapters,
                                   @Nullable Cancellation cancelled) throws DecodeException {
        return analyze(filePath, feedItemId, durationMs, chapters, null, cancelled);
    }

    /**
     * {@link #analyze(String, long, long, List, Cancellation)}, combined with what the episode's
     * transcript said.
     *
     * @param transcript the reading of the transcript, or null when there was none; its segments
     *                   are part of the result, trimmed where chapters overrule them
     */
    @NonNull
    public List<AdSegment> analyze(@Nullable String filePath, long feedItemId, long durationMs,
                                   @Nullable List<Chapter> chapters,
                                   @Nullable TranscriptReading transcript,
                                   @Nullable Cancellation cancelled) throws DecodeException {
        Cancellation cancel = cancelled != null ? cancelled : Cancellation.NEVER;
        List<AdSegment> fromChapters = ChapterAdMatcher.match(chapters, durationMs, feedItemId);

        final AudioFeatureExtractor extractor = new AudioFeatureExtractor();
        PcmDecoder.decode(filePath, new PcmDecoder.Sink() {
            @Override
            public void onPcm(@NonNull float[] samples, int offset, int length) {
                extractor.accept(samples, offset, length);
            }
        }, cancel);

        if (cancel.isCancelled()) {
            return AdSegmentMerger.merge(Collections.<AdSegment>emptyList(), fromChapters, null);
        }

        List<FeatureFrame> frames = extractor.finish();
        long effectiveDuration = durationMs > 0
                ? durationMs
                : (long) frames.size() * AudioFeatureExtractor.HOP_MS;
        List<AdSegment> detected = detector.detect(frames, effectiveDuration, feedItemId);
        return combine(detected, transcript, fromChapters);
    }

    /** Fuses the audio's segments with the transcript's and merges in the chapters. */
    @NonNull
    static List<AdSegment> combine(@NonNull List<AdSegment> detected,
                                   @Nullable TranscriptReading transcript,
                                   @NonNull List<AdSegment> fromChapters) {
        List<AdSegment> all = new ArrayList<>(fromChapters);
        if (transcript == null) {
            all.addAll(detected);
        } else {
            all.addAll(TranscriptAdFusion.fuse(detected, transcript.getSegments(),
                    transcript.getSpan(), transcript.fusionConfig()));
        }
        return AdSegmentMerger.merge(all);
    }

    /**
     * Analyses a profile that has already been extracted. Exposed for callers that want to run the
     * decoder themselves (and for tests, which cannot run a codec on the JVM).
     */
    @NonNull
    public List<AdSegment> analyzeProfile(@NonNull List<FeatureFrame> frames, long feedItemId,
                                          long durationMs, @Nullable List<Chapter> chapters) {
        List<AdSegment> fromChapters = ChapterAdMatcher.match(chapters, durationMs, feedItemId);
        List<AdSegment> detected = detector.detect(frames, durationMs, feedItemId);
        return AdSegmentMerger.merge(detected, fromChapters, null);
    }
}
