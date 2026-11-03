package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.russell.yagi.model.transcript.Transcript;
import dev.russell.yagi.model.transcript.TranscriptSegment;

/**
 * Turns a parsed transcript into the timed lines the transcript ad detectors read.
 *
 * <p>Only a timed transcript can place an ad, so an untimed one (plain text, most HTML) gives no
 * lines. Cues are taken as published, except that very short cues of the same speaker are run
 * together: word-level JSON transcripts would otherwise cost one line, and one line number in the
 * Claude prompt, per word. A cue without an end ends where the next one starts, or after the time
 * its words take to say when the next cue is far away (music, a long pause).
 */
public final class TranscriptLines {

    /** Cues are joined until a line lasts at least this long. */
    static final long MIN_LINE_MS = 6_000;
    /** ... but never across a pause longer than this. */
    static final long MAX_JOIN_GAP_MS = 2_000;
    /** Spoken English runs at about 15 characters a second; used to end a cue that has no end. */
    static final long MS_PER_CHAR = 65;

    /**
     * A transcript that ends this long after the episode does belongs to a different cut of the
     * audio, and its times cannot be trusted.
     */
    static final long MAX_OVERHANG_MS = 15_000;
    /**
     * A transcript whose last words come this long before the end of the episode was most likely
     * made before the host stitched ads into the file, which shifts every later time. Trailing
     * music rarely lasts this long.
     */
    static final long MAX_SHORTFALL_MS = 4 * 60_000;

    private TranscriptLines() {
    }

    /**
     * The timed lines of {@code transcript}, in order; empty when it is untimed or empty.
     */
    @NonNull
    public static List<TranscriptLine> of(@Nullable Transcript transcript) {
        if (transcript == null || !transcript.isTimed()) {
            return Collections.emptyList();
        }
        List<TranscriptSegment> segments = transcript.getSegments();
        List<TranscriptLine> lines = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String speaker = null;
        long start = -1;
        long end = -1;
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment segment = segments.get(i);
            String words = segment.getText().trim();
            if (words.isEmpty()) {
                continue;
            }
            long segmentStart = segment.getStartMs();
            long segmentEnd = endOf(segments, i);
            boolean join = start >= 0
                    && end - start < MIN_LINE_MS
                    && segmentStart - end <= MAX_JOIN_GAP_MS
                    && sameSpeaker(speaker, segment.getSpeaker());
            if (!join) {
                if (start >= 0) {
                    lines.add(new TranscriptLine(lines.size(), start, end, speaker, text.toString()));
                }
                text.setLength(0);
                start = segmentStart;
                speaker = segment.getSpeaker();
            } else {
                text.append(' ');
            }
            text.append(words);
            end = Math.max(end, segmentEnd);
        }
        if (start >= 0) {
            lines.add(new TranscriptLine(lines.size(), start, end, speaker, text.toString()));
        }
        return lines;
    }

    /**
     * Whether the transcript's times match an episode of {@code durationMs}. An unknown duration
     * (not positive) is taken on trust.
     */
    public static boolean fitsEpisode(@NonNull List<TranscriptLine> lines, long durationMs) {
        if (lines.isEmpty()) {
            return false;
        }
        if (durationMs <= 0) {
            return true;
        }
        long lastEnd = lines.get(lines.size() - 1).getEndMs();
        return lastEnd <= durationMs + MAX_OVERHANG_MS && durationMs - lastEnd <= MAX_SHORTFALL_MS;
    }

    /** The span the transcript speaks over, from its first line's start to its last line's end. */
    @NonNull
    public static long[] span(@NonNull List<TranscriptLine> lines) {
        if (lines.isEmpty()) {
            return new long[]{0, 0};
        }
        return new long[]{lines.get(0).getStartMs(), lines.get(lines.size() - 1).getEndMs()};
    }

    private static long endOf(List<TranscriptSegment> segments, int i) {
        TranscriptSegment segment = segments.get(i);
        long start = segment.getStartMs();
        long spoken = start + segment.getText().trim().length() * MS_PER_CHAR;
        long end = segment.getEndMs();
        if (end > start) {
            return end;
        }
        for (int j = i + 1; j < segments.size(); j++) {
            long next = segments.get(j).getStartMs();
            if (next > start) {
                return Math.min(next, spoken);
            }
        }
        return spoken;
    }

    private static boolean sameSpeaker(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }
}
