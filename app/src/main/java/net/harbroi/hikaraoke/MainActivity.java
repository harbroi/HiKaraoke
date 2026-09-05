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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

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
        String searchUrl = "https://www.youtube.com/results?search_query=" + encodedQuery + "&hl=en";
        String searchHtml = readUrl(searchUrl);
        return extractVideoResultsFromSearchHtml(searchHtml);
    }

    private List<YouTubeVideo> extractVideoResultsFromSearchHtml(String html) {
        List<YouTubeVideo> videos = new ArrayList<>();
        LinkedHashSet<String> seenVideoIds = new LinkedHashSet<>();
        String marker = "\"videoRenderer\"";
        int searchStart = 0;

        while (searchStart < html.length()) {
            int rendererIndex = html.indexOf(marker, searchStart);
            if (rendererIndex < 0) {
                break;
            }

            int objectStart = html.indexOf('{', rendererIndex + marker.length());
            if (objectStart < 0) {
                break;
            }

            int objectEnd = findMatchingBrace(html, objectStart);
            if (objectEnd < 0) {
                break;
            }

            String rendererBlock = html.substring(objectStart, objectEnd + 1);
            if (rendererBlock.contains("shorts") || rendererBlock.contains("reelItemRenderer") || rendererBlock.contains("shortsVideoRenderer")) {
                searchStart = objectEnd + 1;
                continue;
            }

            Matcher videoIdMatcher = Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"").matcher(rendererBlock);
            if (!videoIdMatcher.find()) {
                searchStart = objectEnd + 1;
                continue;
            }

            String videoId = videoIdMatcher.group(1);
            if (!seenVideoIds.add(videoId)) {
                searchStart = objectEnd + 1;
                continue;
            }

            String title = extractJsonStringValue(rendererBlock, "\"title\"", "\"text\"");
            String thumbnailUrl = extractJsonStringValue(rendererBlock, "\"thumbnail\"", "\"url\"");
            String durationText = extractJsonStringValue(rendererBlock, "\"lengthText\"", "\"simpleText\"");
            long durationMs = parseDurationTextToMs(durationText);

            if (TextUtils.isEmpty(title) || TextUtils.isEmpty(thumbnailUrl) || durationMs <= 0) {
                searchStart = objectEnd + 1;
                continue;
            }

            if (durationMs < 60000L) {
                searchStart = objectEnd + 1;
                continue;
            }

            videos.add(new YouTubeVideo(videoId, title, "", durationMs, thumbnailUrl));
            searchStart = objectEnd + 1;
        }

        return videos;
    }

    private int findMatchingBrace(String text, int openBraceIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openBraceIndex; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    private String extractJsonStringValue(String json, String parentKey, String childKey) {
        int parentIndex = json.indexOf(parentKey);
        if (parentIndex < 0) {
            return "";
        }
        int childIndex = json.indexOf(childKey, parentIndex);
        if (childIndex < 0) {
            return "";
        }
        int valueStart = json.indexOf('"', childIndex + childKey.length());
        if (valueStart < 0) {
            return "";
        }
        int valueEnd = valueStart + 1;
        boolean escaped = false;
        StringBuilder value = new StringBuilder();
        while (valueEnd < json.length()) {
            char current = json.charAt(valueEnd);
            if (escaped) {
                value.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                break;
            } else {
                value.append(current);
            }
            valueEnd++;
        }
        return decodeJsonString(value.toString());
    }

    private String decodeJsonString(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        return value.replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\n", " ");
    }

    private long parseDurationTextToMs(String durationText) {
        if (TextUtils.isEmpty(durationText) || "LIVE".equalsIgnoreCase(durationText)) {
            return 0L;
        }

        String normalized = durationText.trim();
        String[] parts = normalized.split(":");
        long totalMs = 0L;
        try {
            if (parts.length == 2) {
                totalMs = (Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1])) * 1000L;
            } else if (parts.length == 3) {
                totalMs = (Long.parseLong(parts[0]) * 3600L + Long.parseLong(parts[1]) * 60L + Long.parseLong(parts[2])) * 1000L;
            }
        } catch (NumberFormatException ignored) {
            return 0L;
        }
        return totalMs;
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
            applyHttpHeaders(connection);

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

    private String postJson(String urlString, String body) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            connection.setRequestProperty("Referer", "https://www.youtube.com/");
            connection.setRequestProperty("X-YouTube-Client-Name", "1");
            connection.setRequestProperty("X-YouTube-Client-Version", "2.20240903.01.00");

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);

            int code = connection.getResponseCode();
            InputStream inputStream = (code >= 200 && code < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();

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

    private void applyHttpHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
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
