package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One timed line of an episode's transcript as the ad detection reads it: a cue (or a few short
 * cues of one speaker run together) with a start and an end that are always set. Built by
 * {@link TranscriptLines}; the index is the line's position in that list.
 */
public final class TranscriptLine {

    private final int index;
    private final long startMs;
    private final long endMs;
    @Nullable
    private final String speaker;
    @NonNull
    private final String text;

    public TranscriptLine(int index, long startMs, long endMs, @Nullable String speaker,
                          @NonNull String text) {
        this.index = index;
        this.startMs = startMs;
        this.endMs = Math.max(startMs, endMs);
        this.speaker = speaker;
        this.text = text;
    }

    public int getIndex() {
        return index;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public long getDurationMs() {
        return endMs - startMs;
    }

    @Nullable
    public String getSpeaker() {
        return speaker;
    }

    @NonNull
    public String getText() {
        return text;
    }

    /**
     * The playback position at a character offset into {@link #getText()}, assuming the words are
     * spoken at an even pace across the line. Offsets outside the text clamp to its ends.
     */
    public long timeAtChar(int offset) {
        int length = text.length();
        if (length == 0 || offset <= 0) {
            return startMs;
        }
        if (offset >= length) {
            return endMs;
        }
        return startMs + Math.round((double) getDurationMs() * offset / length);
    }

    @NonNull
    @Override
    public String toString() {
        return "TranscriptLine{" + index + " " + startMs + "-" + endMs + " " + text + "}";
    }
}
