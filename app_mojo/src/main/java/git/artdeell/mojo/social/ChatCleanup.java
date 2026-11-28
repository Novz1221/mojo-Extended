package git.artdeell.mojo.social;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Menghapus pesan chat yang umurnya lebih dari 3 hari. Realtime Database tidak punya TTL,
 * jadi pembersihan dijalankan dari aplikasi (paling sering sekali per 6 jam) dan memakai
 * jam server Firebase supaya tidak bergantung pada jam HP.
 */
public final class ChatCleanup {
    public static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;
    private static final long EVERY_MS = 6L * 60 * 60 * 1000;
    private static final int BATCH = 100, MAX_ROUNDS = 30;
    private static final String KEY_LAST = "last_cleanup";
    private static boolean running;

    private ChatCleanup() { }

    public static void runIfDue(Context ctx, SocialClient client) {
        if (running || !client.isConfigured() || !client.isLoggedIn()) return;
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(SocialClient.PREFS, Context.MODE_PRIVATE);
        long phoneNow = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST, 0);
        if (last <= phoneNow && phoneNow - last < EVERY_MS) return;
        running = true;
        try {
            JSONObject stamp = new JSONObject().put(".sv", "timestamp");
            client.put("cleanup/ping", stamp, (json, err) -> {
                long server = 0;
                try {
                    if (json != null) server = Long.parseLong(String.valueOf(json).trim());
                } catch (NumberFormatException ignored) { }
                if (err != null || server <= 0) {
                    running = false;
                    return;
                }
                final long cutoff = server - MAX_AGE_MS;
                client.get("messages?shallow=true", (rooms, e2) -> {
                    running = false;
                    if (e2 != null || !(rooms instanceof JSONObject)) return;
                    prefs.edit().putLong(KEY_LAST, phoneNow).apply();
                    Iterator<String> it = ((JSONObject) rooms).keys();
                    while (it.hasNext()) cleanRoom(client, it.next(), cutoff, 0);
                });
            });
        } catch (Exception e) {
            running = false;
        }
    }

    /** Pesan diurutkan dari yang terlama; hapus per 100 sampai ketemu pesan yang masih baru. */
    private static void cleanRoom(SocialClient client, String room, long cutoff, int round) {
        if (round >= MAX_ROUNDS) return;
        client.get("messages/" + room + "?orderBy=" + SocialClient.q("$key") + "&limitToFirst=" + BATCH, (json, err) -> {
            if (err != null || !(json instanceof JSONObject)) return;
            JSONObject o = (JSONObject) json;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            JSONObject patch = new JSONObject();
            int old = 0;
            try {
                for (String k : keys) {
                    JSONObject m = o.optJSONObject(k);
                    long ts = m == null ? 0 : m.optLong("ts");
                    if (ts > 0 && ts < cutoff) {
                        patch.put(k, JSONObject.NULL);
                        old++;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                return;
            }
            if (old == 0) return;
            final boolean more = old == keys.size() && keys.size() >= BATCH;
            client.patch("messages/" + room, patch, (j, e) -> {
                if (e == null && more) cleanRoom(client, room, cutoff, round + 1);
            });
        });
    }
}
