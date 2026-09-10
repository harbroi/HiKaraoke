package net.harbroi.hikaraoke;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {

    private YouTubeVideoAdapter listAdapter;
    private ProgressBar loadingQueue;
    private TextView queueLabel;
    private ValueEventListener queueListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        ImageButton searchButton = findViewById(R.id.searchButton);
        TextView clearAllButton = findViewById(R.id.clearAllButton);
        ListView queueList = findViewById(R.id.queueList);
        loadingQueue = findViewById(R.id.loadingQueue);
        queueLabel = findViewById(R.id.queueLabel);
        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);

        List<YouTubeVideoAdapter.VideoItemData> adapterItems = new ArrayList<>();
        listAdapter = new YouTubeVideoAdapter(this, adapterItems);
        queueList.setAdapter(listAdapter);

        searchButton.setOnClickListener(v -> navigateToSearch());
        clearAllButton.setOnClickListener(v -> showClearAllDialog());
        NavigationHelper.setupBottomNavigation(this, bottomNavigation, R.id.navigation_main);
        queueList.setOnItemLongClickListener((parent, view, position, id) -> {
            YouTubeVideoAdapter.VideoItemData item = listAdapter.getItem(position);
            if (item == null || item.firebaseKey == null || item.firebaseKey.isEmpty()) {
                Toast.makeText(this, R.string.queue_remove_error, Toast.LENGTH_SHORT).show();
                return true;
            }
            showRemoveDialog(item);
            return true;
        });

        loadQueueFromFirebase();
    }

    private void navigateToSearch() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void loadQueueFromFirebase() {
        setLoadingQueue(true);
        queueListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<YouTubeVideoAdapter.VideoItemData> items = new ArrayList<>();

                if (snapshot.exists()) {
                    List<DataSnapshot> children = new ArrayList<>();
                    for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                        children.add(childSnapshot);
                    }

                    // Show newest queue entries first.
                    for (int i = children.size() - 1; i >= 0; i--) {
                        DataSnapshot childSnapshot = children.get(i);
                        try {
                            VideoQueueItem queueItem = childSnapshot.getValue(VideoQueueItem.class);
                            String firebaseKey = childSnapshot.getKey();

                            if (queueItem != null && firebaseKey != null) {
                                String formattedDuration = formatDuration(queueItem.duration);
                                items.add(new YouTubeVideoAdapter.VideoItemData(firebaseKey, queueItem.videoId, queueItem.title, queueItem.thumbnailUrl, formattedDuration));
                            }
                        } catch (Exception e) {
                            // Ignore malformed items
                        }
                    }
                }

                listAdapter.clear();
                listAdapter.addAll(items);
                listAdapter.notifyDataSetChanged();
                updateQueueLabel(items.size());
                setLoadingQueue(false);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                setLoadingQueue(false);
                Toast.makeText(HomeActivity.this, "Failed to load queue: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        };
        FirebaseManager.getInstance().observeQueue(queueListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (queueListener != null) {
            FirebaseManager.getInstance().removeObserver(queueListener);
            queueListener = null;
        }
    }

    private void showRemoveDialog(YouTubeVideoAdapter.VideoItemData item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.queue_remove_title)
                .setMessage(getString(R.string.queue_remove_message, item.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.queue_remove_confirm, (dialog, which) -> removeQueueItem(item.firebaseKey))
                .show();
    }

    private void showClearAllDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.queue_clear_all_title)
                .setMessage(R.string.queue_clear_all_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.queue_clear_all_confirm, (dialog, which) -> clearAllQueue())
                .show();
    }

    private void removeQueueItem(String firebaseKey) {
        FirebaseManager.getInstance().removeVideoFromQueue(firebaseKey, (error, committed) -> {
            if (error != null) {
                Toast.makeText(HomeActivity.this, getString(R.string.queue_remove_error_with_reason, error.getMessage()), Toast.LENGTH_SHORT).show();
                return;
            }

            if (!committed) {
                Toast.makeText(HomeActivity.this, R.string.queue_remove_error, Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(HomeActivity.this, R.string.queue_removed, Toast.LENGTH_SHORT).show();
        });
    }

    private void clearAllQueue() {
        FirebaseManager.getInstance().clearQueue((error, committed) -> {
            if (error != null) {
                Toast.makeText(HomeActivity.this, getString(R.string.queue_remove_error_with_reason, error.getMessage()), Toast.LENGTH_SHORT).show();
                return;
            }

            if (!committed) {
                Toast.makeText(HomeActivity.this, R.string.queue_remove_error, Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(HomeActivity.this, R.string.queue_cleared, Toast.LENGTH_SHORT).show();
        });
    }

    private void setLoadingQueue(boolean isLoading) {
        loadingQueue.setVisibility(isLoading ? ProgressBar.VISIBLE : ProgressBar.GONE);
    }

    private void updateQueueLabel(int queueCount) {
        queueLabel.setText(getResources().getQuantityString(R.plurals.queue_label_with_count, queueCount, queueCount));
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
