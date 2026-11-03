package dev.russell.yagi.fragment;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.snackbar.Snackbar;
import com.jetradarmobile.snowfall.SnowfallView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import dev.russell.yagi.common.extensions.ColorExtensionsUtils;
import dev.russell.yagi.common.util.MenuIconUtil;
import dev.russell.yagi.common.util.TopSnackbarUtil;
import dev.russell.yagi.common.util.Timber;
import dev.russell.yagi.common.views.AccentMaterialDialog;
import dev.russell.yagi.R;
import dev.russell.yagi.activity.DriveModeActivity;
import dev.russell.yagi.activity.LockScreenActivity;
import dev.russell.yagi.activity.MainActivity;
import dev.russell.yagi.core.adskip.AdAnalysisWorker;
import dev.russell.yagi.core.adskip.TranscriptAdAnalysis;
import dev.russell.yagi.core.feed.util.AdSkipUtils;
import dev.russell.yagi.core.feed.util.PlaybackSpeedUtils;
import dev.russell.yagi.core.playback.NowPlayingScreen;
import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.service.playback.PlaybackService;
import dev.russell.yagi.core.storage.DBReader;
import dev.russell.yagi.core.storage.DBWriter;
import dev.russell.yagi.core.util.ChapterUtils;
import dev.russell.yagi.core.util.Converter;
import dev.russell.yagi.core.util.IntentUtils;
import dev.russell.yagi.core.util.TimeSpeedConverter;
import dev.russell.yagi.core.util.playback.PlaybackController;
import dev.russell.yagi.core.feed.util.VideoModeUtils;
import dev.russell.yagi.core.util.NetworkUtils;
import dev.russell.yagi.core.util.VideoStreamCheck;
import dev.russell.yagi.dialog.AdSegmentsDialog;
import dev.russell.yagi.dialog.BookmarkNoteDialog;
import dev.russell.yagi.dialog.PlaySpeedDialog;
import dev.russell.yagi.dialog.PlaybackControlsDialog;
import dev.russell.yagi.dialog.SkipPrefDialog;
import dev.russell.yagi.dialog.SleepTimerDialog;
import dev.russell.yagi.dialog.UseStreamConfirmDialog;
import dev.russell.yagi.event.BookmarksChangedEvent;
import dev.russell.yagi.event.CoverColorChangeEvent;
import dev.russell.yagi.event.adskip.AdSegmentsChangedEvent;
import dev.russell.yagi.event.adskip.AdSkipUndoEvent;
import dev.russell.yagi.event.adskip.AdSkippedEvent;
import dev.russell.yagi.event.settings.AdSkipChangedEvent;
import dev.russell.yagi.event.FavoritesEvent;
import dev.russell.yagi.event.FeedItemEvent;
import dev.russell.yagi.event.PlayerErrorEvent;
import dev.russell.yagi.event.UnreadItemsUpdateEvent;
import dev.russell.yagi.event.playback.BufferUpdateEvent;
import dev.russell.yagi.event.playback.PlaybackPositionEvent;
import dev.russell.yagi.event.playback.PlaybackServiceEvent;
import dev.russell.yagi.event.playback.SleepTimerMode;
import dev.russell.yagi.event.playback.SleepTimerUpdatedEvent;
import dev.russell.yagi.event.playback.SpeedChangedEvent;
import dev.russell.yagi.menuprocess.FeedItemMenuProcess;
import dev.russell.yagi.model.feed.AdSegment;
import dev.russell.yagi.model.feed.Bookmark;
import dev.russell.yagi.model.feed.Chapter;
import dev.russell.yagi.model.feed.FeedItem;
import dev.russell.yagi.model.feed.FeedMedia;
import dev.russell.yagi.model.feed.FeedPreferences;
import dev.russell.yagi.model.playback.MediaType;
import dev.russell.yagi.model.playback.Playable;
import dev.russell.yagi.playback.LibraryViewModel;
import dev.russell.yagi.view.AdMarkerSeekBar;
import dev.russell.yagi.view.DrawableGradient;
import dev.russell.yagi.view.PlayButton;
import dev.russell.yagi.view.TextSizedIconSpan;
import dev.russell.yagi.theme.ThemeStore;
import dev.russell.yagi.theme.util.ATHUtil;
import dev.russell.yagi.theme.util.ColorUtil;
import dev.russell.yagi.theme.util.MaterialValueHelper;
import dev.russell.yagi.theme.util.TintHelper;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import kotlin.Unit;

/**
 * Shows the audio player.
 */
