package dev.russell.yagi.core.adskip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.russell.yagi.model.feed.AdSegment;

/**
 * Finds sponsor reads in a timed transcript on the phone, without a network or a model.
 *
 * <p>Ads are written to a formula, and the formula is what this looks for. A read closes with a
 * call to action: a web address with a path ("example dot com slash show"), an offer code, a free
 * trial, a percentage off, "terms apply"; it usually opens with a transition ("this episode is
 * brought to you by", "after this message from", "our friends at"), and in between it keeps
 * naming the advertiser. So each strong call to action, and each sponsor transition, anchors a
 * read, which reaches back and forward through the lines that name the advertiser (the names
 * are taken from the web addresses the read gives), open a read or call to action, as long as no
 * {@link Config#maxQuietMs} passes without one, and ends at a "back to the show" right after the
 * last call. Confidence grows with the calls, the mentions and a sponsor transition. A line whose
 * speaker the transcript names "Sponsor" or "Ad" is an ad outright.
 *
 * <p>The same formula fits the show promoting itself ("support us on Patreon", "head to our
 * website dot fm"). A read whose calls to action point mostly at the show itself, by Patreon,
 * membership or merchandise wording or by a web address made of the show's own name, is reported
 * as {@link AdSegment.Source#SELF_PROMO} instead, unless it opens like a sponsor read.
 *
 * <p>Text is normalised before matching so that machine transcripts with and without punctuation
 * read alike: "Framer.com/practicalai" and "framer dot com slash practical ai" both become
 * {@code framer dot com slash practicalai}.
 */
public final class TranscriptAdHeuristic {

    /** Tunables; the defaults were measured as described in docs/AD_SKIP_TUNING.md. */
    public static final class Config {
        /** Longest stretch without ad evidence (opener, call, the advertiser's name) inside a read. */
        public final long maxQuietMs;
        /** A "back to the show" this soon after the last call to action ends the ad. */
        public final long closeReachMs;
        /** Adjacent ads closer than this are one break. */
        public final long mergeGapMs;
        /** Call-to-action score that makes a line an anchor a read is built around. */
        public final float anchorMinScore;
        /** How far before its first call to action a read may start. */
        public final long maxLeadMs;
        public final long minAdMs;
        public final long maxAdMs;

        public Config(long maxQuietMs, long closeReachMs, long mergeGapMs, float anchorMinScore,
                      long maxLeadMs, long minAdMs, long maxAdMs) {
            this.maxQuietMs = maxQuietMs;
            this.closeReachMs = closeReachMs;
            this.mergeGapMs = mergeGapMs;
            this.anchorMinScore = anchorMinScore;
            this.maxLeadMs = maxLeadMs;
            this.minAdMs = minAdMs;
            this.maxAdMs = maxAdMs;
        }

        @NonNull
        public static Config defaults() {
            return new Config(45_000, 25_000, 10_000, 2.5f, 4 * 60_000, 10_000, 8 * 60_000);
        }
    }

    /** A phrase and how much it says. */
    private static final class Cue {
        final Pattern pattern;
        final float weight;

        Cue(String regex, float weight) {
            this.pattern = Pattern.compile("\\b(?:" + regex + ")\\b");
            this.weight = weight;
        }
    }

    /** Openers at least this strong name a sponsor; a read opened by one is never self-promotion. */
    private static final float SPONSOR_OPENER = 2.5f;
    /**
     * A read that is a single line, with no opener and no other mention of the advertiser, needs
     * more than a bare "check it out at example dot com" (2.5): a path, a code or an offer.
     */
    private static final float LONE_LINE_MIN_CALLS = 3f;
    /** Call-to-action score a read needs in all. */
    private static final float MIN_READ_CALLS = 2f;

