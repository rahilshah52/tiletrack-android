package com.uniglobe.tilestock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Phone restarted (or the app was updated) while the person was at work: carry on recording the route. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i != null ? i.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (Tracker.tracked(c) && Tracker.onDuty(c) && Tracker.hasLocation(c) && Tracker.hasBackground(c)) Tracker.start(c);
    }
}
