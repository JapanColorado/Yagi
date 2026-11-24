package dev.russell.yagi.dialog.filter

import dev.russell.yagi.R
import dev.russell.yagi.core.feed.SubscriptionsFilter
import dev.russell.yagi.core.pref.Prefs
import dev.russell.yagi.core.storage.DBReader
import dev.russell.yagi.core.storage.FeedKind
import dev.russell.yagi.event.UnreadItemsUpdateEvent
import org.greenrobot.eventbus.EventBus

/**
 * Podcast filter of the Library page. The choice is stored in [Prefs.subscriptionsFilter] and
 * applied through [UnreadItemsUpdateEvent], which makes the Library (and the drawer) reload.
 * "Has new episodes" also hides finished audiobooks from the Library's audiobook row
 * ([dev.russell.yagi.adapter.AudiobookEntry.loadForLibrary]); the count shown here is podcasts only.
 */
class SubscriptionFilterSheet : FilterSheet() {

    override val countPluralsRes: Int
        get() = R.plurals.filter_podcasts_shown

    override fun buildSections(): List<FilterSection> {
        val on = getString(R.string.filter_on)
        val off = getString(R.string.filter_off)
        return listOf(
            FilterSection(
                SECTION_NEW, getString(R.string.filter_section_new_episodes),
                listOf(FilterOption(COUNTER_GREATER_ZERO, getString(R.string.filter_has_new_episodes)))
            ),
            FilterSection(
                SECTION_AUTO_DOWNLOAD, getString(R.string.filter_section_auto_download), listOf(
                    FilterOption(AUTO_DOWNLOAD_ON, on), FilterOption(AUTO_DOWNLOAD_OFF, off)
                )
            ),
            FilterSection(
                SECTION_UPDATES, getString(R.string.filter_section_auto_refresh), listOf(
                    FilterOption(UPDATES_ON, on), FilterOption(UPDATES_OFF, off)
                )
            ),
            FilterSection(
                SECTION_NOTIFICATIONS, getString(R.string.filter_section_notifications), listOf(
                    FilterOption(NOTIFICATIONS_ON, on), FilterOption(NOTIFICATIONS_OFF, off)
                )
            )
        )
    }

    override fun currentSelection(): FilterSelection {
        val stored = Prefs.subscriptionsFilter.values.toSet()
        val result = HashMap<String, Set<String>>()
        for ((section, keys) in SECTIONS) {
            // one per section: the old dialog could not store both of a pair either
            keys.firstOrNull { it in stored }?.let { result[section] = setOf(it) }
        }
        return result
    }

    override fun onSelectionChanged(selection: FilterSelection) {
        Prefs.subscriptionsFilter = toFilter(selection)
        EventBus.getDefault().post(UnreadItemsUpdateEvent())
    }

    override fun countShown(selection: FilterSelection): Int =
        DBReader.getFilteredFeedCount(FeedKind.PODCASTS, toFilter(selection))

    companion object {
        const val TAG = "SubscriptionFilterSheet"

        // the keys SubscriptionsFilter understands
        private const val COUNTER_GREATER_ZERO = "counter_greater_zero"
        private const val AUTO_DOWNLOAD_ON = "enabled_auto_download"
        private const val AUTO_DOWNLOAD_OFF = "disabled_auto_download"
        private const val UPDATES_ON = "enabled_updates"
        private const val UPDATES_OFF = "disabled_updates"
        private const val NOTIFICATIONS_ON = "episode_notification_enabled"
        private const val NOTIFICATIONS_OFF = "episode_notification_disabled"

        private const val SECTION_NEW = "new"
        private const val SECTION_AUTO_DOWNLOAD = "auto_download"
        private const val SECTION_UPDATES = "updates"
        private const val SECTION_NOTIFICATIONS = "notifications"

        private val SECTIONS = mapOf(
            SECTION_NEW to listOf(COUNTER_GREATER_ZERO),
            SECTION_AUTO_DOWNLOAD to listOf(AUTO_DOWNLOAD_ON, AUTO_DOWNLOAD_OFF),
            SECTION_UPDATES to listOf(UPDATES_ON, UPDATES_OFF),
            SECTION_NOTIFICATIONS to listOf(NOTIFICATIONS_ON, NOTIFICATIONS_OFF)
        )

        private fun toFilter(selection: FilterSelection): SubscriptionsFilter {
            val keys = SECTIONS.keys.flatMap { selection[it].orEmpty() }
            return SubscriptionsFilter(keys.toTypedArray())
        }
    }
}
