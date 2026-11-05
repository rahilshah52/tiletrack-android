package com.uniglobe.tilestock;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.messaging.FirebaseMessaging;

/**
 * Opens the TileTrack website full screen (as before), after:
 *  1. asking once for permission to show notifications (Android 13+), and
 *  2. getting this phone's Firebase alert address, which is handed to the website as ?ntok=...
 *     The website then tells the server, and alerts (with the new-order ring) come even when the app is closed.
 */
public class LauncherActivity extends com.google.androidbrowserhelper.trusted.LauncherActivity {
    private static final int ASK_NOTIFY = 41;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launched = false;

    @Override
    protected boolean shouldLaunchImmediately() {
        return false;   // first the permission and the alert address, then launchTwa()
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Alerts.prefs(this).getBoolean("askedNotify", false)) {
            Alerts.prefs(this).edit().putBoolean("askedNotify", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFY);
        } else {
            getAddressThenLaunch();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == ASK_NOTIFY) getAddressThenLaunch();
    }

    private void getAddressThenLaunch() {
        // Never keep people waiting: open the app after 2.5 s even if Firebase has not answered yet
        // (the saved address from last time is used; a new one is passed on the next start).
        handler.postDelayed(this::launchOnce, 2500);
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) Alerts.saveToken(this, task.getResult());
                launchOnce();
            });
        } catch (Exception e) {
            launchOnce();
        }
    }

    private void launchOnce() {
        if (launched || isFinishing()) return;
        launched = true;
        handler.removeCallbacksAndMessages(null);
        launchTwa();
    }

    @Override
    protected Uri getLaunchingUrl() {
        Uri uri = super.getLaunchingUrl();
        Uri.Builder b = uri.buildUpon().appendQueryParameter("apk", String.valueOf(BuildConfig.VERSION_CODE));
        String token = Alerts.token(this);
        if (token != null && !token.isEmpty()) b.appendQueryParameter("ntok", token);
        return b.build();
    }
}
