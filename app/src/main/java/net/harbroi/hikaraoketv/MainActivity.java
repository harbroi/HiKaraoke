package net.harbroi.hikaraoketv;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            startActivity(new Intent(this, VideoPlayerWebViewActivity.class));
            finish();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to launch player activity", e);
            Toast.makeText(this,
                    "Unable to open player. Check Logcat for details.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
