package dev.russell.yagi.core.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import dev.russell.yagi.model.feed.Chapter;
import dev.russell.yagi.core.feed.ChapterMerger;
import dev.russell.yagi.core.feed.DescriptionChapterParser;
import dev.russell.yagi.model.feed.FeedItem;
import dev.russell.yagi.model.feed.FeedMedia;
import dev.russell.yagi.core.service.download.PodcastHttpClient;
import dev.russell.yagi.core.storage.DBReader;
import dev.russell.yagi.core.util.comparator.ChapterStartTimeComparator;
import dev.russell.yagi.parser.feed.PodcastIndexChapterParser;
import dev.russell.yagi.parser.media.id3.ChapterReader;
import dev.russell.yagi.parser.media.id3.ID3ReaderException;
import dev.russell.yagi.model.playback.Playable;
import dev.russell.yagi.model.playback.RemoteMedia;
import dev.russell.yagi.parser.media.mp4.FileChannelMp4Source;
import dev.russell.yagi.parser.media.mp4.Mp4ChapterReader;
import dev.russell.yagi.parser.media.mp4.Mp4ReaderException;
import dev.russell.yagi.parser.media.mp4.Mp4Source;
import dev.russell.yagi.parser.media.mp4.StreamMp4Source;
import dev.russell.yagi.parser.media.vorbis.VorbisCommentChapterReader;
import dev.russell.yagi.parser.media.vorbis.VorbisCommentReaderException;
import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.input.CountingInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for getting chapter data from media files.
 */
public class ChapterUtils {

    private static final String TAG = "ChapterUtils";
    /**
     * Most bytes an MP4 network stream may be skipped through (to a moov box at the end, or to
     * chapter-track samples after the audio); beyond that the read gives up rather than download
     * the whole file a second time.
     */
    private static final long MAX_REMOTE_MP4_SKIP = 2L * 1024 * 1024;

    private ChapterUtils() {
    }

