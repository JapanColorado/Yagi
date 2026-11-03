package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.russell.yagi.model.feed.AdSegment;

/** How the transcript's reading overrules, supports and leaves alone the audio's detections. */
public class TranscriptAdFusionTest {

    private static final long ITEM = 3L;
    /** The transcript speaks over the first 20 minutes. */
    private static final long[] SPAN = {0, 1_200_000};
    private static final TranscriptAdFusion.Config CLAUDE = TranscriptAdFusion.Config.claude();
    private static final TranscriptAdFusion.Config OFFLINE = TranscriptAdFusion.Config.offline();

    private static AdSegment detected(long start, long end, float confidence) {
        return new AdSegment(ITEM, start, end, AdSegment.Source.DETECTED, confidence);
    }

    private static AdSegment transcript(long start, long end, float confidence) {
        return new AdSegment(ITEM, start, end, AdSegment.Source.TRANSCRIPT, confidence);
    }

    private static List<AdSegment> ofSource(List<AdSegment> segments, AdSegment.Source source) {
        List<AdSegment> out = new ArrayList<>();
        for (AdSegment segment : segments) {
            if (segment.getSource() == source) {
                out.add(segment);
            }
        }
        return out;
    }

    @Test
    public void aSupportedTranscriptAdIsBoostedAndTheAudioDetectionDropped() {
        List<AdSegment> fused = TranscriptAdFusion.fuse(
                Collections.singletonList(detected(95_000, 170_000, 0.8f)),
                Collections.singletonList(transcript(100_000, 160_000, 0.7f)), SPAN, CLAUDE);

        assertEquals(fused.toString(), 1, fused.size());
        AdSegment ad = fused.get(0);
        assertEquals(AdSegment.Source.TRANSCRIPT, ad.getSource());
        assertEquals("the transcript's edges are kept", 100_000, ad.getStartMs());
        assertEquals(160_000, ad.getEndMs());
        // 1 - (1 - 0.7) * (1 - 0.5 * 0.8)
        assertEquals(0.82f, ad.getConfidence(), 1e-5);
        assertTrue(ad.getConfidence() > 0.7f);
    }

    @Test
    public void theStrongestOverlappingDetectionIsTheSupport() {
        List<AdSegment> fused = TranscriptAdFusion.fuse(
                Arrays.asList(detected(100_000, 120_000, 0.4f), detected(130_000, 150_000, 1f)),
                Collections.singletonList(transcript(100_000, 160_000, 0.6f)), SPAN, OFFLINE);
        assertEquals(fused.toString(), 1, fused.size());
        // 1 - 0.4 * (1 - 0.5 * 1)
        assertEquals(0.8f, fused.get(0).getConfidence(), 1e-5);
    }

    @Test
    public void anUnsupportedTranscriptAdKeepsItsConfidence() {
        AdSegment ad = transcript(100_000, 160_000, 0.65f);
        List<AdSegment> fused = TranscriptAdFusion.fuse(
                Collections.<AdSegment>emptyList(), Collections.singletonList(ad), SPAN, CLAUDE);
        assertEquals(1, fused.size());
        assertEquals(0.65f, fused.get(0).getConfidence(), 1e-6);
        assertEquals(100_000, fused.get(0).getStartMs());
        assertEquals(160_000, fused.get(0).getEndMs());
    }

    @Test
    public void aLongOverhangSurvivesButIsDiscountedInsideTheTranscript() {
        // 60 s before the read and 50 s after it: both more than keepOverhangMs
        List<AdSegment> fused = TranscriptAdFusion.fuse(
                Collections.singletonList(detected(40_000, 210_000, 0.8f)),
                Collections.singletonList(transcript(100_000, 160_000, 0.7f)), SPAN, CLAUDE);

        List<AdSegment> pieces = ofSource(fused, AdSegment.Source.DETECTED);
        assertEquals(fused.toString(), 2, pieces.size());
        assertEquals(40_000, pieces.get(0).getStartMs());
        assertEquals(100_000, pieces.get(0).getEndMs());
        assertEquals(0.8f * CLAUDE.unsupportedFactor, pieces.get(0).getConfidence(), 1e-5);
        assertEquals(160_000, pieces.get(1).getStartMs());
        assertEquals(210_000, pieces.get(1).getEndMs());
        assertEquals(0.8f * CLAUDE.unsupportedFactor, pieces.get(1).getConfidence(), 1e-5);
    }

    @Test
    public void aShortOverhangIsDropped() {
        long overhang = CLAUDE.keepOverhangMs;
        List<AdSegment> fused = TranscriptAdFusion.fuse(
                Collections.singletonList(detected(100_000 - overhang, 160_000 + overhang, 0.8f)),
                Collections.singletonList(transcript(100_000, 160_000, 0.7f)), SPAN, CLAUDE);
        assertTrue(fused.toString(), ofSource(fused, AdSegment.Source.DETECTED).isEmpty());
    }

    @Test
    public void aLongOverhangOutsideTheTranscriptKeepsItsConfidence() {
        // the transcript starts at 5 min; the overhang ends where the 30 s edge margin begins
        long[] span = {300_000, 1_200_000};
        List<AdSegment> fused = TranscriptAdFusion.fuse(
                Collections.singletonList(detected(0, 330_000, 0.8f)),
                Collections.singletonList(transcript(270_000, 330_000, 0.7f)), span, CLAUDE);

        List<AdSegment> pieces = ofSource(fused, AdSegment.Source.DETECTED);
        assertEquals(fused.toString(), 1, pieces.size());
        assertEquals(0, pieces.get(0).getStartMs());
        assertEquals(270_000, pieces.get(0).getEndMs());
        assertEquals(0.8f, pieces.get(0).getConfidence(), 1e-6);
    }

