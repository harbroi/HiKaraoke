package net.harbroi.hikaraoke;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.IdRes;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public final class NavigationHelper {

    private NavigationHelper() {
    }

    public static void setupBottomNavigation(Activity activity, BottomNavigationView navigationView, @IdRes int selectedItemId) {
        navigationView.setSelectedItemId(selectedItemId);
        navigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == selectedItemId) {
                return true;
            }

            Class<?> targetActivity = getTargetActivity(itemId);
            if (targetActivity == null) {
                return false;
            }

            Intent intent = new Intent(activity, targetActivity);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            activity.finish();
            return true;
        });
    }

    private static Class<?> getTargetActivity(int itemId) {
        if (itemId == R.id.navigation_main) {
            return HomeActivity.class;
        }
        if (itemId == R.id.navigation_search) {
            return MainActivity.class;
        }
        if (itemId == R.id.navigation_account) {
            return AccountActivity.class;
        }
        return null;
    }
}