    public static int getCurrentChapterIndex(Playable media, int position) {
        if (media == null || media.getChapters() == null || media.getChapters().size() == 0) {
            return -1;
        }
        List<Chapter> chapters = media.getChapters();
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getStart() > position) {
                return i - 1;
            }
        }
        return chapters.size() - 1;
    }

    public static void loadChapters(Playable playable, Context context) {
        if (playable.getChapters() != null) {
            // Already loaded
            return;
        }

        List<Chapter> chaptersFromDatabase = null;
        List<Chapter> chaptersFromPodcastIndex = null;
        if (playable instanceof FeedMedia) {
            FeedMedia feedMedia = (FeedMedia) playable;
            if (feedMedia.getItem() == null) {
                feedMedia.setItem(DBReader.getFeedItem(feedMedia.getItemId()));
            }
            if (feedMedia.getItem().hasChapters()) {
                chaptersFromDatabase = DBReader.loadChaptersOfFeedItem(feedMedia.getItem());
            }

            if (!TextUtils.isEmpty(feedMedia.getItem().getPodcastIndexChapterUrl())) {
                chaptersFromPodcastIndex = ChapterUtils.loadChaptersFromUrl(
                        feedMedia.getItem().getPodcastIndexChapterUrl());
            }

        }

        List<Chapter> chaptersFromMediaFile = ChapterUtils.loadChaptersFromMediaFile(playable, context);
        List<Chapter> chaptersMergePhase1 = ChapterMerger.merge(chaptersFromDatabase, chaptersFromMediaFile);
        List<Chapter> chapters = ChapterMerger.merge(chaptersMergePhase1, chaptersFromPodcastIndex);
        if ((chapters == null || chapters.isEmpty()) && playable instanceof FeedMedia) {
            chapters = loadChaptersFromDescription(((FeedMedia) playable).getItem(),
                    playable.getDuration());
        }
        if (chapters == null) {
            // Do not try loading again. There are no chapters.
            playable.setChapters(Collections.emptyList());
        } else {
            playable.setChapters(chapters);
        }
    }

    /**
     * The chapters of an episode that do not need its media file: the ones stored with it (from
     * the feed, or read from the file when it was downloaded) merged with its Podcasting 2.0
     * chapter file, or, when neither has any, the chapter list in its description. Reads the
     * database and may fetch the chapter file, so call it off the main thread.
     *
     * @return the chapters, or null when there are none
     */
    @Nullable
    public static List<Chapter> loadChaptersWithoutMediaFile(@Nullable FeedItem item, long durationMs) {
        if (item == null) {
            return null;
        }
        List<Chapter> stored = item.getChapters() != null ? item.getChapters()
                : DBReader.loadChaptersOfFeedItem(item);
        List<Chapter> fromPodcastIndex = null;
        if (!TextUtils.isEmpty(item.getPodcastIndexChapterUrl())) {
            fromPodcastIndex = loadChaptersFromUrl(item.getPodcastIndexChapterUrl());
        }
        List<Chapter> chapters = ChapterMerger.merge(
                stored != null ? new ArrayList<>(stored) : null, fromPodcastIndex);
        if (chapters == null || chapters.isEmpty()) {
            chapters = loadChaptersFromDescription(item, durationMs);
        }
        return chapters;
    }

    /**
     * The chapter list written into an episode's description ("00:00 Intro" lines), or null when
     * it has none. Loads the description from the database when the item does not carry it.
     */
    @Nullable
    public static List<Chapter> loadChaptersFromDescription(@Nullable FeedItem item, long durationMs) {
        if (item == null) {
            return null;
        }
        if (item.getDescription() == null && item.getId() > 0) {
            DBReader.loadDescriptionOfFeedItem(item);
        }
        List<Chapter> chapters = DescriptionChapterParser.parse(item.getDescription(), durationMs);
        if (chapters != null) {
            Log.i(TAG, "Read " + chapters.size() + " chapters from the description of "
                    + item.getTitle());
        }
        return chapters;
    }

    public static List<Chapter> loadChaptersFromMediaFile(Playable playable, Context context) {
        boolean looksLikeMp4 = looksLikeMp4(playable);
        if (looksLikeMp4) {
            List<Chapter> chapters = readMp4Chapters(playable, context);
            if (!chapters.isEmpty()) {
                Log.i(TAG, "MP4 chapters loaded");
                return chapters;
            }
        }

        try (CountingInputStream in = openStream(playable, context)) {
            List<Chapter> chapters = readId3ChaptersFrom(in);
            if (!chapters.isEmpty()) {
                Log.i(TAG, "chapters loaded");
                return chapters;
            }
        } catch (IOException | ID3ReaderException e) {
            Log.e(TAG, "failed to load ID3 chapters: " + e.getMessage());
        }

        try (CountingInputStream in = openStream(playable, context)) {
            List<Chapter> chapters = readOggChaptersFromInputStream(in);
            if (!chapters.isEmpty()) {
                Log.i(TAG, "chapters loaded");
                return chapters;
            }
        } catch (IOException | VorbisCommentReaderException e) {
            Log.e(TAG, "failed to load vorbis chapters: " + e.getMessage());
        }

        // A local MP4 with an unhelpful mime type or file name: the reader gives up after
        // the first 8 bytes on anything that is not MP4, so this costs nearly nothing.
        if (!looksLikeMp4 && !isRemoteStream(playable)) {
            List<Chapter> chapters = readMp4Chapters(playable, context);
            if (!chapters.isEmpty()) {
                Log.i(TAG, "MP4 chapters loaded");
                return chapters;
            }
        }
        return null;
    }

    private static boolean isRemoteStream(Playable playable) {
        return !playable.localFileAvailable() && (playable.getStreamUrl() == null
                || !playable.getStreamUrl().startsWith(ContentResolver.SCHEME_CONTENT));
    }

    /** True if the mime type or file name says MP4/M4A/M4B (QuickTime family). */
    private static boolean looksLikeMp4(Playable playable) {
        String mime = null;
        if (playable instanceof FeedMedia) {
            mime = ((FeedMedia) playable).getMime_type();
        } else if (playable instanceof RemoteMedia) {
            mime = ((RemoteMedia) playable).getMimeType();
        }
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("mp4") || m.contains("m4a") || m.contains("m4b") || m.contains("quicktime")
                    || m.contains("m4v")) {
                return true;
            }
        }
        String path = playable.localFileAvailable() ? playable.getLocalMediaUrl() : playable.getStreamUrl();
        if (path == null) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT);
        int query = p.indexOf('?');
        if (query >= 0) {
            p = p.substring(0, query);
        }
        return p.endsWith(".m4a") || p.endsWith(".m4b") || p.endsWith(".mp4") || p.endsWith(".m4v")
                || p.endsWith(".mov");
    }

    /**
     * Reads MP4 chapters with random access where possible: local files and seekable content
     * URIs go through a FileChannel (the moov box and chapter samples can be anywhere), other
     * content URIs and network streams are read forward-only with a bounded skip.
     */
    @NonNull
    private static List<Chapter> readMp4Chapters(Playable playable, Context context) {
        List<Chapter> chapters;
        try {
            if (playable.localFileAvailable()) {
                if (playable.getLocalMediaUrl() == null) {
                    throw new IOException("no local url");
                }
                try (RandomAccessFile file = new RandomAccessFile(playable.getLocalMediaUrl(), "r")) {
                    chapters = Mp4ChapterReader.readChapters(new FileChannelMp4Source(file.getChannel()));
                }
            } else if (playable.getStreamUrl() != null
                    && playable.getStreamUrl().startsWith(ContentResolver.SCHEME_CONTENT)) {
                Uri uri = Uri.parse(playable.getStreamUrl());
                ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) {
                    throw new IOException("no file descriptor for " + uri);
                }
                boolean seekable = pfd.getStatSize() >= 0;
                try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                    Mp4Source source = seekable ? new FileChannelMp4Source(in.getChannel())
                            : new StreamMp4Source(in);
                    chapters = Mp4ChapterReader.readChapters(source);
                }
            } else {
                try (CountingInputStream in = openStream(playable, context)) {
                    chapters = Mp4ChapterReader.readChapters(
                            new StreamMp4Source(in, -1, MAX_REMOTE_MP4_SKIP));
                }
            }
        } catch (IOException | Mp4ReaderException e) {
            Log.e(TAG, "failed to load MP4 chapters: " + e.getMessage());
            return Collections.emptyList();
        }
        chapters = new ArrayList<>(chapters);
        Collections.sort(chapters, new ChapterStartTimeComparator());
        enumerateEmptyChapterTitles(chapters);
        return chaptersValid(chapters) ? chapters : Collections.emptyList();
    }

    private static CountingInputStream openStream(Playable playable, Context context) throws IOException {
        if (playable.localFileAvailable()) {
            if (playable.getLocalMediaUrl() == null) {
                throw new IOException("no local url");
            }
            File source = new File(playable.getLocalMediaUrl());
            if (!source.exists()) {
                throw new IOException("local file does not exist");
            }
            return new CountingInputStream(new FileInputStream(source));
        } else if (playable.getStreamUrl().startsWith(ContentResolver.SCHEME_CONTENT)) {
            Uri uri = Uri.parse(playable.getStreamUrl());
            return new CountingInputStream(context.getContentResolver().openInputStream(uri));
        } else {
            Request request = new Request.Builder().url(playable.getStreamUrl()).build();
            Response response = PodcastHttpClient.getHttpClient().newCall(request).execute();
            if (response.body() == null) {
                throw new IOException("Body is null");
            }
            return new CountingInputStream(response.body().byteStream());
        }
    }

    public static List<Chapter> loadChaptersFromUrl(String url) {
        try {
            Request request = new Request.Builder().url(url).cacheControl(CacheControl.FORCE_CACHE).build();
            Response response = PodcastHttpClient.getHttpClient().newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                List<Chapter> chapters = PodcastIndexChapterParser.parse(response.body().string());
                if (chapters != null && !chapters.isEmpty()) {
                    return chapters;
                }
            }
            request = new Request.Builder().url(url).build();
            response = PodcastHttpClient.getHttpClient().newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                return PodcastIndexChapterParser.parse(response.body().string());
            }
        } catch (IOException e) {
            // Returns the documented null: chapters are optional metadata and the caller
            // (PlaybackServiceTaskManager) simply keeps the media without them.
            Log.e(TAG, "Failed to load chapters from " + url, e);
        }
        return null;
    }

    @NonNull
    private static List<Chapter> readId3ChaptersFrom(CountingInputStream in) throws IOException, ID3ReaderException {
        ChapterReader reader = new ChapterReader(in);
        reader.readInputStream();
        List<Chapter> chapters = reader.getChapters();
        Collections.sort(chapters, new ChapterStartTimeComparator());
        enumerateEmptyChapterTitles(chapters);
        if (!chaptersValid(chapters)) {
            return Collections.emptyList();
        }
        return chapters;
    }

    @NonNull
    private static List<Chapter> readOggChaptersFromInputStream(InputStream input) throws VorbisCommentReaderException {
        VorbisCommentChapterReader reader = new VorbisCommentChapterReader(input);
        reader.readInputStream();
        List<Chapter> chapters = reader.getChapters();
        if (chapters == null) {
            return Collections.emptyList();
        }
        Collections.sort(chapters, new ChapterStartTimeComparator());
        enumerateEmptyChapterTitles(chapters);
        if (chaptersValid(chapters)) {
            return chapters;
        }
        return Collections.emptyList();
    }

    /**
     * Makes sure that chapter does a title and an item attribute.
     */
    private static void enumerateEmptyChapterTitles(List<Chapter> chapters) {
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            if (c.getTitle() == null) {
                c.setTitle(Integer.toString(i));
            }
        }
    }

    private static boolean chaptersValid(List<Chapter> chapters) {
        if (chapters.isEmpty()) {
            return false;
        }
        for (Chapter c : chapters) {
            if (c.getStart() < 0) {
                return false;
            }
        }
        return true;
    }
}
