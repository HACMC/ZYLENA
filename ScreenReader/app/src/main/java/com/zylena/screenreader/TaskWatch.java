package com.zylena.screenreader;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Runs while the ZYLENA app is open; when the app is swiped away from recent apps, the reader closes with it. */
public class TaskWatch extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        ReaderService r = ReaderService.instance;
        if (r != null) r.closeWithApp();
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
