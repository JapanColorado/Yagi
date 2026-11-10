package dev.russell.yagi.dialog

import android.content.Context
import dev.russell.yagi.R
import dev.russell.yagi.common.views.AccentMaterialDialog
import dev.russell.yagi.core.feed.LocalLibraryStore

/**
 * Lists the library folders that are watched for new books (`LocalLibraryStore`). Tapping one
 * offers to stop watching it; the books already added from it stay.
 */
object WatchedFoldersDialog {

    fun show(context: Context) {
        val libraries = LocalLibraryStore.getAll(context)
        val dialog = AccentMaterialDialog(context, R.style.MaterialAlertDialogTheme)
            .setTitle(R.string.watched_folders)
            .setPositiveButton(R.string.close_label, null)
        if (libraries.isEmpty()) {
            dialog.setMessage(R.string.watched_folders_empty)
        } else {
            val names = libraries.map { it.name }.toTypedArray<CharSequence>()
            dialog.setItems(names) { _, which -> confirmStop(context, libraries[which]) }
        }
        dialog.show()
    }

    private fun confirmStop(context: Context, library: LocalLibraryStore.Library) {
        AccentMaterialDialog(context, R.style.MaterialAlertDialogTheme)
            .setTitle(context.getString(R.string.watched_folder_stop_title, library.name))
            .setMessage(R.string.watched_folder_stop_message)
            .setPositiveButton(R.string.watched_folder_stop) { _, _ ->
                LocalLibraryStore.remove(context.applicationContext, library.treeUri)
            }
            .setNegativeButton(R.string.cancel_label, null)
            .show()
    }
}
