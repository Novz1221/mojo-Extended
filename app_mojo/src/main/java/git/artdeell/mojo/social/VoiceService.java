package git.artdeell.mojo.social;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import git.artdeell.mojo.R;

/**
 * Menjalankan voice chat di proses sendiri (":voice"), terpisah dari launcher dan game.
 * Proses launcher dimatikan saat game dimulai, jadi engine audio tidak boleh hidup di sana.
 * Service ini memegang engine, presence Firebase, dan timer keluar otomatis.
 * UI berkomunikasi lewat intent (join, leave, mute) dan menerima status lewat broadcast ACTION_STATE.
 * Class ini tidak boleh memakai Tools, LauncherPreferences, atau SocialClient karena
 * proses ini tidak menjalankan inisialisasi launcher.
 */
public class VoiceService extends Service {
    public static final String ACTION_STATE = "git.artdeell.mojo.voice.STATE";
    private static final String ACTION_JOIN = "git.artdeell.mojo.voice.JOIN";
    private static final String ACTION_LEAVE = "git.artdeell.mojo.voice.LEAVE";
    private static final String ACTION_MUTE = "git.artdeell.mojo.voice.MUTE";
    private static final String ACTION_TOGGLE_MUTE = "git.artdeell.mojo.voice.TOGGLE_MUTE";
    private static final String ACTION_QUERY = "git.artdeell.mojo.voice.QUERY";
    private static final String ACTION_PEER_VOL = "git.artdeell.mojo.voice.PEER_VOL";
    private static final String ACTION_MIC_SENS = "git.artdeell.mojo.voice.MIC_SENS";

    private static final String CHANNEL = "voice_chat";
    private static final int NOTIF_ID = 7721;
    private static final long BEAT_MS = 30000;

    // ---- API untuk UI ----

