package net.harbroi.hikaraoke;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class AccountActivity extends AppCompatActivity {
    private final AppUpdateManager appUpdateManager = new AppUpdateManager();
    private MaterialButton checkUpdatesButton;
    private MaterialButton scanTvQrButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        TextView userNameText = findViewById(R.id.userNameText);
        TextView accessCodeText = findViewById(R.id.accessCodeText);
        TextView appInfoText = findViewById(R.id.appInfoText);
        TextView signOutButton = findViewById(R.id.signOutButton);
        checkUpdatesButton = findViewById(R.id.checkUpdatesButton);
        scanTvQrButton = findViewById(R.id.scanTvQrButton);
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
        scanTvQrButton.setOnClickListener(v -> scanTvQrCode());
        checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        NavigationHelper.setupBottomNavigation(this, bottomNavigation, R.id.navigation_account);
    }

    private void scanTvQrCode() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        setScanButtonEnabled(false);
        GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(barcode -> {
                    String token = barcode.getRawValue();
                    if (token == null || token.isEmpty()) {
                        setScanButtonEnabled(true);
                        Toast.makeText(this, R.string.tv_qr_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FirebaseManager.getInstance().linkTvPairingSession(token, (linked, errorMessage) -> runOnUiThread(() -> {
                        setScanButtonEnabled(true);
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (linked) {
                            Toast.makeText(this, R.string.tv_qr_connected, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this,
                                    errorMessage == null ? getString(R.string.tv_qr_failed) : errorMessage,
                                    Toast.LENGTH_LONG).show();
                        }
                    }));
                })
                .addOnCanceledListener(() -> setScanButtonEnabled(true))
                .addOnFailureListener(exception -> {
                    setScanButtonEnabled(true);
                    String reason = exception.getLocalizedMessage();
                    Toast.makeText(this,
                            getString(R.string.tv_qr_scan_failed, reason == null ? getString(R.string.tv_qr_failed) : reason),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void setScanButtonEnabled(boolean enabled) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        scanTvQrButton.setEnabled(enabled);
        scanTvQrButton.setAlpha(enabled ? 1f : 0.6f);
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
        appUpdateManager.checkForUpdates(this, new AppUpdateManager.UpdateCheckCallback() {
            @Override
            public void onUpToDate() {
                runOnUiThread(() -> {
                    setUpdateButtonEnabled(true, getString(R.string.check_updates));
                    Toast.makeText(AccountActivity.this, R.string.update_not_available, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onUpdateAvailable(AppUpdateManager.ReleaseInfo releaseInfo) {
                runOnUiThread(() -> {
                    setUpdateButtonEnabled(true, getString(R.string.check_updates));
                    Toast.makeText(AccountActivity.this, getString(R.string.update_available, releaseInfo.versionName), Toast.LENGTH_SHORT).show();
                    if (!appUpdateManager.startApkDownload(AccountActivity.this, releaseInfo)) {
                        Toast.makeText(AccountActivity.this, R.string.update_download_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(AccountActivity.this, R.string.update_download_started, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setUpdateButtonEnabled(true, getString(R.string.check_updates));
                    Toast.makeText(AccountActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
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
