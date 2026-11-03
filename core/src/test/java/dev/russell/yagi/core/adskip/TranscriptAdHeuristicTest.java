package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import dev.russell.yagi.model.feed.AdSegment;

/**
 * The offline transcript reader on small synthetic transcripts. Every line lasts
 * {@link #LINE_MS}, so line {@code n} spans {@code n * LINE_MS} to {@code (n + 1) * LINE_MS}.
 */
public class TranscriptAdHeuristicTest {

    private static final long LINE_MS = 10_000;
    private static final long ITEM = 7L;

    private static final String[] CONVERSATION_BEFORE = {
            "Welcome everyone, today we are talking about gardening.",
            "I planted tomatoes this spring and they grew really well.",
            "My neighbour thinks I water them too much though.",
    };
    private static final String[] CONVERSATION_AFTER = {
            "So the tomatoes, I think the key is compost.",
            "Compost and sunshine, that is really all it takes.",
            "Let us talk about peppers next.",
            "Peppers like it even warmer than tomatoes do.",
    };

    /** Lines of {@code LINE_MS} each, back to back, speaker "Host". */
    private static List<TranscriptLine> lines(String... texts) {
        List<TranscriptLine> out = new ArrayList<>();
        for (String text : texts) {
            int i = out.size();
            out.add(new TranscriptLine(i, i * LINE_MS, (i + 1) * LINE_MS, "Host", text));
        }
        return out;
    }

