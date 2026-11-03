package dev.russell.yagi.core.pref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import dev.russell.yagi.core.feed.AudiobookShelfFilter;
import dev.russell.yagi.model.feed.EpisodeSearchOptions;
import dev.russell.yagi.model.feed.FeedItemFilter;
import dev.russell.yagi.model.playback.ClearVoiceLevel;
import dev.russell.yagi.model.playback.VolumeLeveling;
import dev.russell.yagi.model.playback.MediaType;

/**
 * Tests the documented defaults of {@link Prefs}, that its setters round-trip, and that using
 * it before {@code init()} fails loudly instead of returning a silent zero value.
 */
@RunWith(RobolectricTestRunner.class)
public class PrefsTest {

    private Application context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        claudeKeyPrefs().edit().clear().commit();
        Prefs.resetForTests();
        Prefs.init(context);
    }

    @After
    public void tearDown() {
        Prefs.resetForTests();
    }

    // -------------------------------------------------------------- defaults

    @Test
    public void defaultPlaybackSpeedIsNormal() {
        assertEquals(1.0f, Prefs.getPlaybackSpeed(MediaType.AUDIO), 0.0001f);
        assertEquals(1.0f, Prefs.getPlaybackSpeed(MediaType.VIDEO), 0.0001f);
        assertEquals(1.0f, Prefs.getPlaybackSpeed(null), 0.0001f);
    }

    @Test
    public void defaultUpdateIntervalIsOff() {
        assertEquals(0L, Prefs.getUpdateInterval());
        assertFalse(Prefs.isAutoUpdateTimeOfDay());
    }

    @Test
    public void aPristineInstallHasAutoUpdateDisabled() {
        assertEquals(0L, Prefs.getUpdateInterval());
        assertTrue(Prefs.isAutoUpdateDisabled());
    }

    @Test
    public void anExplicitZeroIntervalMeansAutoUpdateIsDisabled() {
        prefs().edit().putString(Prefs.PREF_UPDATE_INTERVAL, "0").commit();
        assertEquals(0L, Prefs.getUpdateInterval());
        assertTrue(Prefs.isAutoUpdateDisabled());
    }

    @Test
    public void defaultEpisodeCacheSizeIsTwenty() {
        assertEquals(20, Prefs.getEpisodeCacheSize());
    }

    @Test
    public void defaultParallelDownloadCountIsFour() {
        assertEquals(4, Prefs.getParallelDownloads());
    }

    @Test
    public void otherDocumentedDefaults() {
        assertEquals(30, Prefs.getFastForwardSecs());
        assertEquals(10, Prefs.getRewindSecs());
        assertEquals(30, Prefs.getSmartMarkAsPlayedSecs());
        assertTrue(Prefs.enqueueDownloadedEpisodes());
        assertTrue(Prefs.isFollowQueue());
        assertFalse(Prefs.isEnableAutodownload());
        assertTrue(Prefs.isEnableAutodownloadOnBattery());
        assertFalse(Prefs.isSkipSilence());
        assertFalse(Prefs.shouldDeleteRemoveFromQueue());
        assertTrue(Prefs.isPauseOnHeadsetDisconnect());
        assertTrue(Prefs.shouldPauseForFocusLoss());
        assertEquals(Prefs.EnqueueLocation.BACK, Prefs.getEnqueueLocation());
    }

    @Test
    public void adSkippingIsOffByDefaultButAnalysesDownloadsAndAnnouncesSkips() {
        assertFalse(Prefs.isAdSkipEnabled());
        assertEquals(AdSkipPrefs.SENSITIVITY_MEDIUM, Prefs.getAdSkipSensitivity());
        assertEquals(0.60f, Prefs.getAdSkipMinConfidence(), 0.0001f);
        assertTrue(Prefs.isAdSkipAnalyzeOnDownload());
        assertTrue(Prefs.isAdSkipShowSnackbar());
    }

    // ------------------------------------------------------------ round trips

    @Test
    public void playbackSpeedRoundTrips() {
        Prefs.setPlaybackSpeed(1.75f);
        assertEquals(1.75f, Prefs.getPlaybackSpeed(MediaType.AUDIO), 0.0001f);

        Prefs.setVideoPlaybackSpeed(2.0f);
        assertEquals(2.0f, Prefs.getPlaybackSpeed(MediaType.VIDEO), 0.0001f);
        // Audio and video speeds are independent.
        assertEquals(1.75f, Prefs.getPlaybackSpeed(MediaType.AUDIO), 0.0001f);
    }

    @Test
    public void aCorruptPlaybackSpeedFallsBackToNormalSpeed() {
        prefs().edit().putString("pref_globa_playback_speed", "not a number").commit();
        assertEquals(1.0f, Prefs.getPlaybackSpeed(MediaType.AUDIO), 0.0001f);
        // ... and the broken value is repaired in place.
        assertEquals(1.0f, Prefs.getPlaybackSpeed(MediaType.AUDIO), 0.0001f);
    }

    @Test
    public void updateIntervalIsStoredInHoursAndReadBackInMilliseconds() {
        prefs().edit().putString(Prefs.PREF_UPDATE_INTERVAL, "6").commit();
        assertEquals(TimeUnit.HOURS.toMillis(6), Prefs.getUpdateInterval());
        assertFalse(Prefs.isAutoUpdateDisabled());
        assertFalse(Prefs.isAutoUpdateTimeOfDay());
    }

    @Test
    public void aTimeOfDayUpdateScheduleReportsNoInterval() {
        prefs().edit().putString(Prefs.PREF_UPDATE_INTERVAL, "8:30").commit();
        assertEquals(0L, Prefs.getUpdateInterval());
        assertTrue(Prefs.isAutoUpdateTimeOfDay());
        assertFalse(Prefs.isAutoUpdateDisabled());
    }

    @Test
    public void episodeCacheSizeRoundTrips() {
        prefs().edit().putString(Prefs.PREF_EPISODE_CACHE_SIZE, "100").commit();
        assertEquals(100, Prefs.getEpisodeCacheSize());
    }

    @Test
    public void unlimitedEpisodeCacheSizeIsReportedAsNegativeOne() {
        String unlimited = context.getString(
                dev.russell.yagi.core.R.string.pref_episode_cache_unlimited);
        prefs().edit().putString(Prefs.PREF_EPISODE_CACHE_SIZE, unlimited).commit();
        assertEquals(-1, Prefs.getEpisodeCacheSize());
        assertEquals(Prefs.getEpisodeCacheSizeUnlimited(), Prefs.getEpisodeCacheSize());
    }

    @Test
    public void parallelDownloadCountRoundTrips() {
        prefs().edit().putString(Prefs.PREF_PARALLEL_DOWNLOADS, "8").commit();
        assertEquals(8, Prefs.getParallelDownloads());
    }

    @Test
    public void secondsSettersRoundTrip() {
        Prefs.setFastForwardSecs(45);
        Prefs.setRewindSecs(15);
        assertEquals(45, Prefs.getFastForwardSecs());
        assertEquals(15, Prefs.getRewindSecs());
    }

    @Test
    public void booleanSettersRoundTrip() {
        Prefs.setEnqueueDownloadedEpisodes(false);
        assertFalse(Prefs.enqueueDownloadedEpisodes());

        Prefs.setFollowQueue(false);
        assertFalse(Prefs.isFollowQueue());

        Prefs.setEnableAutodownload(true);
        assertTrue(Prefs.isEnableAutodownload());

        Prefs.setSkipSilence(true);
        assertTrue(Prefs.isSkipSilence());
    }

    @Test
    public void adSkipSettersRoundTrip() {
        Prefs.setAdSkipEnabled(true);
        Prefs.setAdSkipAnalyzeOnDownload(false);
        Prefs.setAdSkipShowSnackbar(false);
        assertTrue(Prefs.isAdSkipEnabled());
        assertFalse(Prefs.isAdSkipAnalyzeOnDownload());
        assertFalse(Prefs.isAdSkipShowSnackbar());
    }

    @Test
    public void adSkipSensitivityPicksTheConfidenceThreshold() {
        Prefs.setAdSkipSensitivity(AdSkipPrefs.SENSITIVITY_LOW);
        assertEquals(0.75f, Prefs.getAdSkipMinConfidence(), 0.0001f);

        Prefs.setAdSkipSensitivity(AdSkipPrefs.SENSITIVITY_HIGH);
        assertEquals(0.45f, Prefs.getAdSkipMinConfidence(), 0.0001f);

        // An unusable stored value must not break playback.
        prefs().edit().putString(Prefs.PREF_AD_SKIP_SENSITIVITY, "nonsense").commit();
        assertEquals(0.60f, Prefs.getAdSkipMinConfidence(), 0.0001f);
        prefs().edit().putString(Prefs.PREF_AD_SKIP_SENSITIVITY, null).commit();
        assertEquals(AdSkipPrefs.SENSITIVITY_MEDIUM, Prefs.getAdSkipSensitivity());
    }

    @Test
    public void transcriptAdSkipSettingsDefaultAndRoundTrip() {
        assertTrue(Prefs.isAdSkipUseTranscripts());
        assertFalse(Prefs.isAdSkipSelfPromo());
        Prefs.setAdSkipUseTranscripts(false);
        Prefs.setAdSkipSelfPromo(true);
        assertFalse(Prefs.isAdSkipUseTranscripts());
        assertTrue(Prefs.isAdSkipSelfPromo());
        // what the Settings switches store
        assertFalse(prefs().getBoolean(Prefs.PREF_AD_SKIP_USE_TRANSCRIPTS, true));
        assertTrue(prefs().getBoolean(Prefs.PREF_AD_SKIP_SELF_PROMO, false));
    }

    @Test
    public void theClaudeKeyIsTrimmedAndKeptOutOfTheDefaultPreferences() {
        assertNull(Prefs.getClaudeApiKey());
        assertFalse(Prefs.hasClaudeApiKey());

        Prefs.setClaudeApiKey("  sk-ant-test  ");
        assertEquals("sk-ant-test", Prefs.getClaudeApiKey());
        assertTrue(Prefs.hasClaudeApiKey());
        assertEquals("sk-ant-test", claudeKeyPrefs().getString("api_key", null));
        assertFalse(prefs().getAll().containsValue("sk-ant-test"));

        Prefs.setClaudeApiKey("   ");
        assertNull(Prefs.getClaudeApiKey());
        assertFalse(claudeKeyPrefs().contains("api_key"));

        Prefs.setClaudeApiKey("sk-ant-test");
        Prefs.setClaudeApiKey(null);
        assertFalse(Prefs.hasClaudeApiKey());

        // a blank value written by something else still reads as unset
        claudeKeyPrefs().edit().putString("api_key", " ").commit();
        assertNull(Prefs.getClaudeApiKey());
    }

    @Test
    public void transcriptAdMethodIsRememberedPerMediaAndReplaced() {
        assertNull(Prefs.getTranscriptAdMethod(7));
        Prefs.setTranscriptAdMethod(7, "phrases");
        Prefs.setTranscriptAdMethod(77, "claude");
        assertEquals("phrases", Prefs.getTranscriptAdMethod(7));
        assertEquals("claude", Prefs.getTranscriptAdMethod(77));

        Prefs.setTranscriptAdMethod(7, "claude");
        assertEquals("claude", Prefs.getTranscriptAdMethod(7));
        assertEquals("claude", Prefs.getTranscriptAdMethod(77));
        assertEquals(2, prefs().getStringSet(Prefs.PREF_AD_SKIP_TRANSCRIPT_ANALYZED, null).size());
        assertNull(Prefs.getTranscriptAdMethod(777));
    }

    @Test
    public void enqueueLocationRoundTrips() {
        Prefs.setEnqueueLocation(Prefs.EnqueueLocation.FRONT);
        assertEquals(Prefs.EnqueueLocation.FRONT, Prefs.getEnqueueLocation());
    }

    @Test
    public void anUnknownEnqueueLocationFallsBackToTheBackOfTheQueue() {
        prefs().edit().putString(Prefs.PREF_ENQUEUE_LOCATION, "NO_SUCH_LOCATION").commit();
        assertEquals(Prefs.EnqueueLocation.BACK, Prefs.getEnqueueLocation());
    }

    // ------------------------------------- the never-null string default helper

    @Test
    public void stringPreferencesExplicitlySetToNullStillYieldTheirDefault() {
        // SharedPreferences happily stores a null string; the getString helper must not leak it.
        prefs().edit()
                .putString(Prefs.PREF_UPDATE_INTERVAL, null)
                .putString(Prefs.PREF_PARALLEL_DOWNLOADS, null)
                .putString(Prefs.PREF_EPISODE_CACHE_SIZE, null)
                .putString(Prefs.PREF_ENQUEUE_LOCATION, null)
                .putString(Prefs.PREF_DRAWER_FEED_ORDER_METHOD, null)
                .commit();

        assertEquals(0L, Prefs.getUpdateInterval());
        assertEquals(4, Prefs.getParallelDownloads());
        assertEquals(20, Prefs.getEpisodeCacheSize());
        assertEquals(Prefs.EnqueueLocation.BACK, Prefs.getEnqueueLocation());
        assertNotNull(Prefs.getFeedOrderMethod());
        assertEquals(Prefs.ORDER_ASC, Prefs.getFeedOrderMethod());
    }

    @Test
    public void proxyConfigIsNeverNullAndDefaultsToADirectConnection() {
        assertNotNull(Prefs.getProxyConfig());
        assertEquals(java.net.Proxy.Type.DIRECT, Prefs.getProxyConfig().type);
    }

    // ------------------------------------------------------- the init contract

    @Test
    public void readingAPreferenceBeforeInitThrowsAndNamesTheInitCall() {
        Prefs.resetForTests();
        try {
            Prefs.getParallelDownloads();
            fail("expected an IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage(), expected.getMessage().contains("Prefs.init()"));
        }
    }

    @Test
    public void writingAPreferenceBeforeInitThrowsToo() {
        Prefs.resetForTests();
        try {
            Prefs.setPlaybackSpeed(1.5f);
            fail("expected an IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Prefs.init()"));
        }
    }

    @Test
    public void initMakesPreferencesUsableAgain() {
        Prefs.resetForTests();
        Prefs.init(context);
        assertEquals(4, Prefs.getParallelDownloads());
    }

    @Test
    public void oldAudiobookStatusesAreRewrittenAsUnfinished() {
        for (String old : new String[]{"NOT_STARTED", "IN_PROGRESS"}) {
            prefs().edit().putString("pref_audiobooks_status", old).commit();
            assertNull(Prefs.getAudiobookShelfFilter().status);
            Prefs.migrateAudiobookShelfStatus();
            assertEquals(AudiobookShelfFilter.Status.UNFINISHED, Prefs.getAudiobookShelfFilter().status);
        }
        prefs().edit().putString("pref_audiobooks_status", "FINISHED").commit();
        Prefs.migrateAudiobookShelfStatus();
        assertEquals(AudiobookShelfFilter.Status.FINISHED, Prefs.getAudiobookShelfFilter().status);
    }

    @Test
    public void volumeLevelingDefaultsToOffAndRoundTrips() {
        assertEquals(VolumeLeveling.OFF, Prefs.getVolumeLeveling());
        Prefs.setVolumeLeveling(VolumeLeveling.GENTLE);
        assertEquals(VolumeLeveling.GENTLE, Prefs.getVolumeLeveling());
        // what the Settings ListPreference stores
        assertEquals("1", prefs().getString(Prefs.PREF_VOLUME_LEVELING, null));
        prefs().edit().putString(Prefs.PREF_VOLUME_LEVELING, "9").commit();
        assertEquals(VolumeLeveling.STRONG, Prefs.getVolumeLeveling());
        prefs().edit().putString(Prefs.PREF_VOLUME_LEVELING, "garbage").commit();
        assertEquals(VolumeLeveling.OFF, Prefs.getVolumeLeveling());
    }

    @Test
    public void clearVoiceDefaultsToOffAndRoundTrips() {
        assertEquals(ClearVoiceLevel.OFF, Prefs.getClearVoiceLevel());
        Prefs.setClearVoiceLevel(ClearVoiceLevel.STRONG);
        assertEquals(ClearVoiceLevel.STRONG, Prefs.getClearVoiceLevel());
        // what the Settings ListPreference stores
        assertEquals("2", prefs().getString(Prefs.PREF_CLEAR_VOICE, null));
        prefs().edit().putString(Prefs.PREF_CLEAR_VOICE, "garbage").commit();
        assertEquals(ClearVoiceLevel.OFF, Prefs.getClearVoiceLevel());
    }

    @Test
    public void vocalEnhancementOnBecomesClearVoiceLight() {
        prefs().edit().putBoolean(Prefs.PREF_AUDIO_LOUDNESS_LEGACY, true).commit();
        Prefs.migrateLoudnessToClearVoice();
        assertEquals(ClearVoiceLevel.LIGHT, Prefs.getClearVoiceLevel());
        assertFalse(prefs().contains(Prefs.PREF_AUDIO_LOUDNESS_LEGACY));

        // idempotent: a later choice survives another run
        Prefs.setClearVoiceLevel(ClearVoiceLevel.OFF);
        Prefs.migrateLoudnessToClearVoice();
        assertEquals(ClearVoiceLevel.OFF, Prefs.getClearVoiceLevel());
    }

    @Test
    public void vocalEnhancementOffLeavesClearVoiceOff() {
        prefs().edit().putBoolean(Prefs.PREF_AUDIO_LOUDNESS_LEGACY, false).commit();
        Prefs.migrateLoudnessToClearVoice();
        assertEquals(ClearVoiceLevel.OFF, Prefs.getClearVoiceLevel());
        assertFalse(prefs().contains(Prefs.PREF_AUDIO_LOUDNESS_LEGACY));
    }

    // ------------------------------------------------------ episode search

    @Test
    public void episodeSearchOptionsDefaultToEverythingByRelevance() {
        EpisodeSearchOptions options = Prefs.getEpisodeSearchOptions();
        assertEquals(EpisodeSearchOptions.Sort.RELEVANCE, options.sort);
        assertEquals(EpisodeSearchOptions.Field.defaults(), options.fields);
        assertFalse(options.isFiltering());
        assertEquals(0, options.feedId);
    }

    @Test
    public void episodeSearchOptionsRoundTripWithoutThePodcastScope() {
        EpisodeSearchOptions chosen = EpisodeSearchOptions.defaults()
                .withSort(EpisodeSearchOptions.Sort.OLDEST)
                .withFields(EnumSet.of(EpisodeSearchOptions.Field.SHOW_NOTES))
                .withDateRange(EpisodeSearchOptions.DateRange.PAST_MONTH)
                .withDurationRange(EpisodeSearchOptions.DurationRange.OVER_60_MIN)
                .withFilter(new FeedItemFilter(new String[]{FeedItemFilter.PLAYED, FeedItemFilter.IS_FAVORITE}))
                .withFeedId(42);
        Prefs.setEpisodeSearchOptions(chosen);

        EpisodeSearchOptions stored = Prefs.getEpisodeSearchOptions();
        assertEquals(EpisodeSearchOptions.Sort.OLDEST, stored.sort);
        assertEquals(EnumSet.of(EpisodeSearchOptions.Field.SHOW_NOTES), stored.fields);
        assertEquals(EpisodeSearchOptions.DateRange.PAST_MONTH, stored.dateRange);
        assertEquals(EpisodeSearchOptions.DurationRange.OVER_60_MIN, stored.durationRange);
        assertTrue(stored.filter.showPlayed);
        assertTrue(stored.filter.showIsFavorite);
        assertEquals("the podcast scope is per search", 0, stored.feedId);
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private SharedPreferences claudeKeyPrefs() {
        return context.getSharedPreferences("anthropic", android.content.Context.MODE_PRIVATE);
    }
}
