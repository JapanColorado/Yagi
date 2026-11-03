package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.russell.yagi.model.feed.AdSegment;

/** The Messages API request built for a transcript, and the reading of Claude's answer. */
public class ClaudeAdPromptTest {

    private static final long ITEM = 11L;

    private static List<TranscriptLine> shortTranscript() {
        return Arrays.asList(
                new TranscriptLine(0, 5_000, 10_000, "Host", "Hello and welcome."),
                new TranscriptLine(1, 65_000, 70_000, null, "No speaker here.\nSecond row."),
                new TranscriptLine(2, 3_723_000, 3_730_000, "Guest", "Past the hour."));
    }

    private static JsonObject body(List<TranscriptLine> lines) {
        return JsonParser.parseString(
                ClaudeAdPrompt.requestBody(lines, " Garden Show ", "Episode 12", "medium"))
                .getAsJsonObject();
    }

    // --- requestBody ---

    @Test
    public void requestBodyAsksHaikuForStructuredOutput() {
        JsonObject body = body(shortTranscript());

        assertEquals("claude-haiku-5-5", body.get("model").getAsString());
        assertEquals(ClaudeAdPrompt.MODEL, body.get("model").getAsString());
        assertTrue(body.get("max_tokens").getAsInt() > 0);
        assertEquals(ClaudeAdPrompt.SYSTEM_PROMPT, body.get("system").getAsString());

        JsonObject outputConfig = body.getAsJsonObject("output_config");
        assertEquals("medium", outputConfig.get("effort").getAsString());
        JsonObject format = outputConfig.getAsJsonObject("format");
        assertEquals("json_schema", format.get("type").getAsString());
        assertNotNull(format.getAsJsonObject("schema"));
    }

    @Test
    public void requestBodyPassesTheEffortThrough() {
        JsonObject body = JsonParser.parseString(ClaudeAdPrompt.requestBody(shortTranscript(),
                null, null, "low")).getAsJsonObject();
        assertEquals("low", body.getAsJsonObject("output_config").get("effort").getAsString());
    }

    @Test
    public void requestBodyHasNoSamplingParametersAndNoPrefill() {
        JsonObject body = body(shortTranscript());
        assertFalse(body.has("temperature"));
        assertFalse(body.has("top_p"));
        assertFalse(body.has("top_k"));

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(1, messages.size());
        for (JsonElement message : messages) {
            assertEquals("user", message.getAsJsonObject().get("role").getAsString());
        }
    }

    @Test
    public void theSchemaIsStrict() {
        JsonObject schema = body(shortTranscript()).getAsJsonObject("output_config")
                .getAsJsonObject("format").getAsJsonObject("schema");
        assertStrictObject(schema);
        assertEquals(Collections.singleton("ads"), schema.getAsJsonObject("properties").keySet());

        JsonObject ads = schema.getAsJsonObject("properties").getAsJsonObject("ads");
        assertEquals("array", ads.get("type").getAsString());
        JsonObject ad = ads.getAsJsonObject("items");
        assertStrictObject(ad);
        assertEquals(new HashSet<>(Arrays.asList("start_line", "start_words", "end_line",
                        "end_words", "advertiser", "kind", "certainty")),
                ad.getAsJsonObject("properties").keySet());
        assertEquals(Arrays.asList("sponsor", "cross_promotion", "self_promotion"),
                strings(ad.getAsJsonObject("properties").getAsJsonObject("kind")
                        .getAsJsonArray("enum")));
        assertEquals(Arrays.asList("certain", "likely"),
                strings(ad.getAsJsonObject("properties").getAsJsonObject("certainty")
                        .getAsJsonArray("enum")));
    }

