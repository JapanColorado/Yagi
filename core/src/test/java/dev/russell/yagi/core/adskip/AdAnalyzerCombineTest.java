package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.russell.yagi.model.feed.AdSegment;

/** {@link AdAnalyzer#combine}: the audio fused with the transcript, then merged with chapters. */
public class AdAnalyzerCombineTest {

    private static final long ITEM = 9L;

    private static AdSegment segment(long start, long end, AdSegment.Source source, float conf) {
        return new AdSegment(ITEM, start, end, source, conf);
    }

    private static void assertSegment(AdSegment segment, AdSegment.Source source, long start,
                                      long end, float confidence) {
        assertEquals(segment.toString(), source, segment.getSource());
        assertEquals(segment.toString(), start, segment.getStartMs());
        assertEquals(segment.toString(), end, segment.getEndMs());
        assertEquals(segment.toString(), confidence, segment.getConfidence(), 1e-5);
    }

    private static void assertSortedAndDisjoint(List<AdSegment> segments) {
        for (int i = 1; i < segments.size(); i++) {
            assertTrue("segments must be sorted and disjoint: " + segments,
                    segments.get(i).getStartMs() >= segments.get(i - 1).getEndMs());
        }
    }

    @Test
    public void chapterBeatsTranscriptBeatsDetected() {
        List<AdSegment> chapters = Collections.singletonList(
                segment(100_000, 130_000, AdSegment.Source.CHAPTER, 1f));
        List<AdSegment> detected = Arrays.asList(
                // supports the transcript ad, overhangs it by only 20 s: dropped
                segment(150_000, 200_000, AdSegment.Source.DETECTED, 0.8f),
                // inside the transcript, which Claude found no ad in: discounted
                segment(300_000, 330_000, AdSegment.Source.DETECTED, 0.8f),
                // after the transcript ends: kept as it is
                segment(500_000, 560_000, AdSegment.Source.DETECTED, 0.7f));
        TranscriptReading reading = new TranscriptReading(TranscriptReading.METHOD_CLAUDE,
                Collections.singletonList(
                        segment(120_000, 180_000, AdSegment.Source.TRANSCRIPT, 0.9f)),
                new long[]{0, 400_000});

        List<AdSegment> combined = AdAnalyzer.combine(detected, reading, chapters);

        assertSortedAndDisjoint(combined);
        assertEquals(combined.toString(), 4, combined.size());
        assertSegment(combined.get(0), AdSegment.Source.CHAPTER, 100_000, 130_000, 1f);
        // trimmed by the chapter, boosted by the audio: 1 - 0.1 * (1 - 0.5 * 0.8)
        assertSegment(combined.get(1), AdSegment.Source.TRANSCRIPT, 130_000, 180_000, 0.94f);
        assertSegment(combined.get(2), AdSegment.Source.DETECTED, 300_000, 330_000, 0.24f);
        assertSegment(combined.get(3), AdSegment.Source.DETECTED, 500_000, 560_000, 0.7f);
    }

    @Test
    public void theOfflineReadingOnlyDiscountsTheAudio() {
        TranscriptReading reading = new TranscriptReading(TranscriptReading.METHOD_OFFLINE,
                Collections.<AdSegment>emptyList(), new long[]{0, 400_000});
        List<AdSegment> combined = AdAnalyzer.combine(Collections.singletonList(
                        segment(300_000, 330_000, AdSegment.Source.DETECTED, 0.8f)), reading,
                Collections.<AdSegment>emptyList());
        assertEquals(1, combined.size());
        assertSegment(combined.get(0), AdSegment.Source.DETECTED, 300_000, 330_000, 0.68f);
    }

    @Test
    public void transcriptSegmentsAreMergedWithTheirOwnSourceRank() {
        // a transcript ad and an overhanging audio detection: the remnant after the ad stays
        TranscriptReading reading = new TranscriptReading(TranscriptReading.METHOD_CLAUDE,
                Arrays.asList(segment(100_000, 160_000, AdSegment.Source.TRANSCRIPT, 0.9f),
                        segment(400_000, 460_000, AdSegment.Source.SELF_PROMO, 0.9f)),
                new long[]{0, 1_000_000});
        List<AdSegment> combined = AdAnalyzer.combine(Collections.singletonList(
                        segment(100_000, 300_000, AdSegment.Source.DETECTED, 0.8f)), reading,
                Collections.singletonList(segment(440_000, 470_000, AdSegment.Source.CHAPTER, 1f)));

        assertSortedAndDisjoint(combined);
        assertEquals(combined.toString(), 4, combined.size());
        assertSegment(combined.get(0), AdSegment.Source.TRANSCRIPT, 100_000, 160_000, 0.94f);
        assertSegment(combined.get(1), AdSegment.Source.DETECTED, 160_000, 300_000, 0.24f);
        assertSegment(combined.get(2), AdSegment.Source.SELF_PROMO, 400_000, 440_000, 0.9f);
        assertSegment(combined.get(3), AdSegment.Source.CHAPTER, 440_000, 470_000, 1f);
    }

    @Test
    public void withoutATranscriptTheAudioIsMergedWithTheChapters() {
        List<AdSegment> chapters = Collections.singletonList(
                segment(100_000, 130_000, AdSegment.Source.CHAPTER, 1f));
        List<AdSegment> detected = Collections.singletonList(
                segment(120_000, 200_000, AdSegment.Source.DETECTED, 0.8f));

        for (TranscriptReading reading : new TranscriptReading[]{null, TranscriptReading.none()}) {
            List<AdSegment> combined = AdAnalyzer.combine(detected, reading, chapters);
            assertEquals(combined.toString(), 2, combined.size());
            assertSegment(combined.get(0), AdSegment.Source.CHAPTER, 100_000, 130_000, 1f);
            assertSegment(combined.get(1), AdSegment.Source.DETECTED, 130_000, 200_000, 0.8f);
        }
    }

    @Test
    public void nothingInNothingOut() {
        assertTrue(AdAnalyzer.combine(Collections.<AdSegment>emptyList(), null,
                Collections.<AdSegment>emptyList()).isEmpty());
    }
}
