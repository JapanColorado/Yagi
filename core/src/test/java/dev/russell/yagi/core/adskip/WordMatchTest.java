package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Finding a model's quote of a few words inside a transcript line. */
public class WordMatchTest {

    private static final String TEXT =
            "And we're back. This episode is brought to you by Acme, the rocket people. "
                    + "Go to acme.com/show, that's acme.com/show. Now, back to the show!";

    @Test
    public void startOfIgnoresCaseAndPunctuation() {
        assertEquals(TEXT.indexOf("This"), WordMatch.startOf(TEXT, "this EPISODE is, brought to"));
        assertEquals(TEXT.indexOf("Acme,"), WordMatch.startOf(TEXT, "Acme the rocket people"));
        assertEquals("apostrophes are dropped on both sides",
                0, WordMatch.startOf(TEXT, "and were back"));
        assertEquals(TEXT.indexOf("we're"), WordMatch.startOf(TEXT, "we're back this"));
    }

    @Test
    public void startOfRetriesWithFewerWords() {
        // the fourth word is wrong, the first three match
        assertEquals(TEXT.indexOf("This"), WordMatch.startOf(TEXT, "this episode is sponsored by"));
        // only the first two match
        assertEquals(TEXT.indexOf("This"), WordMatch.startOf(TEXT, "this episode was sponsored"));
    }

    @Test
    public void startOfFallsBackToTheStartOfTheLine() {
        assertEquals(0, WordMatch.startOf(TEXT, "nothing like this at all"));
        assertEquals(0, WordMatch.startOf(TEXT, ""));
        assertEquals(0, WordMatch.startOf("", "this episode"));
    }

    @Test
    public void startOfTakesTheFirstOccurrence() {
        String text = "go to acme dot com, or go to acme dot com";
        assertEquals(0, WordMatch.startOf(text, "go to acme dot"));
    }

    @Test
    public void endOfIgnoresCaseAndPunctuation() {
        int end = TEXT.indexOf("the rocket people") + "the rocket people".length();
        assertEquals(end, WordMatch.endOf(TEXT, "Acme -- THE ROCKET PEOPLE."));
        assertEquals(TEXT.length() - 1, WordMatch.endOf(TEXT, "now back to the show"));
    }

    @Test
    public void endOfTakesTheLastOccurrence() {
        int end = TEXT.lastIndexOf("acme.com/show") + "acme.com/show".length();
        assertEquals(end, WordMatch.endOf(TEXT, "acme com show"));
    }

    @Test
    public void endOfRetriesWithFewerWords() {
        // the last four words do not match, the last three do
        assertEquals(TEXT.length() - 1, WordMatch.endOf(TEXT, "now gone to the show"));
        // only the last two match
        int end = TEXT.indexOf("the rocket people") + "the rocket people".length();
        assertEquals(end, WordMatch.endOf(TEXT, "makers of rocket people"));
    }

    @Test
    public void endOfFallsBackToTheEndOfTheLine() {
        assertEquals(TEXT.length(), WordMatch.endOf(TEXT, "nothing like this at all"));
        assertEquals(TEXT.length(), WordMatch.endOf(TEXT, ""));
    }
}
