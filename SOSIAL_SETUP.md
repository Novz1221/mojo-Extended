# Setup fitur Sosial

URL database sudah tertanam di launcher (`SocialClient.DEFAULT_URL`), pemain tidak perlu memasukkan apa pun.
Langkah satu kali, hanya untuk pemilik project Firebase `noba-be394`:

1. Buka https://console.firebase.google.com > project noba-be394 > Build > Realtime Database > tab Rules.
2. Tempel isi `database.rules.json` dari zip ini, lalu Publish.

Tanpa langkah ini, chat dan akun akan ditolak dengan pesan "Ditolak oleh aturan database".

Catatan:
- Belum ada login sungguhan, jadi orang yang paham API bisa menulis pesan atas nama lain.
- Voice room: daftar room dan anggota lewat Firebase, audio langsung lewat WebRTC P2P (lihat bagian Voice chat di bawah).
- Paket gratis Spark: 1 GB data, 10 GB unduhan per bulan.
- Mengganti database: tab Akun > Ganti database.

## Voice chat (WebRTC P2P)

Audio langsung antar HP lewat WebRTC (library `io.getstream:stream-webrtc-android` di `app_mojo/build.gradle`). Gratis, tanpa akun, tanpa App ID, tanpa token. Pertukaran sinyal (offer, answer, ICE) memakai Firebase yang sama dengan chat, di `voice/{room}/sig/{uid}`. Aturan `database.rules.json` yang ada sudah mengizinkan path ini.

Catatan voice:
- Maksimal 6 orang per room (`MAX_VOICE_MEMBERS`). Mesh P2P: tiap HP mengirim audio ke semua teman, jadi makin banyak orang makin berat.
- Server STUN gratis dipakai untuk menembus NAT. Kalau ada pemain yang tidak terdengar (sering terjadi di jaringan seluler tertentu), perlu server relay TURN. Tambahkan di `WebRtcVoiceEngine.mIce` lewat `PeerConnection.IceServer.builder("turn:...").setUsername(..).setPassword(..)`.
- Mesin audio dibungkus `VoiceEngine`, implementasinya `WebRtcVoiceEngine`, dibuat di `VoiceService.join()`.
- Voice berjalan di `VoiceService` pada proses terpisah `:voice`, jadi tetap hidup saat game dimulai (launcher dimatikan oleh `ContextAwareDoneListener`) dan saat layar Sosial ditutup. Engine, presence Firebase, dan timer keluar otomatis ada di service ini. `SocialFragment` hanya mengirim perintah dan menerima status lewat broadcast.
- Di dalam game, kontrol lewat notifikasi "Voice chat aktif": tombol Matikan/Nyalakan mic dan Keluar. Izinkan notifikasi untuk app ini supaya tombolnya muncul.
- `MojoApplication` melewati inisialisasi launcher di proses `:voice`. `VoiceService` tidak boleh memakai `Tools`, `LauncherPreferences`, atau `SocialClient`.
- Pakai headset saat main. Suara game dari speaker bisa terambil mikrofon dan terdengar oleh teman.
- Keluar dari game tidak mematikan voice. Voice berhenti lewat tombol Keluar, keluar otomatis, atau Force Stop.
- Belum dicoba build nyata. Kalau ada error compile, kirim lognya.


## Menu sosial di dalam game

Di game, buka drawer menu lalu tekan **Sosial** untuk menampilkan bar ikon di kiri atas: Teman, Voice, Chat. Tekan lagi untuk menyembunyikannya.
- **Teman**: tambah lewat username (ADD mengirim permintaan), REQUEST untuk menerima atau menolak, ikon chat membuka chat pribadi, ikon telepon membuka voice room pribadi berdua.
- **Voice**: daftar room (SEARCH, JOIN, CREATE) dan layar panggilan dengan tombol tutup, mute, dan SUARA (volume tiap teman dan sensitivitas mic).
- **Chat**: tab UMUM, room lain, dan chat pribadi. Mengetik lewat dialog.
- Avatar pojok kiri atas muncul saat kamu ada di voice room. Cincin hijau berarti sedang bicara.
- Foto profil: Sosial di launcher, tab **Akun**, tombol **Ganti foto profil**. Foto disimpan 96x96 di `avatars/{username}`.
- Warna avatar dan tile panggilan acak per orang, tetap sama di semua HP.

Aturan database: file `database.rules.json` ditambah `avatars` dan `requests`. Salin isinya ke Firebase Console, Realtime Database, tab Rules, lalu Publish. Tanpa ini foto profil dan permintaan teman ditolak.

## Akun dengan password

Tab Akun di launcher sekarang punya **Daftar** dan **Masuk**. Daftar: isi username, password (minimal 6 karakter), ulangi password, lalu tekan Daftar, dan kamu langsung masuk. Berikutnya cukup Masuk dengan username dan password yang sama.
- Password di-hash dengan PBKDF2 (salt acak) di HP, jadi password asli tidak pernah dikirim ke Firebase. Yang tersimpan hanya `a` (salt) dan `s` (hash) di `users/{username}`.
- Akun lama tanpa password: masuk dari HP yang dulu mendaftarkannya, isi password baru, dan password itu otomatis terpasang. Dari HP lain akun lama tidak bisa dipakai.
- Pengecekan password dilakukan di aplikasi. Realtime Database tanpa Firebase Auth tidak bisa memeriksa password di server, jadi aturan database lain (`friends`, `voice`, `avatars`) tetap terbuka bagi yang membongkar APK.
- Salin `database.rules.json` terbaru ke Firebase Console lalu Publish, supaya akun baru dengan field `a` diterima.