    private static String[] concat(String[]... parts) {
        List<String> out = new ArrayList<>();
        for (String[] part : parts) {
            out.addAll(Arrays.asList(part));
        }
        return out.toArray(new String[0]);
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

    private static void assertOneSegment(List<AdSegment> found, AdSegment.Source source,
                                         long startMs, long endMs) {
        assertEquals("segments: " + found, 1, found.size());
        AdSegment segment = found.get(0);
        assertEquals(source, segment.getSource());
        assertEquals(startMs, segment.getStartMs());
        assertEquals(endMs, segment.getEndMs());
        assertEquals(ITEM, segment.getFeedItemId());
    }

    @Test
    public void aClassicHostReadIsOneSegmentCoveringTheReadOnly() {
        List<TranscriptLine> lines = lines(concat(CONVERSATION_BEFORE, new String[]{
                "Before we get into it, this episode is brought to you by Acme.",
                "Acme makes the best rocket skates I have ever used.",
                "I wear my Acme skates every morning on the way to work.",
                "Go to acme.com/show and use code SHOW for 20% off your first order.",
                "That's acme.com/show, code SHOW.",
                "Anyway, back to the show.",
        }, CONVERSATION_AFTER));

        List<AdSegment> found = new TranscriptAdHeuristic().detect(lines, ITEM);

        // lines 3 (the opener) to 8 (the "back to the show")
        assertOneSegment(found, AdSegment.Source.TRANSCRIPT, 3 * LINE_MS, 9 * LINE_MS);
        assertTrue("confidence " + found.get(0).getConfidence(),
                found.get(0).getConfidence() >= 0.6f);
    }

    @Test
    public void anAsrStyleReadWithoutPunctuationReadsTheSame() {
        List<TranscriptLine> lines = lines(concat(CONVERSATION_BEFORE, new String[]{
                "before we get into it this episode is brought to you by acme",
                "acme makes the best rocket skates i have ever used",
                "i wear my acme skates every morning on the way to work",
                "go to acme dot com slash show and use code show for twenty percent off your first order",
                "thats acme dot com slash show code show",
                "anyway back to the show",
        }, CONVERSATION_AFTER));

        List<AdSegment> found = new TranscriptAdHeuristic().detect(lines, ITEM);

        assertOneSegment(found, AdSegment.Source.TRANSCRIPT, 3 * LINE_MS, 9 * LINE_MS);
        assertTrue(found.get(0).getConfidence() >= 0.6f);
    }

    @Test
    public void aReadWithoutAnOpenerIsAnchoredOnTheCallToActionAndTheBrand() {
        List<TranscriptLine> lines = lines(concat(CONVERSATION_BEFORE, new String[]{
                "I have been using Acme for a few months now.",
                "Acme makes the best rocket skates I have ever used.",
                "Go to acme.com/show and use code SHOW for 20% off your first order.",
                "That's acme.com/show.",
        }, CONVERSATION_AFTER));

        List<AdSegment> found = new TranscriptAdHeuristic().detect(lines, ITEM);

        // reaches back through the lines naming Acme, forward to the last call
        assertOneSegment(found, AdSegment.Source.TRANSCRIPT, 3 * LINE_MS, 7 * LINE_MS);
        assertTrue(found.get(0).getConfidence() >= 0.6f);
    }

    @Test
    public void aSponsorSpeakerRunIsCertain() {
        List<TranscriptLine> lines = new ArrayList<>();
        lines.add(new TranscriptLine(0, 0, 10_000, "Host", "We were talking about gardening."));
        // WebVTT names the speaker on the first cue of a turn only
        lines.add(new TranscriptLine(1, 10_000, 20_000, "Sponsor", "Rocket skates for everyone."));
        lines.add(new TranscriptLine(2, 20_000, 30_000, null, "Fast and fun."));
        lines.add(new TranscriptLine(3, 30_000, 40_000, null, "Available in stores."));
        lines.add(new TranscriptLine(4, 40_000, 50_000, "Host", "So, the tomatoes."));
        lines.add(new TranscriptLine(5, 50_000, 60_000, null, "They need compost."));

        List<AdSegment> found = new TranscriptAdHeuristic().detect(lines, ITEM);

        assertOneSegment(found, AdSegment.Source.TRANSCRIPT, 10_000, 40_000);
        assertEquals(0.95f, found.get(0).getConfidence(), 1e-6);
    }

    @Test
    public void conversationMentioningAWebAddressOnceIsNotAnAd() {
        List<TranscriptLine> punctuated = lines(concat(CONVERSATION_BEFORE, new String[]{
                "The code for the watering robot is up on github.com, it is all open source.",
        }, CONVERSATION_AFTER));
        assertTrue(new TranscriptAdHeuristic().detect(punctuated, ITEM).isEmpty());

        List<TranscriptLine> asr = lines(concat(CONVERSATION_BEFORE, new String[]{
                "the code for the watering robot is up on github dot com it is all open source",
        }, CONVERSATION_AFTER));
        assertTrue(new TranscriptAdHeuristic().detect(asr, ITEM).isEmpty());
    }

    @Test
    public void aLoneLinkInConversationIsNotAnAd() {
        for (String link : new String[]{
                "If you want the code you can go to github.com and look for it.",
                "Check it out at github.com",
                "if you want the code you can go to github dot com and look for it"}) {
            List<TranscriptLine> lines = lines(concat(CONVERSATION_BEFORE, new String[]{link},
                    CONVERSATION_AFTER));
            assertTrue(link, new TranscriptAdHeuristic().detect(lines, ITEM).isEmpty());
        }
    }

    @Test
    public void plainConversationIsNotAnAd() {
        List<TranscriptLine> lines = lines(concat(CONVERSATION_BEFORE, CONVERSATION_AFTER));
        assertTrue(new TranscriptAdHeuristic().detect(lines, ITEM).isEmpty());
        assertTrue(new TranscriptAdHeuristic().detect(
                Collections.<TranscriptLine>emptyList(), ITEM).isEmpty());
    }

    @Test
    public void aPatreonPitchIsSelfPromotion() {
        List<TranscriptLine> lines = lines(concat(CONVERSATION_BEFORE, new String[]{
                "If you enjoy the show, please support us on Patreon.",
                "Go to patreon.com/gardenshow to become a member and get bonus episodes.",
                "That's patreon.com/gardenshow.",
        }, CONVERSATION_AFTER));

        List<AdSegment> found = new TranscriptAdHeuristic().detect(lines, ITEM);

        assertTrue(ofSource(found, AdSegment.Source.TRANSCRIPT).isEmpty());
        assertOneSegment(found, AdSegment.Source.SELF_PROMO, 3 * LINE_MS, 6 * LINE_MS);
    }

    @Test
    public void theShowsOwnWebAddressIsSelfPromotionWhenTheShowsNamesAreKnown() {
        List<TranscriptLine> lines = lines(concat(CONVERSATION_BEFORE, new String[]{
                "Head over to gardenshow.fm/newsletter and sign up today.",
                "That's gardenshow.fm/newsletter.",
        }, CONVERSATION_AFTER));
        Set<String> selfNames = TranscriptAdHeuristic.selfNames("The Garden Show",
                "www.gardenshow.fm");

        List<AdSegment> own = new TranscriptAdHeuristic().detect(lines, selfNames, ITEM);
        assertTrue(ofSource(own, AdSegment.Source.TRANSCRIPT).isEmpty());
        assertOneSegment(own, AdSegment.Source.SELF_PROMO, 3 * LINE_MS, 5 * LINE_MS);

        // without the show's names it reads like anyone else's call to action
        List<AdSegment> unknown = new TranscriptAdHeuristic().detect(lines, ITEM);
        assertOneSegment(unknown, AdSegment.Source.TRANSCRIPT, 3 * LINE_MS, 5 * LINE_MS);
    }

    @Test
    public void aSponsorOpenerIsNeverSelfPromotion() {
        List<TranscriptLine> lines = lines(concat(CONVERSATION_BEFORE, new String[]{
                "This episode is brought to you by our Patreon supporters.",
                "Go to patreon.com/gardenshow to become a member and get bonus episodes.",
                "That's patreon.com/gardenshow.",
        }, CONVERSATION_AFTER));

        List<AdSegment> found = new TranscriptAdHeuristic().detect(lines, ITEM);

        assertTrue(ofSource(found, AdSegment.Source.SELF_PROMO).isEmpty());
        assertOneSegment(found, AdSegment.Source.TRANSCRIPT, 3 * LINE_MS, 6 * LINE_MS);
    }

    @Test
    public void normalizeSpellsOutWebAddressesAndDropsPunctuation() {
        assertEquals("", TranscriptAdHeuristic.normalize(null));
        assertEquals("framer dot com slash practicalai",
                TranscriptAdHeuristic.normalize("Framer.com/practicalai"));
        assertEquals("framer dot com slash practical ai",
                TranscriptAdHeuristic.normalize("framer dot com slash practical ai"));
        assertEquals("get 20 percent off", TranscriptAdHeuristic.normalize("Get 20% off!"));
        assertEquals("dont stop its fine",
                TranscriptAdHeuristic.normalize("Don't stop — it’s fine."));
        assertEquals("r and d", TranscriptAdHeuristic.normalize("R&D"));
        assertEquals("a b c", TranscriptAdHeuristic.normalize("  a,\n b ...  c  "));
        assertEquals("the end", TranscriptAdHeuristic.normalize("The end."));
    }

    @Test
    public void selfNamesComeFromTheTitleAndTheWebsite() {
        Set<String> bitcoin = TranscriptAdHeuristic.selfNames("THE Bitcoin Podcast", null);
        assertTrue(bitcoin.toString(), bitcoin.contains("thebitcoinpodcast"));
        assertTrue(bitcoin.toString(), bitcoin.contains("bitcoin"));

        Set<String> practical = TranscriptAdHeuristic.selfNames("Practical AI",
                "www.practicalai.fm");
        assertTrue(practical.toString(), practical.contains("practicalai"));

        Set<String> hostOnly = TranscriptAdHeuristic.selfNames(null, "WWW.PracticalAI.fm");
        assertEquals(Collections.singleton("practicalai"), hostOnly);

        assertTrue(TranscriptAdHeuristic.selfNames(null, null).isEmpty());
        assertTrue(TranscriptAdHeuristic.selfNames("", "abc.com").isEmpty());
    }

    @Test
    public void brandsAreTheNamesBeforeASpokenWebAddress() {
        assertEquals(Collections.singleton("framer"),
                TranscriptAdHeuristic.brands("go to framer dot com slash practicalai"));

        Set<String> get = TranscriptAdHeuristic.brands("visit getacme dot com");
        assertTrue(get.toString(), get.contains("getacme"));
        assertTrue(get.toString(), get.contains("acme"));

        Set<String> wrapped = TranscriptAdHeuristic.brands("tryacmeapp dot com");
        assertTrue(wrapped.toString(), wrapped.contains("tryacmeapp"));
        assertTrue(wrapped.toString(), wrapped.contains("acme"));

        Set<String> twoWords = TranscriptAdHeuristic.brands("head to acme rockets dot com");
        assertTrue(twoWords.toString(), twoWords.contains("rockets"));
        assertTrue(twoWords.toString(), twoWords.contains("acmerockets"));

        assertTrue("names under four letters are too vague",
                TranscriptAdHeuristic.brands("go to abc dot com").isEmpty());
        assertTrue(TranscriptAdHeuristic.brands("no address here").isEmpty());
    }

    @Test
    public void isSelfRecognisesTheShowsOwnAddress() {
        Set<String> names = TranscriptAdHeuristic.selfNames("Practical AI", "practicalai.fm");
        assertTrue(TranscriptAdHeuristic.isSelf("go to practical ai dot fm slash join", names));
        assertTrue(TranscriptAdHeuristic.isSelf("go to practicalai dot fm", names));
        assertFalse(TranscriptAdHeuristic.isSelf("go to framer dot com slash practicalai", names));
        assertTrue("membership wording is self-promotion without any names",
                TranscriptAdHeuristic.isSelf("become a member today",
                        Collections.<String>emptySet()));
    }
}
