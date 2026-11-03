package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Placing a character offset of a line in time. */
public class TranscriptLineTest {

    @Test
    public void timeAtCharIsProportionalToTheOffset() {
        TranscriptLine line = new TranscriptLine(0, 1000, 2000, null, "0123456789");
        assertEquals(1000, line.timeAtChar(0));
        assertEquals(1300, line.timeAtChar(3));
        assertEquals(1500, line.timeAtChar(5));
        assertEquals(1900, line.timeAtChar(9));
        assertEquals(2000, line.timeAtChar(10));
    }

    @Test
    public void timeAtCharRoundsToTheNearestMillisecond() {
        TranscriptLine line = new TranscriptLine(0, 0, 1000, null, "abc");
        assertEquals(333, line.timeAtChar(1));
        assertEquals(667, line.timeAtChar(2));
    }

    @Test
    public void offsetsOutsideTheTextClampToItsEnds() {
        TranscriptLine line = new TranscriptLine(0, 1000, 2000, null, "0123456789");
        assertEquals(1000, line.timeAtChar(-5));
        assertEquals(2000, line.timeAtChar(11));
        assertEquals(2000, line.timeAtChar(Integer.MAX_VALUE));
    }

    @Test
    public void anEmptyLineIsAllAtItsStart() {
        TranscriptLine line = new TranscriptLine(0, 1000, 2000, null, "");
        assertEquals(1000, line.timeAtChar(0));
        assertEquals(1000, line.timeAtChar(5));
    }

    @Test
    public void anEndBeforeTheStartIsClampedToTheStart() {
        TranscriptLine line = new TranscriptLine(3, 5000, 4000, "Host", "text");
        assertEquals(5000, line.getEndMs());
        assertEquals(0, line.getDurationMs());
        assertEquals(5000, line.timeAtChar(4));
        assertEquals(3, line.getIndex());
    }
}
