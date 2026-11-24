package dev.russell.yagi.fragment

import dev.russell.yagi.core.view.TopAppBarLayout
import dev.russell.yagi.common.util.DoubleClickBackToContentTopListener
import dev.russell.yagi.common.util.MenuIconUtil.showToolbarMenuIcon
import dev.russell.yagi.common.util.Timber
import dev.russell.yagi.R
import dev.russell.yagi.activity.MainActivity
import dev.russell.yagi.adapter.AudiobookEntry
import dev.russell.yagi.adapter.LibraryShelfAdapter
import dev.russell.yagi.adapter.MultiSelectAdapter
import dev.russell.yagi.adapter.MultiSelectAdapter.OnSelectModeListener
import dev.russell.yagi.adapter.SubFeedsAdapter
import dev.russell.yagi.adapter.SubFeedsAdapter.GridDividerItemDecorator
import dev.russell.yagi.appshortcuts.SubscriptionActivityStarter
import dev.russell.yagi.core.event.DownloadEvent
import dev.russell.yagi.core.pref.Prefs.subscriptionsFilter
import dev.russell.yagi.core.service.download.DownloadService
import dev.russell.yagi.core.storage.DBReader
import dev.russell.yagi.core.storage.FeedKind
import dev.russell.yagi.core.storage.NavDrawerData.*
import dev.russell.yagi.core.util.download.AutoUpdateManager
import dev.russell.yagi.core.util.menuhandler.MenuItemUtils
import dev.russell.yagi.core.util.menuhandler.MenuItemUtils.UpdateRefreshMenuItemChecker
import dev.russell.yagi.dialog.AddFeedByUrlDialog
import dev.russell.yagi.dialog.FeedsSortDialog.Companion.newInstance
import dev.russell.yagi.dialog.RemoveFeedDialog.show
import dev.russell.yagi.dialog.RenameItemDialog
import dev.russell.yagi.dialog.filter.SubscriptionFilterSheet
import dev.russell.yagi.dialog.TagEditDialog
import dev.russell.yagi.dialog.TagEditDialog.Companion.newInstance
import dev.russell.yagi.discovery.CombinedSearcher
import dev.russell.yagi.event.FeedItemEvent
import dev.russell.yagi.event.FeedListUpdateEvent
import dev.russell.yagi.event.PlayerStatusEvent
import dev.russell.yagi.event.UnreadItemsUpdateEvent
import dev.russell.yagi.fragment.actions.FeedMultiSelectActionHandler
import dev.russell.yagi.statistics.StatisticsFragment
import dev.russell.yagi.util.LocalFeedImporter
import dev.russell.yagi.util.OpmlImportLauncher
import dev.russell.yagi.util.SkeletonRecyclerDelay
import dev.russell.yagi.view.EmptyViewHandler
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.faltenreich.skeletonlayout.Skeleton
import com.faltenreich.skeletonlayout.applySkeleton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.*

/**
 * The Library page: a row of audiobooks with their progress (only when there are any) above the
 * grid of podcast subscriptions. Opened for a tag folder ([newInstance]) it shows only that
 * folder's podcasts. The class keeps its old name and [TAG] so that stored drawer settings, the
 * last opened page, deep links and launcher shortcuts keep working.
 */
