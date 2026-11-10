package dev.russell.yagi.core.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.russell.yagi.core.util.comparator.NaturalOrderComparator;

/**
 * Splits the files below a picked folder into books, so that a whole audiobook library can be
 * added at once. Pure logic: it sees the files' relative paths and asks for an album tag only
 * where the names do not settle it.
 *
 * <p>A book is either a folder with everything below it, or a single file:</p>
 * <ul>
 * <li>A folder whose files are the tracks of one book is a book, together with its disc
 * subfolders ("CD1", "Disc 2", or subfolders carrying the same album tag).</li>
 * <li>A folder whose files are different books (a shelf of .m4b files, say) gives one book per
 * file.</li>
 * <li>A folder without files of its own is a book if all its subfolders are discs; otherwise it
 * is only a container (the library itself, an author, a series) and its subfolders are split in
 * turn.</li>
 * </ul>
 *
 * <p>Whether a folder's files are one book is decided in this order: file names that start with
 * consecutive numbers are tracks; else the album tags of a sample (one shared album is a book,
 * all different albums are a shelf); else, without tags, names that differ only in a number are
 * parts of one book, .m4b files are separate books, mostly numbered names are tracks, and
 * anything else is a shelf.</p>
 *
 * <p>A folder that simply holds the tracks of one book comes back as one part with the path "",
 * which is how a folder was always added.</p>
 */
public final class LocalLibrarySplitter {
    /** How many files of one folder are asked for their album tag. */
    static final int MAX_SAMPLED_ALBUMS = 6;

