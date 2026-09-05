package net.harbroi.hikaraoketv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView;

import java.util.List;

public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";
    private static final String EXTRA_QUEUE_INDEX = "queue_index";

    private YouTubePlayerView youtubePlayerView;
    private YouTubePlayer youTubePlayer;
    private FirebaseVideoRepository videoRepository;
    private List<VideoItem> videoQueue;
    private int currentQueueIndex = 0;
    private boolean isPlayerInitialized = false;

    public static void startActivity(Context context) {
        Intent intent = new Intent(context, VideoPlayerActivity.class);
        context.startActivity(intent);
    }

    public static void startActivity(Context context, int queueIndex) {
        Intent intent = new Intent(context, VideoPlayerActivity.class);
        intent.putExtra(EXTRA_QUEUE_INDEX, queueIndex);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        youtubePlayerView = new YouTubePlayerView(this);
        youtubePlayerView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        FrameLayout root = new FrameLayout(this);
        root.addView(youtubePlayerView);
        setContentView(root);

        getLifecycle().addObserver(youtubePlayerView);
        videoRepository = new FirebaseVideoRepository(this);

        if (getIntent() != null && getIntent().hasExtra(EXTRA_QUEUE_INDEX)) {
            currentQueueIndex = getIntent().getIntExtra(EXTRA_QUEUE_INDEX, 0);
        }

        IFramePlayerOptions options = new IFramePlayerOptions.Builder(this)
                .controls(1)
                .autoplay(1)
                .build();

        youtubePlayerView.initialize(new AbstractYouTubePlayerListener() {
            @Override
            public void onReady(@NonNull YouTubePlayer initializedYouTubePlayer) {
                youTubePlayer = initializedYouTubePlayer;
                isPlayerInitialized = true;
                Log.d(TAG, "YouTube Player Ready");
                loadVideosFromFirebase();
            }

            @Override
            public void onStateChange(@NonNull YouTubePlayer youTubePlayer, @NonNull PlayerConstants.PlayerState state) {
                if (state == PlayerConstants.PlayerState.ENDED) {
                    Log.d(TAG, "Video ended, playing next...");
                    playNextVideo();
                }
            }

            @Override
            public void onError(@NonNull YouTubePlayer youTubePlayer, @NonNull PlayerConstants.PlayerError error) {
                Log.e(TAG, "Player error: " + error.name());
                Toast.makeText(VideoPlayerActivity.this,
                        "Error playing video: " + error.name(),
                        Toast.LENGTH_SHORT).show();
                playNextVideo();
            }
        }, options);
    }

    private void loadVideosFromFirebase() {
        videoRepository.loadVideoQueue(new FirebaseVideoRepository.VideoQueueCallback() {
            @Override
            public void onVideosLoaded(List<VideoItem> videos) {
                videoQueue = videos;
                Log.d(TAG, "Loaded " + videoQueue.size() + " videos from Firebase");
                if (videoQueue != null && !videoQueue.isEmpty()) {
                    playVideoAt(currentQueueIndex);
                } else {
                    Toast.makeText(VideoPlayerActivity.this,
                            "No videos available",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Firebase error: " + errorMessage);
                Toast.makeText(VideoPlayerActivity.this,
                        "Failed to load videos: " + errorMessage,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void playVideoAt(int index) {
        if (videoQueue == null || videoQueue.isEmpty()) {
            Log.w(TAG, "Video queue is empty");
            return;
        }

        if (index < 0 || index >= videoQueue.size()) {
            Log.d(TAG, "End of queue reached");
            onPlaybackQueueEnded();
            return;
        }

        currentQueueIndex = index;
        VideoItem video = videoQueue.get(index);

        Log.d(TAG, "Playing video at index " + index + ": " + video.getVideoId());

        if (isPlayerInitialized && youTubePlayer != null) {
            youTubePlayer.loadVideo(video.getVideoId(), 0f);
        }
    }

    private void playNextVideo() {
        playVideoAt(currentQueueIndex + 1);
    }

    private void onPlaybackQueueEnded() {
        Log.d(TAG, "Playback queue ended");
        Toast.makeText(this, "End of queue reached", Toast.LENGTH_SHORT).show();

        if (youTubePlayer != null) {
            youTubePlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (youtubePlayerView != null) {
            youtubePlayerView.release();
        }
    }
}
