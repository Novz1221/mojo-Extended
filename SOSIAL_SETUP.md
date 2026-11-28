# Panduan Firebase Pribadi untuk Fitur Sosial (Mabar)

Fitur Sosial di launcher (chat, teman, request, voice room, akun) memakai Firebase Realtime Database.
Dengan panduan ini kamu membuat database sendiri, jadi data grup temanmu tidak bercampur dengan database bawaan.

Gratis: paket Spark, tanpa kartu kredit (1 GB data, 10 GB unduhan per bulan).

> Catatan: ini hanya untuk chat, teman, dan voice. Server game Minecraft untuk mabar adalah hal terpisah.

---

## Bagian 1: Buat project Firebase

1. Buka https://console.firebase.google.com lalu login dengan akun Google.
2. Tekan Add project (Buat project).
3. Isi nama bebas, misalnya mabar-kita, lalu Continue.
4. Google Analytics boleh dimatikan, lalu Create project dan tunggu selesai.

## Bagian 2: Buat Realtime Database

1. Di menu kiri pilih Build > Realtime Database.
2. Tekan Create Database.
3. Lokasi: pilih Singapore (asia-southeast1) supaya dekat dengan Indonesia.
4. Mode keamanan: pilih Locked mode (akan diganti di bagian berikutnya), lalu Enable.

## Bagian 3: Pasang aturan (Rules)

Tanpa langkah ini chat, akun, foto profil, dan request teman akan ditolak dengan pesan "Ditolak oleh aturan database".

1. Buka tab Rules di Realtime Database.
2. Hapus semua isinya.
3. Tempel seluruh isi di bawah ini (sama dengan database.rules.json di proyek), lalu tekan Publish.

{
  "rules": {
    "users": {
      "$u": {
        ".read": true,
        ".write": "!data.exists() || !data.child('a').exists()",
        ".validate": "newData.hasChildren(['n','s','a'])",
        "n": {
          ".validate": "newData.isString() && newData.val().length >= 3 && newData.val().length <= 16"
        },
        "s": {
          ".validate": "newData.isString() && newData.val().length == 64"
        },
        "a": {
          ".validate": "newData.isString() && newData.val().length == 32"
        }
      }
    },
    "rooms": {
      ".read": true,
      "$r": {
        ".write": true,
        ".validate": "$r.length <= 20 && newData.val() == true"
      }
    },
    "messages": {
      "$r": {
        ".read": true,
        "$m": {
          ".write": "!data.exists()",
          ".validate": "newData.hasChildren(['u','t','ts']) && newData.child('t').isString() && newData.child('t').val().length <= 300 && newData.child('u').isString() && newData.child('u').val().length <= 16 && newData.child('ts').val() == now"
        }
      }
    },
    "friends": {
      "$u": {
        ".read": true,
        ".write": true
      }
    },
    "voice": {
      ".read": true,
      "$r": {
        ".write": true
      }
    },
    "avatars": {
      "$u": {
        ".read": true,
        ".write": true,
        ".validate": "newData.isString() && newData.val().length < 20000"
      }
    },
    "requests": {
      "$u": {
        ".read": true,
        "$f": {
          ".write": true,
          ".validate": "newData.isString() && newData.val().length <= 16"
        }
      }
    }
  }
}

## Bagian 4: Salin URL database

1. Buka tab Data.
2. Salin URL yang tampil di bagian atas. Bentuknya:

https://nama-project-default-rtdb.asia-southeast1.firebasedatabase.app

3. Pakai persis seperti itu, tanpa garis miring (/) di belakang. URL harus diawali https://.

## Bagian 5: Hubungkan ke launcher

Pilih salah satu.

### Cara A: lewat aplikasi (tanpa build ulang)

1. Buka launcher, masuk Sosial.
2. Tab Akun, tekan Ganti database.
3. Tempel URL dari Bagian 4, lalu Simpan.
4. Ulangi di HP setiap teman.

### Cara B: tanam di kode (teman tidak perlu mengisi apa pun)

1. Buka app_mojo/src/main/java/git/artdeell/mojo/social/SocialClient.java.
2. Ganti nilai DEFAULT_URL dengan URL barumu:

public static final String DEFAULT_URL = "https://nama-project-default-rtdb.asia-southeast1.firebasedatabase.app";

3. Build APK lalu bagikan ke teman.

## Bagian 6: Mulai pakai 
1. Setiap orang membuka Sosial > tab Akun > Daftar (username 3 sampai 16 karakter, password minimal 6 karakter).
2. Tambah teman lewat tab Teman. Tombol Request menampilkan permintaan masuk.
3. Buat atau gabung voice room dari tab Voice.
4. Pakai headset saat voice supaya suara game tidak terambil mikrofon.

---

## Catatan penting

- Semua pemain harus memakai URL yang sama, kalau tidak kalian tidak saling terlihat.
- Keamanan: aturan database masih terbuka. Password dicek di aplikasi, jadi orang yang membongkar APK bisa menulis data. Untuk grup teman sendiri cukup, tapi jangan dibagikan ke publik luas.
- Voice memakai WebRTC antar HP (maksimal 6 orang per room). Firebase hanya untuk pertukaran sinyal. Kalau ada teman yang tidak terdengar di jaringan seluler tertentu, perlu server relay TURN. Tambahkan di WebRtcVoiceEngine.mIce:

-yg ini ga gw tambahin btw males anu banyak server


PeerConnection.IceServer.builder("turn:alamat-server").setUsername("user").setPassword("pass")

- Batas gratis: kalau pemakaian melewati paket Spark, akses database bisa dibatasi. Pantau di tab Usage.

## Masalah umum

| Gejala | Penyebab | Solusi |
| --- | --- | --- |
| "Ditolak oleh aturan database" | Rules belum dipasang atau belum Publish | Ulangi Bagian 3 |
| Tidak bisa terhubung | URL salah, ada / di belakang, atau tidak https:// | Cek lagi URL di Bagian 4 |
| Teman tidak muncul | Beda URL database | Samakan URL di semua HP |
| Suara teman tidak terdengar | NAT jaringan seluler | Tambahkan server TURN |
