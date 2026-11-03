package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import dev.russell.yagi.model.feed.AdSegment;

/** When an episode needs reading again, and how a reading is fused with the audio. */
public class TranscriptReadingTest {

    private static final String CLAUDE = TranscriptReading.METHOD_CLAUDE;
    private static final String OFFLINE = TranscriptReading.METHOD_OFFLINE;
    private static final String NONE = TranscriptReading.METHOD_NONE;

    @Test
    public void nothingIsNeededWhenTranscriptsAreOff() {
        assertTrue(TranscriptReading.satisfies(null, null));
        assertTrue(TranscriptReading.satisfies(CLAUDE, null));
        assertTrue(TranscriptReading.satisfies(OFFLINE, null));
        assertTrue(TranscriptReading.satisfies(NONE, null));
    }

    @Test
    public void anUnreadEpisodeNeedsReading() {
        assertFalse(TranscriptReading.satisfies(null, CLAUDE));
        assertFalse(TranscriptReading.satisfies(null, OFFLINE));
    }

    @Test
    public void theSameMethodIsEnough() {
        assertTrue(TranscriptReading.satisfies(CLAUDE, CLAUDE));
        assertTrue(TranscriptReading.satisfies(OFFLINE, OFFLINE));
    }

    @Test
    public void aMissingTranscriptStaysMissing() {
        assertTrue(TranscriptReading.satisfies(NONE, CLAUDE));
        assertTrue(TranscriptReading.satisfies(NONE, OFFLINE));
    }

    @Test
    public void claudeIsAtLeastAsGoodAsThePhoneButNotTheOtherWayRound() {
        assertTrue(TranscriptReading.satisfies(CLAUDE, OFFLINE));
        assertFalse("adding a key re-reads episodes the phone read",
                TranscriptReading.satisfies(OFFLINE, CLAUDE));
    }

    @Test
    public void fusionConfigFollowsTheMethod() {
        long[] span = {0, 1000};
        List<AdSegment> none = new ArrayList<>();
        assertEquals(TranscriptAdFusion.Config.claude().unsupportedFactor,
                new TranscriptReading(CLAUDE, none, span).fusionConfig().unsupportedFactor, 1e-6);
        assertEquals(TranscriptAdFusion.Config.offline().unsupportedFactor,
                new TranscriptReading(OFFLINE, none, span).fusionConfig().unsupportedFactor, 1e-6);
        assertEquals(TranscriptAdFusion.Config.offline().unsupportedFactor,
                TranscriptReading.none().fusionConfig().unsupportedFactor, 1e-6);
    }

    @Test
    public void noneHasNoSpanAndNoSegments() {
        TranscriptReading none = TranscriptReading.none();
        assertEquals(NONE, none.getMethod());
        assertTrue(none.getSegments().isEmpty());
        assertNull(none.getSpan());
    }

    @Test
    public void theReadingCannotBeChangedFromOutside() {
        long[] span = {10, 20};
        List<AdSegment> segments = new ArrayList<>();
        segments.add(new AdSegment(1, 0, 1000, AdSegment.Source.TRANSCRIPT, 0.9f));
        TranscriptReading reading = new TranscriptReading(OFFLINE, segments, span);

        reading.getSpan()[0] = 99;
        assertArrayEquals(new long[]{10, 20}, reading.getSpan());
        try {
            reading.getSegments().clear();
            fail("the segments must be read-only");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, reading.getSegments().size());
        }
    }
}
