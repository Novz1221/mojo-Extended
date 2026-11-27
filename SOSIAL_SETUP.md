# Setup fitur Sosial

URL database sudah tertanam di launcher (`SocialClient.DEFAULT_URL`), pemain tidak perlu memasukkan apa pun.
Langkah satu kali, hanya untuk pemilik project Firebase `noba-be394`:

1. Buka https://console.firebase.google.com > project noba-be394 > Build > Realtime Database > tab Rules.
2. Tempel isi `database.rules.json` dari zip ini, lalu Publish.

Tanpa langkah ini, chat dan akun akan ditolak dengan pesan "Ditolak oleh aturan database".

Catatan:
- Belum ada login sungguhan, jadi orang yang paham API bisa menulis pesan atas nama lain.
- Voice room: daftar room dan anggota lewat Firebase, audio langsung lewat Agora (lihat bagian Voice chat di bawah).
- Paket gratis Spark: 1 GB data, 10 GB unduhan per bulan.
- Mengganti database: tab Akun > Ganti database.

## Voice chat (Agora)

Sudah terpasang di proyek ini: dependency `io.agora.rtc:voice-sdk` di `app_mojo/build.gradle`, izin dan `VoiceService` di manifest, aturan proguard, serta Agora App ID bawaan di `SocialClient.DEFAULT_AGORA_APP_ID`.

Kalau mau memakai project Agora lain: buat project di https://console.agora.io dengan mode Testing (App ID saja, tanpa token), lalu ganti `DEFAULT_AGORA_APP_ID` atau isi lewat tab Akun > "Agora App ID (voice chat)".

Catatan voice:
- Kuota gratis Agora 10.000 menit per bulan, dihitung per peserta. Pengaman di kode: maksimal 6 orang per room (`MAX_VOICE_MEMBERS`), keluar otomatis kalau sendirian 2 menit atau tidak ada yang bicara 10 menit.
- Mesin audio dibungkus `VoiceEngine`. Untuk ganti ke WebRTC P2P atau LiveKit, buat kelas baru yang mengimplementasikan interface itu dan ganti `new AgoraVoiceEngine(...)` di `VoiceService.join()`.
- Voice berjalan di `VoiceService` pada proses terpisah `:voice`, jadi tetap hidup saat game dimulai (launcher dimatikan oleh `ContextAwareDoneListener`) dan saat layar Sosial ditutup. Engine, presence Firebase, dan timer keluar otomatis ada di service ini. `SocialFragment` hanya mengirim perintah dan menerima status lewat broadcast.
- Di dalam game, kontrol lewat notifikasi "Voice chat aktif": tombol Matikan/Nyalakan mic dan Keluar. Izinkan notifikasi untuk app ini supaya tombolnya muncul.
- `MojoApplication` melewati inisialisasi launcher di proses `:voice`. `VoiceService` tidak boleh memakai `Tools`, `LauncherPreferences`, atau `SocialClient`.
- Pakai headset saat main. Suara game dari speaker bisa terambil mikrofon dan terdengar oleh teman.
- Keluar dari game tidak mematikan voice. Voice berhenti lewat tombol Keluar, keluar otomatis, atau Force Stop.
- Dua baris di `AgoraVoiceEngine.join()` yang paling mungkin error kalau nama API berbeda di versi SDK: `cfg.mAudioScenario` dan `setDefaultAudioRoutetoSpeakerphone`. Hapus baris itu kalau compile gagal.
- Belum dicoba build nyata. Kalau ada error compile, kirim lognya.
