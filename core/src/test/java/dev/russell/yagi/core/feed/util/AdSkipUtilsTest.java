package dev.russell.yagi.core.feed.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.russell.yagi.core.pref.AdSkipPrefs;
import dev.russell.yagi.core.pref.PlaybackPreferences;
import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.pref.UsageStatistics;
import dev.russell.yagi.core.storage.DBReader;
import dev.russell.yagi.core.storage.DBWriter;
import dev.russell.yagi.core.sync.SynchronizationCredentials;
import dev.russell.yagi.core.sync.SynchronizationSettings;
import dev.russell.yagi.event.settings.AdSkipChangedEvent;
import dev.russell.yagi.model.feed.AdSegment;
import dev.russell.yagi.model.feed.Feed;
import dev.russell.yagi.model.feed.FeedFilter;
import dev.russell.yagi.model.feed.FeedItem;
import dev.russell.yagi.model.feed.FeedMedia;
import dev.russell.yagi.model.feed.FeedPreferences;
import dev.russell.yagi.model.feed.VolumeAdaptionSetting;
import dev.russell.yagi.storage.db.Db;

/**
 * The per-feed ad-skip model: a feed follows the global default until it has an override of its
 * own, and {@link AdSkipUtils} both resolves that and stores changes to it.
 */
@RunWith(RobolectricTestRunner.class)
public class AdSkipUtilsTest {

