package pt.forreta.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps playing with the screen off and shows play/pause/next controls on the lock screen. */
public class RadioService extends Service {
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private MediaSession session;
    private String title = "Forreta TV";
    private boolean playing = true;

    @Override public IBinder onBind(Intent i) { return null; }

    private PendingIntent action(String cmd, int code) {
        Intent i = new Intent(this, RadioService.class).setAction(cmd);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void ensureSession() {
        if (session != null) return;
        session = new MediaSession(this, "ForretaTV");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.media("play"); }
            @Override public void onPause() { MainActivity.media("pause"); }
            @Override public void onSkipToNext() { MainActivity.media("next"); }
            @Override public void onSkipToPrevious() { MainActivity.media("prev"); }
            @Override public void onStop() { MainActivity.media("pause"); }
        });
        session.setActive(true);
    }

    private void show() {
        ensureSession();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Forreta TV").build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel("radio", "A tocar", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "radio") : new Notification.Builder(this);
        b.setContentTitle(title).setContentText("Forreta TV").setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi).setOngoing(playing).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Anterior", action("prev", 1)).build())
                .addAction(playing
                        ? new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pausa", action("pause", 2)).build()
                        : new Notification.Action.Builder(android.R.drawable.ic_media_play, "Tocar", action("play", 3)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Seguinte", action("next", 4)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (a != null && !"show".equals(a)) {               // button pressed on the notification / lock screen
            MainActivity.media(a);
            return START_NOT_STICKY;
        }
        if (intent != null) {
            if (intent.getStringExtra("title") != null && intent.getStringExtra("title").length() > 0) title = intent.getStringExtra("title");
            playing = intent.getBooleanExtra("playing", true);
        }
        show();
        if (wake == null) {
            wake = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forreta:media");
            wake.acquire(6 * 3600 * 1000L);
        }
        if (wifi == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "forreta:media"); wifi.acquire(); }
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception e) {}
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception e) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception e) {}
        super.onDestroy();
    }
}