    /** {@code additionalProperties: false} and every property required. */
    private static void assertStrictObject(JsonObject schema) {
        assertEquals("object", schema.get("type").getAsString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        Set<String> properties = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry
                : schema.getAsJsonObject("properties").entrySet()) {
            properties.add(entry.getKey());
        }
        assertEquals(properties, new HashSet<>(strings(schema.getAsJsonArray("required"))));
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement element : array) {
            out.add(element.getAsString());
        }
        return out;
    }

    @Test
    public void theTranscriptIsSentAsNumberedTimedLines() {
        String content = body(shortTranscript()).getAsJsonArray("messages").get(0)
                .getAsJsonObject().get("content").getAsString();

        assertTrue(content, content.startsWith("Podcast: Garden Show\nEpisode: Episode 12\n"));
        assertTrue(content, content.contains("<transcript>\n"
                + "L0 [00:05] Host: Hello and welcome.\n"
                + "L1 [01:05] No speaker here. Second row.\n"
                + "L2 [1:02:03] Guest: Past the hour.\n"
                + "</transcript>"));
    }

    @Test
    public void blankTitlesAreLeftOut() {
        String content = JsonParser.parseString(ClaudeAdPrompt.requestBody(shortTranscript(),
                        "  ", null, "medium")).getAsJsonObject().getAsJsonArray("messages").get(0)
                .getAsJsonObject().get("content").getAsString();
        assertFalse(content.contains("Podcast:"));
        assertFalse(content.contains("Episode:"));
        assertTrue(content.startsWith("\n<transcript>\n"));
    }

    @Test
    public void formatLineUsesMinutesBelowAnHourAndHoursAbove() {
        assertEquals("L7 [59:59] x", ClaudeAdPrompt.formatLine(
                new TranscriptLine(7, 3_599_999, 3_600_000, null, "x")));
        assertEquals("L8 [1:00:00] x", ClaudeAdPrompt.formatLine(
                new TranscriptLine(8, 3_600_000, 3_601_000, null, "x")));
        assertEquals("L9 [10:00:01] Ann: x", ClaudeAdPrompt.formatLine(
                new TranscriptLine(9, 36_001_000, 36_002_000, "Ann", "x")));
    }

    // --- chunks ---

    @Test
    public void aShortTranscriptIsOneChunk() {
        List<TranscriptLine> lines = shortTranscript();
        List<List<TranscriptLine>> chunks = ClaudeAdPrompt.chunks(lines);
        assertEquals(1, chunks.size());
        assertEquals(lines, chunks.get(0));
        assertTrue(ClaudeAdPrompt.chunks(Collections.<TranscriptLine>emptyList()).isEmpty());
    }

    @Test
    public void aLongTranscriptIsSplitWithOverlap() {
        StringBuilder words = new StringBuilder();
        while (words.length() < 1000) {
            words.append("the quick brown fox jumps over the lazy dog ");
        }
        List<TranscriptLine> lines = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            lines.add(new TranscriptLine(i, i * 10_000L, (i + 1) * 10_000L, "Host",
                    words.toString()));
        }

        List<List<TranscriptLine>> chunks = ClaudeAdPrompt.chunks(lines);

        assertTrue("chunks: " + chunks.size(), chunks.size() >= 3);
        assertEquals(0, chunks.get(0).get(0).getIndex());
        List<TranscriptLine> lastChunk = chunks.get(chunks.size() - 1);
        assertEquals(599, lastChunk.get(lastChunk.size() - 1).getIndex());
        for (int c = 0; c < chunks.size(); c++) {
            List<TranscriptLine> chunk = chunks.get(c);
            int chars = 0;
            for (TranscriptLine line : chunk) {
                chars += ClaudeAdPrompt.formatLine(line).length() + 1;
            }
            assertTrue("chunk " + c + " has " + chars + " characters",
                    chars <= ClaudeAdPrompt.MAX_CHUNK_CHARS + 1);
            if (c > 0) {
                List<TranscriptLine> previous = chunks.get(c - 1);
                int previousLast = previous.get(previous.size() - 1).getIndex();
                assertEquals("chunk " + c + " repeats the last lines of the one before",
                        previousLast - ClaudeAdPrompt.CHUNK_OVERLAP_LINES + 1,
                        chunk.get(0).getIndex());
            }
        }
    }

    @Test
    public void aSingleHugeLineIsStillSent() {
        StringBuilder huge = new StringBuilder();
        while (huge.length() <= ClaudeAdPrompt.MAX_CHUNK_CHARS) {
            huge.append("word ");
        }
        List<TranscriptLine> lines = Arrays.asList(
                new TranscriptLine(0, 0, 1000, null, huge.toString()),
                new TranscriptLine(1, 1000, 2000, null, "short"));
        List<List<TranscriptLine>> chunks = ClaudeAdPrompt.chunks(lines);
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).size());
        // the overlap never moves backwards past the previous chunk's start
        assertEquals(1, chunks.get(1).get(0).getIndex());
    }

    // --- parseResponse ---

    private static String response(String stopReason, String answerJson) {
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "thinking");
        thinking.addProperty("thinking", "Line 3 opens a sponsor read.");
        thinking.addProperty("signature", "abc");
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", answerJson);
        JsonArray content = new JsonArray();
        content.add(thinking);
        content.add(text);
        JsonObject root = new JsonObject();
        root.addProperty("id", "msg_1");
        root.addProperty("type", "message");
        root.addProperty("role", "assistant");
        root.add("content", content);
        root.addProperty("stop_reason", stopReason);
        return root.toString();
    }

    private static final String ONE_AD = "{\"ads\":[{\"start_line\":3,"
            + "\"start_words\":\"This episode is brought\",\"end_line\":5,"
            + "\"end_words\":\"code SHOW\",\"advertiser\":\"Acme\",\"kind\":\"sponsor\","
            + "\"certainty\":\"certain\"},{\"start_line\":9,\"start_words\":\"support us\","
            + "\"end_line\":9,\"end_words\":\"on Patreon\",\"advertiser\":\"\","
            + "\"kind\":\"self_promotion\",\"certainty\":\"likely\"}]}";

    @Test
    public void parseResponseReadsTheTextBlockAfterThinking() throws ClaudeException {
        List<ClaudeAdPrompt.Finding> findings =
                ClaudeAdPrompt.parseResponse(response("end_turn", ONE_AD));

        assertEquals(2, findings.size());
        ClaudeAdPrompt.Finding ad = findings.get(0);
        assertEquals(3, ad.startLine);
        assertEquals("This episode is brought", ad.startWords);
        assertEquals(5, ad.endLine);
        assertEquals("code SHOW", ad.endWords);
        assertEquals("Acme", ad.advertiser);
        assertEquals("sponsor", ad.kind);
        assertTrue(ad.certain);
        ClaudeAdPrompt.Finding promo = findings.get(1);
        assertEquals("self_promotion", promo.kind);
        assertFalse(promo.certain);
    }

    @Test
    public void parseResponseAcceptsNoAds() throws ClaudeException {
        assertTrue(ClaudeAdPrompt.parseResponse(response("end_turn", "{\"ads\":[]}")).isEmpty());
    }

    @Test
    public void aRefusalIsAnError() {
        assertThrowsClaude(response("refusal", "{\"ads\":[]}"));
    }

    @Test
    public void aCutOffAnswerIsAnError() {
        assertThrowsClaude(response("max_tokens", "{\"ads\":["));
        assertThrowsClaude(response("max_tokens", "{\"ads\":[]}"));
    }

    @Test
    public void garbageIsAnError() {
        assertThrowsClaude("");
        assertThrowsClaude("not json at all");
        assertThrowsClaude("[1, 2, 3]");
        assertThrowsClaude("{\"type\":\"error\"}");
        assertThrowsClaude("{\"content\":\"text\"}");
        assertThrowsClaude("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"...\"}]}");
        assertThrowsClaude(response("end_turn", "Here are the ads: none."));
        assertThrowsClaude(response("end_turn", "[]"));
        assertThrowsClaude(response("end_turn", "{\"ads\":[1]}"));
        assertThrowsClaude(response("end_turn", "{\"ads\":[[]]}"));
    }

    @Test
    public void adsThatAreNotAListAreAnError() {
        assertThrowsClaude(response("end_turn", "{\"ads\":\"none\"}"));
        assertThrowsClaude(response("end_turn", "{\"ads\":{}}"));
    }

    @Test
    public void missingFieldsAreAnError() {
        assertThrowsClaude(response("end_turn", "{}"));
        assertThrowsClaude(response("end_turn", "{\"ads\":[{\"start_words\":\"a b\","
                + "\"end_line\":2,\"end_words\":\"c d\",\"advertiser\":\"x\","
                + "\"kind\":\"sponsor\",\"certainty\":\"certain\"}]}"));
        assertThrowsClaude(response("end_turn", "{\"ads\":[{\"start_line\":1,"
                + "\"start_words\":\"a b\",\"end_words\":\"c d\",\"advertiser\":\"x\","
                + "\"kind\":\"sponsor\",\"certainty\":\"certain\"}]}"));
        assertThrowsClaude(response("end_turn", "{\"ads\":[{\"start_line\":\"one\","
                + "\"start_words\":\"a b\",\"end_line\":2,\"end_words\":\"c d\","
                + "\"advertiser\":\"x\",\"kind\":\"sponsor\",\"certainty\":\"certain\"}]}"));
    }

    private static void assertThrowsClaude(String body) {
        try {
            ClaudeAdPrompt.parseResponse(body);
            fail("expected a ClaudeException for " + body);
        } catch (ClaudeException e) {
            assertEquals(ClaudeException.NO_STATUS, e.getHttpStatus());
            assertFalse(e.isKeyRejected());
        }
    }

    // --- toSegments ---

    private static final String LONG_CUE = "And we are back. This episode is brought to you by "
            + "Acme rockets. Acme rockets go really fast, so go to acme.com/show today. "
            + "Okay, now where were we?";

    /** Lines numbered from 40, as in a second chunk. */
    private static List<TranscriptLine> chunkLines() {
        return Arrays.asList(
                new TranscriptLine(40, 600_000, 660_000, "Host", LONG_CUE),
                new TranscriptLine(41, 660_000, 664_000, "Host", "Short line."),
                new TranscriptLine(42, 664_000, 1_400_000, "Host", "A very long rambling cue."));
    }

    private static ClaudeAdPrompt.Finding finding(int startLine, String startWords, int endLine,
                                                  String endWords, String kind, boolean certain) {
        return new ClaudeAdPrompt.Finding(startLine, startWords, endLine, endWords, "Acme", kind,
                certain);
    }

    @Test
    public void toSegmentsPlacesTheEdgesInsideALongLineByTheQuotedWords() {
        List<TranscriptLine> lines = chunkLines();
        List<AdSegment> segments = ClaudeAdPrompt.toSegments(Collections.singletonList(
                finding(40, "This episode is brought to", 40, "acme.com/show today", "sponsor",
                        true)), lines, ITEM);

        assertEquals(1, segments.size());
        AdSegment ad = segments.get(0);
        TranscriptLine cue = lines.get(0);
        assertEquals(cue.timeAtChar(LONG_CUE.indexOf("This")), ad.getStartMs());
        assertEquals(cue.timeAtChar(LONG_CUE.indexOf("today") + "today".length()), ad.getEndMs());
        assertTrue(ad.getStartMs() > cue.getStartMs());
        assertTrue(ad.getEndMs() < cue.getEndMs());
        assertEquals(AdSegment.Source.TRANSCRIPT, ad.getSource());
        assertEquals(ClaudeAdPrompt.CONFIDENCE_CERTAIN, ad.getConfidence(), 1e-6);
        assertEquals(0.9f, ad.getConfidence(), 1e-6);
        assertEquals(ITEM, ad.getFeedItemId());
    }

    @Test
    public void toSegmentsSpansSeveralLines() {
        List<TranscriptLine> lines = chunkLines();
        List<AdSegment> segments = ClaudeAdPrompt.toSegments(Collections.singletonList(
                finding(40, "This episode is brought to", 41, "Short line", "cross_promotion",
                        false)), lines, ITEM);
        assertEquals(1, segments.size());
        assertEquals(lines.get(0).timeAtChar(LONG_CUE.indexOf("This")),
                segments.get(0).getStartMs());
        assertEquals(lines.get(1).timeAtChar("Short line".length()), segments.get(0).getEndMs());
        assertEquals(AdSegment.Source.TRANSCRIPT, segments.get(0).getSource());
        assertEquals(ClaudeAdPrompt.CONFIDENCE_LIKELY, segments.get(0).getConfidence(), 1e-6);
        assertEquals(0.65f, segments.get(0).getConfidence(), 1e-6);
    }

    @Test
    public void selfPromotionBecomesSelfPromo() {
        List<AdSegment> segments = ClaudeAdPrompt.toSegments(Collections.singletonList(
                finding(40, "This episode is brought to", 41, "Short line", "self_promotion",
                        true)), chunkLines(), ITEM);
        assertEquals(1, segments.size());
        assertEquals(AdSegment.Source.SELF_PROMO, segments.get(0).getSource());
    }

    @Test
    public void unmatchedWordsFallBackToTheWholeLine() {
        List<TranscriptLine> lines = chunkLines();
        List<AdSegment> segments = ClaudeAdPrompt.toSegments(Collections.singletonList(
                finding(40, "zzz yyy", 41, "qqq www", "sponsor", true)), lines, ITEM);
        assertEquals(1, segments.size());
        assertEquals(lines.get(0).getStartMs(), segments.get(0).getStartMs());
        assertEquals(lines.get(1).getEndMs(), segments.get(0).getEndMs());
    }

    @Test
    public void outOfRangeLinesAreDropped() {
        List<ClaudeAdPrompt.Finding> findings = Arrays.asList(
                finding(39, "a b", 41, "c d", "sponsor", true),
                finding(40, "a b", 43, "c d", "sponsor", true),
                finding(41, "a b", 40, "c d", "sponsor", true));
        assertTrue(ClaudeAdPrompt.toSegments(findings, chunkLines(), ITEM).isEmpty());
        assertTrue(ClaudeAdPrompt.toSegments(findings,
                Collections.<TranscriptLine>emptyList(), ITEM).isEmpty());
    }

    @Test
    public void implausibleLengthsAreDropped() {
        List<TranscriptLine> lines = chunkLines();
        // line 41 lasts 4 s
        List<AdSegment> tooShort = ClaudeAdPrompt.toSegments(Collections.singletonList(
                finding(41, "", 41, "", "sponsor", true)), lines, ITEM);
        assertTrue(tooShort.toString(), tooShort.isEmpty());
        // line 40 to the end of line 42 is 800 s
        List<AdSegment> tooLong = ClaudeAdPrompt.toSegments(Collections.singletonList(
                finding(40, "And we are back", 42, "rambling cue", "sponsor", true)), lines, ITEM);
        assertTrue(tooLong.toString(), tooLong.isEmpty());
        // the whole of line 40 is 60 s, inside the limits
        assertEquals(1, ClaudeAdPrompt.toSegments(Collections.singletonList(
                finding(40, "", 40, "", "sponsor", true)), lines, ITEM).size());
    }

    // --- usage ---

    @Test
    public void usageSumsInputAndCacheTokens() {
        assertArrayEquals(new long[]{150, 50}, ClaudeAdPrompt.usage("{\"usage\":{"
                + "\"input_tokens\":100,\"cache_creation_input_tokens\":20,"
                + "\"cache_read_input_tokens\":30,\"output_tokens\":50}}"));
        assertArrayEquals(new long[]{100, 0}, ClaudeAdPrompt.usage(
                "{\"usage\":{\"input_tokens\":100,\"cache_read_input_tokens\":null}}"));
    }

    @Test
    public void usageIsZeroWhenTheResponseDoesNotSay() {
        assertArrayEquals(new long[]{0, 0}, ClaudeAdPrompt.usage("{}"));
        assertArrayEquals(new long[]{0, 0}, ClaudeAdPrompt.usage("garbage"));
        assertArrayEquals(new long[]{0, 0}, ClaudeAdPrompt.usage("[]"));
        assertArrayEquals(new long[]{0, 0}, ClaudeAdPrompt.usage("{\"usage\":5}"));
    }

    @Test
    public void theParsedFindingsArePlacedEndToEnd() throws ClaudeException {
        String answer = "{\"ads\":[{\"start_line\":40,\"start_words\":\"This episode is brought\","
                + "\"end_line\":40,\"end_words\":\"acme.com/show today\",\"advertiser\":\"Acme\","
                + "\"kind\":\"sponsor\",\"certainty\":\"certain\"}]}";
        List<TranscriptLine> lines = chunkLines();
        List<AdSegment> segments = ClaudeAdPrompt.toSegments(
                ClaudeAdPrompt.parseResponse(response("end_turn", answer)), lines, ITEM);
        assertEquals(1, segments.size());
        assertSame(AdSegment.Source.TRANSCRIPT, segments.get(0).getSource());
        assertEquals(lines.get(0).timeAtChar(LONG_CUE.indexOf("This")),
                segments.get(0).getStartMs());
    }
}