    private Application context;
    private CountDownLatch changed;
    private long changedFeedId;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        Prefs.resetForTests();
        Prefs.init(context);
        PlaybackPreferences.init(context);
        UsageStatistics.init(context);
        SynchronizationCredentials.init(context);
        SynchronizationSettings.init(context);
        Db.init(context);
        changed = new CountDownLatch(1);
        EventBus.getDefault().register(this);
    }

    @After
    public void tearDown() {
        EventBus.getDefault().unregister(this);
        DBWriter.tearDownTests();
        Db.tearDownTests();
        context.deleteDatabase(Db.DATABASE_NAME);
        Prefs.resetForTests();
    }

    @Subscribe
    public void onAdSkipChanged(AdSkipChangedEvent event) {
        changedFeedId = event.getFeedId();
        changed.countDown();
    }

    @Test
    public void noOverrideFollowsTheGlobalDefault() {
        FeedPreferences preferences = preferences();
        Prefs.setAdSkipEnabled(false);
        assertFalse(AdSkipUtils.isAdSkipEnabled(preferences));
        Prefs.setAdSkipEnabled(true);
        assertTrue(AdSkipUtils.isAdSkipEnabled(preferences));
    }

    @Test
    public void overrideWinsOverTheGlobalDefault() {
        FeedPreferences on = preferences();
        on.setAdSkipOverride(Boolean.TRUE);
        FeedPreferences off = preferences();
        off.setAdSkipOverride(Boolean.FALSE);

        Prefs.setAdSkipEnabled(false);
        assertTrue("a feed switched on must skip while the default is off",
                AdSkipUtils.isAdSkipEnabled(on));
        assertFalse(AdSkipUtils.isAdSkipEnabled(off));

        Prefs.setAdSkipEnabled(true);
        assertTrue(AdSkipUtils.isAdSkipEnabled(on));
        assertFalse("a feed switched off must not skip while the default is on",
                AdSkipUtils.isAdSkipEnabled(off));
    }

    @Test
    public void mediaResolvesThroughItsFeedAndFallsBackToTheDefault() {
        Feed feed = feed();
        feed.getPreferences().setAdSkipOverride(Boolean.TRUE);
        FeedItem item = new FeedItem(0, "Episode", "guid", "https://example.com/e",
                new Date(1_600_000_000_000L), FeedItem.NEW, feed);
        FeedMedia media = new FeedMedia(item, "https://example.com/e.mp3", 1, "audio/mpeg");
        item.setMedia(media);
        Prefs.setAdSkipEnabled(false);

        assertTrue(AdSkipUtils.isAdSkipEnabled(media));

        FeedMedia orphan = new FeedMedia(null, "https://example.com/o.mp3", 1, "audio/mpeg");
        assertFalse("media without a feed follows the default", AdSkipUtils.isAdSkipEnabled(orphan));
        assertFalse(AdSkipUtils.isAdSkipEnabled((FeedMedia) null));
        assertFalse(AdSkipUtils.isAdSkipEnabled((FeedPreferences) null));
        Prefs.setAdSkipEnabled(true);
        assertTrue(AdSkipUtils.isAdSkipEnabled(orphan));
    }

    @Test
    public void setStoresAnExplicitChoiceAndPostsTheEventAfterTheWrite() throws Exception {
        Feed feed = feed();
        DBWriter.addNewFeed(context, feed).get();
        Prefs.setAdSkipEnabled(true);

        // Equal to the default, but still stored: the user chose it for this feed.
        AdSkipUtils.setAdSkipForFeed(feed, true);
        awaitChange(feed);
        assertEquals(Boolean.TRUE, DBReader.getFeedPreferences(feed.getId()).getAdSkipOverride());
    }

    @Test
    public void resetMakesTheFeedFollowTheDefaultAgain() throws Exception {
        Feed feed = feed();
        feed.getPreferences().setAdSkipOverride(Boolean.FALSE);
        DBWriter.addNewFeed(context, feed).get();
        assertEquals(Boolean.FALSE, DBReader.getFeedPreferences(feed.getId()).getAdSkipOverride());

        AdSkipUtils.resetAdSkipForFeed(feed);
        awaitChange(feed);
        assertNull(feed.getPreferences().getAdSkipOverride());
        assertNull(DBReader.getFeedPreferences(feed.getId()).getAdSkipOverride());
    }

    @Test
    public void onlyDetectedSegmentsNeedTheThreshold() {
        assertTrue(AdSkipUtils.meetsThreshold(detected(0.60f), 0.60f));
        assertFalse(AdSkipUtils.meetsThreshold(detected(0.59f), 0.60f));
        assertTrue(AdSkipUtils.meetsThreshold(
                new AdSegment(1, 0, 1000, AdSegment.Source.CHAPTER, 0f), 0.60f));
        assertTrue(AdSkipUtils.meetsThreshold(
                new AdSegment(1, 0, 1000, AdSegment.Source.MANUAL, 0f), 0.60f));
    }

    @Test
    public void transcriptAndDetectedSegmentsAreGatedByConfidence() {
        for (AdSegment.Source source : new AdSegment.Source[]{
                AdSegment.Source.TRANSCRIPT, AdSegment.Source.DETECTED}) {
            for (boolean skipSelfPromo : new boolean[]{false, true}) {
                assertTrue(source.name(), AdSkipUtils.meetsThreshold(
                        segment(source, 0.60f), 0.60f, skipSelfPromo));
                assertFalse(source.name(), AdSkipUtils.meetsThreshold(
                        segment(source, 0.59f), 0.60f, skipSelfPromo));
            }
        }
    }

    @Test
    public void chapterAndManualSegmentsAlwaysCount() {
        for (AdSegment.Source source : new AdSegment.Source[]{
                AdSegment.Source.CHAPTER, AdSegment.Source.MANUAL}) {
            for (boolean skipSelfPromo : new boolean[]{false, true}) {
                assertTrue(source.name(), AdSkipUtils.meetsThreshold(
                        segment(source, 0f), 0.95f, skipSelfPromo));
            }
        }
    }

    @Test
    public void selfPromotionCountsOnlyWhenAskedForAndConfident() {
        assertFalse(AdSkipUtils.meetsThreshold(segment(AdSegment.Source.SELF_PROMO, 1f), 0.6f,
                false));
        assertTrue(AdSkipUtils.meetsThreshold(segment(AdSegment.Source.SELF_PROMO, 0.6f), 0.6f,
                true));
        assertFalse(AdSkipUtils.meetsThreshold(segment(AdSegment.Source.SELF_PROMO, 0.59f), 0.6f,
                true));
    }

    @Test
    public void theTwoArgumentThresholdReadsTheSelfPromoSetting() {
        AdSegment promo = segment(AdSegment.Source.SELF_PROMO, 0.9f);
        AdSegment transcript = segment(AdSegment.Source.TRANSCRIPT, 0.9f);
        Prefs.setAdSkipSensitivity(AdSkipPrefs.SENSITIVITY_MEDIUM);

        Prefs.setAdSkipSelfPromo(false);
        assertFalse(AdSkipUtils.meetsThreshold(promo, 0.6f));
        assertTrue(AdSkipUtils.meetsThreshold(transcript, 0.6f));
        assertEquals(Arrays.asList(transcript),
                AdSkipUtils.segmentsMeetingThreshold(Arrays.asList(promo, transcript)));

        Prefs.setAdSkipSelfPromo(true);
        assertTrue(AdSkipUtils.meetsThreshold(promo, 0.6f));
        assertEquals(Arrays.asList(promo, transcript),
                AdSkipUtils.segmentsMeetingThreshold(Arrays.asList(promo, transcript)));
    }

    private static AdSegment segment(AdSegment.Source source, float confidence) {
        return new AdSegment(1, 0, 1000, source, confidence);
    }

    @Test
    public void segmentsMeetingThresholdFollowsTheSensitivity() {
        AdSegment weak = detected(0.50f);
        AdSegment strong = detected(0.80f);
        AdSegment chapter = new AdSegment(1, 0, 1000, AdSegment.Source.CHAPTER, 0f);
        AdSegment disabled = detected(0.80f);
        disabled.setEnabled(false);
        List<AdSegment> all = Arrays.asList(weak, strong, chapter, disabled);

        Prefs.setAdSkipSensitivity(AdSkipPrefs.SENSITIVITY_MEDIUM);
        assertEquals(Arrays.asList(strong, chapter, disabled), AdSkipUtils.segmentsMeetingThreshold(all));

        Prefs.setAdSkipSensitivity(AdSkipPrefs.SENSITIVITY_HIGH);
        assertEquals(all, AdSkipUtils.segmentsMeetingThreshold(all));
    }

    private static AdSegment detected(float confidence) {
        return new AdSegment(1, 0, 1000, AdSegment.Source.DETECTED, confidence);
    }

    /** The event is posted from the database thread once the row is written. */
    private void awaitChange(Feed feed) throws InterruptedException {
        assertTrue("AdSkipChangedEvent was not posted", changed.await(5, TimeUnit.SECONDS));
        assertEquals(feed.getId(), changedFeedId);
    }

    private static FeedPreferences preferences() {
        return new FeedPreferences(0, true, true,
                FeedPreferences.AutoDeleteAction.GLOBAL, VolumeAdaptionSetting.OFF,
                null, null, new FeedFilter(), FeedPreferences.SPEED_USE_GLOBAL,
                0, 0, false, new HashSet<>(), false, 0, false, false);
    }

    private static Feed feed() {
        Feed feed = new Feed("https://example.com/feed.xml", null, "Ad skip feed");
        feed.setPreferences(preferences());
        feed.setSubscribed(true);
        return feed;
    }
}
