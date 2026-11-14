package dev.russell.yagi.core.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.russell.yagi.model.feed.Chapter;

/**
 * Reads the chapter list many publishers put in an episode's description ("00:00 Intro",
 * "(12:34) Sponsor: Acme", "1:02:03 - Listener mail", or "Intro - 00:00") and turns it into
 * {@link Chapter}s.
 *
 * <p>It only ever looks at whole lines: a timestamp at the start of a line (after an optional
 * bullet, list number or bracket) with the title after it, or a title with the timestamp at the
 * end of the line. A time mentioned in the middle of a sentence is never a chapter. A short
 * timestamp is always minutes and seconds ("62:30" is an hour in), because that is what chapter
 * lists use.
 *
 * <p>To count as a chapter list there must be at least {@link #MIN_CHAPTERS} such lines whose
 * times rise from one line to the next; when the description has several runs of rising times,
 * the longest one wins. Times at or past the episode's end are dropped. Pure Java (jsoup only),
 * so it is tested on the JVM.
 */
public final class DescriptionChapterParser {

    /** Fewer timestamped lines than this are not a chapter list. */
    static final int MIN_CHAPTERS = 2;

    /** Lines longer than this are prose, even when they happen to start with a time. */
    private static final int MAX_LINE_LENGTH = 300;

    /** Titles are cut to this many characters. */
    private static final int MAX_TITLE_LENGTH = 160;

    /** h:mm:ss, hh:mm:ss, m:ss, mm:ss or mmm:ss. Seconds and, with hours, minutes are checked later. */
    private static final String TIME = "(?:(\\d{1,2}):)?(\\d{1,3}):(\\d{2})";

    /** Bullets, dashes and list numbers that may come before a leading timestamp. */
    private static final String LEAD = "^[\\s\\p{Pd}*\u2022\u00b7\u25aa\u25e6\u2023>|]*(?:\\d{1,3}[.)]\\s+)?";

    /** "00:00 Intro", "(00:00) Intro", "[00:00] - Intro", "00:00 - 05:12 Intro". */
    private static final Pattern TIME_FIRST = Pattern.compile(
            LEAD + "[(\\[]?\\s*" + TIME + "\\s*[)\\]]?(?=$|[\\s\\p{Pd}:|.,)\\]])(.*)$");

    /** "Intro - 00:00", "Intro (00:00)", "Intro [00:00]". */
    private static final Pattern TIME_LAST = Pattern.compile(
            "^(.*?\\S)\\s*[\\p{Pd}:|]?\\s*[(\\[]?\\s*" + TIME + "\\s*[)\\]]?\\s*$");

    /** A second timestamp right after the first one, as in a range "00:00 - 05:12 Intro". */
    private static final Pattern RANGE_END = Pattern.compile(
            "^[\\s\\p{Pd}]*" + TIME + "(?=$|[\\s\\p{Pd}:|.,)\\]])");

    /** Separators trimmed off both ends of a title. */
    private static final Pattern EDGE_SEPARATORS = Pattern.compile(
            "^[\\s\\p{Pd}:|.,)\\]\u2022\u00b7]+|[\\s\\p{Pd}:|,(\\[\u2022\u00b7]+$");

    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");

    private static final Pattern HTML_LINE_BREAKS = Pattern.compile("<br\\b|<p\\b|<li\\b|<div\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> BLOCK_TAGS = Arrays.asList("p", "div", "li", "ul", "ol",
            "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table", "blockquote", "pre", "section");

    private DescriptionChapterParser() {
    }

