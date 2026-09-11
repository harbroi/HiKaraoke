package net.harbroi.hikaraoke;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AccountActivity extends AppCompatActivity {
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/harbroi/HiKaraoke/releases/latest";
    private static final String LATEST_RELEASE_REDIRECT_URL = "https://github.com/harbroi/HiKaraoke/releases/latest";
    private MaterialButton checkUpdatesButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        TextView userNameText = findViewById(R.id.userNameText);
        TextView accessCodeText = findViewById(R.id.accessCodeText);
        TextView appInfoText = findViewById(R.id.appInfoText);
        TextView signOutButton = findViewById(R.id.signOutButton);
        checkUpdatesButton = findViewById(R.id.checkUpdatesButton);
        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);

        userNameText.setText(getCurrentUserName());
        appInfoText.setText(getString(R.string.account_app_info_value, getString(R.string.app_name), getAppVersionName(), "harbroi"));
        FirebaseManager.getInstance().loadCurrentUserAccessCode((accessCode, errorMessage) -> {
            if (errorMessage != null) {
                accessCodeText.setText(getString(R.string.account_access_code_error));
                return;
            }
            if (accessCode == null || accessCode.isEmpty()) {
                accessCodeText.setText(getString(R.string.account_access_code_error));
                return;
            }
            accessCodeText.setText(getString(R.string.account_access_code_value, accessCode));
        });
        signOutButton.setOnClickListener(v -> signOut());
        checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        NavigationHelper.setupBottomNavigation(this, bottomNavigation, R.id.navigation_account);
    }

    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getCurrentUserName() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            return getString(R.string.account_user_fallback);
        }
        if (!TextUtils.isEmpty(currentUser.getDisplayName())) {
            return currentUser.getDisplayName();
        }
        if (!TextUtils.isEmpty(currentUser.getEmail())) {
            return currentUser.getEmail();
        }
        return getString(R.string.account_user_fallback);
    }

    private void signOut() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        GoogleSignInClient googleSignInClient = GoogleSignIn.getClient(this, gso);
        googleSignInClient.signOut().addOnCompleteListener(task -> {
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void checkForUpdates() {
        setUpdateButtonEnabled(false, getString(R.string.checking_updates));
        new Thread(() -> {
            try {
                ReleaseInfo releaseInfo = fetchLatestRelease();
                String installedVersionName = getInstalledVersionName();
                if (compareSemanticVersions(releaseInfo.versionName, installedVersionName) <= 0) {
                    runOnUiThread(() -> {
                        setUpdateButtonEnabled(true, getString(R.string.check_updates));
                        Toast.makeText(this, R.string.update_not_available, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                if (TextUtils.isEmpty(releaseInfo.apkDownloadUrl)) {
                    runOnUiThread(() -> {
                        setUpdateButtonEnabled(true, getString(R.string.check_updates));
                        Toast.makeText(this, R.string.update_no_apk_asset, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                runOnUiThread(() -> {
                    setUpdateButtonEnabled(true, getString(R.string.check_updates));
                    Toast.makeText(this, getString(R.string.update_available, releaseInfo.versionName), Toast.LENGTH_SHORT).show();
                    startApkDownload(releaseInfo);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setUpdateButtonEnabled(true, getString(R.string.check_updates));
                    Toast.makeText(this, getString(R.string.update_check_failed, exception.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void startApkDownload(ReleaseInfo releaseInfo) {
        DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show();
            return;
        }

        Uri downloadUri = Uri.parse(releaseInfo.apkDownloadUrl);
        DownloadManager.Request request = new DownloadManager.Request(downloadUri)
                .setTitle(getString(R.string.app_name) + " " + releaseInfo.versionName)
                .setDescription(getString(R.string.update_download_started))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "HIKaraoke-" + sanitizeFileName(releaseInfo.versionName) + ".apk"
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);
        request.addRequestHeader("Accept", "application/octet-stream");

        try {
            downloadManager.enqueue(request);
            Toast.makeText(this, R.string.update_download_started, Toast.LENGTH_LONG).show();
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show();
        }
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        try {
            JSONObject releaseJson = fetchJsonObject(LATEST_RELEASE_URL);
            return toReleaseInfo(releaseJson);
        } catch (IOException exception) {
            if (!isNotFoundForPrivateRepo(exception.getMessage())) {
                throw exception;
            }
        }

        String latestTag = resolveLatestReleaseTagFromRedirect();
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
        return new ReleaseInfo(versionCode, versionName, apkUrl);
    }

    private JSONObject fetchJsonObject(String urlString) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", getPackageName());

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

    private String resolveLatestReleaseTagFromRedirect() throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(LATEST_RELEASE_REDIRECT_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", getPackageName());

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

    private long getInstalledVersionCode() throws Exception {
        PackageInfo packageInfo;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageInfo = getPackageManager().getPackageInfo(
                    getPackageName(),
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
            );
        } else {
            packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }

    private String getInstalledVersionName() throws Exception {
        PackageInfo packageInfo;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageInfo = getPackageManager().getPackageInfo(
                    getPackageName(),
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
            );
        } else {
            packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
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

    private void setUpdateButtonEnabled(boolean enabled, String text) {
        checkUpdatesButton.setEnabled(enabled);
        checkUpdatesButton.setAlpha(enabled ? 1f : 0.6f);
        checkUpdatesButton.setText(text);
    }

    private static final class ReleaseInfo {
        final long versionCode;
        final String versionName;
        final String apkDownloadUrl;

        ReleaseInfo(long versionCode, String versionName, String apkDownloadUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkDownloadUrl = apkDownloadUrl;
        }
    }
}
