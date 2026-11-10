package dev.russell.yagi.fragment

import dev.russell.yagi.R
import dev.russell.yagi.activity.MainActivity
import dev.russell.yagi.adapter.AudiobookAdapter
import dev.russell.yagi.adapter.AudiobookEntry
import dev.russell.yagi.common.util.DoubleClickBackToContentTopListener
import dev.russell.yagi.core.feed.AudiobookShelfFilter
import dev.russell.yagi.core.pref.Prefs
import dev.russell.yagi.databinding.FragmentAudiobooksBinding
import dev.russell.yagi.dialog.filter.AudiobookFilterSheet
import dev.russell.yagi.event.FeedItemEvent
import dev.russell.yagi.event.FeedListUpdateEvent
import dev.russell.yagi.event.PlayerStatusEvent
import dev.russell.yagi.event.UnreadItemsUpdateEvent
import dev.russell.yagi.dialog.WatchedFoldersDialog
import dev.russell.yagi.util.LocalFeedImporter
import dev.russell.yagi.view.EmptyViewHandler
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * All audiobooks as a grid of covers, each with how far the user got. Tapping a cover opens the
 * book; the button on it continues where the user left off. The toolbar's "Sort & filter" sheet
 * ([AudiobookFilterSheet], stored in [Prefs.audiobookShelfFilter]) sorts and filters the grid in
 * memory; it does not affect the audiobook row of the Library page.
 */
class AudiobooksFragment : Fragment(), DoubleClickBackToContentTopListener.IBackToContentTopView,
    AudiobookFilterSheet.Host {
    private var _binding: FragmentAudiobooksBinding? = null
    private lateinit var adapter: AudiobookAdapter
    private lateinit var emptyView: EmptyViewHandler
    private val localFeedImporter = LocalFeedImporter(this)
    private var disposable: Disposable? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val reloader = Runnable { loadAudiobooks() }

    /** Every audiobook as loaded, before sorting and filtering. */
    override var allAudiobooks: List<AudiobookEntry> = emptyList()
        private set
    private var loaded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentAudiobooksBinding.inflate(inflater, container, false)
        _binding = binding
        val toolbar = binding.appBarLayout.toolbar
        toolbar.setTitle(R.string.audiobooks_label)
        var displayUpArrow = parentFragmentManager.backStackEntryCount != 0
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW)
        }
        (requireActivity() as MainActivity).setupToolbarToggle(toolbar, displayUpArrow)
        toolbar.setOnClickListener(DoubleClickBackToContentTopListener(this))
        toolbar.inflateMenu(R.menu.audiobooks)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.audiobooks_filter) {
                if (childFragmentManager.findFragmentByTag(AudiobookFilterSheet.TAG) == null) {
                    AudiobookFilterSheet().show(childFragmentManager, AudiobookFilterSheet.TAG)
                }
                true
            } else if (item.itemId == R.id.audiobooks_watched_folders) {
                WatchedFoldersDialog.show(requireContext())
                true
            } else {
                false
            }
        }

        adapter = AudiobookAdapter(requireActivity() as MainActivity)
        binding.audiobooksGrid.layoutManager = GridLayoutManager(
            context, resources.getInteger(R.integer.subscriptions_default_num_of_columns)
        )
        binding.audiobooksGrid.adapter = adapter

        emptyView = EmptyViewHandler(context)
        showNoAudiobooksEmptyState()
        emptyView.attachToRecyclerView(binding.audiobooksGrid)
        emptyView.hide()

        binding.audiobooksAdd.setOnClickListener { localFeedImporter.addAudiobook() }
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_UP_ARROW, parentFragmentManager.backStackEntryCount != 0)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
        loadAudiobooks()
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
        disposable?.dispose()
        uiHandler.removeCallbacks(reloader)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // back from a book's page: show the progress made there
        if (!hidden && isAdded) {
            loadAudiobooks()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        localFeedImporter.dispose()
    }

    private fun loadAudiobooks() {
        disposable?.dispose()
        disposable = Observable.fromCallable { AudiobookEntry.load() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { entries: List<AudiobookEntry> ->
                    allAudiobooks = entries
                    loaded = true
                    showAudiobooks()
                },
                { error: Throwable -> Log.e(TAG, "loading the audiobooks failed", error) }
            )
    }

    /** Sorts and filters [allAudiobooks] into the grid and updates the filter icon and empty state. */
    private fun showAudiobooks() {
        val binding = _binding ?: return
        val filter = Prefs.audiobookShelfFilter
        val feeds = allAudiobooks.map { it.feed }
        val filtering = filter.isFiltering(feeds)
        val shown = filter.apply(allAudiobooks, { it.feed }, { it.progress })
        adapter.setEntries(shown)

        val filterItem = binding.appBarLayout.toolbar.menu.findItem(R.id.audiobooks_filter)
        filterItem?.setIcon(if (filtering) R.drawable.ic_filter_disable else R.drawable.ic_filter)

        if (allAudiobooks.isNotEmpty() && shown.isEmpty()) {
            showFilteredOutEmptyState(filter)
        } else {
            showNoAudiobooksEmptyState()
        }
        if (loaded) {
            emptyView.updateVisibility()
        }
    }

    private fun showNoAudiobooksEmptyState() {
        emptyView.clearActions()
        emptyView.setIcon(R.drawable.ic_audiobook)
        emptyView.setTitle(R.string.no_audiobooks_head_label)
        emptyView.setMessage(R.string.no_audiobooks_label)
        emptyView.addAction(R.string.add_audiobook, R.drawable.ic_audiobook) {
            localFeedImporter.addAudiobook()
        }
    }

    /** Like the podcast page's "hidden by your filter" state: the filter hides every book. */
    private fun showFilteredOutEmptyState(filter: AudiobookShelfFilter) {
        emptyView.clearActions()
        emptyView.setIcon(R.drawable.ic_filter)
        emptyView.setTitle(R.string.no_audiobooks_match_filter)
        emptyView.setMessage(R.string.no_audiobooks_match_filter_msg)
        emptyView.addAction(R.string.clear_filter_label, R.drawable.ic_filter_disable) {
            // keeps the sort order
            Prefs.audiobookShelfFilter = AudiobookShelfFilter(filter.sort, null, null, null)
            showAudiobooks()
        }
    }

    override fun onAudiobookFilterChanged() {
        showAudiobooks()
    }

    /** Events come in bursts (a feed refresh, marking many played): reload once they settle. */
    private fun scheduleReload() {
        uiHandler.removeCallbacks(reloader)
        uiHandler.postDelayed(reloader, RELOAD_DELAY_MS)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFeedListChanged(event: FeedListUpdateEvent?) {
        scheduleReload()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onUnreadItemsChanged(event: UnreadItemsUpdateEvent?) {
        scheduleReload()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFeedItemsChanged(event: FeedItemEvent?) {
        scheduleReload()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPlayerStatusChanged(event: PlayerStatusEvent?) {
        scheduleReload()
    }

    override fun backToContentTop() {
        val grid = _binding?.audiobooksGrid ?: return
        grid.scrollToPosition(5)
        grid.post { grid.smoothScrollToPosition(0) }
    }

    companion object {
        const val TAG = "AudiobooksFragment"
        private const val KEY_UP_ARROW = "up_arrow"
        private const val RELOAD_DELAY_MS = 500L
    }
}
