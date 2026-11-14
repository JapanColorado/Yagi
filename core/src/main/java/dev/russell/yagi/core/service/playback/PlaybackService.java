package dev.russell.yagi.core.service.playback;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;
import androidx.preference.PreferenceManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.russell.yagi.common.util.TopSnackbarUtil;
import dev.russell.yagi.core.R;
import dev.russell.yagi.core.feed.util.AudioEffectUtils;
import dev.russell.yagi.core.feed.util.PlayableFeedPreferences;
import dev.russell.yagi.core.feed.util.PlaybackSpeedUtils;
import dev.russell.yagi.core.feed.util.VideoModeUtils;
import dev.russell.yagi.core.pref.PlaybackPreferences;
import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.pref.SleepTimerPreferences;
import dev.russell.yagi.core.receiver.MediaButtonReceiver;
import dev.russell.yagi.core.storage.DBReader;
import dev.russell.yagi.core.storage.DBWriter;
import dev.russell.yagi.core.util.NetworkUtils;
import dev.russell.yagi.core.util.playback.PlayableUtils;
import dev.russell.yagi.core.util.playback.PlaybackServiceStarter;
import dev.russell.yagi.core.widget.WidgetUpdater;
import dev.russell.yagi.event.MessageEvent;
import dev.russell.yagi.event.PlayerErrorEvent;
import dev.russell.yagi.event.adskip.AdSegmentsChangedEvent;
import dev.russell.yagi.event.adskip.AdSkipUndoEvent;
import dev.russell.yagi.event.playback.BufferUpdateEvent;
import dev.russell.yagi.event.playback.PlaybackPositionEvent;
import dev.russell.yagi.event.playback.PlaybackServiceEvent;
import dev.russell.yagi.event.playback.SleepTimerMode;
import dev.russell.yagi.event.playback.SleepTimerUpdatedEvent;
import dev.russell.yagi.event.playback.SpeedChangedEvent;
import dev.russell.yagi.event.playback.VideoModeChangedEvent;
import dev.russell.yagi.event.settings.AdSkipChangedEvent;
import dev.russell.yagi.event.settings.AudioEffectsChangedEvent;
import dev.russell.yagi.event.settings.SkipIntroEndingChangedEvent;
import dev.russell.yagi.event.settings.SpeedPresetChangedEvent;
import dev.russell.yagi.event.settings.VolumeAdaptionChangedEvent;
import dev.russell.yagi.model.feed.FeedMedia;
import dev.russell.yagi.model.feed.FeedPreferences;
import dev.russell.yagi.model.playback.MediaType;
import dev.russell.yagi.model.playback.Playable;
import dev.russell.yagi.playback.base.PlaybackServiceMediaPlayer;
import dev.russell.yagi.playback.base.PlayerStatus;
import dev.russell.yagi.ui.startintent.MainActivityStarter;
import dev.russell.yagi.ui.startintent.VideoPlayerActivityStarter;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Controls the MediaPlayer that plays a FeedMedia-file
 */
public class PlaybackService extends MediaBrowserServiceCompat {
    /**
     * Logging tag
     */
    private static final String TAG = "PlaybackService";

    public static final String EXTRA_PLAYABLE = "PlaybackService.PlayableExtra";
    public static final String EXTRA_ALLOW_STREAM_THIS_TIME = "extra.dev.russell.yagi.core.service.allowStream";
    public static final String EXTRA_ALLOW_STREAM_ALWAYS = "extra.dev.russell.yagi.core.service.allowStreamAlways";
    /** One of the user's extra notification buttons, see {@link NotificationCustomAction#getSessionAction()}. */
    static final String EXTRA_CUSTOM_ACTION = "extra.dev.russell.yagi.core.service.customAction";

    public static final String ACTION_PLAYER_STATUS_CHANGED = "action.dev.russell.yagi.core.service.playerStatusChanged";

    public static final String ACTION_PLAYER_NOTIFICATION = "action.dev.russell.yagi.core.service.playerNotification";
    public static final String EXTRA_NOTIFICATION_CODE = "extra.dev.russell.yagi.core.service.notificationCode";
    public static final String EXTRA_NOTIFICATION_TYPE = "extra.dev.russell.yagi.core.service.notificationType";

    /**
     * If the PlaybackService receives this action, it will stop playback and
     * try to shutdown.
     */
    public static final String ACTION_SHUTDOWN_PLAYBACK_SERVICE = "action.dev.russell.yagi.core.service.actionShutdownPlaybackService";

    /**
     * If the PlaybackService receives this action, it will end playback of the
     * current episode and load the next episode if there is one available.
     */
    public static final String ACTION_SKIP_CURRENT_EPISODE = "action.dev.russell.yagi.core.service.skipCurrentEpisode";

    /**
     * If the PlaybackService receives this action, it will pause playback.
     */
    public static final String ACTION_PAUSE_PLAY_CURRENT_EPISODE = "action.dev.russell.yagi.core.service.pausePlayCurrentEpisode";

    /**
     * Used in NOTIFICATION_TYPE_RELOAD.
     */
    public static final int EXTRA_CODE_AUDIO = 1;
    public static final int EXTRA_CODE_VIDEO = 2;
    public static final int EXTRA_CODE_CAST = 3;

    /**
     * Receivers of this intent should update their information about the curently playing media
     */
    public static final int NOTIFICATION_TYPE_RELOAD = 3;

