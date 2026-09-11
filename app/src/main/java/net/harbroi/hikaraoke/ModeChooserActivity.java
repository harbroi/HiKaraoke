package net.harbroi.hikaraoke;

import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;

public class ModeChooserActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isTvDevice()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            openMode(net.harbroi.hikaraoketv.MainActivity.class);
            return;
        }

        setContentView(R.layout.activity_mode_chooser);

        MaterialButton mobileButton = findViewById(R.id.mobileModeButton);
        MaterialButton tvButton = findViewById(R.id.tvModeButton);

        mobileButton.setOnClickListener(v -> openMobileMode());
        tvButton.setOnClickListener(v -> openMode(net.harbroi.hikaraoketv.MainActivity.class));
    }

    private void openMobileMode() {
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            openMode(HomeActivity.class);
            return;
        }
        openMode(LoginActivity.class);
    }

    private void openMode(Class<?> target) {
        startActivity(new Intent(this, target));
        finish();
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
}
