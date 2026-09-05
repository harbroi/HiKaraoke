package net.harbroi.hikaraoketv;

import android.content.res.ColorStateList;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView;

import java.util.List;
import java.util.Locale;

public class VideoPlayerWebViewActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";
    private static final String EXTRA_QUEUE_INDEX = "queue_index";

    private FrameLayout rootContainer;
    private YouTubePlayerView youTubePlayerView;
    private YouTubePlayer currentYouTubePlayer;
    private TextView titleView;
    private TextView nextSongView;
    private TextView queueView;
    private TextView timeView;
    private ImageButton previousButton;
    private ImageButton stopButton;
    private ImageButton nextButton;

    private FirebaseVideoRepository videoRepository;
    private List<VideoItem> videoQueue;
    private int currentQueueIndex = 0;
    private boolean isQueueFinished;
    private boolean isPlaybackPaused = false;
    private String pendingVideoId;
    private float currentSecond = 0f;
    private float durationSecond = 0f;
    private long queueUpdateToken = 0L;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        hideSystemUi();

        rootContainer = new FrameLayout(this);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(rootContainer);

        createOverlayViews();
        try {
            initializePlayer();
            videoRepository = new FirebaseVideoRepository(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to initialize video player", e);
            Toast.makeText(this,
                    "Player initialization failed. Check Logcat for details.",
                    Toast.LENGTH_LONG).show();
            updateOverlay("Player initialization failed", "", 0, 0);
            return;
        }

        if (getIntent() != null && getIntent().hasExtra(EXTRA_QUEUE_INDEX)) {
            currentQueueIndex = getIntent().getIntExtra(EXTRA_QUEUE_INDEX, 0);
        }

        try {
            subscribeToQueueUpdates();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to subscribe to Firebase queue updates", e);
            Toast.makeText(this,
                    "Failed to load Firebase queue. Check Logcat for details.",
                    Toast.LENGTH_LONG).show();
            updateOverlay("Failed to load queue", "", 0, 0);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }

    private void initializePlayer() {
        youTubePlayerView = new YouTubePlayerView(this);
        youTubePlayerView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        rootContainer.addView(youTubePlayerView, 0);
        getLifecycle().addObserver(youTubePlayerView);

        youTubePlayerView.addYouTubePlayerListener(new AbstractYouTubePlayerListener() {
            @Override
            public void onReady(@NonNull YouTubePlayer youTubePlayer) {
                currentYouTubePlayer = youTubePlayer;
                if (pendingVideoId != null) {
                    currentYouTubePlayer.loadVideo(pendingVideoId, 0f);
                }
            }

            @Override
            public void onCurrentSecond(@NonNull YouTubePlayer youTubePlayer, float second) {
                currentSecond = second;
                updatePlaybackTimeView();
            }

            @Override
            public void onVideoDuration(@NonNull YouTubePlayer youTubePlayer, float duration) {
                durationSecond = duration;
                updatePlaybackTimeView();
            }

            @Override
            public void onStateChange(@NonNull YouTubePlayer youTubePlayer,
                                      @NonNull PlayerConstants.PlayerState state) {
                if (state == PlayerConstants.PlayerState.PLAYING) {
                    isPlaybackPaused = false;
                    updatePlayPauseButtonText();
                } else if (state == PlayerConstants.PlayerState.PAUSED) {
                    isPlaybackPaused = true;
                    updatePlayPauseButtonText();
                }
                if (state == PlayerConstants.PlayerState.ENDED) {
                    playNextVideo();
                }
            }

            @Override
            public void onError(@NonNull YouTubePlayer youTubePlayer,
                                @NonNull PlayerConstants.PlayerError error) {
                Log.e(TAG, "YouTube player error: " + error);
                if (error == PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER
                        || error == PlayerConstants.PlayerError.HTML_5_PLAYER) {
                    dropCurrentVideoAsBlockedAndAdvance();
                } else {
                    Toast.makeText(VideoPlayerWebViewActivity.this,
                            "Playback failed. Skipping...",
                            Toast.LENGTH_SHORT).show();
                    playNextVideo();
                }
            }
        });
    }

    private void dropCurrentVideoAsBlockedAndAdvance() {
        if (videoQueue != null && currentQueueIndex >= 0 && currentQueueIndex < videoQueue.size()) {
            VideoItem blocked = videoQueue.remove(currentQueueIndex);
            String id = blocked != null ? blocked.getVideoId() : "unknown";
            Log.w(TAG, "Removing blocked embed video from queue: " + id);
            Toast.makeText(this,
                    "Video blocked in embedded mode. Skipping...",
                    Toast.LENGTH_SHORT).show();

            if (videoQueue.isEmpty()) {
                onPlaybackQueueEnded();
                return;
            }

            if (currentQueueIndex >= videoQueue.size()) {
                currentQueueIndex = 0;
            }
            playVideoAt(currentQueueIndex);
            return;
        }

        playNextVideo();
    }

    private void loadVideosFromFirebase() {
        videoRepository.observeVideoQueue(new FirebaseVideoRepository.VideoQueueCallback() {
            @Override
            public void onVideosLoaded(List<VideoItem> videos) {
                applyPlayableQueueFromFirebase(videos);
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Firebase observe error: " + errorMessage);
                Toast.makeText(VideoPlayerWebViewActivity.this,
                        "Failed to observe queue: " + errorMessage,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void subscribeToQueueUpdates() {
        loadVideosFromFirebase();
    }

    private void applyPlayableQueueFromFirebase(List<VideoItem> videos) {
        final long requestToken = ++queueUpdateToken;
        final String currentVideoId = pendingVideoId;

        java.util.ArrayList<VideoItem> incoming = new java.util.ArrayList<>();
        for (VideoItem video : videos) {
            if (video == null || video.getVideoId() == null || video.getVideoId().trim().isEmpty()) {
                Log.w(TAG, "Skipping video with missing videoId");
                continue;
            }
            incoming.add(video);
        }

        if (requestToken != queueUpdateToken) {
            return;
        }

        List<VideoItem> previousQueue = videoQueue;
        boolean hadQueueBefore = previousQueue != null && !previousQueue.isEmpty();
        int previousQueueSize = previousQueue != null ? previousQueue.size() : 0;
        boolean wasQueueFinished = isQueueFinished;

        showToastForNewQueueItems(previousQueue, incoming, hadQueueBefore);

        videoQueue = incoming;

        if (videoQueue.isEmpty()) {
            onPlaybackQueueEnded();
            updateOverlay("No videos in queue", "", 0, 0);
            return;
        }

        if (wasQueueFinished) {
            int startIndex = (videoQueue.size() > previousQueueSize)
                    ? Math.max(0, Math.min(previousQueueSize, videoQueue.size() - 1))
                    : 0;
            playVideoAt(startIndex);
            return;
        }

        int foundIndex = findIndexByVideoId(videoQueue, currentVideoId);
        if (foundIndex >= 0) {
            currentQueueIndex = foundIndex;
            VideoItem currentItem = videoQueue.get(currentQueueIndex);
            updateOverlay(currentItem.getTitle(), getNextSongTitle(currentQueueIndex),
                    currentQueueIndex + 1, videoQueue.size());
            return;
        }

        if (currentQueueIndex < 0 || currentQueueIndex >= videoQueue.size()) {
            currentQueueIndex = 0;
        }

        playVideoAt(currentQueueIndex);
    }

    private void showToastForNewQueueItems(List<VideoItem> previousItems,
                                           List<VideoItem> currentItems,
                                           boolean hadQueueBefore) {
        if (!hadQueueBefore || currentItems == null || currentItems.isEmpty()) {
            return;
        }

        java.util.HashSet<String> existingIds = new java.util.HashSet<>();
        if (previousItems != null) {
            for (VideoItem item : previousItems) {
                if (item == null || item.getVideoId() == null) {
                    continue;
                }
                String normalizedId = item.getVideoId().trim();
                if (!normalizedId.isEmpty()) {
                    existingIds.add(normalizedId);
                }
            }
        }

        for (VideoItem item : currentItems) {
            if (item == null || item.getVideoId() == null) {
                continue;
            }
            String normalizedId = item.getVideoId().trim();
            if (normalizedId.isEmpty() || existingIds.contains(normalizedId)) {
                continue;
            }
            String title = item.getTitle() == null || item.getTitle().trim().isEmpty()
                    ? "Untitled video"
                    : item.getTitle().trim();
            Toast.makeText(this, "Added to queue: " + title, Toast.LENGTH_SHORT).show();
        }
    }

    private int findIndexByVideoId(List<VideoItem> items, String videoId) {
        if (items == null || videoId == null || videoId.trim().isEmpty()) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            VideoItem item = items.get(i);
            if (item != null && videoId.equals(item.getVideoId())) {
                return i;
            }
        }
        return -1;
    }

    private void playVideoAt(int index) {
        if (videoQueue == null || videoQueue.isEmpty()) {
            return;
        }

        if (index < 0 || index >= videoQueue.size()) {
            onPlaybackQueueEnded();
            return;
        }

        currentQueueIndex = index;
        isQueueFinished = false;
        isPlaybackPaused = false;
        currentSecond = 0f;
        durationSecond = 0f;
        updatePlayPauseButtonText();
        updatePlaybackTimeView();
        VideoItem item = videoQueue.get(index);

        updateOverlay(item.getTitle(), getNextSongTitle(index), index + 1, videoQueue.size());
        playVideoId(item.getVideoId());
    }

    private void playVideoId(String videoId) {
        pendingVideoId = videoId;
        if (currentYouTubePlayer != null) {
            currentYouTubePlayer.loadVideo(videoId, 0f);
        }
    }

    private void playNextVideo() {
        if (isQueueFinished) {
            return;
        }
        playVideoAt(currentQueueIndex + 1);
    }

    private void playPreviousVideo() {
        if (videoQueue == null || videoQueue.isEmpty()) {
            return;
        }
        int previousIndex = currentQueueIndex - 1;
        if (previousIndex < 0) {
            previousIndex = videoQueue.size() - 1;
        }
        playVideoAt(previousIndex);
    }

    private void stopPlayback() {
        if (currentYouTubePlayer != null) {
            if (isPlaybackPaused) {
                currentYouTubePlayer.play();
            } else {
                currentYouTubePlayer.pause();
            }
        }
    }

    private void updatePlayPauseButtonText() {
        if (stopButton != null) {
            stopButton.setImageResource(
                    isPlaybackPaused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause
            );
            stopButton.setContentDescription(isPlaybackPaused ? "Play" : "Pause");
        }
    }

    private void onPlaybackQueueEnded() {
        isQueueFinished = true;
        isPlaybackPaused = true;
        currentSecond = 0f;
        durationSecond = 0f;
        updatePlayPauseButtonText();
        updatePlaybackTimeView();
        updateOverlay("Queue finished", "", 0, 0);
        Toast.makeText(this, "End of queue reached", Toast.LENGTH_SHORT).show();
    }

    private void createOverlayViews() {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(0x66000000);
        overlay.setPadding(24, 24, 24, 24);

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        overlay.setLayoutParams(overlayParams);

        titleView = new TextView(this);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(20);
        titleView.setMaxLines(2);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0f
        ));

        nextSongView = new TextView(this);
        nextSongView.setTextColor(0xFFDDDDDD);
        nextSongView.setTextSize(16);
        nextSongView.setMaxLines(1);
        nextSongView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0f
        ));

        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        controlsRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        previousButton = new ImageButton(this);
        previousButton.setImageResource(android.R.drawable.ic_media_previous);
        previousButton.setContentDescription("Previous");
        previousButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        previousButton.setPadding(0, 0, 0, 0);
        previousButton.setOnClickListener(v -> {
            playPreviousVideo();
            if (stopButton != null) {
                stopButton.requestFocus();
            }
        });

        stopButton = new ImageButton(this);
        stopButton.setImageResource(android.R.drawable.ic_media_pause);
        stopButton.setContentDescription("Pause");
        stopButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        stopButton.setPadding(0, 0, 0, 0);
        stopButton.setOnClickListener(v -> stopPlayback());

        nextButton = new ImageButton(this);
        nextButton.setImageResource(android.R.drawable.ic_media_next);
        nextButton.setContentDescription("Next");
        nextButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        nextButton.setPadding(0, 0, 0, 0);
        nextButton.setOnClickListener(v -> {
            playNextVideo();
            if (stopButton != null) {
                stopButton.requestFocus();
            }
        });

        int activeBlueGray = 0xFF6F7F99;
        int pressedBlueGray = 0xFF5E6F89;
        int idleButtonColor = 0x66000000;
        ColorStateList controlTint = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_pressed},
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_selected},
                        new int[]{}
                },
                new int[]{
                        pressedBlueGray,
                        activeBlueGray,
                        activeBlueGray,
                        idleButtonColor
                }
        );
        ColorStateList iconTint = ColorStateList.valueOf(0xFFFFFFFF);
        previousButton.setBackgroundTintList(controlTint);
        stopButton.setBackgroundTintList(controlTint);
        nextButton.setBackgroundTintList(controlTint);
        previousButton.setImageTintList(iconTint);
        stopButton.setImageTintList(iconTint);
        nextButton.setImageTintList(iconTint);

        previousButton.setFocusable(true);
        stopButton.setFocusable(true);
        nextButton.setFocusable(true);

        previousButton.setId(android.view.View.generateViewId());
        stopButton.setId(android.view.View.generateViewId());
        nextButton.setId(android.view.View.generateViewId());

        previousButton.setNextFocusRightId(stopButton.getId());
        stopButton.setNextFocusLeftId(previousButton.getId());
        stopButton.setNextFocusRightId(nextButton.getId());
        nextButton.setNextFocusLeftId(stopButton.getId());

        int buttonSizePx = dpToPx(56);
        LinearLayout.LayoutParams previousParams = new LinearLayout.LayoutParams(buttonSizePx, buttonSizePx);
        previousParams.rightMargin = 16;
        previousButton.setLayoutParams(previousParams);

        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(buttonSizePx, buttonSizePx);
        stopParams.rightMargin = 16;
        stopButton.setLayoutParams(stopParams);

        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(buttonSizePx, buttonSizePx);
        nextParams.rightMargin = 16;
        nextButton.setLayoutParams(nextParams);

        queueView = new TextView(this);
        queueView.setTextColor(0xFFFFFFFF);
        queueView.setTextSize(18);
        queueView.setPadding(24, 0, 0, 0);
        queueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        queueView.setGravity(Gravity.END);

        timeView = new TextView(this);
        timeView.setTextColor(0xFFDDDDDD);
        timeView.setTextSize(16);
        timeView.setPadding(16, 0, 0, 0);
        timeView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        timeView.setGravity(Gravity.END);
        timeView.setText("00:00/00:00");

        LinearLayout trailingInfo = new LinearLayout(this);
        trailingInfo.setOrientation(LinearLayout.VERTICAL);
        trailingInfo.setGravity(Gravity.END);
        trailingInfo.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        trailingInfo.addView(queueView);
        trailingInfo.addView(timeView);

        controlsRow.addView(previousButton);
        controlsRow.addView(stopButton);
        controlsRow.addView(nextButton);
        controlsRow.addView(trailingInfo);

        overlay.addView(titleView);
        overlay.addView(nextSongView);
        overlay.addView(controlsRow);
        rootContainer.addView(overlay);

        previousButton.requestFocus();
    }

    private String getNextSongTitle(int currentIndex) {
        if (videoQueue == null || videoQueue.isEmpty()) {
            return "-";
        }
        int nextIndex = currentIndex + 1;
        if (nextIndex >= videoQueue.size()) {
            return "-";
        }
        VideoItem nextItem = videoQueue.get(nextIndex);
        if (nextItem == null || nextItem.getTitle() == null || nextItem.getTitle().trim().isEmpty()) {
            return "-";
        }
        return nextItem.getTitle().trim();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void updateOverlay(String title, String nextTitle, int current, int total) {
        if (titleView != null) {
            titleView.setText(title == null ? "" : title);
        }
        if (nextSongView != null) {
            String safeNext = (nextTitle == null || nextTitle.isEmpty()) ? "-" : nextTitle;
            nextSongView.setText("Next: " + safeNext);
        }
        if (queueView != null) {
            queueView.setText(total > 0 ? current + "/" + total : "");
        }
    }

    private void updatePlaybackTimeView() {
        if (timeView == null) {
            return;
        }
        timeView.setText(formatSeconds(currentSecond) + "/" + formatSeconds(durationSecond));
    }

    private String formatSeconds(float value) {
        int totalSeconds = (int) Math.max(0, Math.floor(value));
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoRepository != null) {
            videoRepository.stopObservingVideoQueue();
        }
        if (rootContainer != null) {
            rootContainer.removeAllViews();
        }
        currentYouTubePlayer = null;
        youTubePlayerView = null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                playNextVideo();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                playPreviousVideo();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                stopPlayback();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                stopPlayback();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
