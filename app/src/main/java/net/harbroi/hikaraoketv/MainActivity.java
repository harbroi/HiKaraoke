package net.harbroi.hikaraoketv;

import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import net.harbroi.hikaraoke.FirebaseManager;
import net.harbroi.hikaraoke.R;
import net.harbroi.hikaraoke.AppUpdateManager;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String EXTRA_USER_UID = "user_uid";
    private final AppUpdateManager appUpdateManager = new AppUpdateManager();
    private Button openQueueButton;
    private EditText codeInput;
    private ImageView pairingQrImage;
    private TextView pairingHint;
    private String pairingToken;
    private DatabaseReference pairingSessionRef;
    private ValueEventListener pairingListener;
    private boolean playerOpened;

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
        pairingQrImage = findViewById(R.id.tvPairingQrCode);
        pairingHint = findViewById(R.id.tvPairingHint);
        startTvPairingSession();

        openQueueButton.setOnClickListener(v -> {
            checkForUpdatesBeforeConnecting();
        });
    }

    @Override
    protected void onDestroy() {
        if (pairingSessionRef != null && pairingListener != null) {
            pairingSessionRef.removeEventListener(pairingListener);
        }
        super.onDestroy();
    }

    private void startTvPairingSession() {
        FirebaseManager firebaseManager = FirebaseManager.getInstance();
        pairingToken = firebaseManager.createTvPairingToken();
        pairingSessionRef = firebaseManager.getTvPairingSessionRef(pairingToken);
        pairingListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                String userUid = snapshot.getValue(String.class);
                if (userUid == null || playerOpened) {
                    return;
                }
                openPlayer(userUid);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Unable to listen for TV pairing", error.toException());
                if (!isFinishing() && !isDestroyed()) {
                    pairingHint.setText(R.string.tv_pairing_unavailable);
                }
            }
        };
        pairingSessionRef.addValueEventListener(pairingListener);
        try {
            pairingQrImage.setImageBitmap(createQrBitmap(pairingToken));
            pairingHint.setText(R.string.tv_pairing_scan_hint);
        } catch (WriterException exception) {
            Log.e(TAG, "Unable to generate TV pairing QR code", exception);
            pairingHint.setText(R.string.tv_pairing_unavailable);
        }
    }

    private Bitmap createQrBitmap(String content) throws WriterException {
        int size = 480;
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
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
                if (playerOpened) {
                    return;
                }
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
        if (playerOpened) {
            return;
        }
        playerOpened = true;
        try {
            Intent intent = new Intent(this, VideoPlayerWebViewActivity.class);
            intent.putExtra(EXTRA_USER_UID, userUid);
            startActivity(intent);
            finish();
        } catch (RuntimeException e) {
            playerOpened = false;
            Log.e(TAG, "Failed to launch player activity", e);
            Toast.makeText(this,
                    "Unable to open player. Check Logcat for details.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
