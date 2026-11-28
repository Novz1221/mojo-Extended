package git.artdeell.mojo.social;

import org.json.JSONObject;

import java.util.Iterator;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;

/**
 * Aturan voice room: pembuat (voice/{id}/c), password opsional (voice/{id}/p = salt + hash PBKDF2),
 * dan hapus room yang hanya boleh dilakukan pembuatnya. Pengecekan ada di aplikasi,
 * karena database tanpa Firebase Auth tidak bisa memeriksa ini di sisi server.
 */
public final class VoiceRooms {
    public static final int MIN_PASSWORD = 4, MAX_PASSWORD = 32;
    private static final long STALE_MS = 90000;

    private VoiceRooms() { }

    /** Membungkus hasil voice/<room>/m menjadi objek room ({"m": ...}) agar bisa dibaca membersOf() */
    public static JSONObject withMembers(Object membersJson) {
        JSONObject room = new JSONObject();
        if (membersJson instanceof JSONObject) {
            try {
                room.put("m", membersJson);
            } catch (org.json.JSONException ignored) { }
        }
        return room;
    }

    public static boolean hasLock(JSONObject room) {
        return room != null && room.optJSONObject("p") != null;
    }

    public static boolean isOwner(JSONObject room, String userKey) {
        if (room == null) return false;
        String c = room.optString("c", "");
        return !c.isEmpty() && SocialClient.key(c).equals(userKey);
    }

    /** Buat room baru. Gagal kalau nama sudah punya pembuat. Password boleh kosong. */
    public static void create(SocialClient client, String name, String password, SocialClient.Callback cb) {
        final String pw = password == null ? "" : password;
        if (!pw.isEmpty() && (pw.length() < MIN_PASSWORD || pw.length() > MAX_PASSWORD)) {
            cb.done(null, "Password room " + MIN_PASSWORD + "-" + MAX_PASSWORD + " karakter");
            return;
        }
        final String path = "voice/" + name;
        client.get(path, (json, err) -> {
            if (err != null) {
                cb.done(null, err);
                return;
            }
            if (json instanceof JSONObject && ((JSONObject) json).has("c")) {
                cb.done(null, "Nama room sudah dipakai, pilih nama lain");
                return;
            }
            MojoApplication.sExecutorService.execute(() -> {
                JSONObject body = new JSONObject();
                String error = null;
                try {
                    body.put("c", client.getUser());
                    if (!pw.isEmpty()) {
                        String salt = SocialClient.newSalt();
                        JSONObject lock = new JSONObject();
                        lock.put("a", salt);
                        lock.put("s", SocialClient.hashPassword(pw, salt));
                        body.put("p", lock);
                    }
                } catch (Exception e) {
                    error = "Gagal memproses password: " + e.getMessage();
                }
                final String fe = error;
                Tools.runOnUiThread(() -> {
                    if (fe != null) {
                        cb.done(null, fe);
                        return;
                    }
                    client.patch(path, body, (j, e) -> cb.done(e == null ? Boolean.TRUE : null, e));
                });
            });
        });
    }

    /** Cocokkan password dengan kunci room. cb.done(TRUE, null) kalau benar. */
    public static void verify(JSONObject room, String password, SocialClient.Callback cb) {
        JSONObject lock = room == null ? null : room.optJSONObject("p");
        if (lock == null) {
            cb.done(Boolean.TRUE, null);
            return;
        }
        final String salt = lock.optString("a"), stored = lock.optString("s");
        MojoApplication.sExecutorService.execute(() -> {
            boolean ok = false;
            try {
                ok = SocialClient.constantEquals(SocialClient.hashPassword(password == null ? "" : password, salt), stored);
            } catch (Exception ignored) { }
            final boolean fok = ok;
            Tools.runOnUiThread(() -> cb.done(fok ? Boolean.TRUE : null, fok ? null : "Password room salah"));
        });
    }

    /** Hapus room: hanya pembuatnya, dan hanya kalau tidak ada orang lain di dalam. */
    public static void delete(SocialClient client, String id, SocialClient.Callback cb) {
        final String myKey = SocialClient.key(client.getUser());
        client.get("voice/" + id, (json, err) -> {
            if (err != null) {
                cb.done(null, err);
                return;
            }
            JSONObject room = json instanceof JSONObject ? (JSONObject) json : null;
            if (!isOwner(room, myKey)) {
                cb.done(null, "Hanya pembuat room yang bisa menghapusnya");
                return;
            }
            if (othersInside(room, myKey)) {
                cb.done(null, "Masih ada orang di room ini");
                return;
            }
            client.delete("voice/" + id, (j, e) -> cb.done(e == null ? Boolean.TRUE : null, e));
        });
    }

    private static boolean othersInside(JSONObject room, String myKey) {
        JSONObject mem = room.optJSONObject("m");
        if (mem == null) return false;
        long now = System.currentTimeMillis();
        Iterator<String> it = mem.keys();
        while (it.hasNext()) {
            String u = it.next();
            if (u.equals(myKey)) continue;
            Object v = mem.opt(u);
            long ts = v instanceof JSONObject ? ((JSONObject) v).optLong("t") : mem.optLong(u);
            if (now - ts < STALE_MS) return true;
        }
        return false;
    }
}