    /** Transitions into a read. */
    private static final Cue[] OPENERS = {
            new Cue("brought to you by", 3),
            new Cue("(?:is|are|was) (?:also )?sponsored by|sponsored by", 3),
            new Cue("(?:todays|this weeks|this episodes|our) sponsors?", 2.5f),
            new Cue("sponsors? (?:of|for) (?:this|todays|the) (?:show|episode|podcast)", 3),
            new Cue("thanks? (?:you )?to (?:our sponsors?|our friends)", 2.5f),
            new Cue("support for (?:this|the) (?:show|podcast|episode)", 3),
            new Cue("(?:show|episode|podcast) is (?:supported|presented|made possible|powered) by", 3),
            new Cue("(?:a |quick )?(?:word|message) from (?:our|todays|this weeks) sponsors?", 3),
            new Cue("our friends (?:at|over at)", 2),
            new Cue("(?:lets |going to |gonna |well |we ll )?take a (?:quick |short )?break", 2),
            new Cue("(?:after|right after) (?:the|this) break", 1.5f),
            new Cue("after (?:this|a) (?:quick |short )?(?:message|word) from", 2.5f),
            new Cue("(?:quick |short )?(?:message|word) from (?:our )?(?:sponsors?|friends|partners?)", 2.5f),
            new Cue("(?:thank|thanks to) our (?:sponsors?|partners?)|our (?:partners?|sponsors?) (?:at|over at)", 2.5f),
    };

    /** Calls to action and offer language. */
    private static final Cue[] CALLS = {
            new Cue("(?:promo|offer|discount|coupon|checkout) code", 2.5f),
            new Cue("use (?:the )?code|with (?:the )?code|enter (?:the )?code|code \\w+ at checkout", 2.5f),
            new Cue("at checkout", 1.5f),
            new Cue("free trial|try it free|try (?:it|them) (?:for )?free|for free today", 2),
            new Cue("percent off|\\d+ off (?:your|the|a)|off your first", 2),
            new Cue("first (?:month|order|box|year|purchase|three months)", 1.5f),
            new Cue("free shipping|money back guarantee|risk free|no risk", 1.5f),
            new Cue("terms (?:and conditions )?apply|restrictions (?:may )?apply|conditions apply", 2),
            new Cue("limited time|special offer|exclusive offer|exclusive deal|special deal", 1.5f),
            new Cue("(?:dot|\\.) (?:com|io|ai|co|fm|net|org|app|dev|us) slash \\w+", 2.5f),
            new Cue("(?:go|head|head over|visit|check (?:them |it )?out|learn more|sign up|get started)"
                    + " (?:at |to |for free at |over to )?\\w+(?: \\w+)? dot", 2),
            new Cue("(?:sign up|get started|start (?:your|a) free|download (?:the|it)) (?:today|now)", 1.5f),
            new Cue("(?:link|links) (?:is |are )?in (?:the|our) (?:show notes|description)", 1),
            new Cue("listeners (?:of this show|get|can get)|for our listeners", 1.5f),
            new Cue("dot com", 0.5f),
    };

    /** Returns to the show. */
    private static final Cue[] CLOSERS = {
            new Cue("(?:now |and now |lets get |lets head |lets go )?back to (?:the|our) "
                    + "(?:show|episode|conversation|interview|podcast|discussion)", 2),
    };

    /** The show asking for support for itself. */
    private static final Pattern SELF_PROMO = Pattern.compile("\\b(?:"
            + "patreon|patrons?|supporting cast|memberful|buy me a coffee|ko fi"
            + "|become a (?:member|supporter|subscriber|patron)"
            + "|support (?:the|this|our) (?:show|podcast|work|channel)"
            + "|(?:our|the) (?:merch|merchandise|store|shop|newsletter|membership|discord|community)"
            + "|join (?:the|our) (?:club|community|membership|discord)"
            + "|members get|ad free (?:feed|version)|bonus (?:episodes|content)"
            + ")\\b");

