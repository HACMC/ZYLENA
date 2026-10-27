package com.zylena.screenreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;

/**
 * Ways to control reading without the floating button: a notification with Back / Pause / Skip / Stop,
 * play-pause on headphones (through a media session), and shaking the phone to stop.
 */
final class Controls implements SensorEventListener {
    private static final String CHANNEL = "reading";
    private static final int NOTE_ID = 7;
    private static final String ACTION = "com.zylena.screenreader.CONTROL";

    private final ReaderService svc;
    private final NotificationManager nm;
    private final MediaSession session;
    private final SensorManager sensors;
    private boolean shakeOn;
    private long lastShake;
    private int shakes;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handle(i.getStringExtra("do"));
        }
    };

    Controls(ReaderService s) {
        svc = s;
        nm = (NotificationManager) s.getSystemService(Context.NOTIFICATION_SERVICE);
        sensors = (SensorManager) s.getSystemService(Context.SENSOR_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reading controls", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        session = new MediaSession(s, "ZYLENA");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                handle("toggle");
            }

            @Override
            public void onPause() {
                handle("toggle");
            }

            @Override
            public void onStop() {
                handle("stop");
            }

            @Override
            public void onSkipToNext() {
                handle("next");
            }

            @Override
            public void onSkipToPrevious() {
                handle("back");
            }
        });
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) s.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else s.registerReceiver(receiver, f);
    }

    void release() {
        try {
            svc.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        sensors.unregisterListener(this);
        nm.cancel(NOTE_ID);
        session.release();
    }

    /** Headphone button, notification button or media session: one place that decides what happens. */
    void handle(String what) {
        if (what == null) return;
        Speaker sp = Speaker.get(svc);
        Logs.d(svc, "control: " + what);
        switch (what) {
            case "toggle":
                if (sp.isSpeaking()) sp.pause();
                else if (sp.isPaused()) sp.resume();
                else svc.readScreen();
                break;
            case "next":
                sp.skip(1);
                break;
            case "back":
                sp.skip(-1);
                break;
            default:
                sp.stop();
        }
    }

    /** Call when reading starts, pauses or stops. */
    void update(String nowReading) {
        Speaker sp = Speaker.get(svc);
        boolean speaking = sp.isSpeaking(), paused = sp.isPaused();
        if (!speaking && !paused) {
            nm.cancel(NOTE_ID);
            session.setActive(false);
            listenForShake(false);
            return;
        }
        listenForShake(speaking && Prefs.shakeStop(svc));
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(speaking ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 0, 1f).build());
        session.setActive(true);

        if (!Prefs.notificationControls(svc)) {
            nm.cancel(NOTE_ID);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && svc.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(svc, CHANNEL) : new Notification.Builder(svc);
        b.setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle(speaking ? "ZYLENA is reading" : "ZYLENA paused")
                .setContentText(nowReading == null ? "" : nowReading)
                .setOngoing(speaking)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_prev, "Back", "back", 1))
                .addAction(speaking ? action(R.drawable.ic_pause, "Pause", "toggle", 2) : action(R.drawable.ic_play, "Play", "toggle", 2))
                .addAction(action(R.drawable.ic_next, "Skip", "next", 3))
                .addAction(action(R.drawable.ic_stop, "Stop", "stop", 4))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        nm.notify(NOTE_ID, b.build());
    }

    private Notification.Action action(int icon, String label, String what, int req) {
        Intent i = new Intent(ACTION).setPackage(svc.getPackageName()).putExtra("do", what);
        PendingIntent pi = PendingIntent.getBroadcast(svc, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(svc, icon), label, pi).build();
    }

    // ----------------------------------------------------------- shake to stop

    private void listenForShake(boolean on) {
        if (on == shakeOn) return;
        shakeOn = on;
        if (on) {
            Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        } else {
            sensors.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double g = Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
        if (g < 2.6) return;
        long now = System.currentTimeMillis();
        if (now - lastShake > 900) shakes = 0;
        if (now - lastShake < 120) return; // same jolt
        lastShake = now;
        // Two firm shakes in a row, so walking or putting the phone down doesn't stop it.
        if (++shakes >= 2) {
            shakes = 0;
            Logs.d(svc, "shake: stop");
            Speaker.get(svc).stop();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }
}