    /**
     * Set a max number of episodes to load for Android Auto, otherwise there could be performance issues
     */
    public static final int MAX_ANDROID_AUTO_EPISODES_PER_FEED = 100;

    /**
     * No more episodes are going to be played.
     */
    public static final int NOTIFICATION_TYPE_PLAYBACK_END = 7;

    /**
     * Returned by getPositionSafe() or getDurationSafe() if the playbackService
     * is in an invalid state.
     */
    public static final int INVALID_TIME = -1;

    /**
     * Is true if service is running.
     */
    public static boolean isRunning = false;
    /**
     * Is true if a Cast Device is connected to the service.
     */
    private static volatile boolean isCasting = false;
    /**
     * Set while the playing episode is reloaded from its stream or its downloaded file (a video
     * switch of an audio-only download), so that the PREPARED -> PLAYING it goes through is not
     * taken for an episode start.
     */
    volatile boolean sourceSwitchPending = false;
    /** How long the video stream of an audio-only download may take to start playing. */
    private static final long VIDEO_STREAM_START_TIMEOUT_MS = 10_000;
    /** Progress past the switch position that counts as "the stream plays". */
    private static final int VIDEO_STREAM_PROGRESS_MS = 500;
    private final Handler videoStreamWatchdog = new Handler(Looper.getMainLooper());

    PlaybackServiceMediaPlayer mediaPlayer;
    PlaybackServiceTaskManager taskManager;
    PlaybackServiceStateManager stateManager;
    private Disposable positionEventTimer;
    /**
     * Chains started by this service that must not outlive it. Cleared (not disposed) in
     * {@link #onDestroy()} so the same instance can be reused if the service is recreated.
     */
    private final CompositeDisposable serviceDisposables = new CompositeDisposable();

    /** Owns the playback notification and the "streaming not allowed" notification. */
    PlaybackServiceNotificationUpdater notificationUpdater;
    /** Owns the MediaSession, its transport callback and everything published through it. */
    final PlaybackServiceMediaSession mediaSessionHolder = new PlaybackServiceMediaSession(this);
    /** Applies the per-feed skip-intro / skip-ending preferences. */
    final PlaybackServiceAutoSkipper autoSkipper = new PlaybackServiceAutoSkipper(this);
    /** Skips the stored ad segments of the episode being played. */
    final PlaybackServiceAdSkipper adSkipper = new PlaybackServiceAdSkipper(this);
    /** The user's extra notification / media session buttons. */
    final PlaybackServiceCustomActions customActions = new PlaybackServiceCustomActions(this);
    /** The "end of episode" / "end of chapter" sleep timer; the duration one is in the task manager. */
    final PlaybackServiceBoundarySleepTimer boundarySleepTimer = new PlaybackServiceBoundarySleepTimer(this);
    /** True while the sleep timer's fade has lowered the volume. */
    private boolean sleepTimerFadeActive = false;
    /** Owns the headset / bluetooth / shutdown / skip broadcast receivers. */
    private final PlaybackServiceReceivers receivers = new PlaybackServiceReceivers(this);
    /** Answers the Android Auto media browser tree. */
    private final PlaybackServiceMediaBrowser mediaBrowser = new PlaybackServiceMediaBrowser(this);
    /** Reacts to every media player status change and does the post-playback bookkeeping. */
    private final PlaybackServicePlayerCallback mediaPlayerCallback = new PlaybackServicePlayerCallback(this);