    private static final Pattern SPONSOR_SPEAKER = Pattern.compile(
            "^(?:sponsor|sponsors|ad|ads|advert|advertisement|advertiser|promo|commercial)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DOMAIN_DOT = Pattern.compile("([a-z0-9])\\.([a-z]{2,})");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** The word or words right before a "dot", i.e. the name of a spoken web address. */
    private static final Pattern ADDRESS = Pattern.compile("((?:\\w+ ){0,3}\\w+) dot \\w+");
    /** Words that come before a spoken web address without being part of its name. */
    private static final Set<String> NOT_A_NAME = new LinkedHashSet<>(java.util.Arrays.asList(
            "to", "at", "go", "visit", "head", "over", "out", "check", "the", "and", "www", "on",
            "is", "its", "thats", "just", "by", "from", "of", "for", "website", "site", "us", "them"));
    private static final Set<String> FILLER_WORDS = new LinkedHashSet<>(java.util.Arrays.asList(
            "the", "a", "an", "podcast", "show", "radio", "with", "and"));

    @NonNull
    private final Config config;

    public TranscriptAdHeuristic() {
        this(Config.defaults());
    }

    public TranscriptAdHeuristic(@NonNull Config config) {
        this.config = config;
    }

    /** Per-line scores. */
    private static final class Scored {
        final TranscriptLine line;
        final float open;
        final float call;
        final float close;
        final boolean sponsorSpeaker;
        final boolean self;
        final Set<String> brands;
        final String squashed;

        Scored(TranscriptLine line, String text, float open, float call, float close,
               boolean sponsorSpeaker, boolean self) {
            this.line = line;
            this.open = open;
            this.call = call;
            this.close = close;
            this.sponsorSpeaker = sponsorSpeaker;
            this.self = self;
            this.brands = brands(text);
            this.squashed = text.replace(" ", "");
        }
    }

    /** A read found in the lines {@code first} to {@code last}. */
    private static final class Found {
        long start;
        long end;
        float confidence;
        final boolean promo;

        Found(long start, long end, float confidence, boolean promo) {
            this.start = start;
            this.end = end;
            this.confidence = confidence;
            this.promo = promo;
        }
    }

    /** {@link #detect(List, Collection, long)} without knowing the show's own names. */
    @NonNull
    public List<AdSegment> detect(@NonNull List<TranscriptLine> lines, long feedItemId) {
        return detect(lines, Collections.<String>emptyList(), feedItemId);
    }

    /**
     * The ads in {@code lines}, as {@link AdSegment.Source#TRANSCRIPT} segments, and the show's
     * own promotions, as {@link AdSegment.Source#SELF_PROMO} segments; sorted, and not
     * overlapping within either source.
     *
     * @param selfNames the show's own names as {@link #selfNames} makes them, so that a call to
     *                  action for the show's own web address is recognised as self-promotion
     */
    @NonNull
    public List<AdSegment> detect(@NonNull List<TranscriptLine> lines,
                                  @NonNull Collection<String> selfNames, long feedItemId) {
        List<Scored> scored = new ArrayList<>(lines.size());
        // WebVTT names the speaker on the first cue of a turn only; later cues continue it.
        String speaker = null;
        for (TranscriptLine line : lines) {
            String text = normalize(line.getText());
            if (line.getSpeaker() != null) {
                speaker = line.getSpeaker();
            }
            float call = score(CALLS, text);
            scored.add(new Scored(line, text, score(OPENERS, text), call, score(CLOSERS, text),
                    speaker != null && SPONSOR_SPEAKER.matcher(speaker.trim()).matches(),
                    call > 0 && isSelf(text, selfNames)));
        }
        List<Found> found = new ArrayList<>();
        sponsorSpeakerRuns(scored, found);
        readsAroundCalls(scored, found);

        Collections.sort(found, new Comparator<Found>() {
            @Override
            public int compare(Found a, Found b) {
                return Long.compare(a.start, b.start);
            }
        });
        List<AdSegment> out = new ArrayList<>();
        for (boolean promo : new boolean[]{false, true}) {
            Found last = null;
            List<Found> merged = new ArrayList<>();
            for (Found read : found) {
                if (read.promo != promo) {
                    continue;
                }
                if (last != null && read.start - last.end <= config.mergeGapMs) {
                    last.end = Math.max(last.end, read.end);
                    last.confidence = Math.max(last.confidence, read.confidence);
                } else {
                    last = new Found(read.start, read.end, read.confidence, promo);
                    merged.add(last);
                }
            }
            for (Found read : merged) {
                long length = read.end - read.start;
                if (length >= config.minAdMs && length <= config.maxAdMs) {
                    out.add(new AdSegment(feedItemId, read.start, read.end,
                            promo ? AdSegment.Source.SELF_PROMO : AdSegment.Source.TRANSCRIPT,
                            read.confidence));
                }
            }
        }
        return out;
    }

    /** Lines spoken by a speaker the transcript itself calls "Sponsor": certain. */
    private void sponsorSpeakerRuns(List<Scored> scored, List<Found> found) {
        for (int i = 0; i < scored.size(); i++) {
            if (!scored.get(i).sponsorSpeaker) {
                continue;
            }
            int j = i;
            while (j + 1 < scored.size() && scored.get(j + 1).sponsorSpeaker) {
                j++;
            }
            found.add(new Found(scored.get(i).line.getStartMs(), scored.get(j).line.getEndMs(),
                    0.95f, false));
            i = j;
        }
    }

    /**
     * Reads built around their calls to action. Each strong call (a web address with a path, an
     * offer code, "terms apply") anchors a read. The read reaches back from it through the lines
     * that name the advertiser (taken from the web addresses it gives) or open a read, as long as
     * no {@link Config#maxQuietMs} passes without such a line, and starts at an opener if one is
     * on the way. Forward it reaches to the last call or mention of the advertiser in the same
     * way, and to a "back to the show" right after.
     */
    private void readsAroundCalls(List<Scored> scored, List<Found> found) {
        int i = 0;
        while (i < scored.size()) {
            Scored anchor = scored.get(i);
            boolean sponsorOpener = anchor.open >= SPONSOR_OPENER;
            if ((anchor.call < config.anchorMinScore && !sponsorOpener)
                    || covered(found, anchor.line)) {
                i++;
                continue;
            }
            Set<String> brands = new LinkedHashSet<>(anchor.brands);
            // Forward: further calls and mentions of the same advertiser.
            int last = i;
            float calls = anchor.call;
            float selfCalls = anchor.self ? anchor.call : 0;
            int mentions = 0;
            for (int k = i + 1; k < scored.size(); k++) {
                Scored next = scored.get(k);
                if (next.line.getStartMs() - scored.get(last).line.getEndMs() > config.closeReachMs
                        || covered(found, next.line)) {
                    break;
                }
                boolean named = mentions(next, brands);
                if (next.call > 0 || named) {
                    last = k;
                    calls += next.call;
                    selfCalls += next.self ? next.call : 0;
                    brands.addAll(next.brands);
                    mentions += named ? 1 : 0;
                } else if (next.open >= SPONSOR_OPENER) {
                    // the next read starts here
                    break;
                }
            }
            if (calls < MIN_READ_CALLS) {
                // an opener that leads to no call to action: talk about a sponsor, not a read
                i++;
                continue;
            }
            // Backward: the advertiser's name and openers.
            int first = i;
            int opener = anchor.open > 0 ? i : -1;
            long evidence = anchor.line.getStartMs();
            for (int k = sponsorOpener ? -1 : i - 1; k >= 0; k--) {
                Scored previous = scored.get(k);
                if (evidence - previous.line.getEndMs() > config.maxQuietMs
                        || anchor.line.getStartMs() - previous.line.getStartMs() > config.maxLeadMs
                        || covered(found, previous.line)) {
                    break;
                }
                boolean named = mentions(previous, brands);
                if (named || previous.open > 0 || previous.call > 0) {
                    first = k;
                    evidence = previous.line.getStartMs();
                    mentions += named ? 1 : 0;
                    calls += previous.call;
                    selfCalls += previous.self ? previous.call : 0;
                    if (previous.open > 0) {
                        opener = k;
                        if (previous.open >= SPONSOR_OPENER) {
                            break;
                        }
                    }
                }
            }
            int end = last;
            long callEnd = scored.get(last).line.getEndMs();
            for (int k = last + 1; k < scored.size()
                    && scored.get(k).line.getStartMs() - callEnd <= config.closeReachMs; k++) {
                if (scored.get(k).close > 0) {
                    end = k;
                    break;
                }
            }
            float openStrength = opener >= 0 ? scored.get(opener).open : 0;
            if (first == end && openStrength <= 0 && mentions == 0 && calls < LONE_LINE_MIN_CALLS) {
                // One line pointing at a website and nothing around it: a link, not a read.
                i = end + 1;
                continue;
            }
            float confidence = 0.4f
                    + 0.05f * Math.min(6f, calls)
                    + 0.04f * Math.min(4, mentions)
                    + (openStrength >= SPONSOR_OPENER ? 0.15f : openStrength > 0 ? 0.08f : 0f)
                    + (end != last ? 0.05f : 0f);
            boolean promo = openStrength < SPONSOR_OPENER && selfCalls * 2 >= calls;
            found.add(new Found(scored.get(first).line.getStartMs(), scored.get(end).line.getEndMs(),
                    Math.min(0.95f, confidence), promo));
            i = end + 1;
        }
    }

    private static boolean mentions(Scored line, Set<String> brands) {
        for (String brand : brands) {
            if (line.squashed.contains(brand)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The advertiser names in a normalised line: the name before each spoken web address, with
     * and without the "get"/"try" and "app"/"hq" that brands wrap their domains in.
     */
    @NonNull
    static Set<String> brands(@NonNull String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher address = ADDRESS.matcher(text);
        while (address.find()) {
            String[] words = address.group(1).split(" ");
            StringBuilder name = new StringBuilder();
            for (int k = words.length - 1; k >= 0 && k >= words.length - 2; k--) {
                if (NOT_A_NAME.contains(words[k])) {
                    break;
                }
                name.insert(0, words[k]);
                addBrand(out, name.toString());
            }
        }
        return out;
    }

    private static void addBrand(Set<String> out, String name) {
        String core = name;
        for (String prefix : new String[]{"get", "try", "join", "use", "go"}) {
            if (core.startsWith(prefix) && core.length() - prefix.length() >= 4) {
                core = core.substring(prefix.length());
                break;
            }
        }
        for (String suffix : new String[]{"app", "hq", "inc"}) {
            if (core.endsWith(suffix) && core.length() - suffix.length() >= 4) {
                core = core.substring(0, core.length() - suffix.length());
                break;
            }
        }
        if (name.length() >= 4) {
            out.add(name);
        }
        if (core.length() >= 4) {
            out.add(core);
        }
    }

    private static boolean covered(List<Found> found, TranscriptLine line) {
        for (Found read : found) {
            if (line.getStartMs() < read.end && read.start < line.getEndMs()) {
                return true;
            }
        }
        return false;
    }

    private static float score(Cue[] cues, String text) {
        float total = 0;
        for (Cue cue : cues) {
            Matcher matcher = cue.pattern.matcher(text);
            if (matcher.find()) {
                total += cue.weight;
            }
        }
        return total;
    }

    /** Whether a normalised line points at the show itself rather than at an advertiser. */
    static boolean isSelf(@NonNull String text, @NonNull Collection<String> selfNames) {
        if (SELF_PROMO.matcher(text).find()) {
            return true;
        }
        if (selfNames.isEmpty()) {
            return false;
        }
        Set<String> named = brands(text);
        if (named.isEmpty()) {
            // "sign up at the School of Podcasting": the show's name, and no one else's address
            String squashed = text.replace(" ", "");
            for (String name : selfNames) {
                if (name.length() >= 6 && squashed.contains(name)) {
                    return true;
                }
            }
            return false;
        }
        Matcher address = ADDRESS.matcher(text);
        while (address.find()) {
            // "practical ai dot fm": try the last one, two, three and four words run together.
            String[] words = address.group(1).split(" ");
            StringBuilder name = new StringBuilder();
            for (int k = words.length - 1; k >= 0; k--) {
                name.insert(0, words[k]);
                if (selfNames.contains(name.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The names a show's own web address is likely to use, run together: its title with and
     * without filler words ("THE Bitcoin Podcast" gives {@code thebitcoinpodcast} and
     * {@code bitcoin}), and the first label of its website's host name.
     */
    @NonNull
    public static Set<String> selfNames(@Nullable String podcastTitle, @Nullable String websiteHost) {
        Set<String> names = new LinkedHashSet<>();
        String title = normalize(podcastTitle);
        if (!title.isEmpty()) {
            names.add(title.replace(" ", ""));
            StringBuilder core = new StringBuilder();
            for (String word : title.split(" ")) {
                if (!FILLER_WORDS.contains(word)) {
                    core.append(word);
                }
            }
            if (core.length() >= 4) {
                names.add(core.toString());
            }
        }
        if (websiteHost != null) {
            String host = websiteHost.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            int dot = host.indexOf('.');
            String label = NON_WORD.matcher(dot > 0 ? host.substring(0, dot) : host).replaceAll("");
            if (label.length() >= 4) {
                names.add(label);
            }
        }
        return names;
    }

    /** Lower case, web addresses spelt out, punctuation dropped. */
    @NonNull
    static String normalize(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String s = text.toLowerCase(Locale.ROOT).replace('’', '\'').replace("'", "");
        s = DOMAIN_DOT.matcher(s).replaceAll("$1 dot $2");
        s = s.replace("/", " slash ").replace("%", " percent ").replace("&", " and ");
        s = NON_WORD.matcher(s).replaceAll(" ");
        return SPACES.matcher(s).replaceAll(" ").trim();
    }
}
