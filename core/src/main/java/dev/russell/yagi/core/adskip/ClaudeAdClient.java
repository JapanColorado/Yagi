package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.russell.yagi.model.feed.AdSegment;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Asks Claude, with the user's own Anthropic API key, where the ads in a transcript are.
 *
 * <p>One plain HTTPS call to the Messages API per transcript (two or more only for episodes of
 * many hours), built and read by {@link ClaudeAdPrompt}. Busy and overloaded answers are retried
 * twice; everything else that goes wrong is a {@link ClaudeException} and the caller falls back to
 * reading the transcript on the phone. Nothing but the episode's podcast title, episode title and
 * transcript text is sent.
 */
public final class ClaudeAdClient {

    static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    static final String MODELS_URL = "https://api.anthropic.com/v1/models/" + ClaudeAdPrompt.MODEL;
    static final String API_VERSION = "2026-10-21";
    /**
     * Haiku's default effort. Measured against low on 31 labelled episodes, it found every ad low
     * found and one more, with fewer false segments on rough machine transcripts, for about a
     * tenth of a cent more per episode (docs/AD_SKIP_TUNING.md).
     */
    static final String DEFAULT_EFFORT = "medium";

    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_WAIT_MS = 30_000;

    @NonNull
    private final OkHttpClient http;
    @NonNull
    private final String apiKey;
    @NonNull
    private final String effort;
    private long inputTokens;
    private long outputTokens;

    public ClaudeAdClient(@NonNull OkHttpClient http, @NonNull String apiKey) {
        this(http, apiKey, DEFAULT_EFFORT);
    }

    public ClaudeAdClient(@NonNull OkHttpClient http, @NonNull String apiKey,
                          @NonNull String effort) {
        this.http = http.newBuilder()
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                .build();
        this.apiKey = apiKey.trim();
        this.effort = effort;
    }

    /**
     * The ads Claude finds in {@code lines}, as {@link AdSegment.Source#TRANSCRIPT} segments.
     *
     * @throws ClaudeException when Claude could not be asked or its answer could not be used
     */
    @WorkerThread
    @NonNull
    public List<AdSegment> findAds(@NonNull List<TranscriptLine> lines,
                                   @Nullable String podcastTitle, @Nullable String episodeTitle,
                                   long feedItemId) throws ClaudeException {
        List<AdSegment> out = new ArrayList<>();
        for (List<TranscriptLine> chunk : ClaudeAdPrompt.chunks(lines)) {
            String body = ClaudeAdPrompt.requestBody(chunk, podcastTitle, episodeTitle, effort);
            String response = post(body);
            long[] usage = ClaudeAdPrompt.usage(response);
            inputTokens += usage[0];
            outputTokens += usage[1];
            out.addAll(ClaudeAdPrompt.toSegments(ClaudeAdPrompt.parseResponse(response), chunk,
                    feedItemId));
        }
        return out;
    }

    /** Input tokens billed for all {@link #findAds} calls of this client so far. */
    public long getInputTokens() {
        return inputTokens;
    }

    /** Output (including thinking) tokens billed for all {@link #findAds} calls so far. */
    public long getOutputTokens() {
        return outputTokens;
    }

    /**
     * Checks a key with a free request (looking up the model), so that a mistyped key is reported
     * when it is entered rather than at the next analysis.
     *
     * @throws ClaudeException with {@link ClaudeException#isKeyRejected()} for a bad key, or
     *                         without it when the check itself could not be made
     */
    @WorkerThread
    public void checkKey() throws ClaudeException {
        Request request = authorized(new Request.Builder().url(MODELS_URL).get()).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ClaudeException("key check failed: HTTP " + response.code(),
                        response.code(), null);
            }
        } catch (IOException e) {
            throw new ClaudeException("could not reach Anthropic", e);
        }
    }

    @NonNull
    private String post(@NonNull String body) throws ClaudeException {
        ClaudeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Request request = authorized(new Request.Builder().url(MESSAGES_URL)
                    .post(RequestBody.create(body, JSON))).build();
            long retryAfterMs = 2_000L * attempt;
            try (Response response = http.newCall(request).execute()) {
                String text = response.body().string();
                if (response.isSuccessful()) {
                    return text;
                }
                int code = response.code();
                last = new ClaudeException("HTTP " + code + ": " + abbreviate(text), code, null);
                if (!isRetryable(code)) {
                    throw last;
                }
                retryAfterMs = retryAfter(response.header("retry-after"), retryAfterMs);
            } catch (IOException e) {
                last = new ClaudeException("could not reach Anthropic", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(retryAfterMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ClaudeException("interrupted", e);
                }
            }
        }
        throw last;
    }

    @NonNull
    private Request.Builder authorized(@NonNull Request.Builder builder) {
        return builder
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json");
    }

    static boolean isRetryable(int code) {
        return code == 408 || code == 409 || code == 429 || code >= 500;
    }

    static long retryAfter(@Nullable String header, long fallbackMs) {
        if (header == null) {
            return fallbackMs;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return Math.max(0, Math.min(MAX_RETRY_WAIT_MS, seconds * 1000));
        } catch (NumberFormatException e) {
            return fallbackMs;
        }
    }

    @NonNull
    private static String abbreviate(@NonNull String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