    /** false kalau sistem menolak menjalankan service */
    public static boolean join(Context c, String room, String key, int uid, String dbUrl) {
        Intent i = new Intent(c, VoiceService.class).setAction(ACTION_JOIN)
                .putExtra("room", room).putExtra("key", key).putExtra("uid", uid)
                .putExtra("dbUrl", dbUrl);
        try {
            ContextCompat.startForegroundService(c, i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void leave(Context c) {
        send(c, new Intent(c, VoiceService.class).setAction(ACTION_LEAVE));
    }

    public static void setMuted(Context c, boolean muted) {
        send(c, new Intent(c, VoiceService.class).setAction(ACTION_MUTE).putExtra("muted", muted));
    }

    public static void setPeerVolume(Context c, int uid, int percent) {
        send(c, new Intent(c, VoiceService.class).setAction(ACTION_PEER_VOL)
                .putExtra("uid", uid).putExtra("percent", percent));
    }

    public static void setMicSensitivity(Context c, int percent) {
        send(c, new Intent(c, VoiceService.class).setAction(ACTION_MIC_SENS).putExtra("percent", percent));
    }

    /** Minta service mengirim status terbaru lewat ACTION_STATE */
    public static void query(Context c) {
        send(c, new Intent(c, VoiceService.class).setAction(ACTION_QUERY));
    }

    private static void send(Context c, Intent i) {
        try {
            c.startService(i);
        } catch (RuntimeException ignored) { }
    }

    // ---- state ----

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private ExecutorService mNet;
    private VoiceEngine mEngine;
    private boolean mActive, mMuted;
    private String mRoom, mKey, mDbUrl;
    private int mRemote;
    private Set<Integer> mSpeaking = new HashSet<>();
    private long mActiveAt;

    private final Runnable mBeat = new Runnable() {
        @Override
        public void run() {
            if (!mActive) return;
            putPresence();
            mUi.postDelayed(this, BEAT_MS);
        }
    };

    private final VoiceEngine.Listener mListener = new VoiceEngine.Listener() {
        @Override
        public void onJoined() { }

        @Override
        public void onError(String message) {
            if (mActive) end(message);
        }

        @Override
        public void onSpeaking(Set<Integer> uids) {
            boolean changed = !uids.equals(mSpeaking);
            mSpeaking = uids;
            if (!uids.isEmpty()) mActiveAt = SystemClock.elapsedRealtime();
            if (changed) broadcastState(null);
        }

        @Override
        public void onRemoteCount(int count) {
            mRemote = count;
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_JOIN.equals(a)) {
            join(intent);
        } else if (ACTION_LEAVE.equals(a)) {
            end(null);
        } else if (ACTION_MUTE.equals(a)) {
            if (mActive) setMuted(intent.getBooleanExtra("muted", false));
        } else if (ACTION_TOGGLE_MUTE.equals(a)) {
            if (mActive) setMuted(!mMuted);
        } else if (ACTION_QUERY.equals(a)) {
            broadcastState(null);
        } else if (ACTION_PEER_VOL.equals(a)) {
            if (mActive && mEngine != null) mEngine.setPeerVolume(intent.getIntExtra("uid", 0), intent.getIntExtra("percent", 90));
        } else if (ACTION_MIC_SENS.equals(a)) {
            if (mActive && mEngine != null) mEngine.setMicSensitivity(intent.getIntExtra("percent", 90));
        }
        if (!mActive) stopSelf();
        return START_NOT_STICKY;
    }

    private void join(Intent i) {
        if (mActive) cleanup();
        mRoom = i.getStringExtra("room");
        mKey = i.getStringExtra("key");
        mDbUrl = i.getStringExtra("dbUrl");
        int uid = i.getIntExtra("uid", 1);
        mMuted = false;
        mRemote = 0;
        mSpeaking = new HashSet<>();

        // Harus dipanggil cepat setelah startForegroundService
        if (!startForegroundSafe()) {
            broadcastState("Tidak bisa menjalankan layanan suara");
            return;
        }
        if (mRoom == null || mKey == null || mDbUrl == null) {
            end("Data voice room tidak lengkap");
            return;
        }
        try {
            mEngine = new WebRtcVoiceEngine(this, mDbUrl, mRoom, mKey);
            mEngine.join("mojo_" + mRoom, uid, mListener);
        } catch (Throwable t) {
            mEngine = null;
            end("Gagal memulai suara: " + t.getMessage());
            return;
        }
        mActive = true;
        mActiveAt = SystemClock.elapsedRealtime();
        putPresence();
        mUi.postDelayed(mBeat, BEAT_MS);
        broadcastState(null);
    }

    private void setMuted(boolean muted) {
        mMuted = muted;
        if (mEngine != null) mEngine.setMuted(muted);
        putPresence();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        broadcastState(null);
    }

    /** Menutup sesi, memberi tahu UI, lalu menghentikan service */
    private void end(String message) {
        cleanup();
        broadcastState(message);
        stopForegroundSafe();
        stopSelf();
    }

    private void cleanup() {
        mUi.removeCallbacks(mBeat);
        if (mEngine != null) {
            mEngine.leave();
            mEngine = null;
        }
        if (mActive) deletePresence(mRoom, mKey, mDbUrl);
        mActive = false;
        mSpeaking = new HashSet<>();
        mRemote = 0;
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (mNet != null) mNet.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- status ke UI ----

    private void broadcastState(String error) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("active", mActive);
        String room = mActive ? mRoom : null;
        i.putExtra("room", room);
        i.putExtra("muted", mMuted);
        int[] sp = new int[mSpeaking.size()];
        int n = 0;
        for (int u : mSpeaking) sp[n++] = u;
        i.putExtra("speaking", sp);
        if (error != null) i.putExtra("error", error);
        sendBroadcast(i);
    }

    // ---- notifikasi ----

    private boolean startForegroundSafe() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Voice chat", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void stopForegroundSafe() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent open = launch == null ? null : PendingIntent.getActivity(this, 0, launch, flags);
        PendingIntent mute = PendingIntent.getService(this, 1,
                new Intent(this, VoiceService.class).setAction(ACTION_TOGGLE_MUTE), flags);
        PendingIntent leave = PendingIntent.getService(this, 2,
                new Intent(this, VoiceService.class).setAction(ACTION_LEAVE), flags);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_microphone)
                .setContentTitle(mMuted ? "Voice chat (mic mati)" : "Voice chat aktif")
                .setContentText(mRoom == null ? "Terhubung ke voice room" : "Room: " + mRoom)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, mMuted ? "Nyalakan mic" : "Matikan mic", mute)
                .addAction(0, "Keluar", leave);
        if (open != null) b.setContentIntent(open);
        return b.build();
    }

    // ---- presence Firebase (REST), tanpa SocialClient ----

    private void putPresence() {
        net("PUT", mDbUrl, "voice/" + mRoom + "/m/" + mKey,
                "{\"t\":{\".sv\":\"timestamp\"},\"x\":" + mMuted + "}");
    }

    private void deletePresence(String room, String key, String dbUrl) {
        net("DELETE", dbUrl, "voice/" + room + "/m/" + key, null);
    }

    private void net(final String method, final String base, final String path, final String body) {
        if (base == null || !base.startsWith("https://")) return;
        if (mNet == null || mNet.isShutdown()) mNet = Executors.newSingleThreadExecutor();
        mNet.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/" + path + ".json").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestMethod(method);
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                c.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }
}
