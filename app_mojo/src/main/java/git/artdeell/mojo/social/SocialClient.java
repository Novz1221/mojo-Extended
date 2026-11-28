package git.artdeell.mojo.social;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;

/**
 * Minimal client for a free Firebase Realtime Database over its REST API.
 * Needs only the database URL, no SDK and no google-services.json.
 */
public final class SocialClient {
    public static final String PREFS = "social";
    /** Default database, so players do not have to paste a URL */
    public static final String DEFAULT_URL = "https://noba-be394-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String KEY_URL = "db_url";
    private static final String KEY_VOICE_HINT = "voice_active";
    private static final String KEY_USER = "user";
    private static final String KEY_SECRET = "secret";

    public interface Callback {
        /** error is null on success; json is a JSONObject, JSONArray, String, Number, Boolean or null */
        void done(Object json, String error);
    }

    private final SharedPreferences prefs;

    public SocialClient(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- config and identity ----

    public String getUrl() {
        return prefs.getString(KEY_URL, DEFAULT_URL);
    }

    public void setUrl(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        prefs.edit().putString(KEY_URL, u).apply();
    }

    /** Petunjuk bahwa voice mungkin masih aktif di proses :voice. Hanya ditulis oleh proses launcher. */
    public boolean isVoiceHint() {
        return prefs.getBoolean(KEY_VOICE_HINT, false);
    }

    public void setVoiceHint(boolean active) {
        prefs.edit().putBoolean(KEY_VOICE_HINT, active).apply();
    }

    public boolean isConfigured() {
        return getUrl().startsWith("https://");
    }

    public String getUser() {
        return prefs.getString(KEY_USER, "");
    }

    public boolean isLoggedIn() {
        return !getUser().isEmpty();
    }

    public void logout() {
        prefs.edit().remove(KEY_USER).apply();
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    private String secret() {
        String s = prefs.getString(KEY_SECRET, null);
        if (s == null) {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            s = sb.toString();
            prefs.edit().putString(KEY_SECRET, s).apply();
        }
        return s;
    }

    private String secretHash() {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(secret().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- akun dengan password ----
    // Disimpan di users/{key}: n = nama, a = salt acak (32 hex), s = PBKDF2(password, salt) (64 hex).
    // Pengecekan dilakukan di aplikasi, karena Realtime Database tanpa Firebase Auth tidak bisa
    // memverifikasi password di sisi server. Password asli tidak pernah dikirim atau disimpan.

    public static final int MIN_PASSWORD = 6, MAX_PASSWORD = 64;

    public static String validatePassword(String pw) {
        if (pw == null || pw.length() < MIN_PASSWORD) return "Password minimal " + MIN_PASSWORD + " karakter";
        if (pw.length() > MAX_PASSWORD) return "Password maksimal " + MAX_PASSWORD + " karakter";
        return null;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    static String hashPassword(String password, String saltHex) throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(), unhex(saltHex), 20000, 256);
        byte[] d = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        return hex(d);
    }

    /** Daftar akun baru. Gagal kalau username sudah dipakai. Berhasil berarti langsung masuk. */
    public void register(String name, String password, Callback cb) {
        if (!validName(name)) {
            cb.done(null, "Username 3-16 karakter: huruf, angka, garis bawah");
            return;
        }
        String pwErr = validatePassword(password);
        if (pwErr != null) {
            cb.done(null, pwErr);
            return;
        }
        final String path = "users/" + key(name);
        get(path, (json, err) -> {
            if (err != null) {
                cb.done(null, err);
                return;
            }
            if (json instanceof JSONObject) {
                cb.done(null, "Username sudah dipakai. Pilih yang lain, atau tekan Masuk kalau itu akunmu.");
                return;
            }
            MojoApplication.sExecutorService.execute(() -> {
                String salt = newSalt();
                String hash;
                try {
                    hash = hashPassword(password, salt);
                } catch (Exception e) {
                    Tools.runOnUiThread(() -> cb.done(null, "Gagal memproses password: " + e.getMessage()));
                    return;
                }
                final String h = hash;
                Tools.runOnUiThread(() -> {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("n", name);
                        o.put("a", salt);
                        o.put("s", h);
                        put(path, o, (j, e) -> {
                            if (e == null) prefs.edit().putString(KEY_USER, name).apply();
                            cb.done(e == null ? Boolean.TRUE : null, e);
                        });
                    } catch (Exception e) {
                        cb.done(null, String.valueOf(e));
                    }
                });
            });
        });
    }

    /** Masuk ke akun yang sudah terdaftar. Akun lama (tanpa password) diamankan otomatis dari HP yang mendaftarkannya. */
    public void login(String name, String password, Callback cb) {
        if (!validName(name)) {
            cb.done(null, "Username 3-16 karakter: huruf, angka, garis bawah");
            return;
        }
        if (password == null || password.isEmpty()) {
            cb.done(null, "Isi password");
            return;
        }
        final String path = "users/" + key(name);
        get(path, (json, err) -> {
            if (err != null) {
                cb.done(null, err);
                return;
            }
            if (!(json instanceof JSONObject)) {
                cb.done(null, "Akun belum terdaftar. Tekan Daftar.");
                return;
            }
            JSONObject u = (JSONObject) json;
            final String stored = u.optString("s");
            final String salt = u.optString("a");
            final String shown = u.optString("n", name);
            MojoApplication.sExecutorService.execute(() -> {
                String error = null;
                boolean ok = false;
                String upgradeSalt = null, upgradeHash = null;
                try {
                    if (!salt.isEmpty()) {
                        ok = constantEquals(hashPassword(password, salt), stored);
                        if (!ok) error = "Password salah";
                    } else if (stored.equals(secretHash())) {
                        // akun lama milik HP ini: pasang password sekarang
                        String pwErr = validatePassword(password);
                        if (pwErr != null) {
                            error = "Akun lama ini belum punya password. " + pwErr;
                        } else {
                            upgradeSalt = newSalt();
                            upgradeHash = hashPassword(password, upgradeSalt);
                            ok = true;
                        }
                    } else {
                        error = "Username sudah dipakai orang lain";
                    }
                } catch (Exception e) {
                    error = "Gagal memeriksa password: " + e.getMessage();
                }
                final boolean fok = ok;
                final String fer = error, us = upgradeSalt, uh = upgradeHash;
                Tools.runOnUiThread(() -> {
                    if (!fok) {
                        cb.done(null, fer);
                        return;
                    }
                    if (us == null) {
                        prefs.edit().putString(KEY_USER, shown).apply();
                        cb.done(Boolean.TRUE, null);
                        return;
                    }
                    try {
                        JSONObject o = new JSONObject();
                        o.put("n", shown);
                        o.put("a", us);
                        o.put("s", uh);
                        put(path, o, (j, e) -> {
                            if (e == null) prefs.edit().putString(KEY_USER, shown).apply();
                            cb.done(e == null ? Boolean.TRUE : null, e);
                        });
                    } catch (Exception e) {
                        cb.done(null, String.valueOf(e));
                    }
                });
            });
        });
    }

    static boolean constantEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    // ---- REST helpers, all callbacks run on the UI thread ----

    public void get(String path, Callback cb) {
        request("GET", path, null, cb);
    }

    public void put(String path, Object body, Callback cb) {
        request("PUT", path, body, cb);
    }

    public void post(String path, Object body, Callback cb) {
        request("POST", path, body, cb);
    }

    /** PATCH lewat POST + x-http-method-override, karena HttpURLConnection tidak mendukung PATCH. */
    public void patch(String path, Object body, Callback cb) {
        request("POST", path + (path.contains("?") ? "&" : "?") + "x-http-method-override=PATCH", body, cb);
    }

    public void delete(String path, Callback cb) {
        request("DELETE", path, null, cb);
    }

    public static String q(String s) {
        try {
            return URLEncoder.encode("\"" + s + "\"", "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void request(String method, String path, Object body, Callback cb) {
        final String base = getUrl();
        MojoApplication.sExecutorService.execute(() -> {
            Object result = null;
            String error = null;
            HttpURLConnection c = null;
            try {
                if (!base.startsWith("https://")) throw new IllegalStateException("URL database belum diatur");
                String full = base + "/" + path + (path.contains("?") ? "" : ".json");
                if (path.contains("?")) {
                    int i = path.indexOf('?');
                    full = base + "/" + path.substring(0, i) + ".json" + path.substring(i);
                }
                c = (HttpURLConnection) new URL(full).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestMethod(method);
                if (body != null) {
                    byte[] data = (body instanceof String ? JSONObject.quote((String) body) : body.toString())
                            .getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(data);
                    }
                }
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = is == null ? "" : read(is);
                if (code >= 400) {
                    error = code == 401 || code == 403 ? "Ditolak oleh aturan database (rules)" : "HTTP " + code;
                } else {
                    result = parse(text);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
            final Object r = result;
            final String er = error;
            Tools.runOnUiThread(() -> cb.done(r, er));
        });
    }

    private static String read(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static Object parse(String text) {
        String t = text.trim();
        if (t.isEmpty() || t.equals("null")) return null;
        try {
            if (t.startsWith("{")) return new JSONObject(t);
            if (t.startsWith("[")) return new JSONArray(t);
            if (t.startsWith("\"")) return new org.json.JSONTokener(t).nextValue();
            return t;
        } catch (Exception e) {
            return null;
        }
    }
}
