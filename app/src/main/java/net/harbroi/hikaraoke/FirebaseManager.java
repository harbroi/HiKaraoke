package net.harbroi.hikaraoke;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class FirebaseManager {
    private static final String USERS_PATH = "users";
    private static final String QUEUE_PATH = "videoQueue";
    private static FirebaseManager instance;
    private final FirebaseDatabase database;

    private FirebaseManager() {
        database = FirebaseDatabase.getInstance();
    }

    public static synchronized FirebaseManager getInstance() {
        if (instance == null) {
            instance = new FirebaseManager();
        }
        return instance;
    }

    public void addVideoToQueue(VideoQueueItem item, CompletionListener listener) {
        DatabaseReference queueRef = getUserQueueRef();
        if (queueRef == null) {
            if (listener != null) {
                listener.onComplete(DatabaseError.fromException(new IllegalStateException("User not signed in")), false);
            }
            return;
        }
        queueRef.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                long nextIndex = 0;
                for (MutableData child : currentData.getChildren()) {
                    String key = child.getKey();
                    if (key == null) continue;
                    try {
                        long parsedKey = Long.parseLong(key);
                        if (parsedKey >= nextIndex) {
                            nextIndex = parsedKey + 1;
                        }
                    } catch (NumberFormatException ignored) {}
                }
                currentData.child(String.valueOf(nextIndex)).setValue(item);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                if (listener != null) {
                    listener.onComplete(error, committed);
                }
            }
        });
    }

    public void removeVideoFromQueue(String firebaseKey, CompletionListener listener) {
        DatabaseReference queueRef = getUserQueueRef();
        if (queueRef == null) {
            if (listener != null) {
                listener.onComplete(DatabaseError.fromException(new IllegalStateException("User not signed in")), false);
            }
            return;
        }
        queueRef.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                if (currentData.getValue() == null) {
                    return Transaction.success(currentData);
                }

                List<QueueEntry> remainingEntries = new ArrayList<>();
                boolean removed = false;

                for (MutableData child : currentData.getChildren()) {
                    String childKey = child.getKey();
                    if (childKey == null) continue;

                    if (childKey.equals(firebaseKey)) {
                        removed = true;
                        continue;
                    }

                    try {
                        remainingEntries.add(new QueueEntry(Integer.parseInt(childKey), child.getValue()));
                    } catch (NumberFormatException ignored) {
                        remainingEntries.add(new QueueEntry(Integer.MAX_VALUE, child.getValue()));
                    }
                }

                if (!removed) {
                    return Transaction.abort();
                }

                remainingEntries.sort(Comparator.comparingInt(entry -> entry.originalIndex));
                currentData.setValue(null);
                for (int i = 0; i < remainingEntries.size(); i++) {
                    currentData.child(String.valueOf(i)).setValue(remainingEntries.get(i).value);
                }

                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                if (listener != null) {
                    listener.onComplete(error, committed);
                }
            }
        });
    }

    public void clearQueue(CompletionListener listener) {
        DatabaseReference queueRef = getUserQueueRef();
        if (queueRef == null) {
            if (listener != null) {
                listener.onComplete(DatabaseError.fromException(new IllegalStateException("User not signed in")), false);
            }
            return;
        }
        queueRef.setValue(null, (error, ref) -> {
            if (listener != null) {
                listener.onComplete(error, error == null);
            }
        });
    }

    public void observeQueue(ValueEventListener listener) {
        DatabaseReference queueRef = getUserQueueRef();
        if (queueRef == null) {
            listener.onCancelled(DatabaseError.fromException(new IllegalStateException("User not signed in")));
            return;
        }
        queueRef.addValueEventListener(listener);
    }

    public void removeObserver(ValueEventListener listener) {
        DatabaseReference queueRef = getUserQueueRef();
        if (queueRef == null) {
            return;
        }
        queueRef.removeEventListener(listener);
    }

    private DatabaseReference getUserQueueRef() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            return null;
        }
        return database.getReference(USERS_PATH).child(currentUser.getUid()).child(QUEUE_PATH);
    }

    public interface CompletionListener {
        void onComplete(DatabaseError error, boolean committed);
    }

    private static class QueueEntry {
        final int originalIndex;
        final Object value;

        QueueEntry(int originalIndex, Object value) {
            this.originalIndex = originalIndex;
            this.value = value;
        }
    }
}
