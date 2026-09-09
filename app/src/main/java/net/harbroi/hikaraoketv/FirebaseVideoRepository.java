package net.harbroi.hikaraoketv;

import android.content.Context;

import androidx.annotation.NonNull;

import net.harbroi.hikaraoke.R;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

public class FirebaseVideoRepository {

    private final FirebaseDatabase database;
    private final List<VideoItem> videoQueue = new ArrayList<>();
    private ValueEventListener liveQueueListener;

    public interface VideoQueueCallback {
        void onVideosLoaded(List<VideoItem> videos);
        void onError(String errorMessage);
    }

    public FirebaseVideoRepository(@NonNull Context context) {
        String databaseUrl = context.getString(R.string.firebase_database_url);
        this.database = FirebaseDatabase.getInstance(databaseUrl);
    }

    public void observeVideoQueue(@NonNull final VideoQueueCallback callback) {
        DatabaseReference videoQueueRef = getCurrentUserQueueRef();
        if (videoQueueRef == null) {
            callback.onError("User is not signed in");
            return;
        }
        stopObservingVideoQueue();
        liveQueueListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                videoQueue.clear();
                if (snapshot.exists()) {
                    for (DataSnapshot child : snapshot.getChildren()) {
                        VideoItem video = child.getValue(VideoItem.class);
                        if (video != null) {
                            videoQueue.add(video);
                        }
                    }
                }
                callback.onVideosLoaded(new ArrayList<>(videoQueue));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.onError("Failed to observe videos: " + error.getMessage());
            }
        };
        videoQueueRef.addValueEventListener(liveQueueListener);
    }

    public void stopObservingVideoQueue() {
        DatabaseReference videoQueueRef = getCurrentUserQueueRef();
        if (videoQueueRef != null && liveQueueListener != null) {
            videoQueueRef.removeEventListener(liveQueueListener);
            liveQueueListener = null;
        }
    }

    public void loadVideoQueue(@NonNull final VideoQueueCallback callback) {
        DatabaseReference videoQueueRef = getCurrentUserQueueRef();
        if (videoQueueRef == null) {
            callback.onError("User is not signed in");
            return;
        }
        videoQueueRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                videoQueue.clear();
                if (snapshot.exists()) {
                    for (DataSnapshot child : snapshot.getChildren()) {
                        VideoItem video = child.getValue(VideoItem.class);
                        if (video != null) {
                            videoQueue.add(video);
                        }
                    }
                }
                callback.onVideosLoaded(new ArrayList<>(videoQueue));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.onError("Failed to load videos: " + error.getMessage());
            }
        });
    }

    private DatabaseReference getCurrentUserQueueRef() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            return null;
        }
        return database.getReference("users").child(currentUser.getUid()).child("videoQueue");
    }
}
