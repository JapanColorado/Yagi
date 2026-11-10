package dev.russell.yagi.util

import dev.russell.yagi.R
import dev.russell.yagi.activity.MainActivity
import dev.russell.yagi.common.util.TopSnackbarUtil.showSnack
import dev.russell.yagi.core.feed.LocalFeedCreator
import dev.russell.yagi.fragment.FeedItemlistFragment
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers

/**
 * Adds a folder from the device's own storage: as one feed, or, if it holds several books, as one
 * feed per book (see `LocalFeedCreator`). Whether a feed becomes an audiobook or a podcast is
 * guessed from its files (see `LocalFolderClassifier`) unless the caller says; either way the
 * user can change it in the feed settings. A single new feed opens once it is stored; for a
 * library a snackbar says how many books were added. A failure is shown as a snackbar.
 *
 * The picker is registered in the constructor, and a fragment may only register before it is
 * created, so create this as a field initializer (or in `onCreate`), not in a click handler.
 * Call [dispose] from the fragment's `onDestroy`.
 */
class LocalFeedImporter(private val fragment: Fragment) {

    private val disposables = CompositeDisposable()

    /** The audiobook flag for the folder being picked; null to guess. */
    private var pendingAudiobook: Boolean? = null

    private val folderLauncher =
        fragment.registerForActivityResult(OpenFolder()) { uri: Uri? ->
            val audiobook = pendingAudiobook
            if (uri != null) add(uri) { context -> LocalFeedCreator.addLocalFolder(context, uri, audiobook) }
        }

    /** Picks a folder and adds it as an audiobook or a podcast, whichever it looks like. */
    fun addFromDevice() {
        pendingAudiobook = null
        launch { folderLauncher.launch(null) }
    }

    /** Picks a folder and adds it as an audiobook, for pages that only show audiobooks. */
    fun addAudiobook() {
        pendingAudiobook = true
        launch { folderLauncher.launch(null) }
    }

    fun dispose() {
        disposables.dispose()
    }

    private fun launch(start: () -> Unit) {
        try {
            start()
        } catch (ignored: ActivityNotFoundException) {
            // no document picker on this device
            showSnack(fragment.activity, R.string.unable_to_start_system_file_manager, Toast.LENGTH_LONG)
        }
    }

    private fun add(uri: Uri, create: (Context) -> LocalFeedCreator.ImportResult) {
        val context = fragment.context?.applicationContext ?: return
        disposables.add(
            Observable.fromCallable { create(context) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result -> show(result) }, { error: Throwable ->
                    Log.e(TAG, "adding $uri failed", error)
                    showSnack(fragment.activity, error.localizedMessage, Toast.LENGTH_LONG)
                })
        )
    }

    private fun show(result: LocalFeedCreator.ImportResult) {
        val activity = fragment.activity as? MainActivity ?: return
        val added = result.added
        if (!result.library) {
            (added.firstOrNull() ?: result.existing.firstOrNull())?.let { activity.loadChildFragment(FeedItemlistFragment.newInstance(it.id)) }
            return
        }
        val message = when {
            added.isEmpty() -> activity.getString(R.string.local_library_nothing_new)
            added.all { it.preferences?.isAudiobook == true } -> activity.resources.getQuantityString(
                R.plurals.local_library_audiobooks_added, added.size, added.size
            )
            else -> activity.resources.getQuantityString(
                R.plurals.local_library_feeds_added, added.size, added.size
            )
        }
        showSnack(activity, message, Toast.LENGTH_LONG)
    }

    private class OpenFolder : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent {
            return super.createIntent(context, input)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        private const val TAG = "LocalFeedImporter"
    }
}
