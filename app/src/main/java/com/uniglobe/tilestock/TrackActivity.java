package com.uniglobe.tilestock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Invisible screen that starts the route recording.
 *  - tiletrack://track/on : opened by the website right after "Start work" (TileTrack app only)
 *  - ACTION_ASK           : from the "Allow location" notification
 * It asks for location (and "Allow all the time", so recording goes on when the phone is locked), then closes.
 */
public class TrackActivity extends Activity {
    static final String ACTION_ASK = "com.uniglobe.tilestock.ASK_LOCATION";
    private static final int ASK_FG = 51, ASK_BG = 52;
    private static final int THEME = android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    private boolean fromStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri d = getIntent() != null ? getIntent().getData() : null;
        fromStart = d != null && "tiletrack".equals(d.getScheme()) && "track".equals(d.getHost()) && "/on".equals(d.getPath());
        if (!fromStart && !ACTION_ASK.equals(getIntent() != null ? getIntent().getAction() : null)) { finish(); return; }
        stepLocation();
    }

    private void stepLocation() {
        if (Tracker.hasLocation(this)) { stepBackground(); return; }
        new AlertDialog.Builder(this, THEME)
                .setTitle(R.string.allowLocationTitle)
                .setMessage(R.string.allowLocationText)
                .setCancelable(false)
                .setPositiveButton(R.string.next, (x, y) -> requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_FG))
                .setNegativeButton(R.string.notNow, (x, y) -> done())
                .show();
    }

    private void stepBackground() {
        if (Tracker.hasBackground(this) || Alerts.prefs(this).getLong("askedBg", 0) > System.currentTimeMillis() - 7 * 86400_000L) { stepBattery(); return; }
        Alerts.prefs(this).edit().putLong("askedBg", System.currentTimeMillis()).apply();
        CharSequence label = Build.VERSION.SDK_INT >= 30 ? getPackageManager().getBackgroundPermissionOptionLabel() : getString(R.string.allowAllTime);
        new AlertDialog.Builder(this, THEME)
                .setTitle(R.string.allowAllTimeTitle)
                .setMessage(getString(R.string.allowAllTimeText, label))
                .setCancelable(false)
                .setPositiveButton(R.string.next, (x, y) -> requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, ASK_BG))
                .setNegativeButton(R.string.notNow, (x, y) -> stepBattery())
                .show();
    }

    /** Many phones close apps in the background to save battery; ask once to let TileTrack run. */
    private void stepBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (Build.VERSION.SDK_INT < 23 || pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())
                || Alerts.prefs(this).getBoolean("askedBattery", false)) { done(); return; }
        Alerts.prefs(this).edit().putBoolean("askedBattery", true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) { }
        done();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == ASK_FG) { if (Tracker.hasLocation(this)) stepBackground(); else done(); }
        else if (requestCode == ASK_BG) stepBattery();
    }

    private void done() {
        if (Tracker.hasLocation(this)) {
            if (fromStart) Tracker.startedWork(this); else Tracker.start(this);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
