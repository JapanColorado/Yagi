package dev.russell.yagi.core.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import dev.russell.yagi.core.adskip.ChapterAdMatcher;
import dev.russell.yagi.model.feed.AdSegment;
import dev.russell.yagi.model.feed.Chapter;

/** Plain JUnit tests for {@link DescriptionChapterParser}. */
public class DescriptionChapterParserTest {

    private static final long HOUR = 3_600_000;

    private static void assertChapter(Chapter chapter, long startMs, String title) {
        assertEquals(startMs, chapter.getStart());
        assertEquals(title, chapter.getTitle());
    }

    @Test
    public void plainTextLinesWithLeadingTimestamps() {
        List<Chapter> chapters = DescriptionChapterParser.parse(
                "In this episode we talk about things.\n\n"
                        + "00:00 Intro\n"
                        + "02:15 - Sponsor: Acme Mattresses\n"
                        + "05:40 \u2013 The main topic\n"
                        + "1:02:03 Listener mail\n", 2 * HOUR);
        assertEquals(4, chapters.size());
        assertChapter(chapters.get(0), 0, "Intro");
        assertChapter(chapters.get(1), 135_000, "Sponsor: Acme Mattresses");
        assertChapter(chapters.get(2), 340_000, "The main topic");
        assertChapter(chapters.get(3), 3_723_000, "Listener mail");
    }

    @Test
    public void htmlParagraphsBreaksAndListItems() {
        List<Chapter> fromParagraphs = DescriptionChapterParser.parse(
                "<p>Show notes</p><p>(00:00) Cold open</p><p>(10:30) <b>Ad break</b></p>"
                        + "<p>(13:00) Interview &amp; questions</p>", HOUR);
        assertEquals(3, fromParagraphs.size());
        assertChapter(fromParagraphs.get(1), 630_000, "Ad break");
        assertChapter(fromParagraphs.get(2), 780_000, "Interview & questions");

        List<Chapter> fromBreaks = DescriptionChapterParser.parse(
                "<p>Chapters:<br>[00:00] Hello<br/>[04:05] Sponsored segment<br />[08:00] News</p>",
                HOUR);
        assertEquals(3, fromBreaks.size());
        assertChapter(fromBreaks.get(1), 245_000, "Sponsored segment");

        List<Chapter> fromList = DescriptionChapterParser.parse(
                "<ul><li>0:00 Start</li><li>12:00 Middle</li><li>24:00 End</li></ul>", HOUR);
        assertEquals(3, fromList.size());
        assertChapter(fromList.get(2), 1_440_000, "End");
    }

    @Test
    public void bulletsNumbersAndRanges() {
        List<Chapter> chapters = DescriptionChapterParser.parse(
                "\u2022 00:00 Welcome\n"
                        + "- 03:00 First story\n"
                        + "3. 07:30 Second story\n"
                        + "10:00 - 12:30 Mid-roll\n", HOUR);
        assertEquals(4, chapters.size());
        assertChapter(chapters.get(0), 0, "Welcome");
        assertChapter(chapters.get(1), 180_000, "First story");
        assertChapter(chapters.get(2), 450_000, "Second story");
        assertChapter(chapters.get(3), 600_000, "Mid-roll");
    }

    @Test
    public void trailingTimestamps() {
        List<Chapter> chapters = DescriptionChapterParser.parse(
                "Intro - 00:00\nSponsor (12:10)\nOutro [55:00]", HOUR);
        assertEquals(3, chapters.size());
        assertChapter(chapters.get(0), 0, "Intro");
        assertChapter(chapters.get(1), 730_000, "Sponsor");
        assertChapter(chapters.get(2), 3_300_000, "Outro");
    }

    @Test
    public void longMinuteTimestampsAreMinutesAndSeconds() {
        List<Chapter> chapters = DescriptionChapterParser.parse(
                "00:00 Start\n62:30 Late topic", 2 * HOUR);
        assertChapter(chapters.get(1), 3_750_000, "Late topic");
    }

    @Test
    public void timesInProseAreNotChapters() {
        assertNull(DescriptionChapterParser.parse(
                "We recorded this at 10:30 in the morning and went live at 11:15 after lunch.",
                HOUR));
        assertNull(DescriptionChapterParser.parse(
                "Join us at 10:30 for the live show,\nor catch the replay after 18:00 today.", HOUR));
    }

    @Test
    public void aSingleTimestampIsNotAChapterList() {
        assertNull(DescriptionChapterParser.parse("Skip to 12:00 for the interview\n"
                + "05:00 is when it starts", HOUR));
        assertNull(DescriptionChapterParser.parse("00:00 Intro", HOUR));
    }

    @Test
    public void timesPastTheEndAreDropped() {
        List<Chapter> chapters = DescriptionChapterParser.parse(
                "00:00 Intro\n10:00 Topic\n59:00 Way past the end", 30 * 60_000);
        assertEquals(2, chapters.size());
        // Unknown duration: everything is kept.
        assertEquals(3, DescriptionChapterParser.parse(
                "00:00 Intro\n10:00 Topic\n59:00 Way past the end", 0).size());
    }

    @Test
    public void theLongestRisingRunWins() {
        List<Chapter> chapters = DescriptionChapterParser.parse(
                "00:00 Intro\n05:00 One\n10:00 Two\n20:00 Three\n"
                        + "Previously:\n03:00 Something old\n04:00 Something else", HOUR);
        assertEquals(4, chapters.size());
        assertChapter(chapters.get(3), 1_200_000, "Three");
    }

    @Test
    public void invalidTimesAndEmptyTitlesAreIgnored() {
        assertNull(DescriptionChapterParser.parse("00:75 Bad seconds\n01:61:00 Bad minutes", 0));
        List<Chapter> chapters = DescriptionChapterParser.parse(
                "00:00 -\n01:00 Real one\n02:00 Another", HOUR);
        assertEquals(2, chapters.size());
        assertChapter(chapters.get(0), 60_000, "Real one");
    }

    @Test
    public void nullAndEmptyDescriptions() {
        assertNull(DescriptionChapterParser.parse(null, HOUR));
        assertNull(DescriptionChapterParser.parse("", HOUR));
    }

    @Test
    public void sponsorChaptersFromADescriptionBecomeAdSegments() {
        List<Chapter> chapters = DescriptionChapterParser.parse(
                "00:00 Intro\n02:15 Sponsor: Acme\n04:15 Main topic\n50:00 Ad - Widgets Inc\n"
                        + "52:00 Wrap up", HOUR);
        List<AdSegment> ads = ChapterAdMatcher.match(chapters, HOUR, 7);
        assertEquals(2, ads.size());
        assertEquals(135_000, ads.get(0).getStartMs());
        assertEquals(255_000, ads.get(0).getEndMs());
        assertEquals(3_000_000, ads.get(1).getStartMs());
        assertEquals(3_120_000, ads.get(1).getEndMs());
        assertTrue(ads.get(0).getSource() == AdSegment.Source.CHAPTER);
    }
}
