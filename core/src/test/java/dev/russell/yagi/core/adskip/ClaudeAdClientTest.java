package dev.russell.yagi.core.adskip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import dev.russell.yagi.model.feed.AdSegment;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * The client against canned responses: an interceptor answers every call, so nothing reaches
 * the network.
 */
public class ClaudeAdClientTest {

    private static final long ITEM = 5L;

    /** Answers calls from a queue of canned responses and remembers the requests. */
    private static final class Canned implements Interceptor {
        final Deque<Response.Builder> responses = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();

        Canned then(int code, String json) {
            return then(code, json, null);
        }

        Canned then(int code, String json, String retryAfter) {
            Response.Builder builder = new Response.Builder()
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(code == 200 ? "OK" : "Error")
                    .body(ResponseBody.create(json, MediaType.get("application/json")));
            if (retryAfter != null) {
                builder.header("retry-after", retryAfter);
            }
            responses.add(builder);
            return this;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            requests.add(request);
            if (request.body() != null) {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                bodies.add(buffer.readUtf8());
            } else {
                bodies.add(null);
            }
            Response.Builder next = responses.poll();
            if (next == null) {
                throw new IOException("no canned response left");
            }
            return next.request(request).build();
        }

        OkHttpClient client() {
            return new OkHttpClient.Builder().addInterceptor(this).build();
        }
    }

    private static List<TranscriptLine> lines() {
        return Arrays.asList(
                new TranscriptLine(0, 0, 10_000, "Host", "Welcome to the show."),
                new TranscriptLine(1, 10_000, 40_000, "Host",
                        "This episode is brought to you by Acme, go to acme dot com"),
                new TranscriptLine(2, 40_000, 50_000, "Host", "Back to the show."));
    }

    private static String answer(long inputTokens, long outputTokens) {
        String ads = "{\"ads\":[{\"start_line\":1,\"start_words\":\"This episode is brought to\","
                + "\"end_line\":1,\"end_words\":\"acme dot com\",\"advertiser\":\"Acme\","
                + "\"kind\":\"sponsor\",\"certainty\":\"certain\"}]}";
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", ads);
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", inputTokens);
        usage.addProperty("cache_read_input_tokens", 0);
        usage.addProperty("output_tokens", outputTokens);
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        content.add(text);
        root.add("content", content);
        root.addProperty("stop_reason", "end_turn");
        root.add("usage", usage);
        return root.toString();
    }

    private static final String ERROR = "{\"type\":\"error\",\"error\":{\"type\":\"x\"}}";

    @Test
    public void findAdsPostsTheRequestWithTheKeyAndVersion() throws ClaudeException {
        Canned canned = new Canned().then(200, answer(100, 20));
        ClaudeAdClient client = new ClaudeAdClient(canned.client(), "  sk-ant-test  ");

        List<AdSegment> ads = client.findAds(lines(), "Garden Show", "Episode 1", ITEM);

        assertEquals(1, canned.requests.size());
        Request request = canned.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals(ClaudeAdClient.MESSAGES_URL, request.url().toString());
        assertEquals("sk-ant-test", request.header("x-api-key"));
        assertEquals("2026-10-21", request.header("anthropic-version"));
        assertEquals(ClaudeAdClient.API_VERSION, request.header("anthropic-version"));
        assertTrue(request.header("content-type").startsWith("application/json"));

        JsonObject body = JsonParser.parseString(canned.bodies.get(0)).getAsJsonObject();
        assertEquals(ClaudeAdPrompt.MODEL, body.get("model").getAsString());
        assertEquals(ClaudeAdClient.DEFAULT_EFFORT,
                body.getAsJsonObject("output_config").get("effort").getAsString());

        assertEquals(1, ads.size());
        assertEquals(AdSegment.Source.TRANSCRIPT, ads.get(0).getSource());
        assertEquals(10_000, ads.get(0).getStartMs());
        assertEquals(40_000, ads.get(0).getEndMs());
        assertEquals(ITEM, ads.get(0).getFeedItemId());
    }

    @Test
    public void theEffortIsSentAsGiven() throws ClaudeException {
        Canned canned = new Canned().then(200, answer(1, 1));
        new ClaudeAdClient(canned.client(), "k", "low").findAds(lines(), null, null, ITEM);
        JsonObject body = JsonParser.parseString(canned.bodies.get(0)).getAsJsonObject();
        assertEquals("low", body.getAsJsonObject("output_config").get("effort").getAsString());
    }

    @Test
    public void aRejectedKeyIsNotRetried() {
        Canned canned = new Canned().then(401, ERROR).then(200, answer(1, 1));
        ClaudeAdClient client = new ClaudeAdClient(canned.client(), "bad");
        try {
            client.findAds(lines(), null, null, ITEM);
            fail("expected a ClaudeException");
        } catch (ClaudeException e) {
            assertTrue(e.isKeyRejected());
            assertEquals(401, e.getHttpStatus());
        }
        assertEquals(1, canned.requests.size());
    }

    @Test
    public void aBadRequestIsNotRetried() {
        Canned canned = new Canned().then(400, ERROR).then(200, answer(1, 1));
        try {
            new ClaudeAdClient(canned.client(), "k").findAds(lines(), null, null, ITEM);
            fail("expected a ClaudeException");
        } catch (ClaudeException e) {
            assertFalse(e.isKeyRejected());
            assertEquals(400, e.getHttpStatus());
        }
        assertEquals(1, canned.requests.size());
    }

