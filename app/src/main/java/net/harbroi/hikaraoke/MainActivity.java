package net.harbroi.hikaraoke;

import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String YOUTUBE_API_KEY = "AIzaSyCa7rzV2uEuIPFEZcAwxJBQ6sYySB-2fzk";
    private final List<YouTubeVideo> currentResults = new ArrayList<>();
    private YouTubeVideoAdapter listAdapter;

    private EditText queryInput;
    private ProgressBar loadingIndicator;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        queryInput = findViewById(R.id.queryInput);
        queryInput.requestFocus();
        ImageButton backButton = findViewById(R.id.backButton);
        ImageButton searchButton = findViewById(R.id.searchButton);
        ListView resultsList = findViewById(R.id.resultsList);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        statusText = findViewById(R.id.statusText);

        List<YouTubeVideoAdapter.VideoItemData> adapterItems = new ArrayList<>();
        listAdapter = new YouTubeVideoAdapter(this, adapterItems, this::addVideoItemToFirebaseQueue);
        resultsList.setAdapter(listAdapter);

        backButton.setOnClickListener(v -> navigateToHome());
        searchButton.setOnClickListener(v -> searchVideos());
        queryInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchVideos();
                return true;
            }
            return false;
        });

    }

    private void navigateToHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void addVideoItemToFirebaseQueue(YouTubeVideoAdapter.VideoItemData item) {
        for (YouTubeVideo video : currentResults) {
            if (video.videoId.equals(item.videoId)) {
                addVideoToFirebaseQueue(video);
                return;
            }
        }
    }

    private void searchVideos() {
        String userQuery = queryInput.getText().toString().trim();
        if (TextUtils.isEmpty(userQuery)) {
            queryInput.setError("Enter a search term");
            return;
        }

        hideKeyboard();
        setLoading(true, getString(R.string.searching));
        new Thread(() -> {
            try {
                String finalQuery = userQuery + " karaoke";
                List<YouTubeVideo> videos = fetchYouTubeVideos(finalQuery);
                runOnUiThread(() -> updateSearchResults(videos));
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false, getString(R.string.search_results_label));
                    Toast.makeText(this, "Search failed: " + exception.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void hideKeyboard() {
        View focusedView = getCurrentFocus();
        if (focusedView == null) {
            focusedView = queryInput;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
        }
        queryInput.clearFocus();
    }

    private List<YouTubeVideo> fetchYouTubeVideos(String query) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String searchUrl = "https://www.googleapis.com/youtube/v3/search"
                + "?part=snippet"
                + "&type=video"
                + "&maxResults=30"
                + "&q=" + encodedQuery
                + "&key=" + YOUTUBE_API_KEY;

        String searchResponse = readUrl(searchUrl);
        JSONObject searchJson = new JSONObject(searchResponse);
        JSONArray searchItems = searchJson.optJSONArray("items");
        if (searchItems == null || searchItems.length() == 0) {
            return new ArrayList<>();
        }

        List<String> videoIds = new ArrayList<>();
        for (int i = 0; i < searchItems.length(); i++) {
            JSONObject item = searchItems.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject idObject = item.optJSONObject("id");
            if (idObject == null) {
                continue;
            }
            String videoId = idObject.optString("videoId");
            if (!TextUtils.isEmpty(videoId)) {
                videoIds.add(videoId);
            }
        }

        if (videoIds.isEmpty()) {
            return new ArrayList<>();
        }

        String idsParam = TextUtils.join(",", videoIds);
        String detailsUrl = "https://www.googleapis.com/youtube/v3/videos"
                + "?part=snippet,contentDetails,status"
                + "&id=" + URLEncoder.encode(idsParam, StandardCharsets.UTF_8.name())
                + "&key=" + YOUTUBE_API_KEY;

        String detailsResponse = readUrl(detailsUrl);
        JSONObject detailsJson = new JSONObject(detailsResponse);
        JSONArray detailsItems = detailsJson.optJSONArray("items");

        List<YouTubeVideo> videos = new ArrayList<>();
        if (detailsItems == null) {
            return videos;
        }

        for (int i = 0; i < detailsItems.length(); i++) {
            JSONObject videoObject = detailsItems.optJSONObject(i);
            if (videoObject == null) {
                continue;
            }

            String videoId = videoObject.optString("id");
            JSONObject snippet = videoObject.optJSONObject("snippet");
            JSONObject contentDetails = videoObject.optJSONObject("contentDetails");
            JSONObject status = videoObject.optJSONObject("status");

            if (TextUtils.isEmpty(videoId) || snippet == null || contentDetails == null || !isEmbeddable(status)) {
                continue;
            }

            String title = snippet.optString("title", "");
            String description = snippet.optString("description", "");
            String durationIso = contentDetails.optString("duration", "PT0S");
            long durationMs = parseIsoDurationToMs(durationIso);
            String thumbnailUrl = extractThumbnailUrl(snippet);

            videos.add(new YouTubeVideo(videoId, title, description, durationMs, thumbnailUrl));
        }

        return videos;
    }

    private void updateSearchResults(List<YouTubeVideo> videos) {
        currentResults.clear();
        currentResults.addAll(videos);

        List<YouTubeVideoAdapter.VideoItemData> labels = new ArrayList<>();
        for (YouTubeVideo video : videos) {
            String formattedDuration = formatDuration(video.durationMs);
            labels.add(new YouTubeVideoAdapter.VideoItemData(video.videoId, video.title, video.thumbnailUrl, formattedDuration));
        }

        listAdapter.clear();
        listAdapter.addAll(labels);
        listAdapter.notifyDataSetChanged();

        if (videos.isEmpty()) {
            setLoading(false, getString(R.string.empty_results));
        } else {
            setLoading(false, getString(R.string.search_results_label));
        }
    }

    private void addVideoToFirebaseQueue(YouTubeVideo video) {
        VideoQueueItem queueItem = new VideoQueueItem(video.videoId, video.title, video.description, video.durationMs, video.thumbnailUrl);
        FirebaseManager.getInstance().addVideoToQueue(queueItem, (error, committed) -> {
            if (error != null || !committed) {
                Toast.makeText(MainActivity.this, getString(R.string.firebase_save_error), Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(MainActivity.this, getString(R.string.firebase_saved), Toast.LENGTH_SHORT).show();
        });
    }

    private void setLoading(boolean isLoading, String status) {
        loadingIndicator.setVisibility(isLoading ? ProgressBar.VISIBLE : ProgressBar.GONE);
        statusText.setText(status);
    }

    private String readUrl(String urlString) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            applyAndroidApiHeaders(connection);

            int code = connection.getResponseCode();
            InputStream inputStream;
            if (code >= 200 && code < 300) {
                inputStream = connection.getInputStream();
            } else {
                inputStream = connection.getErrorStream();
            }

            if (inputStream == null) {
                throw new JSONException("YouTube response is empty");
            }

            StringBuilder responseBuilder = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }
            reader.close();

            if (code < 200 || code >= 300) {
                String reason = extractYouTubeErrorReason(responseBuilder.toString());
                throw new JSONException("YouTube API error (" + code + "): " + reason);
            }

            return responseBuilder.toString();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void applyAndroidApiHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("X-Android-Package", getPackageName());
        String certSha1 = getSigningCertSha1();
        if (!TextUtils.isEmpty(certSha1)) {
            connection.setRequestProperty("X-Android-Cert", certSha1);
        }
    }

    private String getSigningCertSha1() {
        try {
            android.content.pm.PackageManager packageManager = getPackageManager();
            android.content.pm.PackageInfo packageInfo;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo = packageManager.getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (packageInfo.signingInfo == null) {
                    return null;
                }

                android.content.pm.Signature[] signatures = packageInfo.signingInfo.hasMultipleSigners()
                        ? packageInfo.signingInfo.getApkContentsSigners()
                        : packageInfo.signingInfo.getSigningCertificateHistory();

                if (signatures == null || signatures.length == 0) {
                    return null;
                }
                return toSha1Fingerprint(signatures[0].toByteArray());
            }

            packageInfo = packageManager.getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (packageInfo.signatures == null || packageInfo.signatures.length == 0) {
                return null;
            }
            return toSha1Fingerprint(packageInfo.signatures[0].toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toSha1Fingerprint(byte[] certBytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(certBytes);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format(Locale.US, "%02X", digest[i]));
        }
        return builder.toString();
    }

    private String extractYouTubeErrorReason(String responseBody) {
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONObject error = root.optJSONObject("error");
            if (error == null) {
                return responseBody;
            }

            String message = error.optString("message", "");
            JSONArray details = error.optJSONArray("errors");
            if (details != null && details.length() > 0) {
                JSONObject first = details.optJSONObject(0);
                if (first != null) {
                    String reason = first.optString("reason", "");
                    if (!TextUtils.isEmpty(reason) && !TextUtils.isEmpty(message)) {
                        return reason + ": " + message;
                    }
                    if (!TextUtils.isEmpty(reason)) {
                        return reason;
                    }
                }
            }

            if (!TextUtils.isEmpty(message)) {
                return message;
            }
            return responseBody;
        } catch (JSONException ignored) {
            return responseBody;
        }
    }

    private boolean isEmbeddable(JSONObject status) {
        return status != null && status.optBoolean("embeddable", false);
    }

    private long parseIsoDurationToMs(String isoDuration) {
        long hours = 0;
        long minutes = 0;
        long seconds = 0;

        Matcher matcher = Pattern.compile("(\\d+)([HMS])").matcher(isoDuration);
        while (matcher.find()) {
            String valueText = matcher.group(1);
            if (valueText == null) {
                continue;
            }
            long value = Long.parseLong(valueText);
            String unit = matcher.group(2);
            if ("H".equals(unit)) {
                hours = value;
            } else if ("M".equals(unit)) {
                minutes = value;
            } else if ("S".equals(unit)) {
                seconds = value;
            }
        }

        return ((hours * 3600) + (minutes * 60) + seconds) * 1000L;
    }

    private String extractThumbnailUrl(JSONObject snippet) {
        try {
            JSONObject thumbnails = snippet.optJSONObject("thumbnails");
            if (thumbnails == null) {
                return "";
            }
            JSONObject medium = thumbnails.optJSONObject("medium");
            if (medium != null) {
                return medium.optString("url", "");
            }
            JSONObject default_ = thumbnails.optJSONObject("default");
            if (default_ != null) {
                return default_.optString("url", "");
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static class YouTubeVideo {
        final String videoId;
        final String title;
        final String description;
        final long durationMs;
        final String thumbnailUrl;

        YouTubeVideo(String videoId, String title, String description, long durationMs, String thumbnailUrl) {
            this.videoId = videoId;
            this.title = title;
            this.description = description;
            this.durationMs = durationMs;
            this.thumbnailUrl = thumbnailUrl;
        }
    }
}
