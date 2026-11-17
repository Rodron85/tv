package pt.forreta.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the app awake while a radio plays with the screen off. */
public class RadioService extends Service {
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String title = intent != null && intent.getStringExtra("title") != null ? intent.getStringExtra("title") : "Rádio";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("radio", "Rádio a tocar", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "radio") : new Notification.Builder(this);
        Notification n = b.setContentTitle(title).setContentText("Forreta TV · a tocar")
                .setSmallIcon(R.mipmap.ic_launcher).setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
        if (wake == null) {
            wake = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forreta:radio");
            wake.acquire(6 * 3600 * 1000L);
        }
        if (wifi == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "forreta:radio"); wifi.acquire(); }
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception e) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception e) {}
        super.onDestroy();
    }
}
