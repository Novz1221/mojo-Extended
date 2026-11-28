package git.artdeell.mojo.social;

import android.service.notification.NotificationListenerService;

/**
 * Kosong dengan sengaja. Android hanya mengizinkan aplikasi membaca sesi media
 * (judul, artis, posisi lagu) kalau punya akses notifikasi, dan akses itu
 * diberikan per komponen listener. LyricsOverlay memakai komponen ini.
 */
public class NowPlayingListener extends NotificationListenerService {
}