    @Test
    public void anOverloadedServiceIsRetried() throws ClaudeException {
        Canned canned = new Canned().then(529, ERROR, "0").then(200, answer(100, 20));
        ClaudeAdClient client = new ClaudeAdClient(canned.client(), "k");

        List<AdSegment> ads = client.findAds(lines(), null, null, ITEM);

        assertEquals(2, canned.requests.size());
        assertEquals(1, ads.size());
        assertEquals("the failed attempt costs nothing", 100, client.getInputTokens());
        assertEquals(canned.bodies.get(0), canned.bodies.get(1));
    }

    @Test
    public void retriesStopAfterThreeAttempts() {
        Canned canned = new Canned().then(529, ERROR, "0").then(500, ERROR, "0")
                .then(503, ERROR, "0").then(200, answer(1, 1));
        try {
            new ClaudeAdClient(canned.client(), "k").findAds(lines(), null, null, ITEM);
            fail("expected a ClaudeException");
        } catch (ClaudeException e) {
            assertEquals(503, e.getHttpStatus());
            assertFalse(e.isKeyRejected());
        }
        assertEquals(3, canned.requests.size());
    }

    @Test
    public void anUnusableAnswerIsAnError() {
        Canned canned = new Canned().then(200, "{\"stop_reason\":\"refusal\",\"content\":[]}");
        try {
            new ClaudeAdClient(canned.client(), "k").findAds(lines(), null, null, ITEM);
            fail("expected a ClaudeException");
        } catch (ClaudeException e) {
            assertEquals(ClaudeException.NO_STATUS, e.getHttpStatus());
        }
    }

    @Test
    public void tokenCountersAccumulateOverCalls() throws ClaudeException {
        Canned canned = new Canned().then(200, answer(100, 20)).then(200, answer(50, 5));
        ClaudeAdClient client = new ClaudeAdClient(canned.client(), "k");
        assertEquals(0, client.getInputTokens());
        assertEquals(0, client.getOutputTokens());

        client.findAds(lines(), null, null, ITEM);
        assertEquals(100, client.getInputTokens());
        assertEquals(20, client.getOutputTokens());

        client.findAds(lines(), null, null, ITEM);
        assertEquals(150, client.getInputTokens());
        assertEquals(25, client.getOutputTokens());
    }

    @Test
    public void noLinesMeansNoRequest() throws ClaudeException {
        Canned canned = new Canned();
        assertTrue(new ClaudeAdClient(canned.client(), "k")
                .findAds(new ArrayList<TranscriptLine>(), null, null, ITEM).isEmpty());
        assertTrue(canned.requests.isEmpty());
    }

    @Test
    public void checkKeyLooksUpTheModel() throws ClaudeException {
        Canned canned = new Canned().then(200, "{\"id\":\"" + ClaudeAdPrompt.MODEL + "\"}");
        new ClaudeAdClient(canned.client(), " sk-ant-test ").checkKey();

        assertEquals(1, canned.requests.size());
        Request request = canned.requests.get(0);
        assertEquals("GET", request.method());
        assertEquals(ClaudeAdClient.MODELS_URL, request.url().toString());
        assertEquals("https://api.anthropic.com/v1/models/claude-haiku-5-5",
                request.url().toString());
        assertEquals("sk-ant-test", request.header("x-api-key"));
        assertEquals("2026-10-21", request.header("anthropic-version"));
    }

    @Test
    public void checkKeyReportsARejectedKey() {
        for (int code : new int[]{401, 403}) {
            Canned canned = new Canned().then(code, ERROR);
            try {
                new ClaudeAdClient(canned.client(), "bad").checkKey();
                fail("expected a ClaudeException for HTTP " + code);
            } catch (ClaudeException e) {
                assertTrue(e.isKeyRejected());
                assertEquals(code, e.getHttpStatus());
            }
        }
    }

    @Test
    public void checkKeyReportsOtherFailuresWithoutBlamingTheKey() {
        Canned busy = new Canned().then(500, ERROR);
        try {
            new ClaudeAdClient(busy.client(), "k").checkKey();
            fail("expected a ClaudeException");
        } catch (ClaudeException e) {
            assertFalse(e.isKeyRejected());
            assertEquals(500, e.getHttpStatus());
        }

        Canned unreachable = new Canned();
        try {
            new ClaudeAdClient(unreachable.client(), "k").checkKey();
            fail("expected a ClaudeException");
        } catch (ClaudeException e) {
            assertFalse(e.isKeyRejected());
            assertEquals(ClaudeException.NO_STATUS, e.getHttpStatus());
        }
    }

    @Test
    public void retryableStatuses() {
        for (int code : new int[]{408, 409, 429, 500, 502, 503, 529}) {
            assertTrue(String.valueOf(code), ClaudeAdClient.isRetryable(code));
        }
        for (int code : new int[]{200, 400, 401, 403, 404, 413}) {
            assertFalse(String.valueOf(code), ClaudeAdClient.isRetryable(code));
        }
    }

    @Test
    public void retryAfterIsReadInSecondsAndCapped() {
        assertEquals(2000, ClaudeAdClient.retryAfter(null, 2000));
        assertEquals(0, ClaudeAdClient.retryAfter("0", 2000));
        assertEquals(5000, ClaudeAdClient.retryAfter(" 5 ", 2000));
        assertEquals(30_000, ClaudeAdClient.retryAfter("3600", 2000));
        assertEquals(0, ClaudeAdClient.retryAfter("-3", 2000));
        assertEquals("an HTTP date is not understood", 2000,
                ClaudeAdClient.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT", 2000));
    }
}
