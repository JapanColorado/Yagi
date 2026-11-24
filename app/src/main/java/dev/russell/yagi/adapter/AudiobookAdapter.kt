package dev.russell.yagi.adapter

import dev.russell.yagi.R
import dev.russell.yagi.actionbuttons.ItemActionButton
import dev.russell.yagi.activity.MainActivity
import dev.russell.yagi.core.pref.Prefs
import dev.russell.yagi.core.storage.DBReader
import dev.russell.yagi.core.util.Converter
import dev.russell.yagi.databinding.AudiobookItemBinding
import dev.russell.yagi.fragment.FeedItemlistFragment
import dev.russell.yagi.model.feed.AudiobookProgress
import dev.russell.yagi.model.feed.Feed
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.WorkerThread
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference

/** One book on the shelf: the feed and how far the user got (null while it has no tracks). */
class AudiobookEntry(val feed: Feed, val progress: AudiobookProgress?) {
    companion object {
        /**
         * Every subscribed audiobook with its progress, unfinished books first, otherwise in the
         * order of [DBReader.getAudiobookFeeds].
         */
        @JvmStatic
        @WorkerThread
        fun load(): List<AudiobookEntry> {
            val progress = DBReader.getAudiobookProgress()
            return DBReader.getAudiobookFeeds()
                .map { AudiobookEntry(it, progress[it.id]) }
                .sortedBy { it.progress?.isFinished == true }
        }

        /**
         * The books for the Library page's row: [load], without the finished ones while the
         * Library's "Has new episodes" filter is on (a finished book has nothing new to offer).
         */
        @JvmStatic
        @WorkerThread
        fun loadForLibrary(): List<AudiobookEntry> {
            val books = load()
            if (!Prefs.subscriptionsFilter.isShowIfCounterGreaterZero) {
                return books
            }
            return books.filterNot { it.progress?.isFinished == true }
        }
    }
}

/**
 * Covers of audiobooks with a progress bar under each. Tapping a cover opens the book's feed page;
 * the button on the cover continues the book at [AudiobookProgress.getResumeItem].
 *
 * @param itemWidth fixed width of one item in pixels (for a horizontal row), or 0 to fill the
 *     column of a grid
 */
class AudiobookAdapter(activity: MainActivity, private val itemWidth: Int = 0) :
    RecyclerView.Adapter<AudiobookAdapter.Holder>() {
    private val activityRef = WeakReference(activity)
    private var entries: List<AudiobookEntry> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun setEntries(entries: List<AudiobookEntry>) {
        this.entries = entries
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemId(position: Int): Long = entries[position].feed.id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = AudiobookItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (itemWidth > 0) {
            binding.root.layoutParams = binding.root.layoutParams.apply { width = itemWidth }
        }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(entries[position])
    }

    inner class Holder(private val binding: AudiobookItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: AudiobookEntry) {
            val context = binding.root.context
            val feed = entry.feed
            binding.txtvTitle.text = feed.title
            binding.imgvCover.contentDescription = feed.title
            CoverLoader()
                .withUri(feed.imageUrl)
                .withCoverView(binding.imgvCover)
                .load()

            val progress = entry.progress
            val percent = progress?.percent ?: 0
            binding.progress.progress = percent
            val percentText = context.getString(R.string.audiobook_percent, percent)
            binding.txtvProgress.text = when {
                progress == null -> percentText
                progress.isFinished -> context.getString(R.string.audiobook_finished)
                itemWidth > 0 || progress.remainingDuration <= 0 -> percentText
                else -> percentText + " · " + context.getString(
                    R.string.audiobook_time_left,
                    Converter.getDurationStringLocalized(context, progress.remainingDuration)
                )
            }

            binding.root.setOnClickListener {
                activityRef.get()?.loadChildFragment(FeedItemlistFragment.newInstance(feed.id))
            }

            val resumeItem = progress?.resumeItem
            val activity = activityRef.get()
            if (resumeItem == null || activity == null) {
                binding.butPlay.visibility = View.GONE
                binding.butPlay.setOnClickListener(null)
            } else {
                // play, or pause while this track is the one playing
                ItemActionButton.forItem(resumeItem).configure(binding.butPlay, binding.butPlay, activity)
            }
        }
    }
}
