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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AccountActivity extends AppCompatActivity {
    private static final String LATEST_WORKFLOW_RUN_URL = "https://api.github.com/repos/harbroi/HiKaraoke/actions/workflows/build-apk.yml/runs?status=success&per_page=1";
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
                ReleaseInfo releaseInfo = fetchLatestBuild();
                long installedVersionCode = getInstalledVersionCode();
                if (releaseInfo.versionCode <= installedVersionCode) {
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

    private ReleaseInfo fetchLatestBuild() throws Exception {
        JSONObject runJson = fetchJsonObject(LATEST_WORKFLOW_RUN_URL);
        JSONArray workflowRuns = runJson.optJSONArray("workflow_runs");
        if (workflowRuns == null || workflowRuns.length() == 0) {
            throw new IllegalStateException("No successful APK workflow runs found.");
        }

        JSONObject latestRun = workflowRuns.optJSONObject(0);
        if (latestRun == null) {
            throw new IllegalStateException("Invalid workflow run response.");
        }

        long runNumber = latestRun.optLong("run_number", -1L);
        if (runNumber < 0L) {
            throw new IllegalStateException("Workflow run number is missing.");
        }

        String artifactsUrl = latestRun.optString("artifacts_url", "").trim();
        if (artifactsUrl.isEmpty()) {
            throw new IllegalStateException("Workflow artifacts URL is missing.");
        }

        JSONObject artifactsJson = fetchJsonObject(artifactsUrl);
        JSONArray artifacts = artifactsJson.optJSONArray("artifacts");
        if (artifacts == null || artifacts.length() == 0) {
            throw new IllegalStateException("No APK artifact found in the latest workflow run.");
        }

        String artifactDownloadUrl = null;
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject artifact = artifacts.optJSONObject(i);
            if (artifact == null) {
                continue;
            }
            String artifactName = artifact.optString("name", "");
            boolean isExpired = artifact.optBoolean("expired", false);
            String archiveDownloadUrl = artifact.optString("archive_download_url", "");
            if (!isExpired && "app-debug-apk".equals(artifactName) && !archiveDownloadUrl.isEmpty()) {
                artifactDownloadUrl = archiveDownloadUrl;
                break;
            }
        }

        if (artifactDownloadUrl == null) {
            throw new IllegalStateException("No downloadable APK artifact found in the latest workflow run.");
        }

        return new ReleaseInfo(runNumber, "build-" + runNumber, artifactDownloadUrl);
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
                throw new IllegalStateException("GitHub returned " + responseCode + ".");
            }
            return new JSONObject(responseBody);
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
