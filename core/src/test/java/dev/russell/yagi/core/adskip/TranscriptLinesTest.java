package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.russell.yagi.model.transcript.Transcript;
import dev.russell.yagi.model.transcript.TranscriptSegment;

/** How a parsed transcript becomes the timed lines the transcript ad detectors read. */
public class TranscriptLinesTest {

    private static TranscriptSegment cue(long start, long end, String speaker, String text) {
        return new TranscriptSegment(start, end, speaker, text);
    }

    private static List<TranscriptLine> linesOf(TranscriptSegment... segments) {
        return TranscriptLines.of(new Transcript(Arrays.asList(segments)));
    }

    private static TranscriptLine line(long start, long end) {
        return new TranscriptLine(0, start, end, null, "words");
    }

    @Test
    public void untimedOrMissingTranscriptsGiveNoLines() {
        assertTrue(TranscriptLines.of(null).isEmpty());
        assertTrue(TranscriptLines.of(Transcript.empty()).isEmpty());
        assertTrue(linesOf(
                cue(TranscriptSegment.UNTIMED, TranscriptSegment.UNTIMED, null, "Plain text"),
                cue(TranscriptSegment.UNTIMED, TranscriptSegment.UNTIMED, null, "more text"))
                .isEmpty());
        assertTrue("one untimed cue makes the whole transcript untimed",
                linesOf(cue(0, 1000, null, "timed"),
                        cue(TranscriptSegment.UNTIMED, TranscriptSegment.UNTIMED, null, "not"))
                        .isEmpty());
    }

    @Test
    public void shortCuesOfOneSpeakerAreJoinedUntilALineIsLongEnough() {
        List<TranscriptSegment> cues = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            cues.add(cue(i * 1000L, (i + 1) * 1000L, "Host", "w" + i));
        }
        List<TranscriptLine> lines = TranscriptLines.of(new Transcript(cues));

