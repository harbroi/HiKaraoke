package net.harbroi.hikaraoketv;

import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import net.harbroi.hikaraoke.FirebaseManager;
import net.harbroi.hikaraoke.R;
import net.harbroi.hikaraoke.AppUpdateManager;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String EXTRA_USER_UID = "user_uid";
    private final AppUpdateManager appUpdateManager = new AppUpdateManager();
    private Button openQueueButton;
    private EditText codeInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_code_entry);

        codeInput = findViewById(R.id.tvCodeInput);
        codeInput.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.AllCaps(),
                new android.text.InputFilter.LengthFilter(8)
        });
        openQueueButton = findViewById(R.id.openQueueButton);

        openQueueButton.setOnClickListener(v -> {
            checkForUpdatesBeforeConnecting();
        });
    }

    private void checkForUpdatesBeforeConnecting() {
        if (!isTvDevice()) {
            connectToQueue();
            return;
        }
        setConnectEnabled(false);
        appUpdateManager.checkForUpdates(this, new AppUpdateManager.UpdateCheckCallback() {
            @Override
            public void onUpToDate() {
                runOnUiThread(() -> {
                    setConnectEnabled(true);
                    connectToQueue();
                });
            }

            @Override
            public void onUpdateAvailable(AppUpdateManager.ReleaseInfo releaseInfo) {
                runOnUiThread(() -> {
                    setConnectEnabled(true);
                    showUpdateDialog(releaseInfo);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setConnectEnabled(true);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    connectToQueue();
                });
            }
        });
    }

    private void connectToQueue() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        try {
            String code = codeInput.getText() == null ? "" : codeInput.getText().toString().trim();
            if (code.isEmpty()) {
                Toast.makeText(this, "Enter an 8-character code.", Toast.LENGTH_SHORT).show();
                return;
            }

            openQueueButton.setEnabled(false);
            FirebaseManager.getInstance().lookupUserByAccessCode(code, (userUid, resolvedCode, errorMessage) -> {
                openQueueButton.setEnabled(true);
                if (errorMessage != null) {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (userUid == null || userUid.isEmpty()) {
                    Toast.makeText(this, "No user found for this code.", Toast.LENGTH_SHORT).show();
                    return;
                }
                openPlayer(userUid);
            });
        } catch (RuntimeException exception) {
            setConnectEnabled(true);
            throw exception;
        }
    }

    private void showUpdateDialog(AppUpdateManager.ReleaseInfo releaseInfo) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_dialog_title)
                .setMessage(getString(R.string.update_dialog_message, releaseInfo.versionName))
                .setNegativeButton(R.string.update_dialog_later, (dialog, which) -> connectToQueue())
                .setPositiveButton(R.string.update_dialog_download, (dialog, which) -> {
                    if (!appUpdateManager.startApkDownload(MainActivity.this, releaseInfo)) {
                        Toast.makeText(MainActivity.this, R.string.update_download_failed, Toast.LENGTH_LONG).show();
                        connectToQueue();
                        return;
                    }
                    Toast.makeText(MainActivity.this, R.string.update_download_started, Toast.LENGTH_LONG).show();
                })
                .setCancelable(false)
                .show();
    }

    private void setConnectEnabled(boolean enabled) {
        if (openQueueButton != null) {
            openQueueButton.setEnabled(enabled);
            openQueueButton.setAlpha(enabled ? 1f : 0.6f);
        }
    }

    private boolean isTvDevice() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UiModeManager.class);
        boolean uiModeTv = uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        PackageManager packageManager = getPackageManager();
        boolean televisionFeature = packageManager != null
                && packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION);
        boolean leanbackFeature = packageManager != null
                && packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK);

        return uiModeTv || televisionFeature || leanbackFeature;
    }

    private void openPlayer(String userUid) {
        try {
            Intent intent = new Intent(this, VideoPlayerWebViewActivity.class);
            intent.putExtra(EXTRA_USER_UID, userUid);
            startActivity(intent);
            finish();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to launch player activity", e);
            Toast.makeText(this,
                    "Unable to open player. Check Logcat for details.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