    private static volatile MediaType currentMediaType = MediaType.UNKNOWN;

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind");
        return super.onUnbind(intent);
    }

    /**
     * Returns an intent which starts an audio- or videoplayer, depending on the
     * type of media that is being played. If the playbackservice is not
     * running, the type of the last played media will be looked up.
     */
    public static Intent getPlayerActivityIntent(Context context) {
        boolean showVideoPlayer = !isCasting
                && (!isRunning || currentMediaType == MediaType.VIDEO)
                && VideoModeUtils.shouldShowVideoForCurrentMedia();

        if (showVideoPlayer) {
            return new VideoPlayerActivityStarter(context).getIntent();
        } else {
            return new MainActivityStarter(context).withOpenPlayer().getIntent();
        }
    }

    /**
     * Same as {@link #getPlayerActivityIntent(Context)}, but here the type of activity
     * depends on the FeedMedia that is provided as an argument.
     */
    public static Intent getPlayerActivityIntent(Context context, Playable media) {
        if (!isCasting && VideoModeUtils.shouldShowVideo(media)) {
            return new VideoPlayerActivityStarter(context).getIntent()
                    .putExtra(VideoPlayerActivityStarter.EXTRA_MEDIA_KEY, String.valueOf(media.getIdentifier()));
        } else {
            return new MainActivityStarter(context).withOpenPlayer().getIntent();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        isRunning = true;

        stateManager = new PlaybackServiceStateManager(this);
        notificationUpdater = new PlaybackServiceNotificationUpdater(this);

        receivers.register();
        EventBus.getDefault().register(this);
        taskManager = new PlaybackServiceTaskManager(this, taskManagerCallback);

        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(prefListener);
        recreateMediaSessionIfNeeded();
        EventBus.getDefault().post(new PlaybackServiceEvent(PlaybackServiceEvent.Action.SERVICE_STARTED));
    }

    void recreateMediaSessionIfNeeded() {
        mediaSessionHolder.recreateIfNeeded();
    }

    void recreateMediaPlayer() {
        Playable media = null;
        boolean wasPlaying = false;
        if (mediaPlayer != null) {
            media = mediaPlayer.getPlayable();
            wasPlaying = mediaPlayer.getPlayerStatus() == PlayerStatus.PLAYING;
            mediaPlayer.pause(true, false);
            mediaPlayer.shutdown();
        }
        mediaPlayer = new LocalPSMP(this, mediaPlayerCallback);
        if (media != null) {
            mediaPlayer.playMediaObject(media, VideoModeUtils.shouldStream(media), wasPlaying, true);
        }
        isCasting = mediaPlayer.isCasting();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");

        videoStreamWatchdog.removeCallbacksAndMessages(null);
        notificationUpdater.notifyStoppedIfPlaying();
        stateManager.stopForeground(!Prefs.isPersistNotify());
        isRunning = false;
        currentMediaType = MediaType.UNKNOWN;
        notificationUpdater.dispose();
        // Nothing started by this service may deliver a result after it is gone.
        serviceDisposables.clear();

        cancelPositionObserver();
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        mediaSessionHolder.release();
        receivers.unregister();
        // before the player shuts down: the "cancelled" event restores the volume through it
        boundarySleepTimer.cancel();
        if (mediaPlayer != null) {
            mediaPlayer.shutdown();
        }
        if (taskManager != null) {
            taskManager.shutdown();
        }
        EventBus.getDefault().unregister(this);
    }

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, Bundle rootHints) {
        Log.d(TAG, "OnGetRoot clientPackageName " + clientPackageName
                + "; clientUid=" + clientUid + " ; rootHints " + rootHints);
        return new BrowserRoot(
                getResources().getString(R.string.app_name), // Name visible in Android Auto
                null); // Bundle of optional extras
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        Log.d(TAG, "OnLoadChildren parentMediaId " + parentId);
        mediaBrowser.onLoadChildren(parentId, result);
    }

    /**
     * Registers a chain started by this service so that it cannot outlive it.
     */
    void addServiceDisposable(Disposable disposable) {
        serviceDisposables.add(disposable);
    }

    static void setCurrentMediaType(MediaType mediaType) {
        currentMediaType = mediaType;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        if (intent.getAction() != null && TextUtils.equals(intent.getAction(), MediaBrowserServiceCompat.SERVICE_INTERFACE)) {
            return super.onBind(intent);
        } else {
            return mBinder;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(TAG, "onStartCommand");

        try {
            stateManager.startForeground(R.id.notification_playing, notificationUpdater.build());
        } catch (Exception e) {
            // Android 12+: a stale media button / widget start without a background exemption
            // throws ForegroundServiceStartNotAllowedException; do not take the process down.
            Log.e(TAG, "startForeground failed", e);
            stopSelf();
            return Service.START_NOT_STICKY;
        }
        notificationUpdater.cancelStreamingConfirmation();

        final String customAction = intent.getStringExtra(EXTRA_CUSTOM_ACTION);
        if (customAction != null) {
            boolean handled = customActions.perform(customAction);
            if (!handled && !stateManager.hasReceivedValidStartCommand()) {
                stateManager.stopService();
            }
            return Service.START_NOT_STICKY;
        }

        final int keycode = intent.getIntExtra(MediaButtonReceiver.EXTRA_KEYCODE, -1);
        final boolean hardwareButton = intent.getBooleanExtra(MediaButtonReceiver.EXTRA_HARDWAREBUTTON, false);
        Playable playable = intent.getParcelableExtra(EXTRA_PLAYABLE);
        if (keycode == -1 && playable == null) {
            Log.e(TAG, "playbackService was started with no arguments");
            stateManager.stopService();
            return Service.START_NOT_STICKY;
        }

        if ((flags & Service.START_FLAG_REDELIVERY) != 0) {
            Log.d(TAG, "onStartCommand is a redelivered intent, calling stopForeground now.");
            stateManager.stopForeground(true);
        } else {
            if (keycode != -1) {
                boolean notificationButton;
                if (hardwareButton) {
                    notificationButton = false;
                } else {
                    notificationButton = true;
                }
                boolean handled = handleKeycode(keycode, notificationButton);
                if (!handled && !stateManager.hasReceivedValidStartCommand()) {
                    stateManager.stopService();
                    return Service.START_NOT_STICKY;
                }
            } else {
                stateManager.validStartCommandWasReceived();
                boolean allowStreamThisTime = intent.getBooleanExtra(EXTRA_ALLOW_STREAM_THIS_TIME, false);
                boolean allowStreamAlways = intent.getBooleanExtra(EXTRA_ALLOW_STREAM_ALWAYS, false);
                sendNotificationBroadcast(NOTIFICATION_TYPE_RELOAD, 0);
                if (allowStreamAlways) {
                    Prefs.setAllowMobileStreaming(true);
                }
                serviceDisposables.add(Observable.fromCallable(
                        () -> {
                            if (playable instanceof FeedMedia) {
                                return DBReader.getFeedMedia(((FeedMedia) playable).getId());
                            } else {
                                return playable;
                            }
                        })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                loadedPlayable -> startPlaying(loadedPlayable, allowStreamThisTime),
                                error -> {
                                    Log.e(TAG, "Playable was not found. Stopping service.", error);
                                    stateManager.stopService();
                                }));
                return Service.START_NOT_STICKY;
            }
        }

        return Service.START_NOT_STICKY;
    }

    /**
     * Handles media button events
     * return: keycode was handled
     */
    boolean handleKeycode(int keycode, boolean notificationButton) {
        Log.d(TAG, "handle keycode: " + keycode);
        final PlaybackServiceMediaPlayer.PSMPInfo info = mediaPlayer.getPSMPInfo();
        final PlayerStatus status = info.playerStatus;
        switch (keycode) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (status == PlayerStatus.PLAYING) {
                    mediaPlayer.pause(!Prefs.isPersistNotify(), false);
                } else if (status == PlayerStatus.PAUSED || status == PlayerStatus.PREPARED) {
                    mediaPlayer.resume();
                } else if (status == PlayerStatus.PREPARING) {
                    mediaPlayer.setStartWhenPrepared(!mediaPlayer.isStartWhenPrepared());
                } else if (status == PlayerStatus.INITIALIZED) {
                    mediaPlayer.setStartWhenPrepared(true);
                    mediaPlayer.prepare();
                } else if (mediaPlayer.getPlayable() == null) {
                    startPlayingFromPreferences();
                } else {
                    return false;
                }
                taskManager.restartSleepTimer();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (status == PlayerStatus.PAUSED || status == PlayerStatus.PREPARED) {
                    mediaPlayer.resume();
                } else if (status == PlayerStatus.INITIALIZED) {
                    mediaPlayer.setStartWhenPrepared(true);
                    mediaPlayer.prepare();
                } else if (mediaPlayer.getPlayable() == null) {
                    startPlayingFromPreferences();
                } else {
                    return false;
                }
                taskManager.restartSleepTimer();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (status == PlayerStatus.PLAYING) {
                    mediaPlayer.pause(!Prefs.isPersistNotify(), false);
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (!notificationButton) {
                    // Handle remapped button as notification button which is not remapped again.
                    return handleKeycode(Prefs.getHardwareForwardButton(), true);
                } else if (getStatus() == PlayerStatus.PLAYING || getStatus() == PlayerStatus.PAUSED) {
                    mediaPlayer.skip();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (getStatus() == PlayerStatus.PLAYING || getStatus() == PlayerStatus.PAUSED) {
                    mediaPlayer.seekDelta(Prefs.getFastForwardSecs() * 1000);
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (!notificationButton) {
                    // Handle remapped button as notification button which is not remapped again.
                    return handleKeycode(Prefs.getHardwarePreviousButton(), true);
                } else if (getStatus() == PlayerStatus.PLAYING || getStatus() == PlayerStatus.PAUSED) {
                    skipToPrevious();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (getStatus() == PlayerStatus.PLAYING || getStatus() == PlayerStatus.PAUSED) {
                    mediaPlayer.seekDelta(-Prefs.getRewindSecs() * 1000);
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (status == PlayerStatus.PLAYING) {
                    mediaPlayer.pause(true, true);
                }

                stateManager.stopForeground(true); // gets rid of persistent notification
                return true;
            default:
                Log.d(TAG, "unknown key code: " + keycode);
                if (info.playable != null && info.playerStatus == PlayerStatus.PLAYING) {   // only notify the user about an unknown key event if it is actually doing something
                    String message = String.format(getResources().getString(R.string.unknown_media_key), keycode);
                    TopSnackbarUtil.showSnack(this, message, Toast.LENGTH_SHORT);
                }
        }
        return false;
    }

    void startPlayingFromPreferences() {
        serviceDisposables.add(Observable.fromCallable(
                () -> PlayableUtils.createInstanceFromPreferences(getApplicationContext()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        playable -> startPlaying(playable, false),
                        error -> {
                            Log.e(TAG, "Playable was not loaded from preferences. Stopping service.", error);
                            stateManager.stopService();
                        }));
    }

    void startPlaying(Playable playable, boolean allowStreamThisTime) {
        boolean localFeed = URLUtil.isContentUrl(playable.getStreamUrl());
        // An audio-only download is streamed when it is to be watched, unless streaming is not
        // allowed: then it is listened to (resolveStream switches it to audio only).
        // The episode that is already playing keeps its source (playMediaObject ignores it anyway).
        Playable current = mediaPlayer.getPlayable();
        boolean alreadyPlaying = current != null && mediaPlayer.getPlayerStatus() == PlayerStatus.PLAYING
                && current.getIdentifier().equals(playable.getIdentifier());
        boolean stream = localFeed || (alreadyPlaying ? mediaPlayer.isStreaming()
                : VideoModeUtils.resolveStream(playable, NetworkUtils.isStreamingAllowed() || allowStreamThisTime));
        if (stream && !localFeed && !NetworkUtils.isStreamingAllowed() && !allowStreamThisTime) {
            notificationUpdater.displayStreamingNotAllowedNotification(
                    new PlaybackServiceStarter(this, playable)
                            .getIntent());
            PlaybackPreferences.writeNoMediaPlaying();
            stateManager.stopService();
            return;
        }

        mediaPlayer.playMediaObject(playable, stream, true, true);
        stateManager.validStartCommandWasReceived();
        stateManager.startForeground(R.id.notification_playing, notificationUpdater.build());
        recreateMediaSessionIfNeeded();
        updateNotificationAndMediaSession(playable);
        addPlayableToQueue(playable);
    }

    /**
     * Called by a mediaplayer Activity as soon as it has prepared its
     * mediaplayer.
     */
    public void setVideoSurface(SurfaceHolder sh) {
        Log.d(TAG, "setting display");
        mediaPlayer.setVideoSurface(sh);
    }

    /**
     * The video player's surface is gone (the player was left, or the screen went off). The
     * episode keeps playing as audio only; pausing is up to the listener.
     */
    public void notifyVideoSurfaceAbandoned() {
        mediaPlayer.resetVideoSurface();
    }

    /**
     * The listener switched the playing episode between video and audio only: point the
     * notification and the media session at the player that now shows it.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void videoModeChanged(VideoModeChangedEvent event) {
        Playable playable = getPlayable();
        updateNotificationAndMediaSession(playable);
        // Watching uses the default video speed, listening the audio one (a podcast's own wins).
        if (playable != null) {
            setSpeed(PlaybackSpeedUtils.getCurrentPlaybackSpeed(playable));
        }
        // An audio-only download has no picture: watching it streams the video, and going back
        // to audio only returns to the downloaded file. The position is kept either way.
        if (playable != null && VideoModeUtils.isAudioOnlyDownload(playable)) {
            boolean stream = VideoModeUtils.shouldStream(playable);
            if (stream != mediaPlayer.isStreaming()) {
                // The reload goes through PREPARED -> PLAYING again, which is not an episode start.
                sourceSwitchPending = mediaPlayer.getPlayerStatus() == PlayerStatus.PLAYING;
                mediaPlayer.switchSource(stream);
                videoStreamWatchdog.removeCallbacksAndMessages(null);
                if (stream) {
                    Object identifier = playable.getIdentifier();
                    int startPosition = playable.getPosition();
                    videoStreamWatchdog.postDelayed(() -> checkVideoStreamStarted(identifier, startPosition),
                            VIDEO_STREAM_START_TIMEOUT_MS);
                }
            }
        }
    }

    /**
     * Goes back to the downloaded audio if the video stream of an audio-only download has not
     * started playing: a 4K episode can need more bandwidth than the connection has, and then it
     * would sit silent forever. Does nothing if the listener paused or moved on meanwhile.
     */
    private void checkVideoStreamStarted(@NonNull Object identifier, int startPosition) {
        Playable playable = getPlayable();
        if (playable == null || !identifier.equals(playable.getIdentifier()) || !mediaPlayer.isStreaming()
                || mediaPlayer.getPlayerStatus() != PlayerStatus.PLAYING) {
            return;
        }
        int position = mediaPlayer.getPosition();
        if (position != Playable.INVALID_TIME && position > startPosition + VIDEO_STREAM_PROGRESS_MS) {
            return; // it plays
        }
        Log.w(TAG, "video stream did not start in time, back to the downloaded audio");
        EventBus.getDefault().post(new MessageEvent(getString(R.string.video_stream_fell_back)));
        VideoModeUtils.setShowVideo(playable, false);
    }

    /** True while playback is inside an ad segment that {@link #skipCurrentAd()} can skip. */
    public boolean isInSkippableAdSegment() {
        return adSkipper.isInSkippableSegment();
    }

    /** Skips the ad segment playback is in, with the usual Undo offer. */
    public void skipCurrentAd() {
        adSkipper.skipCurrentSegment();
    }

    private final PlaybackServiceTaskManager.PSTMCallback taskManagerCallback = new PlaybackServiceTaskManager.PSTMCallback() {
        @Override
        public void positionSaverTick() {
            saveCurrentPosition(true, null, PlaybackServiceMediaPlayer.INVALID_TIME);
        }

        @Override
        public WidgetUpdater.WidgetState requestWidgetState() {
            return new WidgetUpdater.WidgetState(getPlayable(), getStatus(),
                    getCurrentPosition(), getDuration(), getCurrentPlaybackSpeed());
        }

        @Override
        public void onChapterLoaded(Playable media) {
            sendNotificationBroadcast(NOTIFICATION_TYPE_RELOAD, 0);
            customActions.refreshIfChanged();
            if (media == getPlayable()) {
                adSkipper.onChaptersLoaded(media);
            }
        }
    };

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void playerError(PlayerErrorEvent event) {
        if (mediaPlayer.getPlayerStatus() == PlayerStatus.PLAYING) {
            mediaPlayer.pause(true, false);
        }
        stateManager.stopService();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void bufferUpdate(BufferUpdateEvent event) {
        if (event.hasEnded()) {
            Playable playable = getPlayable();
            if (getPlayable() instanceof FeedMedia
                    && playable.getDuration() <= 0 && mediaPlayer.getDuration() > 0) {
                // Playable is being streamed and does not have a duration specified in the feed
                playable.setDuration(mediaPlayer.getDuration());
                DBWriter.setFeedMedia((FeedMedia) playable);
                updateNotificationAndMediaSession(playable);
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void sleepTimerUpdate(SleepTimerUpdatedEvent event) {
        if (event.isOver()) {
            // The boundary modes stop playback themselves: at a chapter start the boundary timer
            // pauses, at the end of an episode the episode completes and the next one is not started.
            if (event.getMode() == SleepTimerMode.DURATION) {
                mediaPlayer.pause(true, true);
            }
            mediaPlayer.setVolume(1.0f, 1.0f);
            sleepTimerFadeActive = false;
        } else if (event.isCancelled()) {
            mediaPlayer.setVolume(1.0f, 1.0f);
            sleepTimerFadeActive = false;
        } else if (event.isTimeLeftKnown()
                && event.getTimeLeft() < PlaybackServiceTaskManager.SleepTimer.NOTIFICATION_THRESHOLD) {
            final float[] multiplicators = {0.1f, 0.2f, 0.3f, 0.3f, 0.3f, 0.4f, 0.4f, 0.4f, 0.6f, 0.8f};
            float multiplicator = multiplicators[Math.max(0, (int) event.getTimeLeft() / 1000)];
            Log.d(TAG, "onSleepTimerAlmostExpired: " + multiplicator);
            mediaPlayer.setVolume(multiplicator, multiplicator);
            sleepTimerFadeActive = true;
        } else if (sleepTimerFadeActive) {
            // Moved away from the end again (extended, or sought back before a boundary).
            mediaPlayer.setVolume(1.0f, 1.0f);
            sleepTimerFadeActive = false;
        }
        // Starting or ending an end-of-episode / end-of-chapter timer shows or hides "+15 min".
        customActions.refreshIfChanged();
    }

    /** The speed button shows the current speed. */
    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void speedChanged(SpeedChangedEvent event) {
        customActions.refreshIfChanged();
    }

    /** Starts a sleep timer that pauses after {@code waitingTime} ms, replacing any running one. */
    public void setSleepTimer(long waitingTime) {
        Log.d(TAG, "Setting sleep timer to " + waitingTime + " milliseconds");
        boundarySleepTimer.cancel();
        taskManager.setSleepTimer(waitingTime);
    }

    /**
     * Starts a sleep timer in {@code mode}, replacing any running one. {@link SleepTimerMode#DURATION}
     * uses the last chosen duration ({@link SleepTimerPreferences#timerMillis()}).
     */
    public void setSleepTimer(SleepTimerMode mode) {
        if (mode == SleepTimerMode.DURATION) {
            setSleepTimer(SleepTimerPreferences.timerMillis());
            return;
        }
        Log.d(TAG, "Setting sleep timer to " + mode);
        taskManager.disableSleepTimer();
        boundarySleepTimer.start(mode);
    }

    public void disableSleepTimer() {
        taskManager.disableSleepTimer();
        boundarySleepTimer.cancel();
    }

    /** The running sleep timer's mode, or null if none runs. */
    @Nullable
    public SleepTimerMode getSleepTimerMode() {
        if (taskManager.isSleepTimerActive()) {
            return SleepTimerMode.DURATION;
        }
        return boundarySleepTimer.getMode();
    }

    void sendNotificationBroadcast(int type, int code) {
        Intent intent = new Intent(ACTION_PLAYER_NOTIFICATION);
        intent.putExtra(EXTRA_NOTIFICATION_TYPE, type);
        intent.putExtra(EXTRA_NOTIFICATION_CODE, code);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    void updateNotificationAndMediaSession(final Playable p) {
        setupNotification(p);
        mediaSessionHolder.updateMetadata(p);
    }

    /**
     * Prepares notification and starts the service in the foreground.
     */
    private synchronized void setupNotification(final Playable playable) {
        notificationUpdater.setupNotification(playable);
    }

    /** Re-posts the playback notification, e.g. because its extra buttons changed. */
    void refreshNotification(final Playable playable) {
        setupNotification(playable);
    }

    /**
     * Persists the current position and last played time of the media file.
     *
     * @param fromMediaPlayer if true, the information is gathered from the current Media Player
     *                        and {@param playable} and {@param position} become irrelevant.
     * @param playable        the playable for which the current position should be saved, unless
     *                        {@param fromMediaPlayer} is true.
     * @param position        the position that should be saved, unless {@param fromMediaPlayer} is true.
     */
    synchronized void saveCurrentPosition(boolean fromMediaPlayer, Playable playable, int position) {
        int duration;
        if (fromMediaPlayer) {
            position = getCurrentPosition();
            duration = getDuration();
            playable = mediaPlayer.getPlayable();
        } else {
            duration = playable.getDuration();
        }
        if (position != INVALID_TIME && duration != INVALID_TIME && playable != null) {
            PlayableUtils.saveCurrentPosition(playable, position, System.currentTimeMillis());
        }
    }

    public boolean sleepTimerActive() {
        return taskManager.isSleepTimerActive() || boundarySleepTimer.isActive();
    }

    /**
     * Wall-clock milliseconds until the sleep timer stops playback; 0 if no timer runs, and
     * {@link SleepTimerUpdatedEvent#TIME_LEFT_UNKNOWN} for a boundary timer whose target is not
     * known yet.
     */
    public long getSleepTimerTimeLeft() {
        if (boundarySleepTimer.isActive()) {
            return boundarySleepTimer.getTimeLeft();
        }
        return taskManager.getSleepTimerTimeLeft();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void volumeAdaptionChanged(VolumeAdaptionChangedEvent event) {
        PlaybackVolumeUpdater playbackVolumeUpdater = new PlaybackVolumeUpdater();
        playbackVolumeUpdater.updateVolumeIfNecessary(mediaPlayer, event.getFeedId(), event.getVolumeAdaptionSetting());
    }

    /**
     * A podcast's own speed changed outside the player (Feed Settings, bulk edit), or the global
     * default did (feed id 0). A podcast change is copied into the in-memory preferences of the
     * playing episode's feed so a later re-prepare uses it; either way the effective speed is
     * re-applied if it concerns what is playing.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void speedPresetChanged(SpeedPresetChangedEvent event) {
        Playable playable = getPlayable();
        if (playable == null) {
            return;
        }
        FeedPreferences playingPreferences = PlayableFeedPreferences.of(playable);
        if (event.isGlobal()) {
            if (!PlaybackSpeedUtils.hasOwnSpeed(playingPreferences)) {
                setSpeed(PlaybackSpeedUtils.getCurrentPlaybackSpeed(playable));
            }
        } else if (playingPreferences != null
                && PlayableFeedPreferences.feedIdOf(playable) == event.getFeedId()) {
            playingPreferences.setFeedPlaybackSpeed(event.getSpeed());
            setSpeed(PlaybackSpeedUtils.getCurrentPlaybackSpeed(playable));
        }
    }

    /**
     * The audio effects of a podcast, or the global defaults (feed id 0), changed. Like
     * {@link #speedPresetChanged}, a podcast change is copied into the in-memory preferences of
     * the playing episode's feed, then the effective effects are re-applied.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void audioEffectsChanged(AudioEffectsChangedEvent event) {
        Playable playable = getPlayable();
        if (playable == null) {
            return;
        }
        FeedPreferences playingPreferences = PlayableFeedPreferences.of(playable);
        if (event.isGlobal()) {
            if (!AudioEffectUtils.hasOwnEffects(playingPreferences)) {
                applyAudioEffects();
            }
        } else if (playingPreferences != null
                && PlayableFeedPreferences.feedIdOf(playable) == event.getFeedId()) {
            playingPreferences.setUseFeedEffect(event.isUseFeedEffect());
            playingPreferences.setSkipSilence(event.isSkipSilence());
            playingPreferences.setMono(event.isMono());
            playingPreferences.setClearVoiceLevel(event.getClearVoiceLevel());
            playingPreferences.setVolumeLeveling(event.getVolumeLeveling());
            applyAudioEffects();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void skipIntroEndingPresetChanged(SkipIntroEndingChangedEvent event) {
        if (getPlayable() instanceof FeedMedia) {
            if (((FeedMedia) getPlayable()).getItem().getFeed().getId() == event.getFeedId()) {
                if (event.getSkipEnding() != 0) {
                   FeedPreferences feedPreferences
                           = ((FeedMedia) getPlayable()).getItem().getFeed().getPreferences();
                   feedPreferences.setFeedSkipIntro(event.getSkipIntro());
                   feedPreferences.setFeedSkipEnding(event.getSkipEnding());

                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void adSegmentsChanged(AdSegmentsChangedEvent event) {
        adSkipper.onSegmentsChanged(event.getFeedItemId());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void adSkipUndoRequested(AdSkipUndoEvent event) {
        adSkipper.onUndo(event);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void adSkipSettingChanged(AdSkipChangedEvent event) {
        adSkipper.onAdSkipSettingChanged(event.getFeedId());
    }

    public static MediaType getCurrentMediaType() {
        return currentMediaType;
    }

    public static boolean isCasting() {
        return isCasting;
    }

    public void resume() {
        mediaPlayer.resume();
        taskManager.restartSleepTimer();
    }

    public void prepare() {
        mediaPlayer.prepare();
        taskManager.restartSleepTimer();
    }

    public void pause(boolean abandonAudioFocus, boolean reinit) {
        mediaPlayer.pause(abandonAudioFocus, reinit);
    }

    public void reinit() {
        mediaPlayer.reinit();
    }

    public PlaybackServiceMediaPlayer.PSMPInfo getPSMPInfo() {
        return mediaPlayer.getPSMPInfo();
    }

    public PlayerStatus getStatus() {
        return mediaPlayer.getPlayerStatus();
    }

    public Playable getPlayable() {
        return mediaPlayer.getPlayable();
    }

    /** Applies {@code speed} to the player without storing it anywhere. */
    public void setSpeed(float speed) {
        mediaPlayer.setPlaybackParams(speed, AudioEffectUtils.isSkipEnable(getPlayable()));
    }

    /**
     * A speed the user chose in the player for what is playing: it becomes the podcast's own
     * speed (or the global default for media without a subscribed podcast) and is applied.
     * {@link FeedPreferences#SPEED_USE_GLOBAL} makes the podcast follow the default again.
     */
    public void setSpeedForCurrentMedia(float speed) {
        Playable playable = getPlayable();
        PlaybackSpeedUtils.rememberSpeed(playable, speed);
        setSpeed(PlaybackSpeedUtils.getCurrentPlaybackSpeed(playable));
    }

    /**
     * Re-applies skip silence, stereo to mono, Clear voice and volume leveling as configured for
     * what is playing (its podcast's own effects or the global defaults).
     */
    private void applyAudioEffects() {
        Playable playable = getPlayable();
        mediaPlayer.setPlaybackParams(PlaybackSpeedUtils.getCurrentPlaybackSpeed(playable),
                AudioEffectUtils.isSkipEnable(playable));
        mediaPlayer.setDownmix(AudioEffectUtils.isMonoEnable(playable));
        mediaPlayer.setClearVoiceLevel(AudioEffectUtils.getClearVoiceLevel(playable));
        mediaPlayer.setVolumeLeveling(AudioEffectUtils.getVolumeLeveling(playable));
    }

    public float getCurrentPlaybackSpeed() {
        if (mediaPlayer == null) {
            return 1.0f;
        }
        return mediaPlayer.getPlaybackSpeed();
    }


    public boolean isStartWhenPrepared() {
        return mediaPlayer.isStartWhenPrepared();
    }

    public void setStartWhenPrepared(boolean s) {
        mediaPlayer.setStartWhenPrepared(s);
    }

    public void seekTo(final int t) {
        mediaPlayer.seekTo(t);
        EventBus.getDefault().post(new PlaybackPositionEvent(t, getDuration()));
    }

    /**
     * Music-player style "previous" (see {@link PreviousEpisodePolicy}): restarts the episode, or
     * within its first seconds starts the most recently played other episode from its saved
     * position. That episode goes through {@link PlaybackServiceStarter} like a play tap anywhere
     * else, so history, sync and the queue behave as usual.
     */
    public void skipToPrevious() {
        int position = getCurrentPosition();
        Playable current = getPlayable();
        long currentMediaId = current instanceof FeedMedia ? ((FeedMedia) current).getId() : 0;
        serviceDisposables.add(Maybe.fromCallable(() -> PreviousEpisodePolicy.pickPrevious(
                        DBReader.getPlaybackHistory(0, PreviousEpisodePolicy.HISTORY_LOOKUP_LIMIT),
                        currentMediaId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(previous -> skipToPrevious(position, previous),
                        error -> {
                            Log.e(TAG, "could not read the playback history", error);
                            skipToPrevious(position, null);
                        },
                        () -> skipToPrevious(position, null)));
    }

    private void skipToPrevious(int position, @Nullable FeedMedia previous) {
        PreviousEpisodePolicy.Action action = PreviousEpisodePolicy.decide(position, previous != null);
        if (action == PreviousEpisodePolicy.Action.PLAY_PREVIOUS && previous != null) {
            // The service cannot ask whether to stream over mobile data. If the episode being left
            // was streaming, the user has already agreed to stream, so the previous one may too;
            // otherwise startPlaying() posts the usual "streaming not allowed" notification.
            new PlaybackServiceStarter(this, previous)
                    .callEvenIfRunning(true)
                    .shouldStreamThisTime(isStreaming())
                    .start();
        } else {
            seekTo(0);
        }
    }

    void seekDelta(final int d) {
        mediaPlayer.seekDelta(d);
    }

    /**
     * call getDuration() on mediaplayer or return INVALID_TIME if player is in
     * an invalid state.
     */
    public int getDuration() {
        if (mediaPlayer == null) {
            return INVALID_TIME;
        }
        return mediaPlayer.getDuration();
    }

    /**
     * call getCurrentPosition() on mediaplayer or return INVALID_TIME if player
     * is in an invalid state.
     */
    public int getCurrentPosition() {
        if (mediaPlayer == null) {
            return INVALID_TIME;
        }
        return mediaPlayer.getPosition();
    }

    public List<String> getAudioTracks() {
        if (mediaPlayer == null) {
            return Collections.emptyList();
        }
        return mediaPlayer.getAudioTracks();
    }

    public int getSelectedAudioTrack() {
        if (mediaPlayer == null) {
            return -1;
        }
        return mediaPlayer.getSelectedAudioTrack();
    }

    public void setAudioTrack(int track) {
        if (mediaPlayer != null) {
            mediaPlayer.setAudioTrack(track);
        }
    }

    public boolean isStreaming() {
        return mediaPlayer.isStreaming();
    }

    public Pair<Integer, Integer> getVideoSize() {
        return mediaPlayer.getVideoSize();
    }

    void setupPositionObserver() {
        if (positionEventTimer != null) {
            positionEventTimer.dispose();
        }

        Log.d(TAG, "setting up position observer");
        positionEventTimer = Observable.interval(1, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(number -> {
                    EventBus.getDefault().post(new PlaybackPositionEvent(getCurrentPosition(), getDuration()));
                    if (Build.VERSION.SDK_INT < 29) {
                        notificationUpdater.updatePositionAndNotify(getCurrentPosition(), getCurrentPlaybackSpeed());
                    }
                    autoSkipper.skipEndingIfNecessary();
                    adSkipper.skipIfNecessary();
                    boundarySleepTimer.tick();
                    customActions.refreshIfChanged();
                }, error -> Log.e(TAG, "Position observer failed", error));
    }

    void cancelPositionObserver() {
        if (positionEventTimer != null) {
            positionEventTimer.dispose();
        }
    }

    private void addPlayableToQueue(Playable playable) {
        if (playable instanceof FeedMedia) {
            long itemId = ((FeedMedia) playable).getItem().getId();
            DBWriter.addQueueItem(this, false, true, itemId);
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (Prefs.PREF_LOCKSCREEN_BACKGROUND.equals(key)) {
                    updateNotificationAndMediaSession(getPlayable());
                } else if (Prefs.PREF_NOTIFICATION_CUSTOM_BUTTONS.equals(key)) {
                    customActions.republish();
                }
            };
}
