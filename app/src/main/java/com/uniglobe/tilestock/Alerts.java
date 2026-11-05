package com.uniglobe.tilestock;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Map;

/** Notification channels and how alerts look. */
final class Alerts {
    static final String RING = "order_ring_v1";   // new order: plays res/raw/order_ring (10 seconds) at ringer volume
    static final String ALERTS = "alerts";        // everything else: the phone's normal notification sound
    private static final long[] RING_VIBRATE = {0, 800, 300, 800, 300, 800, 300, 800};

    private Alerts() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tiletrack", Context.MODE_PRIVATE);
    }

    static void saveToken(Context c, String token) {
        prefs(c).edit().putString("fcmToken", token).apply();
    }

    static String token(Context c) {
        return prefs(c).getString("fcmToken", null);
    }

    static Uri ringSound(Context c) {
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + c.getPackageName() + "/" + R.raw.order_ring);
    }

    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel ring = new NotificationChannel(RING, c.getString(R.string.channelRing), NotificationManager.IMPORTANCE_HIGH);
        ring.setDescription(c.getString(R.string.channelRingText));
        ring.setSound(ringSound(c), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ring.enableVibration(true);
        ring.setVibrationPattern(RING_VIBRATE);
        ring.enableLights(true);
        ring.setLightColor(0xFFF2A541);
        ring.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        ring.setBypassDnd(true);   // only if the phone allows TileTrack in Do Not Disturb
        nm.createNotificationChannel(ring);

        NotificationChannel alerts = new NotificationChannel(ALERTS, c.getString(R.string.channelAlerts), NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription(c.getString(R.string.channelAlertsText));
        alerts.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alerts);
    }

    static void show(Context c, Map<String, String> d) {
        createChannels(c);
        boolean ring = "1".equals(d.get("ring"));
        String title = d.get("title") != null ? d.get("title") : "TileTrack";
        String body = d.get("body") != null ? d.get("body") : "";
        String path = d.get("url") != null && d.get("url").startsWith("#") ? d.get("url") : "#/";

        // Tapping opens TileTrack on the orders (through LauncherActivity, so the alert address is passed again).
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(c.getString(R.string.siteUrl) + path), c, LauncherActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int id;
        try { id = Integer.parseInt(d.get("id")); } catch (Exception e) { id = (int) (System.currentTimeMillis() & 0x7fffffff); }
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder n = new NotificationCompat.Builder(c, ring ? RING : ALERTS)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(ContextCompat.getColor(c, R.color.brand))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(ring ? NotificationCompat.CATEGORY_CALL : NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (ring && Build.VERSION.SDK_INT < 26) {   // old phones: no channels
            n.setSound(ringSound(c), android.media.AudioManager.STREAM_RING).setVibrate(RING_VIBRATE);
        }
        try {
            NotificationManagerCompat.from(c).notify(id, n.build());
        } catch (SecurityException e) {
            // notifications not allowed on this phone
        }
    }
}
