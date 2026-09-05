package net.harbroi.hikaraoke;

import android.app.UiModeManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class ModeChooserActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isTvDevice()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        setContentView(R.layout.activity_mode_chooser);

        MaterialButton mobileButton = findViewById(R.id.mobileModeButton);
        MaterialButton tvButton = findViewById(R.id.tvModeButton);

        mobileButton.setOnClickListener(v -> openMode(HomeActivity.class));
        tvButton.setOnClickListener(v -> openMode(net.harbroi.hikaraoketv.MainActivity.class));
    }

    private void openMode(Class<?> target) {
        startActivity(new Intent(this, target));
        finish();
    }

    private boolean isTvDevice() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UiModeManager.class);
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }
}
