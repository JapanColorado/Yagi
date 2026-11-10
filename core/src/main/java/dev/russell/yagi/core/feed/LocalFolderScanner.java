package dev.russell.yagi.core.feed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.russell.yagi.model.playback.MediaType;
import dev.russell.yagi.parser.feed.util.MimeTypeUtils;

/**
 * Lists the media and image files below a folder picked with the storage access framework,
 * including subfolders ("CD1/", "CD2/", ...). It asks the documents provider directly with one
 * {@link DocumentsContract} children query per folder, which returns name, type, size and date in
 * one go; {@code DocumentFile.listFiles()} would issue extra queries for every single file, which
 * is far too slow for big audiobook libraries.
 *
 * <p>Hidden files and folders (name starting with ".") are skipped. The walk stops descending
 * below {@link #MAX_DEPTH} levels and stops altogether after {@link #MAX_FOLDERS} folders or
 * {@link #MAX_FILES} media files, so a mistakenly picked storage root cannot hang the refresh.</p>
 */
public final class LocalFolderScanner {
    private static final String TAG = "LocalFolderScanner";

    /** Deepest subfolder level that is still scanned; the picked folder itself is level 0. */
    static final int MAX_DEPTH = 8;
    static final int MAX_FOLDERS = 2000;
    static final int MAX_FILES = 10000;

    private static final String[] PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    private LocalFolderScanner() {
    }

    /** One file found by the scan. */
    public static final class LocalFile {
        /** Document URI, the same one {@code DocumentFile.fromTreeUri(..).listFiles()} returns. */
        @NonNull
        public final Uri uri;
        /** Path below the picked folder with "/" separators, e.g. "CD1/01 Intro.mp3". */
        @NonNull
        public final String relativePath;
        @NonNull
        public final String name;
        /** The provider's type, resolved from the file extension when the provider is vague. */
        @Nullable
        public final String mimeType;
        public final long size;
        public final long lastModified;
        /** 0 for files directly in the picked folder. */
        public final int depth;

        public LocalFile(@NonNull Uri uri, @NonNull String relativePath, @NonNull String name,
                         @Nullable String mimeType, long size, long lastModified, int depth) {
            this.uri = uri;
            this.relativePath = relativePath;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
            this.lastModified = lastModified;
            this.depth = depth;
        }
    }

    /** Result of {@link #scan}: the audio/video files and the image files, in provider order. */
    public static final class Result {
        public final List<LocalFile> mediaFiles = new ArrayList<>();
        public final List<LocalFile> imageFiles = new ArrayList<>();
        /**
         * The document id of every folder that was walked, by its path below the scanned folder
         * ("" for the scanned folder itself). {@link LocalFeedCreator} builds the URLs of the
         * books inside a library folder from these.
         */
        public final Map<String, String> folderIds = new LinkedHashMap<>();
        /** True if a limit was hit, so the lists may be incomplete. */
        public boolean truncated;
    }

    /**
     * The id of the document a storage access framework URI points at: the folder below the
     * picked tree for {@code .../tree/<picked>/document/<folder>} (a book inside a library
     * folder), else the picked tree itself.
     */
    @NonNull
    public static String documentIdOf(@NonNull Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() >= 4 && "document".equals(segments.get(2))) {
            return segments.get(3);
        }
        if (segments.size() >= 2 && "document".equals(segments.get(0))) {
            return segments.get(1);
        }
        return DocumentsContract.getTreeDocumentId(uri);
    }

    /**
     * Walks the tree below {@code treeUri}: the tree URI the OpenDocumentTree picker returned, or
     * the URI of a folder inside such a tree.
     *
     * @throws IOException if the picked folder itself cannot be listed
     */
    @NonNull
    public static Result scan(@NonNull Context context, @NonNull Uri treeUri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Result result = new Result();
        Deque<Folder> pending = new ArrayDeque<>();
        pending.push(new Folder(documentIdOf(treeUri), "", 0));
        int folders = 0;
        boolean root = true;
        while (!pending.isEmpty()) {
            if (folders >= MAX_FOLDERS || result.mediaFiles.size() >= MAX_FILES) {
                Log.w(TAG, "Stopping the scan of " + treeUri + ": too many folders or files");
                result.truncated = true;
                break;
            }
            Folder folder = pending.pop();
            folders++;
            result.folderIds.put(folder.relativePath, folder.documentId);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder.documentId);
            List<Folder> subfolders = new ArrayList<>();
            try (Cursor cursor = resolver.query(childrenUri, PROJECTION, null, null, null)) {
                if (cursor == null) {
                    if (root) {
                        throw new IOException("Unable to list the folder " + treeUri);
                    }
                    continue;
                }
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (documentId == null || name == null || name.startsWith(".")) {
                        continue;
                    }
                    String type = cursor.getString(2);
                    String relativePath = folder.relativePath.isEmpty() ? name : folder.relativePath + "/" + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) {
                        if (folder.depth < MAX_DEPTH) {
                            subfolders.add(new Folder(documentId, relativePath, folder.depth + 1));
                        } else {
                            result.truncated = true;
                        }
                        continue;
                    }
                    long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                    long lastModified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    if (MimeTypeUtils.isImageFile(type)) {
                        result.imageFiles.add(new LocalFile(uri, relativePath, name, type, size,
                                lastModified, folder.depth));
                        continue;
                    }
                    String mimeType = MimeTypeUtils.getMimeType(type, name);
                    MediaType mediaType = MediaType.fromMimeType(mimeType);
                    if (mediaType == MediaType.AUDIO || mediaType == MediaType.VIDEO) {
                        result.mediaFiles.add(new LocalFile(uri, relativePath, name, mimeType, size,
                                lastModified, folder.depth));
                        if (result.mediaFiles.size() >= MAX_FILES) {
                            result.truncated = true;
                            break;
                        }
                    }
                }
            } catch (SecurityException | IllegalArgumentException e) {
                if (root) {
                    throw new IOException("Unable to list the folder " + treeUri + ": " + e.getMessage(), e);
                }
                // A subfolder that cannot be read is skipped; the rest of the book still loads.
                Log.w(TAG, "Unable to list " + childrenUri + ": " + e.getMessage());
            }
            root = false;
            // push in reverse so the first subfolder is walked first
            for (int i = subfolders.size() - 1; i >= 0; i--) {
                pending.push(subfolders.get(i));
            }
        }
        return result;
    }

    private static final class Folder {
        final String documentId;
        final String relativePath;
        final int depth;

        Folder(String documentId, String relativePath, int depth) {
            this.documentId = documentId;
            this.relativePath = relativePath;
            this.depth = depth;
        }
    }
}