    @Test
    public void anUnsupportedDetectionInsideTheTranscriptIsDiscounted() {
        AdSegment heard = detected(300_000, 360_000, 0.8f);
        List<AdSegment> byClaude = TranscriptAdFusion.fuse(Collections.singletonList(heard),
                Collections.<AdSegment>emptyList(), SPAN, CLAUDE);
        assertEquals(1, byClaude.size());
        assertEquals(0.8f * 0.3f, byClaude.get(0).getConfidence(), 1e-5);
        assertEquals(300_000, byClaude.get(0).getStartMs());
        assertEquals(360_000, byClaude.get(0).getEndMs());

        List<AdSegment> offline = TranscriptAdFusion.fuse(Collections.singletonList(heard),
                Collections.<AdSegment>emptyList(), SPAN, OFFLINE);
        assertEquals(0.8f * 0.85f, offline.get(0).getConfidence(), 1e-5);
    }

    @Test
    public void theThemeMusicAroundTheTranscriptCountsAsInside() {
        long margin = TranscriptAdFusion.EDGE_MUSIC_MS;
        long[] span = {60_000, 1_200_000};
        List<AdSegment> fused = TranscriptAdFusion.fuse(Arrays.asList(
                        // inside the 30 s before the first words
                        detected(60_000 - margin + 5_000, 70_000, 0.8f),
                        // inside the 30 s after the last words
                        detected(1_200_000, 1_200_000 + margin, 0.8f)),
                Collections.<AdSegment>emptyList(), span, CLAUDE);
        assertEquals(2, fused.size());
        for (AdSegment segment : fused) {
            assertEquals(segment.toString(), 0.8f * CLAUDE.unsupportedFactor,
                    segment.getConfidence(), 1e-5);
        }
    }

    @Test
    public void aDetectionOutsideTheTranscriptIsUntouched() {
        long margin = TranscriptAdFusion.EDGE_MUSIC_MS;
        long[] span = {120_000, 1_200_000};
        AdSegment before = detected(0, 120_000 - margin, 0.8f);
        AdSegment after = detected(1_200_000 + margin, 1_300_000, 0.7f);
        List<AdSegment> fused = TranscriptAdFusion.fuse(Arrays.asList(before, after),
                Collections.<AdSegment>emptyList(), span, CLAUDE);
        assertEquals(Arrays.asList(before, after), fused);
    }

    @Test
    public void aDetectionMostlyOutsideTheTranscriptIsUntouched() {
        // 40 s of 100 s inside (with the margin): not covered
        long[] span = {130_000, 1_200_000};
        AdSegment heard = detected(40_000, 140_000, 0.8f);
        List<AdSegment> fused = TranscriptAdFusion.fuse(Collections.singletonList(heard),
                Collections.<AdSegment>emptyList(), span, CLAUDE);
        assertSame(heard, fused.get(0));

        // exactly half inside counts as covered
        AdSegment half = detected(50_000, 150_000, 0.8f);
        List<AdSegment> halfFused = TranscriptAdFusion.fuse(Collections.singletonList(half),
                Collections.<AdSegment>emptyList(), span, CLAUDE);
        assertEquals(0.8f * CLAUDE.unsupportedFactor, halfFused.get(0).getConfidence(), 1e-5);
    }

    @Test
    public void selfPromotionPassesThroughAndDoesNotSupportTheAudio() {
        AdSegment promo = new AdSegment(ITEM, 300_000, 360_000, AdSegment.Source.SELF_PROMO, 0.7f);
        List<AdSegment> fused = TranscriptAdFusion.fuse(
                Collections.singletonList(detected(300_000, 360_000, 0.8f)),
                Collections.singletonList(promo), SPAN, CLAUDE);

        assertEquals(fused.toString(), 2, fused.size());
        assertSame(promo, ofSource(fused, AdSegment.Source.SELF_PROMO).get(0));
        AdSegment heard = ofSource(fused, AdSegment.Source.DETECTED).get(0);
        assertEquals("the show's own promotion is not an ad",
                0.8f * CLAUDE.unsupportedFactor, heard.getConfidence(), 1e-5);
        assertEquals(300_000, heard.getStartMs());
        assertEquals(360_000, heard.getEndMs());
    }

    @Test
    public void withoutATranscriptSpanTheInputsAreReturnedUnchanged() {
        AdSegment heard = detected(300_000, 360_000, 0.8f);
        AdSegment ad = transcript(100_000, 160_000, 0.7f);
        List<AdSegment> fused = TranscriptAdFusion.fuse(Collections.singletonList(heard),
                Collections.singletonList(ad), null, CLAUDE);
        assertEquals(2, fused.size());
        assertSame(ad, fused.get(0));
        assertSame(heard, fused.get(1));
    }

    @Test
    public void fusedSegmentsKeepIdItemAndEnabledFlag() {
        AdSegment heard = new AdSegment(42, ITEM, 300_000, 360_000, AdSegment.Source.DETECTED,
                0.8f, false);
        AdSegment fused = TranscriptAdFusion.fuse(Collections.singletonList(heard),
                Collections.<AdSegment>emptyList(), SPAN, CLAUDE).get(0);
        assertEquals(42, fused.getId());
        assertEquals(ITEM, fused.getFeedItemId());
        assertFalse(fused.isEnabled());
    }

    @Test
    public void configs() {
        assertEquals(0.3f, CLAUDE.unsupportedFactor, 1e-6);
        assertEquals(0.85f, OFFLINE.unsupportedFactor, 1e-6);
        assertTrue("Claude's reading overrules the audio more than the phone's",
                CLAUDE.unsupportedFactor < OFFLINE.unsupportedFactor);
    }
}
