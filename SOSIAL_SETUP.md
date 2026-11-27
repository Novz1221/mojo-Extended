# Setup fitur Sosial

URL database sudah tertanam di launcher (`SocialClient.DEFAULT_URL`), pemain tidak perlu memasukkan apa pun.
Langkah satu kali, hanya untuk pemilik project Firebase `noba-be394`:

1. Buka https://console.firebase.google.com > project noba-be394 > Build > Realtime Database > tab Rules.
2. Tempel isi `database.rules.json` dari zip ini, lalu Publish.

Tanpa langkah ini, chat dan akun akan ditolak dengan pesan "Ditolak oleh aturan database".

Catatan:
- Belum ada login sungguhan, jadi orang yang paham API bisa menulis pesan atas nama lain.
- Voice room hanya daftar room dan anggota. Audio langsung butuh WebRTC/LiveKit.
- Paket gratis Spark: 1 GB data, 10 GB unduhan per bulan.
- Mengganti database: tab Akun > Ganti database.
