package net.harbroi.hikaraoke;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AppUpdateManager {
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/harbroi/HiKaraoke/releases/latest";
    private static final String LATEST_RELEASE_REDIRECT_URL = "https://github.com/harbroi/HiKaraoke/releases/latest";

    public interface UpdateCheckCallback {
        void onUpToDate();
        void onUpdateAvailable(ReleaseInfo releaseInfo);
        void onError(String message);
    }

    public static final class ReleaseInfo {
        public final long versionCode;
        public final String versionName;
        public final String apkDownloadUrl;

        ReleaseInfo(long versionCode, String versionName, String apkDownloadUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkDownloadUrl = apkDownloadUrl;
        }
    }

    public void checkForUpdates(Context context, UpdateCheckCallback callback) {
        new Thread(() -> {
            try {
                ReleaseInfo releaseInfo = fetchLatestRelease(context);
                String installedVersionName = getInstalledVersionName(context);
                if (compareSemanticVersions(releaseInfo.versionName, installedVersionName) <= 0) {
                    callback.onUpToDate();
                    return;
                }
                if (TextUtils.isEmpty(releaseInfo.apkDownloadUrl)) {
                    callback.onError(context.getString(R.string.update_no_apk_asset));
                    return;
                }
                callback.onUpdateAvailable(releaseInfo);
            } catch (Exception exception) {
                callback.onError(context.getString(R.string.update_check_failed, exception.getMessage()));
            }
        }).start();
    }

    public boolean startApkDownload(Context context, ReleaseInfo releaseInfo) {
        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            return false;
        }

        String apkFileName = "HIKaraoke_v" + sanitizeFileName(releaseInfo.versionName) + ".apk";
        Uri downloadUri = Uri.parse(releaseInfo.apkDownloadUrl);
        DownloadManager.Request request = new DownloadManager.Request(downloadUri)
                .setTitle(context.getString(R.string.app_name) + " " + releaseInfo.versionName)
                .setDescription(context.getString(R.string.update_download_started))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        apkFileName
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);
        request.addRequestHeader("Accept", "application/octet-stream");

        try {
            long downloadId = downloadManager.enqueue(request);
            registerInstallReceiver(context, downloadId, apkFileName);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void registerInstallReceiver(Context context, long downloadId, String apkFileName) {
        Context appContext = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                long completedDownloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (completedDownloadId != downloadId) {
                    return;
                }
                try {
                    appContext.unregisterReceiver(this);
                } catch (IllegalArgumentException ignored) {
                }
                triggerApkInstall(appContext, apkFileName);
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void triggerApkInstall(Context context, String apkFileName) {
        File apkFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), apkFileName);
        if (!apkFile.exists()) {
            return;
        }

        Uri apkUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                apkFile
        );

        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        try {
            context.startActivity(installIntent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private ReleaseInfo fetchLatestRelease(Context context) throws Exception {
        try {
            JSONObject releaseJson = fetchJsonObject(LATEST_RELEASE_URL, context);
            return toReleaseInfo(releaseJson);
        } catch (IOException exception) {
            if (!isNotFoundForPrivateRepo(exception.getMessage())) {
                throw exception;
            }
        }

        String latestTag = resolveLatestReleaseTagFromRedirect(context);
        long versionCode = parseVersionCodeFromTag(latestTag);
        if (versionCode < 0L) {
            throw new IllegalStateException("Latest release is missing a valid version code.");
        }
        String normalizedTag = latestTag.startsWith("v") || latestTag.startsWith("V")
                ? latestTag.substring(1)
                : latestTag;
        String apkUrl = "https://github.com/harbroi/HiKaraoke/releases/download/"
                + latestTag
                + "/HIKaraoke_v"
                + normalizedTag
                + ".apk";
        return new ReleaseInfo(versionCode, normalizedTag, apkUrl);
    }

    private ReleaseInfo toReleaseInfo(JSONObject releaseJson) {
        String tagName = releaseJson.optString("tag_name", "").trim();
        long versionCode = parseVersionCodeFromRelease(releaseJson, tagName);
        if (versionCode < 0L) {
            throw new IllegalStateException("Latest release is missing a valid version code.");
        }

        JSONArray assets = releaseJson.optJSONArray("assets");
        String apkUrl = null;
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String assetName = asset.optString("name", "");
                String browserDownloadUrl = asset.optString("browser_download_url", "");
                if (assetName.toLowerCase().endsWith(".apk") && !TextUtils.isEmpty(browserDownloadUrl)) {
                    apkUrl = browserDownloadUrl;
                    break;
                }
            }
        }

        String versionName = releaseJson.optString("name", "").trim();
        if (versionName.isEmpty()) {
            versionName = tagName;
        }
        return new ReleaseInfo(versionCode, normalizeVersion(versionName), apkUrl);
    }

    private JSONObject fetchJsonObject(String urlString, Context context) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", context.getPackageName());

            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (responseStream == null) {
                throw new IllegalStateException("Empty response from GitHub.");
            }

            String responseBody = readStream(responseStream);
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("GitHub returned " + responseCode + ".");
            }
            return new JSONObject(responseBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String resolveLatestReleaseTagFromRedirect(Context context) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(LATEST_RELEASE_REDIRECT_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", context.getPackageName());

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_MOVED_PERM
                    && responseCode != HttpURLConnection.HTTP_MOVED_TEMP
                    && responseCode != HttpURLConnection.HTTP_SEE_OTHER) {
                throw new IllegalStateException("Unable to resolve latest release.");
            }

            String location = connection.getHeaderField("Location");
            if (TextUtils.isEmpty(location)) {
                throw new IllegalStateException("Latest release location is missing.");
            }

            int marker = location.lastIndexOf("/tag/");
            if (marker < 0) {
                throw new IllegalStateException("Latest release tag could not be resolved.");
            }
            return location.substring(marker + 5).trim();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readStream(InputStream inputStream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private String getInstalledVersionName(Context context) throws Exception {
        PackageInfo packageInfo;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageInfo = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
            );
        } else {
            packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        }
        String versionName = packageInfo.versionName;
        return versionName == null ? "0" : versionName;
    }

    private long parseVersionCodeFromRelease(JSONObject releaseJson, String tagName) {
        String body = releaseJson.optString("body", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("versionCode\\s+([0-9]+)")
                .matcher(body);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }

        if (TextUtils.isEmpty(tagName)) {
            return -1L;
        }
        String normalized = tagName.startsWith("v") || tagName.startsWith("V")
                ? tagName.substring(1)
                : tagName;
        String[] parts = normalized.split("\\.");
        long computed = 0L;
        for (String part : parts) {
            if (part.isEmpty()) {
                return -1L;
            }
            try {
                computed = (computed * 100L) + Long.parseLong(part);
            } catch (NumberFormatException exception) {
                return -1L;
            }
        }
        return computed;
    }

    private long parseVersionCodeFromTag(String tagName) {
        return parseVersionCodeFromRelease(new JSONObject(), tagName);
    }

    private boolean isNotFoundForPrivateRepo(String message) {
        return message != null && message.contains("404");
    }

    private int compareSemanticVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left).split("\\.");
        String[] rightParts = normalizeVersion(right).split("\\.");
        int partCount = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < partCount; i++) {
            int leftValue = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private String normalizeVersion(String version) {
        if (version == null) {
            return "0";
        }
        String trimmed = version.trim();
        if (trimmed.regionMatches(true, 0, "HIKaraoke", 0, "HIKaraoke".length())) {
            trimmed = trimmed.substring("HIKaraoke".length()).trim();
        }
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? "0" : trimmed;
    }

    private int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