public class AudioPlayerFragment extends Fragment implements
        SeekBar.OnSeekBarChangeListener, Toolbar.OnMenuItemClickListener, SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String TAG = "AudioPlayerFragment";
    public static final int POS_COVER = 0;
    public static final int POS_DESCRIPTION = 1;
    protected static final int NUM_CONTENT_FRAGMENTS = 1;

    TextView txtvPlaybackSpeed;
    protected ViewPager2 pager;
    protected TextView txtvPosition;
    protected TextView txtvLength;
    protected AdMarkerSeekBar sbPosition;
    protected ImageButton butRev;
    protected TextView txtvRev;
    protected PlayButton butPlay;
    protected ImageButton butFF;
    protected TextView txtvFF;
    protected ImageButton butSkip;
    /** Restarts the episode or goes back to the previous one; null in a layout without it. */
    @Nullable
    protected ImageButton butPrev;
    /** Opens the sleep timer dialog and shows the time left; null where a layout has no room for it. */
    @Nullable
    protected TextView butSleepTimer;
    /** Bookmarks the current position (long-press: with a note); null in a layout without it. */
    @Nullable
    protected ImageButton butBookmark;
    protected Toolbar toolbar;
    protected ProgressBar progressIndicator;
    protected CardView cardViewSeek;
    protected TextView txtvSeek;

    /** No ad start is pending; the "mark ad start" menu item is the one on offer. */
    private static final long NO_PENDING_AD_START = -1;
    /** Anything shorter is a mis-tap rather than an ad, and is refused with a message. */
    private static final long MIN_MANUAL_AD_LENGTH_MS = 2000;
    private static final String KEY_PENDING_AD_START = "dev.russell.yagi.pendingAdStartMs";
    /** {@link #sleepTimerShownMinutes} while the sleep timer button shows its idle icon. */
    private static final long SLEEP_TIMER_IDLE = -1;
    /** {@link #sleepTimerShownMinutes} while a boundary timer runs whose time left is unknown. */
    private static final long SLEEP_TIMER_UNKNOWN = -2;
    private static final long MINUTE_MS = 60_000;
    /** Moon icon edge as a multiple of the text size while it stands alone. */
    private static final float SLEEP_ICON_SCALE_IDLE = 1.6f;
    /** Moon icon edge as a multiple of the text size next to the time left. */
    private static final float SLEEP_ICON_SCALE_RUNNING = 1.15f;

    protected PlaybackController controller;
    protected Disposable disposable;
    /** Loads the ad segments of the episode currently shown; replaced on every media change. */
    private Disposable adSegmentsDisposable;
    /** Loads the bookmarks of the episode currently shown, for the seek bar ticks. */
    private Disposable bookmarksDisposable;
    /** The episode the seek bar is currently showing bookmark ticks for, or 0 for none. */
    private long bookmarksItemId;
    /** Runs the chapter-only analysis a streamed episode can get. */
    private Disposable adAnalysisDisposable;
    /** Reads the video's size before "Watch video" on an audio-only download. */
    private Disposable videoStreamCheckDisposable;
    /** The episode the seek bar is currently showing segments for, or 0 for none. */
    private long adSegmentsItemId;
    /** Where the user tapped "mark ad start", or {@link #NO_PENDING_AD_START}. */
    private long pendingAdStartMs = NO_PENDING_AD_START;
    /**
     * Whole minutes left that the sleep timer button's content description announces, or
     * {@link #SLEEP_TIMER_IDLE}. The description only changes when this does, so TalkBack does
     * not re-read a new value every second.
     */
    private long sleepTimerShownMinutes = SLEEP_TIMER_IDLE;
    /** Time left that the sleep timer button shows while the timer runs. */
    private long sleepTimerLeftMs;
    /** The running timer's mode the content description was built for. */
    @Nullable
    private SleepTimerMode sleepTimerShownMode;
    protected boolean showTimeLeft;
    protected boolean seekedToChapterStart = false;
    protected int currentChapterIndex = -1;
    protected int duration;
    protected LibraryViewModel libraryViewModel;
    protected View slideView;
    protected boolean isFromScreenActivity = false;
    protected boolean isDriveMode = false;
    protected ImageView driveCloseIv;
    protected NowPlayingScreen nowPlayingScreen;
    protected View colorGradientBackground;
    /** Player styles that draw their own background set this to false and hide the gradient for good. */
    protected boolean gradientBackgroundEnabled = true;

    /**
     * The palette gradient spans the whole sheet, so while the sheet is collapsed its top edge
     * would show below the mini player, in the navigation-bar inset. Only show it while the
     * sheet is (partly) expanded.
     */
    public void setGradientBackgroundVisible(boolean visible) {
        if (colorGradientBackground == null || !gradientBackgroundEnabled) {
            return;
        }
        colorGradientBackground.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }
    protected ImageView playPauseButton;
    protected ImageView swipeUpIv;
    protected TextView swipeUpTipTv;
    protected SnowfallView snowfallView;
    protected ImageView colorBackground;
    protected View mask;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        int layoutResId;
        nowPlayingScreen = Prefs.getNowPlayingScreen();
        if (isDriveMode) {
            layoutResId = R.layout.drive_audioplayer_fragment;
        } else if (nowPlayingScreen == NowPlayingScreen.Adaptive) {
            layoutResId = R.layout.adapter_audioplayer_fragment;
        } else if (nowPlayingScreen == NowPlayingScreen.BlurCard) {
            layoutResId = R.layout.blur_card_audioplayer_fragment;
        } else if (nowPlayingScreen == NowPlayingScreen.Full) {
            layoutResId = R.layout.full_audioplayer_fragment;
        } else if (nowPlayingScreen == NowPlayingScreen.Circle) {
            layoutResId = R.layout.circle_audioplayer_fragment;
        }  else {
            layoutResId = R.layout.audioplayer_fragment;
        }

        View root = inflater.inflate(layoutResId, container, false);
        root.setOnTouchListener((v, event) -> true); // Avoid clicks going through player to fragments below
        toolbar = root.findViewById(R.id.toolbar);
        toolbar.setTitle("");
        toolbar.setNavigationOnClickListener(v ->
                ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED));
        toolbar.setOnMenuItemClickListener(this);

        MiniPlayerFragment miniPlayerFragment = new MiniPlayerFragment();
        getChildFragmentManager().beginTransaction()
                .replace(R.id.playerFragment, miniPlayerFragment, MiniPlayerFragment.TAG)
                .commit();

        txtvPlaybackSpeed = root.findViewById(R.id.txtvPlaybackSpeed);
        sbPosition = root.findViewById(R.id.sbPosition);
        txtvPosition = root.findViewById(R.id.txtvPosition);
        txtvLength = root.findViewById(R.id.txtvLength);
        butRev = root.findViewById(R.id.butRev);
        txtvRev = root.findViewById(R.id.txtvRev);
        butPlay = root.findViewById(R.id.butPlay);
        butFF = root.findViewById(R.id.butFF);
        txtvFF = root.findViewById(R.id.txtvFF);
        butSkip = root.findViewById(R.id.butSkip);
        butPrev = root.findViewById(R.id.butPrev);
        butSleepTimer = root.findViewById(R.id.butSleepTimer);
        butBookmark = root.findViewById(R.id.butBookmark);
        progressIndicator = root.findViewById(R.id.progLoading);
        cardViewSeek = root.findViewById(R.id.cardViewSeek);
        txtvSeek = root.findViewById(R.id.txtvSeek);
        slideView = root.findViewById(R.id.slide);
        driveCloseIv = root.findViewById(R.id.close);
        colorGradientBackground = root.findViewById(R.id.colorGradientBackground);
        playPauseButton = root.findViewById(R.id.playPauseButton);
        swipeUpIv = root.findViewById(R.id.swipe_up_iv);
        swipeUpTipTv = root.findViewById(R.id.swipe_up_tip_tv);
        snowfallView = root.findViewById(R.id.snowfall_view);
        colorBackground = root.findViewById(R.id.colorBackground);
        mask = root.findViewById(R.id.mask);

        setupDriveMode();
        setupLengthTextView();
        setupControlButtons();
        txtvPlaybackSpeed.setOnClickListener(v -> new PlaySpeedDialog().show(getChildFragmentManager(), null));
        if (butSleepTimer != null) {
            butSleepTimer.setOnClickListener(v -> showSleepTimerDialog());
        }
        if (butBookmark != null) {
            butBookmark.setOnClickListener(v -> addBookmark(false));
            butBookmark.setOnLongClickListener(v -> {
                addBookmark(true);
                return true;
            });
            ViewCompat.replaceAccessibilityAction(butBookmark,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    getString(R.string.bookmark_add_note), null);
        }
        sleepTimerShownMinutes = SLEEP_TIMER_IDLE;
        renderSleepTimerButton();
        sbPosition.setOnSeekBarChangeListener(this);

        pager = root.findViewById(R.id.pager);
        pager.setAdapter(new AudioPlayerPagerAdapter(this,isDriveMode));
        // Required for getChildAt(int) in ViewPagerBottomSheetBehavior to return the correct page
        pager.setOffscreenPageLimit((int) NUM_CONTENT_FRAGMENTS);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                pager.post(() -> {
                    if (getActivity() != null) {
                        // By the time this is posted, the activity might be closed again.
//                        ((MainActivity) getActivity()).getBottomSheet().updateScrollingChild();
                    }
                });
            }
        });
        libraryViewModel = new ViewModelProvider(getActivity()).get(LibraryViewModel.class);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupScreenLayout();
        startOrStopSnow(Prefs.showSnowFall());
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    protected void setChapterDividers(Playable media) {

        if (media == null) {
            return;
        }

        float[] dividerPos = null;

        if (media.getChapters() != null && !media.getChapters().isEmpty()) {
            List<Chapter> chapters = media.getChapters();
            dividerPos = new float[chapters.size()];

            for (int i = 0; i < chapters.size(); i++) {
                dividerPos[i] = chapters.get(i).getStart() / (float) duration;
            }
        }

    }

    public View getExternalPlayerHolder() {
        return getView().findViewById(R.id.playerFragment);
    }

    protected void setupControlButtons() {
        butRev.setOnClickListener(v -> {
            if (controller != null) {
                int curr = controller.getPosition();
                controller.seekTo(curr - Prefs.getRewindSecs() * 1000);
            }
        });
        butRev.setOnLongClickListener(v -> {
            SkipPrefDialog.showSkipPreference(getContext(),
                    SkipPrefDialog.SkipDirection.SKIP_REWIND, txtvRev);
            return true;
        });
        butPlay.setOnClickListener(v -> {
            if (controller != null) {
                controller.init();
                controller.playPause();
            }
        });
        butFF.setOnClickListener(v -> {
            if (controller != null) {
                int curr = controller.getPosition();
                controller.seekTo(curr + Prefs.getFastForwardSecs() * 1000);
            }
        });
        butFF.setOnLongClickListener(v -> {
            SkipPrefDialog.showSkipPreference(getContext(),
                    SkipPrefDialog.SkipDirection.SKIP_FORWARD, txtvFF);
            return false;
        });
        if (butPrev != null) {
            butPrev.setOnClickListener(v -> {
                if (controller != null) {
                    controller.skipToPrevious();
                }
            });
        }
        butSkip.setOnClickListener(v ->
                IntentUtils.sendLocalBroadcast(getActivity(), PlaybackService.ACTION_SKIP_CURRENT_EPISODE));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onUnreadItemsUpdate(UnreadItemsUpdateEvent event) {
        if (controller == null) {
            return;
        }
        updatePosition(new PlaybackPositionEvent(controller.getPosition(),
                controller.getDuration()));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaybackServiceChanged(PlaybackServiceEvent event) {
        if (event.action == PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN) {
            if(isFromScreenActivity){
                return;
            }
            ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    }

    protected void setupLengthTextView() {
        showTimeLeft = Prefs.shouldShowRemainingTime();
        txtvLength.setOnClickListener(v -> {
            if (controller == null) {
                return;
            }
            showTimeLeft = !showTimeLeft;
            Prefs.setShowRemainTimeSetting(showTimeLeft);
            updatePosition(new PlaybackPositionEvent(controller.getPosition(),
                    controller.getDuration()));
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updatePlaybackSpeedButton(SpeedChangedEvent event) {
        String speedStr = new DecimalFormat("0.0").format(event.getNewSpeed());
        txtvPlaybackSpeed.setText(speedStr + "x");
    }

    protected void loadMediaInfo(boolean includingChapters, boolean forceReloadMedia) {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Maybe.<Playable>create(emitter -> {
            Playable media = controller.getMedia(forceReloadMedia);
            if (media != null) {
                if (includingChapters) {
                    ChapterUtils.loadChapters(media, getContext());
                }
                emitter.onSuccess(media);
            } else {
                emitter.onComplete();
            }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(media -> {
            updateUi(media);
            if (media.getChapters() == null && !includingChapters) {
                loadMediaInfo(true,forceReloadMedia);
            }
        }, error -> Log.e(TAG, Log.getStackTraceString(error)),
            () -> updateUi(null));
    }

    protected PlaybackController newPlaybackController() {
        return new PlaybackController(getActivity()) {
            @Override
            protected void updatePlayButtonShowsPlay(boolean showPlay) {
                butPlay.setIsShowPlay(showPlay);
            }

            @Override
            public void loadMediaInfo() {
                AudioPlayerFragment.this.loadMediaInfo(false,false);
            }

            @Override
            public void onPlaybackEnd() {
                if(isFromScreenActivity){
                    return;
                }
                ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        };
    }

    protected void updateUi(Playable media) {
        if (controller == null || media == null) {
            return;
        }
        duration = controller.getDuration();
        updatePosition(new PlaybackPositionEvent(media.getPosition(), media.getDuration()));
        updatePlaybackSpeedButton(new SpeedChangedEvent(PlaybackSpeedUtils.getCurrentPlaybackSpeed(media)));
        setChapterDividers(media);
        setupOptionsMenu(media);
        loadAdSegments(media);
        loadBookmarkTicks(media);
        // The timer events are not sticky, so the button learns the current state here.
        updateSleepTimerButton(controller.sleepTimerActive(), controller.getSleepTimerTimeLeft(),
                controller.getSleepTimerMode());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void sleepTimerUpdate(SleepTimerUpdatedEvent event) {
        if (event.isCancelled() || event.wasJustEnabled()) {
            AudioPlayerFragment.this.loadMediaInfo(false,false);
        }
        updateSleepTimerButton(!event.isCancelled() && !event.isOver(), event.getTimeLeft(),
                event.getMode());
    }

    private void showSleepTimerDialog() {
        new SleepTimerDialog().show(getChildFragmentManager(), "SleepTimerDialog");
    }

    /**
     * Shows the time left on the sleep timer button while the timer runs, and the moon icon
     * otherwise. For "end of episode" / "end of chapter" the time left is the wall-clock time to
     * that point (already divided by the speed); "--:--" while it is unknown.
     */
    private void updateSleepTimerButton(boolean active, long timeLeftMs, @Nullable SleepTimerMode mode) {
        if (butSleepTimer == null) {
            return;
        }
        boolean unknown = timeLeftMs == SleepTimerUpdatedEvent.TIME_LEFT_UNKNOWN
                && mode != null && mode != SleepTimerMode.DURATION;
        if (!active || (timeLeftMs <= 0 && !unknown)) {
            if (sleepTimerShownMinutes != SLEEP_TIMER_IDLE) {
                sleepTimerShownMinutes = SLEEP_TIMER_IDLE;
                sleepTimerShownMode = null;
                butSleepTimer.setContentDescription(getString(R.string.sleep_timer_label));
                renderSleepTimerButton();
            }
            return;
        }
        sleepTimerLeftMs = timeLeftMs;
        long minutes = unknown ? SLEEP_TIMER_UNKNOWN : (timeLeftMs + MINUTE_MS - 1) / MINUTE_MS;
        if (minutes != sleepTimerShownMinutes || mode != sleepTimerShownMode) {
            sleepTimerShownMinutes = minutes;
            sleepTimerShownMode = mode;
            StringBuilder description = new StringBuilder(getString(R.string.sleep_timer_label));
            if (mode == SleepTimerMode.END_OF_EPISODE) {
                description.append(", ").append(getString(R.string.sleep_timer_stops_end_of_episode));
            } else if (mode == SleepTimerMode.END_OF_CHAPTER) {
                description.append(", ").append(getString(R.string.sleep_timer_stops_end_of_chapter));
            }
            if (!unknown) {
                description.append(", ").append(getResources().getQuantityString(
                        R.plurals.time_minutes_quantified, (int) minutes, (int) minutes));
            }
            butSleepTimer.setContentDescription(description);
        }
        renderSleepTimerButton();
    }

    /** Remaining time as mm:ss, or h:mm:ss from an hour up, rounded up to the next second. */
    private static String formatSleepTimeLeft(long timeLeftMs) {
        long seconds = (timeLeftMs + 999) / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, rest);
        }
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, rest);
    }

    /**
     * Idle: the moon icon alone. Running: the moon followed by the time left; drive mode's round
     * button is too small for both, so it shows only the time there. The icon is drawn as part
     * of the text, so it follows the text colour and shrinks with auto-sized text.
     */
    private void renderSleepTimerButton() {
        TextView button = butSleepTimer;
        if (button == null) {
            return;
        }
        boolean idle = sleepTimerShownMinutes == SLEEP_TIMER_IDLE;
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (idle || !isDriveMode) {
            Drawable icon = AppCompatResources.getDrawable(button.getContext(), R.drawable.ic_sleep);
            if (icon != null) {
                icon = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(icon, button.getCurrentTextColor());
                text.append(" ", new TextSizedIconSpan(icon,
                        idle ? SLEEP_ICON_SCALE_IDLE : SLEEP_ICON_SCALE_RUNNING),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (!idle) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(sleepTimerShownMinutes == SLEEP_TIMER_UNKNOWN
                    ? "--:--" : formatSleepTimeLeft(sleepTimerLeftMs));
        }
        button.setText(text);
    }

    /**
     * Tints the secondary controls around the play button: previous, rewind, fast forward and
     * next with their labels, the speed label and the sleep timer button.
     */
    protected void applyControlsColor(int color) {
        tintSkipControls(color);
        txtvPlaybackSpeed.setTextColor(color);
        if (butSleepTimer != null) {
            butSleepTimer.setTextColor(color);
            renderSleepTimerButton();
        }
        if (butBookmark != null) {
            butBookmark.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    private void tintSkipControls(int color) {
        if (butPrev != null) {
            butPrev.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        butFF.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        butRev.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        butSkip.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        txtvRev.setTextColor(color);
        txtvFF.setTextColor(color);
    }

    protected ValueAnimator valueAnimator;
    protected void colorize(int i) {
        if (valueAnimator != null) {
            valueAnimator.cancel();
        }

        valueAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                ColorExtensionsUtils.surfaceColor(this),
                i
        );
        valueAnimator.addUpdateListener(animation -> {
            if (isAdded()) {
                DrawableGradient drawable = new DrawableGradient(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{
                                (int) animation.getAnimatedValue(),
                                ColorExtensionsUtils.surfaceColor(AudioPlayerFragment.this)
                        }, 0
                );
                colorGradientBackground.setBackground(drawable);
            }
        });
        valueAnimator.setDuration(1000).start();
    }

    protected int lastPlaybackControlsColor = 0;

    protected int lastDisabledPlaybackControlsColor = 0;

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void coverColorUpdate(CoverColorChangeEvent event) {
        libraryViewModel.updateColor(event.getColor().getBackgroundColor());

        if(isDriveMode){
            return;
        }

        if (Prefs.isAdapterColor() && (Prefs.getNowPlayingScreen() == NowPlayingScreen.Normal
                || Prefs.getNowPlayingScreen() == NowPlayingScreen.Adaptive
                || Prefs.getNowPlayingScreen() == NowPlayingScreen.Circle)) {
            colorize(event.getColor().getBackgroundColor());
        }

        int colorBg = ATHUtil.resolveColor(requireContext(), android.R.attr.colorBackground);
        if (ColorUtil.isColorLight(colorBg)) {
            lastPlaybackControlsColor =
                    MaterialValueHelper.getSecondaryTextColor(requireContext(), true);
            lastDisabledPlaybackControlsColor =
                    MaterialValueHelper.getSecondaryDisabledTextColor(requireContext(), true);
        } else {
            lastPlaybackControlsColor =
                    MaterialValueHelper.getPrimaryTextColor(requireContext(), false);
            lastDisabledPlaybackControlsColor =
                    MaterialValueHelper.getPrimaryDisabledTextColor(requireContext(), false);
        }

        int colorFinal;
        if(Prefs.isAdapterColor()){
            colorFinal = event.getColor().getPrimaryTextColor();
        }else {
            colorFinal = ThemeStore.accentColor(requireContext());
        }
        colorFinal = ColorExtensionsUtils.ripAlpha(colorFinal);
        //with this line there is a reproducible issue: setting a "custom color" during playback and coming back leaves the play button animation unfinished
        TintHelper.setTintAuto(
                butPlay,
                MaterialValueHelper.getPrimaryTextColor(
                        requireContext(),
                        ColorUtil.isColorLight(colorFinal)
                ),
                false
        );
        TintHelper.setTintAuto(playPauseButton, colorFinal, true);
        ColorExtensionsUtils.applyColor(sbPosition,colorFinal);
        // The speed label and sleep timer button keep their theme colour in the plain styles;
        // the styles that paint their own background tint them through applyControlsColor.
        tintSkipControls(lastPlaybackControlsColor);

        if(isFromScreenActivity){
            swipeUpIv.setColorFilter(lastPlaybackControlsColor, PorterDuff.Mode.SRC_IN);
            swipeUpTipTv.setTextColor(lastPlaybackControlsColor);
        }
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingAdStartMs = savedInstanceState.getLong(KEY_PENDING_AD_START, NO_PENDING_AD_START);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(KEY_PENDING_AD_START, pendingAdStartMs);
    }

    @Override
    public void onStart() {
        super.onStart();
        controller = newPlaybackController();
        controller.init();
        loadMediaInfo(false,false);
        EventBus.getDefault().register(this);
        txtvRev.setText(NumberFormat.getInstance().format(Prefs.getRewindSecs()));
        txtvFF.setText(NumberFormat.getInstance().format(Prefs.getFastForwardSecs()));
    }

    @Override
    public void onStop() {
        super.onStop();
        controller.release();
        controller = null;
        progressIndicator.setVisibility(View.GONE); // Controller released; we will not receive buffering updates
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
        if (adSegmentsDisposable != null) {
            adSegmentsDisposable.dispose();
            adSegmentsDisposable = null;
        }
        if (adAnalysisDisposable != null) {
            adAnalysisDisposable.dispose();
            adAnalysisDisposable = null;
        }
        if (videoStreamCheckDisposable != null) {
            videoStreamCheckDisposable.dispose();
            videoStreamCheckDisposable = null;
        }
        if (bookmarksDisposable != null) {
            bookmarksDisposable.dispose();
            bookmarksDisposable = null;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void bufferUpdate(BufferUpdateEvent event) {
        if (event.hasStarted()) {
            progressIndicator.setVisibility(View.VISIBLE);
        } else if (event.hasEnded()) {
            progressIndicator.setVisibility(View.GONE);
        } else if (controller != null && controller.isStreaming()) {
            sbPosition.setSecondaryProgress((int) (event.getProgress() * sbPosition.getMax()));
        } else {
            sbPosition.setSecondaryProgress(0);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updatePosition(PlaybackPositionEvent event) {
        if (controller == null || txtvPosition == null || txtvLength == null || sbPosition == null) {
            return;
        }

        TimeSpeedConverter converter = new TimeSpeedConverter(controller.getCurrentPlaybackSpeedMultiplier());
        int currentPosition = converter.convert(event.getPosition());
        int duration = converter.convert(event.getDuration());
        int remainingTime = converter.convert(Math.max(event.getDuration() - event.getPosition(), 0));
        currentChapterIndex = ChapterUtils.getCurrentChapterIndex(controller.getMedia(), currentPosition);
        if (currentPosition == PlaybackService.INVALID_TIME || duration == PlaybackService.INVALID_TIME) {
            Log.w(TAG, "failed to position observer update because of invalid time");
            return;
        }
        txtvPosition.setText(Converter.getDurationStringLong(currentPosition));
        showTimeLeft = Prefs.shouldShowRemainingTime();
        if (showTimeLeft) {
            txtvLength.setText(((remainingTime > 0) ? "-" : "") + Converter.getDurationStringLong(remainingTime));
        } else {
            txtvLength.setText(Converter.getDurationStringLong(duration));
        }

        if (!sbPosition.isPressed()) {
            float progress = ((float) event.getPosition()) / event.getDuration();
            sbPosition.setProgress((int) (progress * sbPosition.getMax()));
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void favoritesChanged(FavoritesEvent event) {
        //the favorite state changed, so re-read the latest value from the database (this screen works because DBWriter mutates the item it was handed, but changes made on other screens do not propagate)
        AudioPlayerFragment.this.loadMediaInfo(false,true);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void mediaPlayerError(PlayerErrorEvent event) {
        Timber.e("media player error " + event.getMessage());
        if(isFromScreenActivity){
            return;
        }
/*        final AlertDialog.Builder errorDialog =            new AccentMaterialDialog(
                    getContext(),
                    R.style.MaterialAlertDialogTheme
            );
        errorDialog.setTitle(R.string.error_label);
        errorDialog.setMessage(event.getMessage());
        errorDialog.setPositiveButton(android.R.string.ok, (dialog, which) ->
                ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED));
        errorDialog.create().show();*/
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (controller == null || txtvLength == null) {
            return;
        }

        if (fromUser) {
            float prog = progress / ((float) seekBar.getMax());
            TimeSpeedConverter converter = new TimeSpeedConverter(controller.getCurrentPlaybackSpeedMultiplier());
            int position = converter.convert((int) (prog * controller.getDuration()));
            int newChapterIndex = ChapterUtils.getCurrentChapterIndex(controller.getMedia(), position);
            if (newChapterIndex > -1) {
                if (!sbPosition.isPressed() && currentChapterIndex != newChapterIndex) {
                    currentChapterIndex = newChapterIndex;
                    position = (int) controller.getMedia().getChapters().get(currentChapterIndex).getStart();
                    seekedToChapterStart = true;
                    controller.seekTo(position);
                    updateUi(controller.getMedia());
                }
                txtvSeek.setText(controller.getMedia().getChapters().get(newChapterIndex).getTitle()
                                + "\n" + Converter.getDurationStringLong(position));
            } else {
                txtvSeek.setText(Converter.getDurationStringLong(position));
            }
        } else if (duration != controller.getDuration()) {
            updateUi(controller.getMedia());
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // interrupt position Observer, restart later
        cardViewSeek.setScaleX(.8f);
        cardViewSeek.setScaleY(.8f);
        cardViewSeek.animate()
                .setInterpolator(new FastOutSlowInInterpolator())
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200)
                .start();
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (controller != null) {
            if (seekedToChapterStart) {
                seekedToChapterStart = false;
            } else {
                float prog = seekBar.getProgress() / ((float) seekBar.getMax());
                controller.seekTo((int) (prog * controller.getDuration()));
            }
        }
        cardViewSeek.setScaleX(1f);
        cardViewSeek.setScaleY(1f);
        cardViewSeek.animate()
                .setInterpolator(new FastOutSlowInInterpolator())
                .alpha(0f).scaleX(.8f).scaleY(.8f)
                .setDuration(200)
                .start();
    }

    public void setupOptionsMenu(Playable media) {
        if (toolbar.getMenu().size() == 0) {
            toolbar.inflateMenu(R.menu.mediaplayer);
        }
        if (controller == null) {
            return;
        }
        boolean isFeedMedia = media instanceof FeedMedia;
        toolbar.getMenu().findItem(R.id.open_feed_item).setVisible(isFeedMedia);
        FeedItem playingItem = isFeedMedia ? ((FeedMedia) media).getItem() : null;
        toolbar.getMenu().findItem(R.id.transcript_item)
                .setVisible(playingItem != null && playingItem.hasTranscript());
        if (isFeedMedia) {
            FeedItemMenuProcess.onPrepareMenu(toolbar.getMenu(), ((FeedMedia) media).getItem());
        }

        toolbar.getMenu().findItem(R.id.ad_segments_item).setVisible(isFeedMedia);
        toolbar.getMenu().findItem(R.id.analyze_ads_item).setVisible(isFeedMedia);
        toolbar.getMenu().findItem(R.id.mark_ad_start_item)
                .setVisible(isFeedMedia && pendingAdStartMs == NO_PENDING_AD_START);
        toolbar.getMenu().findItem(R.id.mark_ad_end_item)
                .setVisible(isFeedMedia && pendingAdStartMs != NO_PENDING_AD_START);

        // Layouts with a sleep timer button in the control row do not repeat it in the toolbar.
        boolean sleepTimerInToolbar = butSleepTimer == null;
        toolbar.getMenu().findItem(R.id.set_sleeptimer_item)
                .setVisible(sleepTimerInToolbar && !controller.sleepTimerActive());
        toolbar.getMenu().findItem(R.id.disable_sleeptimer_item)
                .setVisible(sleepTimerInToolbar && controller.sleepTimerActive());
        toolbar.getMenu().findItem(R.id.driver_mode).setVisible(true);
        // A video episode shows here only while it is listened to as audio only.
        toolbar.getMenu().findItem(R.id.watch_video_item).setVisible(!isDriveMode
                && media != null && media.getMediaType() == MediaType.VIDEO && !PlaybackService.isCasting());
        MenuIconUtil.showToolbarMenuIcon(toolbar);
    }

    /**
     * Watching an audio-only download streams its video, which for a 4K episode can need far
     * more than the connection delivers: warn first when Android's speed estimate says it will
     * not keep up. (If it is watched anyway and never starts, the service goes back to the audio.)
     */
    private void checkVideoStreamThenWatch(@NonNull FeedMedia media) {
        if (videoStreamCheckDisposable != null) {
            videoStreamCheckDisposable.dispose();
        }
        Context appContext = requireContext().getApplicationContext();
        videoStreamCheckDisposable = Single.fromCallable(() -> VideoStreamCheck.fetchStreamSize(media))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(size -> {
                    float speed = VideoStreamCheck.watchingSpeed(media);
                    long required = VideoStreamCheck.requiredKbps(size, media.getDuration(), speed);
                    long available = VideoStreamCheck.estimatedDownstreamKbps(appContext);
                    if (!isAdded()) {
                        return;
                    }
                    if (VideoStreamCheck.isLikelyTooSlow(required, available)) {
                        showVideoTooHeavyDialog(media, required, available, speed);
                    } else {
                        watchVideo(media);
                    }
                }, error -> {
                    Log.e(TAG, "Checking the video stream failed", error);
                    watchVideo(media);
                });
    }

    private void showVideoTooHeavyDialog(@NonNull FeedMedia media, long requiredKbps, long availableKbps,
                                         float speed) {
        DecimalFormat number = new DecimalFormat("0.#");
        new AccentMaterialDialog(requireContext(), R.style.MaterialAlertDialogTheme)
                .setTitle(R.string.video_stream_too_heavy_title)
                .setMessage(getString(R.string.video_stream_too_heavy_message,
                        number.format(speed), number.format(requiredKbps / 1000.0),
                        number.format(availableKbps / 1000.0)))
                .setPositiveButton(R.string.video_stream_watch_anyway, (dialog, which) -> watchVideo(media))
                .setNegativeButton(R.string.video_stream_keep_listening, null)
                .show();
    }

    private void watchVideo(@NonNull Playable media) {
        if (controller == null || !isAdded()) {
            return;
        }
        controller.setShowVideo(true);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
        startActivity(PlaybackService.getPlayerActivityIntent(requireContext(), media));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(FeedItemEvent event) {

    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (controller == null) {
            return false;
        }
        Playable media = controller.getMedia();
        if (media == null) {
            return false;
        }

        final @Nullable FeedItem feedItem = (media instanceof FeedMedia) ? ((FeedMedia) media).getItem() : null;
        if (feedItem != null && FeedItemMenuProcess.onMenuItemClicked(this, item.getItemId(), feedItem)) {
            return true;
        }

        final int itemId = item.getItemId();
        if (itemId == R.id.disable_sleeptimer_item || itemId == R.id.set_sleeptimer_item) {
            new SleepTimerDialog().show(getChildFragmentManager(), "SleepTimerDialog");
            return true;
        } else if (itemId == R.id.watch_video_item) {
            if (!VideoModeUtils.isAudioOnlyDownload(media)) {
                watchVideo(media);
            } else if (!NetworkUtils.isStreamingAllowed()) {
                // The download holds no picture: watching streams the video, so ask first.
                new UseStreamConfirmDialog(requireContext(), media, () -> {
                    checkVideoStreamThenWatch((FeedMedia) media);
                    return Unit.INSTANCE;
                }).show();
            } else {
                checkVideoStreamThenWatch((FeedMedia) media);
            }
            return true;
        } else if (itemId == R.id.audio_controls) {
            PlaybackControlsDialog dialog = PlaybackControlsDialog.newInstance(feedItem != null ? feedItem.getFeedId() : 0);
            dialog.show(getChildFragmentManager(), "playback_controls");
            return true;
        } else if (itemId == R.id.transcript_item) {
            if (feedItem != null) {
                FeedItemExtraInfoFragment.newInstance(feedItem.hasTranscript())
                        .scrollToPage(FeedItemExtraInfoFragment.TRANSCRIPT_POS)
                        .show(requireActivity().getSupportFragmentManager(), "FeedItemExtraInfoFragment");
            }
            return true;
        } else if (itemId == R.id.open_feed_item) {
            if (feedItem != null) {
                Intent intent = MainActivity.getIntentToOpenFeedWithId(getContext(), feedItem.getFeedId());
                startActivity(intent);
            }
            return true;
        } else if (itemId == R.id.driver_mode) {
            if (feedItem != null) {
                Intent intent = new Intent(getActivity(), DriveModeActivity.class);
                startActivity(intent);
            }
            return true;
        } else if (itemId == R.id.mark_ad_start_item) {
            pendingAdStartMs = controller.getPosition();
            showShortMessage(getString(R.string.ad_mark_start_set,
                    Converter.getDurationStringLong((int) pendingAdStartMs)));
            setupOptionsMenu(media);
            return true;
        } else if (itemId == R.id.mark_ad_end_item) {
            finishAdMark(media, feedItem);
            return true;
        } else if (itemId == R.id.ad_segments_item) {
            if (feedItem != null) {
                AdSegmentsDialog.newInstance(feedItem.getId())
                        .show(getChildFragmentManager(), AdSegmentsDialog.TAG);
            }
            return true;
        } else if (itemId == R.id.analyze_ads_item) {
            analyzeAds(media, feedItem);
            return true;
        }
        return false;
    }

    /**
     * Closes a hand-marked ad segment. The two marks may be tapped in either order, so they are
     * sorted here; a range under {@link #MIN_MANUAL_AD_LENGTH_MS} is treated as a mis-tap and
     * dropped. Either way the pending mark is cleared and the menu swaps back.
     */
    private void finishAdMark(Playable media, @Nullable FeedItem feedItem) {
        long start = pendingAdStartMs;
        pendingAdStartMs = NO_PENDING_AD_START;
        setupOptionsMenu(media);
        if (start == NO_PENDING_AD_START || feedItem == null || controller == null) {
            return;
        }
        long end = controller.getPosition();
        long from = Math.min(start, end);
        long to = Math.max(start, end);
        if (to - from < MIN_MANUAL_AD_LENGTH_MS) {
            showShortMessage(getString(R.string.ad_mark_too_short));
            return;
        }
        DBWriter.addAdSegment(new AdSegment(feedItem.getId(), from, to, AdSegment.Source.MANUAL, 1f));
        showShortMessage(getString(R.string.ad_mark_saved));
    }

    /**
     * Runs detection on demand. A downloaded episode gets the full analysis through WorkManager,
     * and so does a streamed one with a transcript to read (chapters and transcript only); any
     * other streamed one can only be matched against its chapter titles, which is cheap but still
     * touches the database, so it runs off the main thread.
     */
    private void analyzeAds(Playable media, @Nullable FeedItem feedItem) {
        if (!(media instanceof FeedMedia) || feedItem == null) {
            return;
        }
        FeedMedia feedMedia = (FeedMedia) media;
        Context context = requireContext().getApplicationContext();
        if (feedMedia.isDownloaded()
                || (feedItem.hasTranscript() && TranscriptAdAnalysis.wantedMethod() != null)) {
            AdAnalysisWorker.enqueue(context, feedMedia.getId(), true);
        } else {
            if (adAnalysisDisposable != null) {
                adAnalysisDisposable.dispose();
            }
            adAnalysisDisposable = Completable
                    .fromAction(() -> AdAnalysisWorker.applyChapterSegments(feedItem))
                    .subscribeOn(Schedulers.io())
                    .subscribe(() -> { },
                            error -> Log.e(TAG, "could not read the chapter ad segments", error));
        }
        showShortMessage(getString(R.string.analyze_ads_started));
    }

    private void showShortMessage(String message) {
        TopSnackbarUtil.showSnack(getActivity(), message, Toast.LENGTH_SHORT);
    }

    /**
     * Paints the ad segments of the episode onto the position bar. Nothing is drawn while ad
     * skipping is off for the episode's feed (its own choice, else the global default), and
     * detections below the sensitivity threshold are left out, so the bands never claim a skip
     * that will not happen. The feed's preferences are re-read from the
     * database because a change made elsewhere (the audio-effects dialog, the feed settings) may
     * not have touched the object this screen holds.
     */
    private void loadAdSegments(@Nullable Playable media) {
        if (adSegmentsDisposable != null) {
            adSegmentsDisposable.dispose();
            adSegmentsDisposable = null;
        }
        if (sbPosition == null) {
            return;
        }
        FeedItem item = (media instanceof FeedMedia) ? ((FeedMedia) media).getItem() : null;
        if (item == null) {
            adSegmentsItemId = 0;
            sbPosition.setAdSegments(Collections.emptyList(), 0);
            return;
        }
        final long itemId = item.getId();
        final long feedId = item.getFeedId();
        final long mediaDuration = media.getDuration();
        adSegmentsItemId = itemId;
        adSegmentsDisposable = Single.fromCallable(() -> {
                    FeedPreferences stored = feedId != 0 ? DBReader.getFeedPreferences(feedId) : null;
                    boolean enabled = stored != null
                            ? AdSkipUtils.isAdSkipEnabled(stored)
                            : AdSkipUtils.isAdSkipEnabled(media);
                    return enabled
                            ? AdSkipUtils.segmentsMeetingThreshold(DBReader.loadAdSegmentsOfFeedItem(itemId))
                            : Collections.<AdSegment>emptyList();
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(segments -> {
                    if (sbPosition != null) {
                        sbPosition.setAdSegments(segments, mediaDuration);
                    }
                }, error -> Log.e(TAG, "could not load the ad segments of item " + itemId, error));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAdSegmentsChanged(AdSegmentsChangedEvent event) {
        if (controller == null || event.getFeedItemId() != adSegmentsItemId) {
            return;
        }
        loadAdSegments(controller.getMedia());
    }

    /**
     * Bookmarks the position being played, at once or (with {@code withNote}) through the note
     * dialog. An instant bookmark offers "Add note" on its confirmation snackbar.
     */
    private void addBookmark(boolean withNote) {
        if (controller == null) {
            return;
        }
        Playable media = controller.getMedia();
        if (!(media instanceof FeedMedia) || ((FeedMedia) media).getItemId() <= 0) {
            showShortMessage(getString(R.string.bookmark_unavailable));
            return;
        }
        long itemId = ((FeedMedia) media).getItemId();
        long position = Math.max(0, controller.getPosition());
        if (withNote) {
            BookmarkNoteDialog.show(requireContext(), R.string.bookmark_add, position, null,
                    note -> {
                        DBWriter.addBookmark(new Bookmark(itemId, position, note, System.currentTimeMillis()));
                        showBookmarkSnackbar(getString(R.string.bookmark_added), null);
                    });
            return;
        }
        Bookmark bookmark = new Bookmark(itemId, position, null, System.currentTimeMillis());
        DBWriter.addBookmark(bookmark);
        showBookmarkSnackbar(getString(R.string.bookmark_added), v -> {
            Context context = getContext();
            if (context != null) {
                BookmarkNoteDialog.show(context, R.string.bookmark_add_note, position, null,
                        note -> DBWriter.setBookmarkNote(bookmark, note));
            }
        });
    }

    private void showBookmarkSnackbar(String text, @Nullable View.OnClickListener addNote) {
        if (!isAdded()) {
            return;
        }
        Snackbar snackbar;
        if (getActivity() instanceof MainActivity) {
            snackbar = ((MainActivity) getActivity()).showSnackbarAbovePlayer(text, Snackbar.LENGTH_LONG);
        } else {
            View root = getView();
            if (root == null) {
                return;
            }
            snackbar = Snackbar.make(root, text, Snackbar.LENGTH_LONG);
            snackbar.show();
        }
        if (addNote != null) {
            snackbar.setAction(getString(R.string.bookmark_add_note), addNote);
        }
    }

    /** Paints the bookmarks of the episode onto the position bar as ticks. */
    private void loadBookmarkTicks(@Nullable Playable media) {
        if (bookmarksDisposable != null) {
            bookmarksDisposable.dispose();
            bookmarksDisposable = null;
        }
        if (sbPosition == null) {
            return;
        }
        long itemId = (media instanceof FeedMedia) ? ((FeedMedia) media).getItemId() : 0;
        if (itemId <= 0) {
            bookmarksItemId = 0;
            sbPosition.setBookmarks(Collections.emptyList(), 0);
            return;
        }
        final long mediaDuration = media.getDuration();
        bookmarksItemId = itemId;
        bookmarksDisposable = Single.fromCallable(() -> {
                    List<Long> positions = new ArrayList<>();
                    for (Bookmark bookmark : DBReader.loadBookmarksOfFeedItem(itemId)) {
                        positions.add(bookmark.getPositionMs());
                    }
                    return positions;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(positions -> {
                    if (sbPosition != null) {
                        sbPosition.setBookmarks(positions, mediaDuration);
                    }
                }, error -> Log.e(TAG, "could not load the bookmarks of item " + itemId, error));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBookmarksChanged(BookmarksChangedEvent event) {
        if (controller == null || event.getFeedItemId() != bookmarksItemId) {
            return;
        }
        loadBookmarkTicks(controller.getMedia());
    }

    /** Ad skipping was switched on or off, globally or for a feed: redraw or clear the bands. */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAdSkipChanged(AdSkipChangedEvent event) {
        if (controller == null || adSegmentsItemId == 0) {
            return;
        }
        loadAdSegments(controller.getMedia());
    }

    /**
     * Tells the user what the service just skipped and offers to take it back. The service does
     * the seeking; this only posts the undo event so the same segment is not skipped again.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAdSkipped(AdSkippedEvent event) {
        if (!Prefs.isAdSkipShowSnackbar() || !isAdded()) {
            return;
        }
        String skipped = Converter.getDurationStringShort((int) event.getSkippedMs(), false);
        String text = getString(R.string.ad_skipped_snackbar, skipped);
        Snackbar snackbar;
        if (getActivity() instanceof MainActivity) {
            snackbar = ((MainActivity) getActivity())
                    .showSnackbarAbovePlayer(text, Snackbar.LENGTH_LONG);
        } else {
            View root = getView();
            if (root == null) {
                return;
            }
            snackbar = Snackbar.make(root, text, Snackbar.LENGTH_LONG);
            snackbar.show();
        }
        snackbar.setAction(getString(R.string.undo), v -> EventBus.getDefault().post(
                new AdSkipUndoEvent(event.getFeedItemId(), event.getSegmentId(),
                        event.getFromMs())));
    }

    protected void startOrStopSnow(boolean isSnowFalling) {

        if(isDriveMode){
            return;
        }
        if (isSnowFalling && !ColorExtensionsUtils.isColorLight(ColorExtensionsUtils.surfaceColor(this))) {
            snowfallView.setVisibility(View.VISIBLE);
            snowfallView.restartFalling();
        } else {
            snowfallView.setVisibility(View.GONE);
            snowfallView.stopFalling();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Objects.equals(key, Prefs.PREF_SNOWFALL)) {
            startOrStopSnow(Prefs.showSnowFall());
        }
    }

    protected static class AudioPlayerPagerAdapter extends FragmentStateAdapter {
        protected static final String TAG = "AudioPlayerPagerAdapter";
        protected boolean isDriveMode = false;

        public AudioPlayerPagerAdapter(@NonNull Fragment fragment,boolean isDriveMode) {
            super(fragment);
            this.isDriveMode = isDriveMode;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                default:
                    return new CoverFragment(isDriveMode);
            }
        }

        @Override
        public int getItemCount() {
            return NUM_CONTENT_FRAGMENTS;
        }
    }

    public void scrollToPage(int page, boolean smoothScroll) {
        if (pager == null) {
            return;
        }

        pager.setCurrentItem(page, smoothScroll);

        Fragment visibleChild = getChildFragmentManager().findFragmentByTag("f" + POS_DESCRIPTION);
    }

    public void scrollToPage(int page) {
        scrollToPage(page, false);
    }

    protected void setupScreenLayout(){
        if(getActivity() instanceof LockScreenActivity){
            isFromScreenActivity = true;
            toolbar.setVisibility(View.GONE);
            slideView.setVisibility(View.VISIBLE);
            slideView.setTranslationY(250f);
            slideView.setAlpha(0f);
            slideView.animate().translationY(0f).alpha(1f).setDuration(1500).start();

            getExternalPlayerHolder().setVisibility(View.GONE);

        }
    }

    public AudioPlayerFragment(boolean isDriveMode){
        this.isDriveMode = isDriveMode;
    }

    //required no-arg constructor, do not remove
    public AudioPlayerFragment(){
    }

    protected void setupDriveMode(){
        if(isDriveMode){
            driveCloseIv.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        }
    }
}
