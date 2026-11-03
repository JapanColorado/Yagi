package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds a few quoted words inside a transcript line, ignoring case and punctuation, so that an
 * ad's edge can be placed inside a long cue. Quotes come back from a language model and are not
 * always verbatim, so a quote that does not match whole is retried with fewer of its words.
 */
final class WordMatch {

    private WordMatch() {
    }

    /** One word of a text and where it sits. */
    private static final class Word {
        final String word;
        final int start;
        final int end;

        Word(String word, int start, int end) {
            this.word = word;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * The character offset in {@code text} where {@code quote} begins: the first place the
     * quote's first words (four, else three, else two) occur in order. 0 when they do not occur.
     */
    static int startOf(@NonNull String text, @NonNull String quote) {
        List<Word> words = words(text);
        List<Word> wanted = words(quote);
        for (int n = Math.min(4, wanted.size()); n >= 2; n--) {
            List<Word> head = wanted.subList(0, n);
            for (int i = 0; i + n <= words.size(); i++) {
                if (matchesAt(words, i, head)) {
                    return words.get(i).start;
                }
            }
        }
        return 0;
    }

    /**
     * The character offset in {@code text} just after {@code quote} ends: the last place the
     * quote's last words (four, else three, else two) occur in order. The length of the text when
     * they do not occur.
     */
    static int endOf(@NonNull String text, @NonNull String quote) {
        List<Word> words = words(text);
        List<Word> wanted = words(quote);
        for (int n = Math.min(4, wanted.size()); n >= 2; n--) {
            List<Word> tail = wanted.subList(wanted.size() - n, wanted.size());
            for (int i = words.size() - n; i >= 0; i--) {
                if (matchesAt(words, i, tail)) {
                    return words.get(i + n - 1).end;
                }
            }
        }
        return text.length();
    }

    private static boolean matchesAt(List<Word> words, int at, List<Word> wanted) {
        for (int k = 0; k < wanted.size(); k++) {
            if (!words.get(at + k).word.equals(wanted.get(k).word)) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    private static List<Word> words(@NonNull String text) {
        List<Word> out = new ArrayList<>();
        int i = 0;
        int length = text.length();
        while (i < length) {
            while (i < length && !Character.isLetterOrDigit(text.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < length && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '\'')) {
                i++;
            }
            if (i > start) {
                out.add(new Word(text.substring(start, i).replace("'", "").toLowerCase(Locale.ROOT),
                        start, i));
            }
        }
        return out;
    }
}
