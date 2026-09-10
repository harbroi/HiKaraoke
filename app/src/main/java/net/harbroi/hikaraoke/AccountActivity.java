package net.harbroi.hikaraoke;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class AccountActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        TextView userNameText = findViewById(R.id.userNameText);
        TextView signOutButton = findViewById(R.id.signOutButton);
        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);

        userNameText.setText(getCurrentUserName());
        signOutButton.setOnClickListener(v -> signOut());
        NavigationHelper.setupBottomNavigation(this, bottomNavigation, R.id.navigation_account);
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
}