    private static final String NUMBER_WORD = "(\\d{1,3}|[ivx]{1,4}|one|two|three|four|five|six|seven|eight|nine|ten)";
    private static final Pattern DISC_FOLDER = Pattern.compile(
            "^(cd|dis[ck]|part|teil|side|tape|cassette)[\\s._#-]*" + NUMBER_WORD + "\\b.*$");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^[#(\\[]?(\\d{1,4})(?!\\d).*$");
    private static final Pattern ANY_DIGIT = Pattern.compile(".*\\d.*");

    private LocalLibrarySplitter() {
    }

    /** Reads the album tag of a file, given its relative path; null when it has none. */
    public interface AlbumReader {
        @Nullable
        String albumOf(@NonNull String relativePath);
    }

    /** One book: a folder with everything below it, or a single file. */
    public static final class Part {
        /** True if the book is the single file at {@link #path}, false if it is a folder. */
        public final boolean file;
        /** Path below the picked folder, "" for the picked folder itself. */
        @NonNull
        public final String path;

        Part(boolean file, @NonNull String path) {
            this.file = file;
            this.path = path;
        }

        /** Identifies the part in {@link LocalLibraryStore}: "F:" or "D:" plus the path. */
        @NonNull
        public String key() {
            return (file ? "F:" : "D:") + path;
        }

        /** The file or folder name, "" for the picked folder itself. */
        @NonNull
        public String name() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        /** True if the file at {@code relativePath} belongs to this book. */
        public boolean contains(@NonNull String relativePath) {
            if (file) {
                return path.equals(relativePath);
            }
            return path.isEmpty() || relativePath.startsWith(path + "/");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Part && ((Part) other).key().equals(key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }

        @NonNull
        @Override
        public String toString() {
            return key();
        }
    }

    /**
     * @param mediaPaths the relative paths ("/" separated) of every audio or video file below the
     *                   picked folder
     * @return the books, a folder's own files before its subfolders, each in natural order;
     *     empty if there are no files
     */
    @NonNull
    public static List<Part> split(@NonNull List<String> mediaPaths, @NonNull AlbumReader albums) {
        Node root = new Node("", "");
        for (String path : mediaPaths) {
            Node node = root;
            String[] segments = path.split("/");
            for (int i = 0; i < segments.length - 1; i++) {
                node = node.child(segments[i]);
            }
            node.files.add(path);
        }
        Map<String, String> cache = new HashMap<>();
        AlbumReader cached = path -> {
            if (!cache.containsKey(path)) {
                String album = albums.albumOf(path);
                cache.put(path, album == null || album.trim().isEmpty() ? null
                        : album.trim().toLowerCase(Locale.ROOT));
            }
            return cache.get(path);
        };
        List<Part> parts = new ArrayList<>();
        split(root, true, cached, parts);
        return parts;
    }

    /**
     * True if the parts are just the picked folder as one book, which needs no library: it is
     * added the way a single folder always was.
     */
    public static boolean isSingleFolder(@NonNull List<Part> parts) {
        return parts.size() == 1 && !parts.get(0).file && parts.get(0).path.isEmpty();
    }

    private static void split(Node folder, boolean isRoot, AlbumReader albums, List<Part> out) {
        folder.files.sort(NaturalOrderComparator.INSTANCE);
        List<Node> subfolders = new ArrayList<>(folder.children.values());
        if (folder.files.isEmpty()) {
            if (!subfolders.isEmpty() && areDiscsOfOneBook(subfolders, albums)) {
                out.add(new Part(false, folder.path));
                return;
            }
            for (Node subfolder : subfolders) {
                split(subfolder, false, albums, out);
            }
            return;
        }

        boolean oneBook = looksLikeOneBook(folder.files, albums);
        boolean foreignSubfolders = false;
        if (oneBook && !subfolders.isEmpty()) {
            String album = albums.albumOf(folder.files.get(0));
            for (Node subfolder : subfolders) {
                if (!isDiscName(subfolder.name)
                        && (album == null || !album.equals(albums.albumOf(subfolder.firstFile())))) {
                    foreignSubfolders = true;
                    break;
                }
            }
        }
        // A book folder below the library keeps subfolders such as "Extras". The picked folder
        // itself does not swallow them: they are far more likely to be other books.
        if (oneBook && (!foreignSubfolders || (!isRoot && folder.files.size() > 1))) {
            out.add(new Part(false, folder.path));
            return;
        }
        for (String file : folder.files) {
            out.add(new Part(true, file));
        }
        for (Node subfolder : subfolders) {
            split(subfolder, false, albums, out);
        }
    }

    private static boolean areDiscsOfOneBook(List<Node> subfolders, AlbumReader albums) {
        boolean allNamedLikeDiscs = true;
        for (Node subfolder : subfolders) {
            if (!isDiscName(subfolder.name)) {
                allNamedLikeDiscs = false;
                break;
            }
        }
        if (allNamedLikeDiscs) {
            return true;
        }
        if (subfolders.size() < 2) {
            return false;
        }
        String album = null;
        for (Node subfolder : subfolders) {
            String other = albums.albumOf(subfolder.firstFile());
            if (other == null || (album != null && !album.equals(other))) {
                return false;
            }
            album = other;
        }
        return true;
    }

    static boolean isDiscName(@NonNull String folderName) {
        return DISC_FOLDER.matcher(folderName.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /** @param files the files directly in one folder, as relative paths; never empty */
    static boolean looksLikeOneBook(@NonNull List<String> files, @NonNull AlbumReader albums) {
        if (files.size() == 1) {
            return true;
        }
        List<String> names = new ArrayList<>();
        for (String file : files) {
            String name = file.substring(file.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            names.add((dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT));
        }
        boolean allM4b = true;
        for (String file : files) {
            if (!file.toLowerCase(Locale.ROOT).endsWith(".m4b")) {
                allM4b = false;
                break;
            }
        }
        // An .m4b usually is a whole book, and "1 - Dune.m4b", "2 - Dune Messiah.m4b" is a series,
        // so for those the numbers alone do not make them tracks.
        if (!allM4b && startWithConsecutiveNumbers(names)) {
            return true;
        }

        Map<String, Integer> albumCounts = new HashMap<>();
        int tagged = 0;
        for (String file : sample(files)) {
            String album = albums.albumOf(file);
            if (album != null) {
                tagged++;
                Integer count = albumCounts.get(album);
                albumCounts.put(album, count == null ? 1 : count + 1);
            }
        }
        if (tagged >= 2) {
            int mostCommon = 0;
            for (int count : albumCounts.values()) {
                mostCommon = Math.max(mostCommon, count);
            }
            return mostCommon * 2 > tagged;
        }

        Set<String> withoutDigits = new HashSet<>();
        int withNumber = 0;
        for (String name : names) {
            withoutDigits.add(name.replaceAll("\\d+", "#"));
            if (ANY_DIGIT.matcher(name).matches()) {
                withNumber++;
            }
        }
        if (withoutDigits.size() == 1 && withNumber == names.size()) {
            return true; // "Book - Part 1", "Book - Part 2"
        }
        if (allM4b) {
            return false;
        }
        return withNumber * 5 >= names.size() * 4;
    }

    /** "01 Intro", "02 ...": most names start with a number and the numbers are close together. */
    private static boolean startWithConsecutiveNumbers(List<String> names) {
        Set<Integer> numbers = new HashSet<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (String name : names) {
            Matcher matcher = LEADING_NUMBER.matcher(name);
            if (!matcher.matches()) {
                return false;
            }
            int number = Integer.parseInt(matcher.group(1));
            numbers.add(number);
            min = Math.min(min, number);
            max = Math.max(max, number);
        }
        return numbers.size() * 5 >= names.size() * 4 && max - min < names.size() * 2;
    }

    private static List<String> sample(List<String> files) {
        if (files.size() <= MAX_SAMPLED_ALBUMS) {
            return files;
        }
        List<String> sample = new ArrayList<>(MAX_SAMPLED_ALBUMS);
        for (int i = 0; i < MAX_SAMPLED_ALBUMS; i++) {
            sample.add(files.get((int) ((long) i * (files.size() - 1) / (MAX_SAMPLED_ALBUMS - 1))));
        }
        return sample;
    }

    private static final class Node {
        final String name;
        final String path;
        final List<String> files = new ArrayList<>();
        final Map<String, Node> children = new TreeMap<>(NaturalOrderComparator.INSTANCE);

        Node(String name, String path) {
            this.name = name;
            this.path = path;
        }

        Node child(String childName) {
            Node child = children.get(childName);
            if (child == null) {
                child = new Node(childName, path.isEmpty() ? childName : path + "/" + childName);
                children.put(childName, child);
            }
            return child;
        }

        /** The first file in natural order, looking into subfolders when there is none here. */
        String firstFile() {
            if (!files.isEmpty()) {
                files.sort(NaturalOrderComparator.INSTANCE);
                return files.get(0);
            }
            // a node only exists because a file lies somewhere below it
            return children.values().iterator().next().firstFile();
        }
    }
}
