package io.beaconhome.jedistestapp;

import android.app.Application;

import timber.log.Timber;

public class JedisApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
    }
}
