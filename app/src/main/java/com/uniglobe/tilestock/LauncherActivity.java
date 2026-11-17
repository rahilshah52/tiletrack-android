package com.uniglobe.tilestock;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.messaging.FirebaseMessaging;

/**
 * Opens the TileTrack website full screen (as before). On the very first launch it now asks, one tap each,
 * for everything the app needs so nobody has to dig through phone settings later:
 *   1. notifications (Android 13+),
 *   2. location while using the app, then
 *   3. "Allow all the time", so the on-duty route keeps recording when the app is closed.
 * (On Android 11+ Android itself shows the "all the time" choice on its own location screen — that is the one
 *  step no app can turn into a plain in-app tap.) Then it fetches this phone's Firebase alert address and launches.
 */
public class LauncherActivity extends com.google.androidbrowserhelper.trusted.LauncherActivity {
    private static final int ASK_START = 41, ASK_BG = 42;
    private static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
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
        if (!Alerts.prefs(this).getBoolean("onboardedPerms", false)) {
            Alerts.prefs(this).edit().putBoolean("onboardedPerms", true).apply();
            askStartupPermissions();
        } else {
            getAddressThenLaunch();
        }
    }

    /** First launch: ask for notifications and location together — each a single tap, no digging in settings. */
    private void askStartupPermissions() {
        java.util.ArrayList<String> ask = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ask.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!Tracker.hasLocation(this)) {
            ask.add(Manifest.permission.ACCESS_FINE_LOCATION);
            ask.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (ask.isEmpty()) { afterForeground(); return; }
        requestPermissions(ask.toArray(new String[0]), ASK_START);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == ASK_START) afterForeground();
        else if (requestCode == ASK_BG) getAddressThenLaunch();
    }

    /** Location granted while-using: now ask for "Allow all the time" so the route keeps recording with the app closed. */
    private void afterForeground() {
        if (Tracker.hasLocation(this) && !Tracker.hasBackground(this) && Build.VERSION.SDK_INT >= 29) {
            CharSequence label = Build.VERSION.SDK_INT >= 30 ? getPackageManager().getBackgroundPermissionOptionLabel() : getString(R.string.allowAllTime);
            new AlertDialog.Builder(this, DIALOG_THEME)
                    .setTitle(R.string.allowAllTimeTitle)
                    .setMessage(getString(R.string.allowAllTimeText, label))
                    .setCancelable(false)
                    .setPositiveButton(R.string.next, (x, y) -> requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, ASK_BG))
                    .setNegativeButton(R.string.notNow, (x, y) -> getAddressThenLaunch())
                    .show();
        } else {
            getAddressThenLaunch();
        }
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
