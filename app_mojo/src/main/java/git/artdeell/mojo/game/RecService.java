package git.artdeell.mojo.game;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

/** Foreground service required by Android to hold a MediaProjection. Runs in the game process. */
public class RecService extends Service {
    private static final String CHANNEL = "mojorec";
    private static final int ID = 1537;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Rekam layar", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        Notification n = nb.setContentTitle("Merekam layar game")
                .setSmallIcon(getApplicationInfo().icon)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, 0x20 /* FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION */);
        } else {
            startForeground(ID, n);
        }
        MediaProjection proj = null;
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            int rc = intent == null ? 0 : intent.getIntExtra("rc", 0);
            Intent data = intent == null ? null : intent.getParcelableExtra("d");
            if (data != null) proj = mpm.getMediaProjection(rc, data);
            if (proj != null) {
                proj.registerCallback(new MediaProjection.Callback() {
                }, null);
            }
        } catch (Throwable t) {
            proj = null;
        }
        ScreenRecorder.onProjection(proj);
        return START_NOT_STICKY;
    }
}
