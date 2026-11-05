package com.uniglobe.tilestock;

public class Application extends android.app.Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Alerts.createChannels(this);
    }
}