class SubFeedsFragment : Fragment(), Toolbar.OnMenuItemClickListener, OnSelectModeListener,
    DoubleClickBackToContentTopListener.IBackToContentTopView {
    private lateinit var subscriptionRecycler: RecyclerView
    private lateinit var subscriptionAdapter: SubFeedsAdapter
    /** The audiobook row; null in a tag folder, which shows podcasts only. */
    private var shelfAdapter: LibraryShelfAdapter? = null
    private var concatAdapter: ConcatAdapter? = null
    private val localFeedImporter = LocalFeedImporter(this)
    private var shelfDisposable: Disposable? = null
    private lateinit var subscriptionAddButton: FloatingActionButton
    private lateinit var emptyView: EmptyViewHandler
    private val opmlImportLauncher = OpmlImportLauncher(this)
    private lateinit var toolbar: Toolbar
    private var displayedFolder: String? = null
    private var isUpdatingFeeds = false
    private var displayUpArrow = false
    private var disposable: Disposable? = null

    /** Confirmed destructive actions; they call back into this fragment when they finish. */
    private val userActions = CompositeDisposable()
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var skeleton: Skeleton
    private var listItems: List<DrawerItem> = emptyList()
    private lateinit var skeletonRecyclerDelay: SkeletonRecyclerDelay
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        prefs = requireActivity().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Timber.v("subfeeds oncreate view")
        val root = inflater.inflate(R.layout.fragment_subscriptions, container, false)
        toolbar = root.findViewById<TopAppBarLayout>(R.id.appBarLayout).toolbar
        toolbar.setOnMenuItemClickListener(this)
        displayUpArrow = parentFragmentManager.backStackEntryCount != 0
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW)
        }
        (requireActivity() as MainActivity).setupToolbarToggle(toolbar, displayUpArrow)
        toolbar.inflateMenu(R.menu.subscriptions)
        showToolbarMenuIcon(toolbar)
        for (i in COLUMN_CHECKBOX_IDS.indices) {
            // Do this in Java to localize numbers
            toolbar.getMenu().findItem(COLUMN_CHECKBOX_IDS[i]).title =
                String.format(Locale.getDefault(), "%d", i + MIN_NUM_COLUMNS)
        }
        refreshToolbarState()
        if (arguments != null) {
            displayedFolder = requireArguments().getString(ARGUMENT_FOLDER, null)
            if (displayedFolder != null) {
                toolbar.setTitle(displayedFolder)
            }
        }
        subscriptionRecycler = root.findViewById(R.id.subscriptions_grid)
        setColumnNumber(prefs.getInt(PREF_NUM_COLUMNS, defaultNumOfColumns))
        subscriptionRecycler.addItemDecoration(GridDividerItemDecorator())

        toolbar.setOnClickListener(DoubleClickBackToContentTopListener(this))
        registerForContextMenu(subscriptionRecycler)
        subscriptionAddButton = root.findViewById(R.id.subscriptions_add)
        val swipeRefreshLayout = root.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        swipeRefreshLayout.setDistanceToTriggerSync(resources.getInteger(R.integer.swipe_refresh_distance))
        swipeRefreshLayout.setOnRefreshListener {
            AutoUpdateManager.runImmediate(requireContext())
            uiHandler.postDelayed(
                { swipeRefreshLayout.isRefreshing = false },
                resources.getInteger(R.integer.swipe_to_refresh_duration_in_ms).toLong()
            )
        }
        if (displayedFolder == null) {
            toolbar.setTitle(R.string.library_label)
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacksAndMessages(null)
        userActions.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        localFeedImporter.dispose()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow)
        super.onSaveInstanceState(outState)
    }

    private fun refreshToolbarState() {
        val columns = prefs.getInt(PREF_NUM_COLUMNS, defaultNumOfColumns)
        toolbar.menu.findItem(COLUMN_CHECKBOX_IDS[columns - MIN_NUM_COLUMNS]).isChecked =
            true
        isUpdatingFeeds = MenuItemUtils.updateRefreshMenuItem(
            toolbar.menu,
            R.id.refresh_item, updateRefreshMenuItemChecker
        )
        val filterItem = toolbar.menu.findItem(R.id.subscriptions_filter)
        if (subscriptionsFilter.isEnabled) {
            filterItem.setTitle(R.string.filtered_label)
            filterItem.setIcon(R.drawable.ic_filter_disable)
        } else {
            filterItem.setTitle(R.string.filter)
            filterItem.setIcon(R.drawable.ic_filter)
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.refresh_item) {
            AutoUpdateManager.runImmediate(requireContext())
            return true
        } else if (itemId == R.id.subscriptions_filter) {
            if (childFragmentManager.findFragmentByTag(SubscriptionFilterSheet.TAG) == null) {
                SubscriptionFilterSheet().show(childFragmentManager, SubscriptionFilterSheet.TAG)
            }
            return true
        } else if (itemId == R.id.subscriptions_sort) {
            newInstance().show(childFragmentManager, null)
            return true
        } else if (itemId == R.id.subscription_num_columns_3) {
            setColumnNumber(3)
            return true
        } else if (itemId == R.id.subscription_num_columns_4) {
            setColumnNumber(4)
            return true
        } else if (itemId == R.id.subscription_num_columns_5) {
            setColumnNumber(5)
            return true
        } else if (itemId == R.id.action_search) {
            (requireActivity() as MainActivity).loadChildFragment(LocalSearchFragment.newFeedSearchInstance())
            return true
        } else if (itemId == R.id.action_statistics) {
            (requireActivity() as MainActivity).loadChildFragment(StatisticsFragment())
            return true
        }
        return false
    }

    private fun setColumnNumber(columns: Int) {
        val gridLayoutManager = GridLayoutManager(
            context,
            columns, RecyclerView.VERTICAL, false
        )
        // the audiobook row spans the whole width; the skeleton adapter (while loading) does not
        // contain it, hence the adapter check
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val shelf = shelfAdapter ?: return 1
                return if (subscriptionRecycler.adapter === concatAdapter && position < shelf.itemCount) columns else 1
            }
        }
        subscriptionRecycler.layoutManager = gridLayoutManager
        prefs.edit().putInt(PREF_NUM_COLUMNS, columns).apply()
        refreshToolbarState()
    }

    private fun setupEmptyView() {
        emptyView = EmptyViewHandler(context)
        emptyView.setIcon(R.drawable.ic_add_subscription)
        emptyView.setTitle(R.string.no_subscriptions_head_label)
        emptyView.setMessage(R.string.no_subscriptions_label)
        emptyView.addAction(R.string.search_podcasts_label, R.drawable.ic_search) {
            (requireActivity() as MainActivity).loadChildFragment(
                OnlineSearchFragment.newInstance(CombinedSearcher::class.java, "")
            )
        }
        emptyView.addAction(R.string.opml_add_podcast_label, R.drawable.ic_import_opml) {
            opmlImportLauncher.launch()
        }
        emptyView.addAction(R.string.add_podcast_by_url_label, R.drawable.ic_link) {
            AddFeedByUrlDialog.show(requireContext())
        }
        emptyView.addAction(R.string.add_from_device, R.drawable.ic_add_virtual_podcast) {
            localFeedImporter.addFromDevice()
        }
        emptyView.attachToRecyclerView(subscriptionRecycler)
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        //how to animate a grid: https://proandroiddev.com/enter-animation-using-recyclerview-and-layoutanimation-part-2-grids-688829b1d29b
        val loadLayoutAnimation =
            AnimationUtils.loadLayoutAnimation(context, R.anim.grid_layout_animation_from_bottom)
        subscriptionRecycler.layoutAnimation = loadLayoutAnimation

        subscriptionAdapter = object : SubFeedsAdapter(requireActivity() as MainActivity) {
            override fun onCreateContextMenu(
                menu: ContextMenu,
                v: View,
                menuInfo: ContextMenuInfo?
            ) {
                super.onCreateContextMenu(menu, v, menuInfo)
                MenuItemUtils.setOnClickListeners(menu) { item: MenuItem ->
                    this@SubFeedsFragment.onContextItemSelected(
                        item
                    )
                }
            }
        }
        subscriptionAdapter.setOnSelectModeListener(this)
        if (displayedFolder == null) {
            val shelf = LibraryShelfAdapter(requireActivity() as MainActivity) {
                (activity as? MainActivity)?.loadChildFragment(AudiobooksFragment())
            }
            shelfAdapter = shelf
            val concat = ConcatAdapter(
                ConcatAdapter.Config.Builder()
                    .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                    .build(),
                shelf, subscriptionAdapter
            )
            concatAdapter = concat
            subscriptionRecycler.adapter = concat
        } else {
            subscriptionRecycler.adapter = subscriptionAdapter
        }

        subscriptionAdapter.setOnMenuItemClickListener(object :
            MultiSelectAdapter.OnMenuItemClickListener {
            override fun onMenuItemClick(item: MenuItem?) {
                val menuItem = item ?: return
                FeedMultiSelectActionHandler(
                    requireActivity() as MainActivity,
                    subscriptionAdapter.selectedItems
                )
                    .handleAction(menuItem.itemId)
            }
        })
        setupEmptyView()
        subscriptionAddButton.setOnClickListener { showAddMenu(it) }
        skeleton =
            subscriptionRecycler.applySkeleton(R.layout.item_grid_recyclerview_skeleton, 30)
        skeletonRecyclerDelay = SkeletonRecyclerDelay(skeleton, subscriptionRecycler)
        skeletonRecyclerDelay.showSkeleton()
        Timber.v("subfeeds onview create ")
    }

    /** Everything that adds to the library: browse, search, a feed URL, local files, OPML. */
    private fun showAddMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.inflate(R.menu.library_add)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item: MenuItem ->
            val mainActivity = activity as? MainActivity
            when (item.itemId) {
                R.id.libraryAddDiscover -> mainActivity?.loadChildFragment(DiscoverFragment())
                R.id.libraryAddSearch -> mainActivity?.loadChildFragment(
                    OnlineSearchFragment.newInstance(CombinedSearcher::class.java, "")
                )
                R.id.libraryAddByUrl -> AddFeedByUrlDialog.show(requireContext())
                R.id.libraryAddFromDevice -> localFeedImporter.addFromDevice()
                R.id.libraryAddOpml -> opmlImportLauncher.launch()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
        loadSubscriptions()
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
        disposable?.dispose()
        shelfDisposable?.dispose()
        uiHandler.removeCallbacks(shelfReloader)
        subscriptionAdapter.endSelectMode()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // back from a book's page or the Audiobooks page: show the progress made there
        if (!hidden && isAdded) {
            loadShelf()
        }
    }

    private fun loadSubscriptions() {
        disposable?.dispose()
        emptyView.hide()
        val withShelf = shelfAdapter != null
        disposable = Observable.fromCallable {
            Pair(
                loadDrawerItems(),
                if (withShelf) AudiobookEntry.loadForLibrary() else emptyList()
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { (result: List<DrawerItem>, books: List<AudiobookEntry>) ->
                    if (listItems.size > result.size) {
                        // We have fewer items. This can result in items being selected that are no longer visible.
                        subscriptionAdapter.endSelectMode()
                    }
                    listItems = result
                    shelfAdapter?.setEntries(books, result.isNotEmpty())
                    subscriptionAdapter.setItems(result)
                    subscriptionAdapter.notifyDataSetChanged()
                    if (skeleton.isSkeleton()) {
                        skeletonRecyclerDelay.showOriginal()
                    }
                    emptyView.updateVisibility()
                }) { error: Throwable? ->
                Log.e(TAG, Log.getStackTraceString(error))
                skeletonRecyclerDelay.showOriginal()
            }

        refreshToolbarState()
    }

    /** Reloads only the audiobook row, e.g. after progress was made. */
    private fun loadShelf() {
        val shelf = shelfAdapter ?: return
        shelfDisposable?.dispose()
        shelfDisposable = Observable.fromCallable { AudiobookEntry.loadForLibrary() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { books: List<AudiobookEntry> -> shelf.setEntries(books, listItems.isNotEmpty()) },
                { error: Throwable -> Log.e(TAG, "loading the audiobooks failed", error) }
            )
    }

    private fun loadDrawerItems(): List<DrawerItem> {
        val data = DBReader.getNavDrawerData(true, FeedKind.PODCASTS)
        val feedItems: MutableList<DrawerItem> = ArrayList()
        for (item in data.items) {
            if (item.type == DrawerItem.Type.TAG
                && item.title == displayedFolder
            ) {
                return (item as TagDrawerItem).children
            } else if (item.type != DrawerItem.Type.TAG) {
                feedItems.add(item)
            }
        }
        return feedItems
    }

    private val defaultNumOfColumns: Int
        private get() = resources.getInteger(R.integer.subscriptions_default_num_of_columns)

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val drawerItem = subscriptionAdapter.selectedItem ?: return false
        val itemId = item.itemId
        if (drawerItem.type == DrawerItem.Type.TAG && itemId == R.id.rename_folder_item) {
            RenameItemDialog(requireActivity(), drawerItem).show()
            return true
        }
        val feed = (drawerItem as FeedDrawerItem).feed
        if (itemId == R.id.edit_tags) {
            newInstance(listOf(feed.preferences))
                .show(childFragmentManager, TagEditDialog.TAG)
            return true
        } else if (itemId == R.id.rename_item) {
            RenameItemDialog(requireActivity(), feed).show()
            return true
        } else if (itemId == R.id.shortcut_item) {
            SubscriptionActivityStarter.createShortcut(context, feed)
            return true
        } else if (itemId == R.id.remove_feed) {
            show(requireContext(), feed)
            return true
        } else if (itemId == R.id.multi_select) {
            return subscriptionAdapter.onContextItemSelected(item)
        }
        return super.onContextItemSelected(item)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFeedListChanged(event: FeedListUpdateEvent?) {
        loadSubscriptions()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onUnreadItemsChanged(event: UnreadItemsUpdateEvent?) {
        loadSubscriptions()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFeedItemsChanged(event: FeedItemEvent?) {
        scheduleShelfReload()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPlayerStatusChanged(event: PlayerStatusEvent?) {
        scheduleShelfReload()
    }

    /** Item events come in bursts (a feed refresh, marking many played): reload once they settle. */
    private fun scheduleShelfReload() {
        if (shelfAdapter == null) return
        uiHandler.removeCallbacks(shelfReloader)
        uiHandler.postDelayed(shelfReloader, SHELF_RELOAD_DELAY_MS)
    }

    private val shelfReloader = Runnable { loadShelf() }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: DownloadEvent) {
        if (event.hasChangedFeedUpdateStatus(isUpdatingFeeds)) {
            refreshToolbarState()
        }
    }

    private val updateRefreshMenuItemChecker =
        UpdateRefreshMenuItemChecker { DownloadService.isRunning() && DownloadService.isDownloadingFeeds() }

    override fun onEndSelectMode() {
        shelfAdapter?.hidden = false
        subscriptionAdapter.setItems(listItems)
        subscriptionAdapter.notifyDataSetChanged()
        toolbar.visibility = View.VISIBLE
    }

    override fun onStartSelectMode() {
        shelfAdapter?.hidden = true
        toolbar.visibility = View.GONE
        val feedsOnly: MutableList<DrawerItem> = ArrayList()
        for (item in listItems) {
            if (item.type == DrawerItem.Type.FEED) {
                feedsOnly.add(item)
            }
        }
        subscriptionAdapter.setItems(feedsOnly)
        subscriptionAdapter.notifyDataSetChanged()
    }

    companion object {
        const val TAG = "SubFeedsFragment"
        const val PREFS = "SubFeedsFragment"
        const val PREF_NUM_COLUMNS = "columns"
        private const val KEY_UP_ARROW = "up_arrow"
        private const val ARGUMENT_FOLDER = "folder"
        private const val MIN_NUM_COLUMNS = 3
        private const val SHELF_RELOAD_DELAY_MS = 1000L
        private val COLUMN_CHECKBOX_IDS = intArrayOf(
            R.id.subscription_num_columns_3,
            R.id.subscription_num_columns_4,
            R.id.subscription_num_columns_5
        )

        fun newInstance(folderTitle: String?): SubFeedsFragment {
            val fragment = SubFeedsFragment()
            val args = Bundle()
            args.putString(ARGUMENT_FOLDER, folderTitle)
            fragment.arguments = args
            return fragment
        }
    }

    override fun backToContentTop() {
        subscriptionRecycler.scrollToPosition(5)
        subscriptionRecycler.post { subscriptionRecycler.smoothScrollToPosition(0) }
    }
}