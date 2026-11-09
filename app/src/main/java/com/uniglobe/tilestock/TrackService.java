package com.uniglobe.tilestock;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.app.ServiceCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Records the location about once a minute while the person is at work, and sends it every 5 minutes.
 * Shows a "Recording your route while at work" notification the whole time (Android requires it).
 */
public class TrackService extends Service {
    private FusedLocationProviderClient fused;
    private ScheduledExecutorService timer;
    private volatile boolean stopping = false;

    private final LocationCallback callback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult result) {
            for (Location l : result.getLocations()) {
                if (l == null) continue;
                Tracker.add(TrackService.this, l.getTime(), Math.round(l.getLatitude() * 1e6) / 1e6, Math.round(l.getLongitude() * 1e6) / 1e6,
                        l.hasAccuracy() ? Math.round(l.getAccuracy()) : 0);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
            ServiceCompat.startForeground(this, Tracker.NOTE_ID, Tracker.runningNote(this).build(), type);
        } catch (Exception e) {
            // not allowed right now (e.g. location permission taken away): ask, and stop
            Tracker.askPermissionNote(this);
            stopSelf();
            return;
        }
        fused = LocationServices.getFusedLocationProviderClient(this);
        try {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000)
                    .setMinUpdateIntervalMillis(30_000)
                    .setMaxUpdateDelayMillis(3 * 60_000)   // let the phone deliver in small batches: saves battery
                    .build();
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Tracker.askPermissionNote(this);
            stopSelf();
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(this::send, 20, 5 * 60, TimeUnit.SECONDS);
    }

    private void send() {
        if (stopping) return;
        Boolean keep = Tracker.upload(this);
        if (Boolean.FALSE.equals(keep)) {
            stopping = true;
            Tracker.upload(this);   // whatever is still waiting (the server keeps only work-time points)
            Tracker.stop(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;   // Android restarts it if it had to close it
    }

    @Override
    public void onDestroy() {
        if (fused != null) try { fused.removeLocationUpdates(callback); } catch (Exception ignored) { }
        if (timer != null) {
            timer.shutdownNow();
            if (!stopping && Tracker.hasWaiting(this)) new Thread(() -> Tracker.upload(getApplicationContext())).start();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
