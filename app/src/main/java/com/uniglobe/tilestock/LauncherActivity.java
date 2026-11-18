package com.uniglobe.tilestock;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import com.google.firebase.messaging.FirebaseMessaging;

/**
 * Opens the TileTrack website full screen. On the very first launch it asks, one tap each and WITHOUT ever sending
 * anyone into phone Settings, for everything the app needs:
 *   1. notifications (Android 13+),
 *   2. location "while using the app",
 *   3. permission to keep running in the background (a single battery dialog).
 * Location recording works through the whole shift (even with the app closed or the phone locked) on the
 * "while using" grant, because the foreground service is started when the person clocks in. "Allow all the time"
 * is deliberately NOT asked here: Android 11+ only lets that be chosen on its own settings screen, and it is not
 * needed for on-shift tracking.
 */
public class LauncherActivity extends com.google.androidbrowserhelper.trusted.LauncherActivity {
    private static final int ASK_START = 41, REQ_BATTERY = 61;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launched = false;

    @Override
    protected boolean shouldLaunchImmediately() {
        return false;   // first the permissions and the alert address, then launchTwa()
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isFinishing()) return;
        if (!Alerts.prefs(this).getBoolean("onboardedPerms2", false)) {
            Alerts.prefs(this).edit().putBoolean("onboardedPerms2", true).apply();
            askStartupPermissions();
        } else {
            getAddressThenLaunch();
        }
    }

    /** Ask for notifications and "while using" location together — each a single tap, no settings. */
    private void askStartupPermissions() {
        java.util.ArrayList<String> ask = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ask.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!Tracker.hasLocation(this)) {
            ask.add(Manifest.permission.ACCESS_FINE_LOCATION);
            ask.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (ask.isEmpty()) { askBattery(); return; }
        requestPermissions(ask.toArray(new String[0]), ASK_START);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == ASK_START) askBattery();
    }

    /** One system dialog ("Allow TileTrack to run in the background?") — a single tap, not a settings screen. */
    private void askBattery() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())), REQ_BATTERY);
                return;
            }
        } catch (Exception ignored) { }
        getAddressThenLaunch();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BATTERY) getAddressThenLaunch();
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
        // At work and tracked (e.g. the phone restarted, or Android closed the recording): carry on.
        // The recording asks the server at once and stops by itself if the person is not at work.
        if (Tracker.tracked(this) && Tracker.onDuty(this)) Tracker.start(this);
        launchTwa();
    }

    @Override
    protected Uri getLaunchingUrl() {
        Uri uri = super.getLaunchingUrl();
        Uri.Builder b = uri.buildUpon().appendQueryParameter("apk", String.valueOf(BuildConfig.VERSION_CODE));
        String token = Alerts.token(this);
        if (token != null && !token.isEmpty()) b.appendQueryParameter("ntok", token);
        b.appendQueryParameter("nsec", Tracker.secret(this));   // lets this app send on-duty locations for the person signed in
        return b.build();
    }
}
