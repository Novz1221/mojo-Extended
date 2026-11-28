package git.artdeell.mojo.social;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Foto profil dan warna avatar. Foto disimpan sebagai JPEG kecil (96x96) dalam teks base64
 * di Firebase pada avatars/{key}, jadi tidak perlu Firebase Storage.
 * Semua callback jalan di UI thread, dan class ini aman dipakai dari proses launcher maupun game.
 */
public final class Avatars {
    public static final int SIZE = 96;
    public static final int RING_ON = 0xFF4CD964;
    public static final int RING_OFF = 0x88B0B0B0;

    private static final int[] COLORS = {
            0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF7986CB, 0xFF4FC3F7,
            0xFF4DB6AC, 0xFF81C784, 0xFFFFD54F, 0xFFFFB74D, 0xFFA1887F
    };
    private static final long REFETCH_MS = 120000;

    private static final Map<String, Bitmap> PHOTOS = new HashMap<>();
    private static final Map<String, Long> FETCHED = new HashMap<>();

    private Avatars() { }

    /** Warna acak tapi tetap sama untuk orang yang sama di semua HP */
    public static int colorFor(String key) {
        int h = key == null ? 0 : key.toLowerCase(Locale.ROOT).hashCode();
        return COLORS[(h & 0x7fffffff) % COLORS.length];
    }

    public static Bitmap photo(String key) {
        return key == null ? null : PHOTOS.get(key.toLowerCase(Locale.ROOT));
    }

    /** Ambil foto dari server kalau belum ada atau sudah lama. onChange dipanggil kalau fotonya berubah. */
    public static void fetch(SocialClient client, String key, Runnable onChange) {
        if (key == null || key.isEmpty()) return;
        final String k = key.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = FETCHED.get(k);
        if (last != null && now - last < REFETCH_MS) return;
        FETCHED.put(k, now);
        client.get("avatars/" + k, (json, err) -> {
            if (err != null || !(json instanceof String)) return;
            Bitmap b = decode((String) json);
            if (b == null) return;
            PHOTOS.put(k, b);
            if (onChange != null) onChange.run();
        });
    }

    public static void put(String key, Bitmap b) {
        if (key != null && b != null) PHOTOS.put(key.toLowerCase(Locale.ROOT), b);
    }

    public static Bitmap decode(String b64) {
        try {
            byte[] d = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(d, 0, d.length);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Potong persegi dari tengah, kecilkan ke 96x96, jadikan JPEG base64. Jalankan di thread latar. */
    public static String encode(Context ctx, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= SIZE * 2 && bounds.outHeight / (sample * 2) >= SIZE * 2) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            src = BitmapFactory.decodeStream(in, null, o);
        }
        if (src == null) throw new IllegalStateException("Gambar tidak bisa dibaca");
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap out = Bitmap.createScaledBitmap(square, SIZE, SIZE, true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 80, bo);
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }
}
