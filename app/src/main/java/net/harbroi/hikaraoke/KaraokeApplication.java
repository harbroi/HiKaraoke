package net.harbroi.hikaraoke;

import android.app.Application;
import com.google.firebase.database.FirebaseDatabase;

public class KaraokeApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Enable Firebase offline persistence
        FirebaseDatabase.getInstance().setPersistenceEnabled(true);
    }
}
