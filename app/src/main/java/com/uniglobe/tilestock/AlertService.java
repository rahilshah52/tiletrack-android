package com.uniglobe.tilestock;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** Receives alerts from the TileTrack server. Runs even when the app is closed. */
public class AlertService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        Alerts.saveToken(this, token);   // handed to the website the next time the app opens
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> d = message.getData();
        if (d == null) return;
        if ("track".equals(d.get("type"))) { Tracker.fromServer(this, "1".equals(d.get("on")), d.get("tracked")); return; }
        if (!"alert".equals(d.get("type"))) return;
        Alerts.show(this, d);
    }
}