    /**
     * Parses the chapters out of a description.
     *
     * @param description the episode's description, HTML or plain text; may be null
     * @param durationMs  the episode's duration; when positive, times at or past it are dropped
     * @return the chapters, earliest first, or null when the description has no chapter list
     */
    @Nullable
    public static List<Chapter> parse(@Nullable String description, long durationMs) {
        if (description == null || description.isEmpty()) {
            return null;
        }
        List<Candidate> timeFirst = new ArrayList<>();
        List<Candidate> timeLast = new ArrayList<>();
        for (String line : toLines(description)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.length() > MAX_LINE_LENGTH) {
                continue;
            }
            Candidate first = matchTimeFirst(trimmed);
            if (first != null) {
                timeFirst.add(first);
                continue;
            }
            Candidate last = matchTimeLast(trimmed);
            if (last != null) {
                timeLast.add(last);
            }
        }
        // One description uses one style; mixing them would pick up stray times from prose.
        List<Candidate> candidates = timeFirst.size() >= timeLast.size() ? timeFirst : timeLast;
        List<Candidate> inRange = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (durationMs <= 0 || candidate.startMs < durationMs) {
                inRange.add(candidate);
            }
        }
        List<Candidate> run = longestRisingRun(inRange);
        if (run.size() < MIN_CHAPTERS) {
            return null;
        }
        List<Chapter> chapters = new ArrayList<>(run.size());
        for (Candidate candidate : run) {
            chapters.add(new Chapter(candidate.startMs, candidate.title, null, null));
        }
        return chapters;
    }

    /** The description as text lines, with HTML block elements and line breaks as line ends. */
    @NonNull
    static List<String> toLines(@NonNull String description) {
        String html = description;
        if (!HTML_LINE_BREAKS.matcher(html).find()) {
            // Plain text (or HTML without any line structure): its newlines are the line ends.
            html = html.replace("\r\n", "\n").replace("\n", "<br>");
        }
        Document document = Jsoup.parseBodyFragment(html);
        final StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(@NonNull Node node, int depth) {
                if (node instanceof TextNode) {
                    text.append(((TextNode) node).text());
                } else if (node instanceof Element) {
                    String tag = ((Element) node).normalName();
                    if ("br".equals(tag) || BLOCK_TAGS.contains(tag)) {
                        text.append('\n');
                    }
                }
            }

            @Override
            public void tail(@NonNull Node node, int depth) {
                if (node instanceof Element && BLOCK_TAGS.contains(((Element) node).normalName())) {
                    text.append('\n');
                }
            }
        }, document.body());
        return Arrays.asList(text.toString().replace('\u00a0', ' ').split("\n"));
    }

    @Nullable
    private static Candidate matchTimeFirst(@NonNull String line) {
        Matcher matcher = TIME_FIRST.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        long start = toMs(matcher.group(1), matcher.group(2), matcher.group(3));
        if (start < 0) {
            return null;
        }
        String rest = matcher.group(4);
        Matcher range = RANGE_END.matcher(rest);
        if (range.find()) {
            rest = rest.substring(range.end());
        }
        String title = cleanTitle(rest);
        return title != null ? new Candidate(start, title) : null;
    }

    @Nullable
    private static Candidate matchTimeLast(@NonNull String line) {
        Matcher matcher = TIME_LAST.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        long start = toMs(matcher.group(2), matcher.group(3), matcher.group(4));
        if (start < 0) {
            return null;
        }
        String title = cleanTitle(matcher.group(1));
        return title != null ? new Candidate(start, title) : null;
    }

    /** The title without surrounding separators, or null when nothing readable is left. */
    @Nullable
    private static String cleanTitle(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String title = EDGE_SEPARATORS.matcher(raw.trim()).replaceAll("").trim();
        title = title.replaceAll("\\s+", " ");
        if (!HAS_WORD.matcher(title).find()) {
            return null;
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH).trim() + "\u2026";
        }
        return title;
    }

    /** Milliseconds, or -1 when the fields are not a valid time. */
    private static long toMs(@Nullable String hours, @NonNull String minutes, @NonNull String seconds) {
        long h = hours != null ? Long.parseLong(hours) : 0;
        long m = Long.parseLong(minutes);
        long s = Long.parseLong(seconds);
        if (s >= 60 || (hours != null && m >= 60)) {
            return -1;
        }
        return ((h * 60 + m) * 60 + s) * 1000;
    }

    /** The longest stretch of consecutive candidates whose times strictly rise; the first on a tie. */
    @NonNull
    private static List<Candidate> longestRisingRun(@NonNull List<Candidate> candidates) {
        int bestStart = 0;
        int bestLength = 0;
        int runStart = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0 && candidates.get(i).startMs <= candidates.get(i - 1).startMs) {
                runStart = i;
            }
            int length = i - runStart + 1;
            if (length > bestLength) {
                bestLength = length;
                bestStart = runStart;
            }
        }
        return candidates.subList(bestStart, bestStart + bestLength);
    }

    private static final class Candidate {
        final long startMs;
        @NonNull
        final String title;

        Candidate(long startMs, @NonNull String title) {
            this.startMs = startMs;
            this.title = title;
        }
    }
}
