package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A call to Claude that did not produce ad segments: the key was refused, the service was busy,
 * the model declined or answered in an unexpected shape. The caller falls back to reading the
 * transcript on the phone.
 */
public class ClaudeException extends Exception {

    /** No HTTP status: the request never got a response, or the response was the problem. */
    public static final int NO_STATUS = 0;

    private final int httpStatus;

    public ClaudeException(@NonNull String message) {
        this(message, NO_STATUS, null);
    }

    public ClaudeException(@NonNull String message, @Nullable Throwable cause) {
        this(message, NO_STATUS, cause);
    }

    public ClaudeException(@NonNull String message, int httpStatus, @Nullable Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** True when the API key itself was rejected; retrying will not help until it is changed. */
    public boolean isKeyRejected() {
        return httpStatus == 401 || httpStatus == 403;
    }
}
