package git.artdeell.mojo.social;

import java.util.Set;

/**
 * Lapisan tipis di atas mesin audio realtime. UI hanya bicara ke interface ini,
 * jadi mesinnya (Agora, WebRTC P2P, LiveKit) bisa diganti tanpa mengubah SocialFragment.
 * Semua callback Listener dipanggil di UI thread.
 */
public interface VoiceEngine {
    interface Listener {
        /** Berhasil masuk channel audio */
        void onJoined();

        /** Gagal tersambung atau koneksi putus permanen */
        void onError(String message);

        /** uid yang sedang bicara saat ini (suara sendiri memakai uid lokal) */
        void onSpeaking(Set<Integer> uids);

        /** Jumlah peserta lain yang terhubung ke channel audio */
        void onRemoteCount(int count);
    }

    /** Mulai sesi. Melempar exception kalau mesin gagal dibuat. */
    void join(String channel, int uid, Listener listener) throws Exception;

    void setMuted(boolean muted);

    /** Keluar dan membebaskan semua resource. Aman dipanggil berkali-kali. */
    void leave();
}
