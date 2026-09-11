package net.harbroi.hikaraoke;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import java.util.Locale;
import java.util.Random;

public class FirebaseManager {
    private static final String USERS_PATH = "users";
    private static final String QUEUE_PATH = "videoQueue";
    private static final String ACCESS_CODE_PATH = "accessCode";
    private static final String PUBLIC_ACCESS_CODES_PATH = "publicAccessCodes";
    private static final int ACCESS_CODE_LENGTH = 8;
    private static final String ACCESS_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static FirebaseManager instance;
    private final FirebaseDatabase database;
    private final Random random = new Random();

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
                    } catch (NumberFormatException ignored) {
                    }
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

    public void loadCurrentUserAccessCode(@NonNull final AccessCodeCallback callback) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            callback.onResult(null, "User not signed in");
            return;
        }

        DatabaseReference userRef = getUserRef(currentUser.getUid());
        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String accessCode = snapshot.child(ACCESS_CODE_PATH).getValue(String.class);
                if (accessCode == null || accessCode.trim().isEmpty()) {
                    assignUniqueAccessCodeForUser(currentUser.getUid(), callback, 0);
                    return;
                }
                String normalizedCode = normalizeAccessCode(accessCode);
                if (normalizedCode != null && !normalizedCode.equals(accessCode.trim().toUpperCase(Locale.US))) {
                    getUserRef(currentUser.getUid()).child(ACCESS_CODE_PATH).setValue(normalizedCode);
                }
                if (normalizedCode != null && !normalizedCode.isEmpty()) {
                    database.getReference(PUBLIC_ACCESS_CODES_PATH).child(normalizedCode).setValue(currentUser.getUid());
                }
                callback.onResult(normalizedCode, null);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.onResult(null, error.getMessage());
            }
        });
    }

    public void lookupUserByAccessCode(@NonNull String enteredCode, @NonNull final UserLookupCallback callback) {
        String normalizedCode = normalizeAccessCode(enteredCode);
        if (normalizedCode == null) {
            callback.onUserFound(null, null, "Invalid code. Use 8 alphanumeric characters.");
            return;
        }

        database.getReference(PUBLIC_ACCESS_CODES_PATH)
                .child(normalizedCode)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String uid = snapshot.getValue(String.class);
                        if (uid == null || uid.trim().isEmpty()) {
                            callback.onUserFound(null, normalizedCode, "No user found for this code.");
                            return;
                        }
                        callback.onUserFound(uid, normalizedCode, null);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        callback.onUserFound(null, normalizedCode, error.getMessage());
                    }
                });
    }

    @Nullable
    public String normalizeAccessCode(@Nullable String accessCode) {
        if (accessCode == null) {
            return null;
        }
        String normalized = accessCode.trim().toUpperCase(Locale.US);
        if (normalized.length() != ACCESS_CODE_LENGTH) {
            return null;
        }
        if (!normalized.matches("^[A-Z0-9]+$")) {
            return null;
        }
        return normalized;
    }

    private DatabaseReference getUserQueueRef() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            return null;
        }
        return getUserQueueRef(currentUser.getUid());
    }

    public DatabaseReference getUserQueueRef(@NonNull String userUid) {
        return database.getReference(USERS_PATH).child(userUid).child(QUEUE_PATH);
    }

    public DatabaseReference getUserRef(@NonNull String userUid) {
        return database.getReference(USERS_PATH).child(userUid);
    }

    private void assignUniqueAccessCodeForUser(@NonNull String userUid, @NonNull final AccessCodeCallback callback, int attempt) {
        if (attempt >= 20) {
            callback.onResult(null, "Unable to generate a unique access code.");
            return;
        }

        final String candidate = generateAccessCode();
        database.getReference(PUBLIC_ACCESS_CODES_PATH)
                .child(candidate)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            assignUniqueAccessCodeForUser(userUid, callback, attempt + 1);
                            return;
                        }

                        getUserRef(userUid).child(ACCESS_CODE_PATH).setValue(candidate)
                                .addOnCompleteListener(task -> {
                                    if (!task.isSuccessful()) {
                                        callback.onResult(null, task.getException() != null ? task.getException().getMessage() : "Failed to save access code.");
                                        return;
                                    }
                                    database.getReference(PUBLIC_ACCESS_CODES_PATH)
                                            .child(candidate)
                                            .setValue(userUid)
                                            .addOnCompleteListener(publicTask -> {
                                                if (!publicTask.isSuccessful()) {
                                                    callback.onResult(null, publicTask.getException() != null ? publicTask.getException().getMessage() : "Failed to save public access code.");
                                                    return;
                                                }
                                                callback.onResult(candidate, null);
                                            });
                                });
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        callback.onResult(null, error.getMessage());
                    }
                });
    }

    private String generateAccessCode() {
        StringBuilder builder = new StringBuilder(ACCESS_CODE_LENGTH);
        for (int i = 0; i < ACCESS_CODE_LENGTH; i++) {
            int index = random.nextInt(ACCESS_CODE_ALPHABET.length());
            builder.append(ACCESS_CODE_ALPHABET.charAt(index));
        }
        return builder.toString();
    }

    public interface CompletionListener {
        void onComplete(DatabaseError error, boolean committed);
    }

    public interface AccessCodeCallback {
        void onResult(@Nullable String accessCode, @Nullable String errorMessage);
    }

    public interface UserLookupCallback {
        void onUserFound(@Nullable String userUid, @Nullable String accessCode, @Nullable String errorMessage);
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
