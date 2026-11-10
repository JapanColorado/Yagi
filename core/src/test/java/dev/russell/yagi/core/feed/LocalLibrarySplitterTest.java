package dev.russell.yagi.core.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** How {@link LocalLibrarySplitter} cuts a picked folder into books. */
public class LocalLibrarySplitterTest {

    /** Album tags by path prefix; files without an entry have no tag. */
    private final Map<String, String> albums = new HashMap<>();
    private int albumReads;

    private List<String> split(String... paths) {
        List<String> keys = new ArrayList<>();
        for (LocalLibrarySplitter.Part part : LocalLibrarySplitter.split(Arrays.asList(paths), this::albumOf)) {
            keys.add(part.key());
        }
        return keys;
    }

    private String albumOf(String path) {
        albumReads++;
        for (Map.Entry<String, String> entry : albums.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Test
    public void noFilesNoBooks() {
        assertTrue(split().isEmpty());
    }

    @Test
    public void folderOfNumberedTracksIsOneBookWithoutReadingTags() {
        List<String> parts = split("01 Intro.mp3", "02 Chapter.mp3", "03 Chapter.mp3");
        assertEquals(Collections.singletonList("D:"), parts);
        assertEquals(0, albumReads);
        assertTrue(LocalLibrarySplitter.isSingleFolder(
                LocalLibrarySplitter.split(Arrays.asList("01.mp3", "02.mp3"), this::albumOf)));
    }

    @Test
    public void singleFileFolderIsOneBook() {
        assertEquals(Collections.singletonList("D:"), split("Dune.m4b"));
    }

    @Test
    public void pickedBookWithDiscFoldersIsOneBook() {
        assertEquals(Collections.singletonList("D:"),
                split("CD1/01.mp3", "CD1/02.mp3", "CD2/01.mp3", "Disc 3/01.mp3"));
    }

    @Test
    public void oneFolderPerBook() {
        assertEquals(Arrays.asList("D:Dune", "D:Emma", "D:Ulysses"),
                split("Dune/01.mp3", "Dune/02.mp3", "Emma/Emma.m4b",
                        "Ulysses/CD1/01.mp3", "Ulysses/CD2/01.mp3"));
    }

    @Test
    public void looseM4bFilesAreSeparateBooks() {
        assertEquals(Arrays.asList("F:Dune.m4b", "F:Emma.m4b"), split("Emma.m4b", "Dune.m4b"));
    }

    @Test
    public void numberedM4bSeriesIsSeparateBooks() {
        assertEquals(Arrays.asList("F:1 - Dune.m4b", "F:2 - Dune Messiah.m4b"),
                split("1 - Dune.m4b", "2 - Dune Messiah.m4b"));
    }

    @Test
    public void m4bPartsOfOneBookStayTogether() {
        assertEquals(Collections.singletonList("D:"), split("Ulysses Part 1.m4b", "Ulysses Part 2.m4b"));
        albums.put("Dune", "Dune");
        assertEquals(Collections.singletonList("D:"), split("Dune (1 of 2).m4b", "Dune (2 of 2).m4b"));
    }

    @Test
    public void looseUntaggedMp3WithDifferentNamesAreSeparateBooks() {
        assertEquals(Arrays.asList("F:Dune.mp3", "F:Emma.mp3", "F:Ulysses.mp3"),
                split("Dune.mp3", "Emma.mp3", "Ulysses.mp3"));
    }

    @Test
    public void looseFilesWithDifferentAlbumsAreSeparateBooks() {
        albums.put("Chapter 1 of Dune", "Dune");
        albums.put("Chapter 1 of Emma", "Emma");
        assertEquals(Arrays.asList("F:Chapter 1 of Dune.mp3", "F:Chapter 1 of Emma.mp3"),
                split("Chapter 1 of Dune.mp3", "Chapter 1 of Emma.mp3"));
    }

    @Test
    public void filesSharingAnAlbumAreOneBook() {
        albums.put("", "Dune");
        assertEquals(Collections.singletonList("D:"), split("Prologue.mp3", "Arrakis.mp3", "Muad'Dib.mp3"));
    }

    @Test
    public void untaggedTracksNamedWithNumbersAreOneBook() {
        assertEquals(Collections.singletonList("D:"),
                split("Dune - Chapter 1.mp3", "Dune - Chapter 2.mp3", "Dune - Epilogue 3.mp3"));
    }

    @Test
    public void looseBooksNextToBookFolders() {
        assertEquals(Arrays.asList("F:Emma.m4b", "F:Persuasion.m4b", "D:Dune"),
                split("Emma.m4b", "Persuasion.m4b", "Dune/01.mp3", "Dune/02.mp3"));
        assertEquals(Arrays.asList("F:Emma.m4b", "D:Dune"),
                split("Emma.m4b", "Dune/01.mp3", "Dune/02.mp3"));
    }

    @Test
    public void authorAndSeriesFoldersAreOnlyContainers() {
        assertEquals(Arrays.asList("D:Austen/Emma", "F:Herbert/Whipping Star.m4b",
                        "D:Herbert/Dune/1 Dune", "D:Herbert/Dune/2 Messiah"),
                split("Austen/Emma/01.mp3", "Austen/Emma/02.mp3",
                        "Herbert/Dune/1 Dune/01.mp3", "Herbert/Dune/2 Messiah/01.mp3",
                        "Herbert/Whipping Star.m4b"));
    }

    @Test
    public void bookFolderKeepsItsExtras() {
        assertEquals(Arrays.asList("D:Dune", "D:Emma"),
                split("Dune/01.mp3", "Dune/02.mp3", "Dune/Extras/Interview.mp3", "Emma/01.mp3"));
    }

    @Test
    public void pickedFolderDoesNotSwallowOtherBooks() {
        // stray tracks at the top of a library must not turn the whole library into one book
        assertEquals(Arrays.asList("F:01.mp3", "F:02.mp3", "D:Dune", "D:Emma"),
                split("01.mp3", "02.mp3", "Dune/01.mp3", "Emma/01.mp3"));
    }

    @Test
    public void subfoldersWithTheSameAlbumAreDiscs() {
        albums.put("Ulysses/", "Ulysses");
        albums.put("Dune/", "Dune");
        assertEquals(Arrays.asList("D:Dune", "D:Ulysses"),
                split("Ulysses/Telemachiad/01.mp3", "Ulysses/Odyssey/01.mp3", "Ulysses/Nostos/01.mp3",
                        "Dune/01.mp3", "Dune/02.mp3", "Dune/Appendix/01.mp3"));
    }

    @Test
    public void discNames() {
        for (String name : Arrays.asList("CD1", "cd 02", "Disc 1", "Disk_3", "Part 2", "Part II", "Part One",
                "Disc 1 of 3", "CD 1 - The Shire")) {
            assertTrue(name, LocalLibrarySplitter.isDiscName(name));
        }
        for (String name : Arrays.asList("Dune", "Discworld 1", "Particle Physics", "Book 2", "1", "Side Jobs")) {
            assertFalse(name, LocalLibrarySplitter.isDiscName(name));
        }
    }

    @Test
    public void partsKnowTheirFiles() {
        List<LocalLibrarySplitter.Part> parts = LocalLibrarySplitter.split(
                Arrays.asList("Dune/01.mp3", "Dune 2/01.mp3", "Emma.m4b", "Emma.m4b.mp3"), this::albumOf);
        assertEquals("D:Dune", parts.get(2).key());
        assertTrue(parts.get(2).contains("Dune/01.mp3"));
        assertFalse(parts.get(2).contains("Dune 2/01.mp3"));
        assertEquals("Dune", parts.get(2).name());
        assertTrue(parts.get(0).contains("Emma.m4b"));
        assertFalse(parts.get(0).contains("Emma.m4b.mp3"));
    }

    @Test
    public void rescanOnlyAddsBooksThatAreNew() {
        List<LocalLibrarySplitter.Part> parts = LocalLibrarySplitter.split(Arrays.asList(
                "Dune/01.mp3", "Dune/02.mp3", "Emma.m4b", "New/01.mp3", "New/02.mp3", "New.m4b"), this::albumOf);
        List<LocalLibrarySplitter.Part> added = LocalFeedCreator.newParts(parts,
                new HashSet<>(Arrays.asList("D:Dune", "F:Emma.m4b", "D:Removed")));
        assertEquals("[F:New.m4b, D:New]", added.toString());
    }

    @Test
    public void rescanDoesNotDuplicateFilesOfKnownBooks() {
        // "Herbert" was one book while it held a single file; with a second file it would split
        // into two books whose files the existing feed already shows
        List<LocalLibrarySplitter.Part> parts = LocalLibrarySplitter.split(Arrays.asList(
                "Herbert/Dune.m4b", "Herbert/Whipping Star.m4b", "Emma/01.mp3"), this::albumOf);
        assertEquals("[D:Emma, F:Herbert/Dune.m4b, F:Herbert/Whipping Star.m4b]", parts.toString());
        assertTrue(LocalFeedCreator.newParts(parts, new HashSet<>(Arrays.asList("D:Herbert", "D:Emma"))).isEmpty());
        // and the other way round: a folder around books that are already known
        List<LocalLibrarySplitter.Part> folder = LocalLibrarySplitter.split(
                Arrays.asList("Herbert/01.mp3", "Herbert/02.mp3"), this::albumOf);
        assertTrue(LocalFeedCreator.newParts(folder, Collections.singleton("F:Herbert/01.mp3")).isEmpty());
    }
}
