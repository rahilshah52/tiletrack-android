package com.uniglobe.tilestock;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Location while on duty (only for people the owner ticked "Track location while at work").
 *
 * The app keeps a secret key (made once on this phone). The website registers it for the person who signs in,
 * so this app can send points by itself, also when TileTrack is closed. The server keeps only points that fall
 * between that person's start and finish of work, and answers whether to keep recording.
 *
 * Starts: right after "Start work" in the app (tiletrack://track/on), by the server's message, or when the app opens.
 * Stops: when the server says the person is not at work (finished, untick, signed out).
 */
final class Tracker {
    static final String CHANNEL = "tracking";
    static final int NOTE_ID = 7101, NOTE_PERMISSION = 7102;
    private static final int MAX_POINTS = 6000;
    private static final Object LOCK = new Object();

    private Tracker() {}

    private static SharedPreferences prefs(Context c) {
        return Alerts.prefs(c);
    }

    /** This phone's secret key for sending locations (32 random bytes, made once). */
    static String secret(Context c) {
        String s = prefs(c).getString("locSecret", null);
        if (s == null || s.length() < 32) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            s = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            prefs(c).edit().putString("locSecret", s).apply();
        }
        return s;
    }

    static boolean tracked(Context c) { return prefs(c).getBoolean("tracked", false); }
    static void setTracked(Context c, boolean v) { prefs(c).edit().putBoolean("tracked", v).apply(); }
    static boolean onDuty(Context c) { return prefs(c).getBoolean("onDuty", false); }

    /** Just started work in the app: record at once; the server needs a moment to receive the start, so a "not at work"
     *  answer in the next 3 minutes is not believed. */
    static void startedWork(Context c) {
        prefs(c).edit().putBoolean("onDuty", true).putBoolean("tracked", true)
                .putLong("graceUntil", System.currentTimeMillis() + 3 * 60_000L).apply();
        start(c);
    }

    static boolean hasLocation(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBackground(Context c) {
        return Build.VERSION.SDK_INT < 29
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Start the recording service (if location is allowed; otherwise a notification asks for it). */
    static void start(Context c) {
        prefs(c).edit().putBoolean("onDuty", true).apply();
        if (!hasLocation(c)) { askPermissionNote(c); return; }
        try {
            ContextCompat.startForegroundService(c, new Intent(c, TrackService.class));
        } catch (Exception e) {
            // Android did not allow starting from the background (e.g. "Allow all the time" not given): ask in a notification
            askPermissionNote(c);
        }
    }

    static void stop(Context c) {
        prefs(c).edit().putBoolean("onDuty", false).remove("graceUntil").apply();
        c.stopService(new Intent(c, TrackService.class));
        NotificationManagerCompat.from(c).cancel(NOTE_PERMISSION);
    }

    /** The server's message (type "track"). */
    static void fromServer(Context c, boolean on, String tracked) {
        if ("0".equals(tracked)) setTracked(c, false);
        if ("1".equals(tracked)) setTracked(c, true);
        if (on) start(c);
        else if (System.currentTimeMillis() > prefs(c).getLong("graceUntil", 0)) stop(c);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channelTracking), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.channelTrackingText));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent openApp(Context c, int code) {
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(c.getString(R.string.siteUrl)), c, LauncherActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, code, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static NotificationCompat.Builder runningNote(Context c) {
        createChannel(c);
        return new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(ContextCompat.getColor(c, R.color.brand))
                .setContentTitle(c.getString(R.string.trackingTitle))
                .setContentText(c.getString(R.string.trackingText))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openApp(c, 1));
    }

    static void askPermissionNote(Context c) {
        createChannel(c);
        Intent ask = new Intent(c, TrackActivity.class).setAction(TrackActivity.ACTION_ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 2, ask, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder n = new NotificationCompat.Builder(c, Alerts.ALERTS)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(ContextCompat.getColor(c, R.color.brand))
                .setContentTitle(c.getString(R.string.allowLocationTitle))
                .setContentText(c.getString(R.string.allowLocationShort))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(c.getString(R.string.allowLocationShort)))
                .setAutoCancel(true)
                .setContentIntent(pi);
        Alerts.createChannels(c);
        try { NotificationManagerCompat.from(c).notify(NOTE_PERMISSION, n.build()); } catch (SecurityException ignored) { }
    }

    // ---- points waiting to be sent (a small text file: time,lat,lng,accuracy per line)
    private static File file(Context c) { return new File(c.getFilesDir(), "points.txt"); }

    static void add(Context c, long t, double lat, double lng, int acc) {
        synchronized (LOCK) {
            try (FileWriter w = new FileWriter(file(c), true)) {
                w.write(t + "," + lat + "," + lng + "," + acc + "\n");
            } catch (Exception ignored) { }
            if (file(c).length() > MAX_POINTS * 48L) trim(c, MAX_POINTS / 2);
        }
    }

    private static List<String> read(Context c) {
        List<String> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) if (l.split(",").length == 4) out.add(l);
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(Context c, List<String> lines) {
        try (FileWriter w = new FileWriter(file(c), false)) {
            for (String l : lines) w.write(l + "\n");
        } catch (Exception ignored) { }
    }

    private static void trim(Context c, int keepNewest) {
        List<String> all = read(c);
        if (all.size() > keepNewest) write(c, new ArrayList<>(all.subList(all.size() - keepNewest, all.size())));
    }

    /**
     * Sends waiting points (up to 500) and asks whether to keep recording. Runs on a background thread.
     * @return true = keep recording, false = stop, null = no answer (no internet): keep recording and try later.
     */
    static Boolean upload(Context c) {
        long pause = prefs(c).getLong("sendPauseUntil", 0);
        if (System.currentTimeMillis() < pause) return null;
        List<String> batch;
        synchronized (LOCK) {
            List<String> all = read(c);
            batch = new ArrayList<>(all.subList(0, Math.min(500, all.size())));
        }
        HttpURLConnection con = null;
        try {
            JSONArray pts = new JSONArray();
            for (String l : batch) {
                String[] p = l.split(",");
                JSONObject o = new JSONObject();
                o.put("t", Long.parseLong(p[0]));
                o.put("lat", Double.parseDouble(p[1]));
                o.put("lng", Double.parseDouble(p[2]));
                o.put("acc", Integer.parseInt(p[3]));
                pts.put(o);
            }
            JSONObject body = new JSONObject();
            body.put("secret", secret(c));
            body.put("points", pts);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);

            con = (HttpURLConnection) new URL(c.getString(R.string.siteUrl) + "backend/api.php?action=loc").openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(15_000);
            con.setReadTimeout(20_000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TileTrack/" + BuildConfig.VERSION_NAME);
            try (OutputStream os = con.getOutputStream()) { os.write(data); }
            int code = con.getResponseCode();
            String text = "";
            try (InputStream is = code < 400 ? con.getInputStream() : con.getErrorStream()) {
                if (is != null) text = new String(readAll(is), StandardCharsets.UTF_8);
            }
            JSONObject r = text.trim().startsWith("{") ? new JSONObject(text) : null;
            if (r == null) {
                // not our server's answer (e.g. the hosting firewall's page): stop sending for a while so it does not block us
                blocked(c);
                return null;
            }
            prefs(c).edit().putInt("sendFails", 0).apply();
            if (code >= 400) return null;
            synchronized (LOCK) {
                List<String> all = read(c);
                int n = Math.min(batch.size(), all.size());
                write(c, new ArrayList<>(all.subList(n, all.size())));
            }
            if (!r.optBoolean("known", true)) { setTracked(c, false); return false; }   // signed out on this phone
            if (r.has("tracked")) setTracked(c, r.optBoolean("tracked"));
            boolean keep = r.optBoolean("track", false);
            if (!keep && System.currentTimeMillis() < prefs(c).getLong("graceUntil", 0)) return true;
            if (keep) prefs(c).edit().remove("graceUntil").apply();
            return keep;
        } catch (java.io.IOException e) {
            return null;   // no internet: try later, points stay on the phone
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Two odd answers in a row → wait an hour (the host blocks a phone after 5 blocked requests in an hour). */
    private static void blocked(Context c) {
        int n = prefs(c).getInt("sendFails", 0) + 1;
        SharedPreferences.Editor e = prefs(c).edit().putInt("sendFails", n);
        if (n >= 2) e.putLong("sendPauseUntil", System.currentTimeMillis() + 60 * 60_000L).putInt("sendFails", 0);
        e.apply();
    }

    private static byte[] readAll(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0 && b.size() < 64_000) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static boolean hasWaiting(Context c) {
        synchronized (LOCK) { return file(c).exists() && file(c).length() > 0; }
    }
}
