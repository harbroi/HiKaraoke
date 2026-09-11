package net.harbroi.hikaraoketv;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import net.harbroi.hikaraoke.FirebaseManager;
import net.harbroi.hikaraoke.R;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String EXTRA_USER_UID = "user_uid";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_code_entry);

        EditText codeInput = findViewById(R.id.tvCodeInput);
        codeInput.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.AllCaps(),
                new android.text.InputFilter.LengthFilter(8)
        });
        Button openQueueButton = findViewById(R.id.openQueueButton);

        openQueueButton.setOnClickListener(v -> {
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
        });
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