        assertEquals(2, lines.size());
        // a line grows while it is shorter than MIN_LINE_MS, so six one-second cues make one
        assertEquals(0, lines.get(0).getStartMs());
        assertEquals(TranscriptLines.MIN_LINE_MS, lines.get(0).getEndMs());
        assertEquals("w0 w1 w2 w3 w4 w5", lines.get(0).getText());
        assertEquals("Host", lines.get(0).getSpeaker());
        assertEquals(6000, lines.get(1).getStartMs());
        assertEquals(9000, lines.get(1).getEndMs());
        assertEquals("w6 w7 w8", lines.get(1).getText());
        assertEquals(0, lines.get(0).getIndex());
        assertEquals(1, lines.get(1).getIndex());
    }

    @Test
    public void cuesAreNotJoinedAcrossALongPause() {
        List<TranscriptLine> lines = linesOf(
                cue(0, 1000, null, "before"),
                cue(1000 + TranscriptLines.MAX_JOIN_GAP_MS + 1, 4000, null, "after a pause"));
        assertEquals(2, lines.size());
        assertEquals("before", lines.get(0).getText());
        assertEquals("after a pause", lines.get(1).getText());

        List<TranscriptLine> joined = linesOf(
                cue(0, 1000, null, "before"),
                cue(1000 + TranscriptLines.MAX_JOIN_GAP_MS, 4000, null, "after a breath"));
        assertEquals("a pause of exactly MAX_JOIN_GAP_MS still joins", 1, joined.size());
        assertEquals("before after a breath", joined.get(0).getText());
        assertEquals(0, joined.get(0).getStartMs());
        assertEquals(4000, joined.get(0).getEndMs());
    }

    @Test
    public void cuesAreNotJoinedAcrossASpeakerChange() {
        List<TranscriptLine> lines = linesOf(
                cue(0, 1000, "Alice", "Hi"),
                cue(1000, 2000, "Bob", "Hello"),
                cue(2000, 3000, "Bob", "there"),
                cue(3000, 4000, null, "unnamed"));
        assertEquals(3, lines.size());
        assertEquals("Alice", lines.get(0).getSpeaker());
        assertEquals("Hi", lines.get(0).getText());
        assertEquals("Bob", lines.get(1).getSpeaker());
        assertEquals("Hello there", lines.get(1).getText());
        assertNull(lines.get(2).getSpeaker());
        assertEquals("unnamed", lines.get(2).getText());
    }

    @Test
    public void blankCuesAreSkippedAndTextIsTrimmed() {
        List<TranscriptLine> lines = linesOf(
                cue(0, 1000, null, "  one  "),
                cue(1000, 2000, null, "   "),
                cue(2000, 3000, null, "two"));
        assertEquals(1, lines.size());
        assertEquals("one two", lines.get(0).getText());
    }

    @Test
    public void aCueWithoutAnEndEndsAtTheNextStartOrAfterItsWords() {
        // next cue far away: the spoken-length estimate ends it
        List<TranscriptLine> far = linesOf(
                cue(0, TranscriptSegment.UNTIMED, null, "hello"),
                cue(60_000, 61_000, null, "later"));
        assertEquals(2, far.size());
        assertEquals(5 * TranscriptLines.MS_PER_CHAR, far.get(0).getEndMs());

        // next cue closer than the estimate: it ends where the next one starts
        String words = "a rather long cue that takes a few seconds to say";
        List<TranscriptLine> near = linesOf(
                cue(0, TranscriptSegment.UNTIMED, "A", words),
                cue(1000, 2000, "B", "next"));
        assertEquals(1000, near.get(0).getEndMs());

        // an end equal to the start counts as no end
        List<TranscriptLine> zero = linesOf(
                cue(5000, 5000, "A", words),
                cue(20_000, 21_000, "B", "next"));
        assertEquals(5000 + words.length() * TranscriptLines.MS_PER_CHAR, zero.get(0).getEndMs());

        // the last cue has no next start at all
        List<TranscriptLine> last = linesOf(cue(10_000, TranscriptSegment.UNTIMED, null, "bye"));
        assertEquals(10_000 + 3 * TranscriptLines.MS_PER_CHAR, last.get(0).getEndMs());
    }

    @Test
    public void aCueWithoutAnEndSkipsNextCuesThatStartAtTheSameTime() {
        List<TranscriptLine> lines = linesOf(
                cue(0, TranscriptSegment.UNTIMED, "A", "first words here"),
                cue(0, TranscriptSegment.UNTIMED, "B", "same start"),
                cue(500, 1500, "C", "then"));
        assertEquals(500, lines.get(0).getEndMs());
    }

    @Test
    public void fitsEpisodeAllowsASmallOverhangAndAShortfallOfAFewMinutes() {
        long duration = 30 * 60_000;
        assertTrue(TranscriptLines.fitsEpisode(
                Collections.singletonList(line(0, duration)), duration));
        assertTrue(TranscriptLines.fitsEpisode(
                Collections.singletonList(line(0, duration + TranscriptLines.MAX_OVERHANG_MS)),
                duration));
        assertFalse("ends more than 15 s after the audio: another cut",
                TranscriptLines.fitsEpisode(Collections.singletonList(
                        line(0, duration + TranscriptLines.MAX_OVERHANG_MS + 1)), duration));
        assertTrue(TranscriptLines.fitsEpisode(
                Collections.singletonList(line(0, duration - TranscriptLines.MAX_SHORTFALL_MS)),
                duration));
        assertFalse("stops more than 4 min before the audio: ads were stitched in later",
                TranscriptLines.fitsEpisode(Collections.singletonList(
                        line(0, duration - TranscriptLines.MAX_SHORTFALL_MS - 1)), duration));
    }

    @Test
    public void fitsEpisodeTrustsAnUnknownDurationButNotAnEmptyTranscript() {
        List<TranscriptLine> lines = Collections.singletonList(line(0, 99 * 60_000));
        assertTrue(TranscriptLines.fitsEpisode(lines, 0));
        assertTrue(TranscriptLines.fitsEpisode(lines, -1));
        assertFalse(TranscriptLines.fitsEpisode(Collections.<TranscriptLine>emptyList(), 0));
        assertFalse(TranscriptLines.fitsEpisode(Collections.<TranscriptLine>emptyList(), 60_000));
    }

    @Test
    public void spanRunsFromTheFirstStartToTheLastEnd() {
        assertArrayEquals(new long[]{0, 0},
                TranscriptLines.span(Collections.<TranscriptLine>emptyList()));
        assertArrayEquals(new long[]{4000, 95_000}, TranscriptLines.span(Arrays.asList(
                line(4000, 10_000), line(10_000, 50_000), line(60_000, 95_000))));
    }
}
