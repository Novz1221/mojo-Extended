package git.artdeell.mojo.social;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mengambil lirik berstempel waktu dari LRCLIB (lrclib.net), tanpa API key. */
public final class LyricsClient {

    public static final class Line {
        public final long t;
        public final String text;

        Line(long t, String text) {
            this.t = t;
            this.text = text;
        }
    }

    public interface Callback {
        /** lines null kalau lirik tidak ditemukan. Selalu dipanggil di main thread. */
        void onResult(String key, List<Line> lines);
    }

    private static final String BASE = "https://lrclib.net/api/";
    private static final String UA = "MojoExtended/1.0";
    private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern INLINE_TS = Pattern.compile("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final File dir;
    private final Map<String, List<Line>> mem = new HashMap<>();
    private final List<String> missing = new ArrayList<>();

    public LyricsClient(Context c) {
        dir = new File(c.getCacheDir(), "lyrics");
    }

    public void shutdown() {
        ex.shutdownNow();
    }

    public void fetch(final String title, final String artist, final String album, final long durMs, final Callback cb) {
        final String key = keyOf(title, artist, durMs);
        synchronized (this) {
            if (mem.containsKey(key)) {
                final List<Line> l = mem.get(key);
                main.post(() -> cb.onResult(key, l));
                return;
            }
            if (missing.contains(key)) {
                main.post(() -> cb.onResult(key, null));
                return;
            }
        }
        try {
            ex.execute(() -> {
                List<Line> lines = null;
                try {
                    String lrc = readDisk(key);
                    if (lrc == null) {
                        lrc = download(title, artist, album, durMs);
                        if (lrc != null) writeDisk(key, lrc);
                    }
                    if (lrc != null) lines = parse(lrc);
                } catch (Throwable ignored) { }
                if (lines != null && lines.isEmpty()) lines = null;
                synchronized (LyricsClient.this) {
                    if (lines != null) mem.put(key, lines);
                    else missing.add(key);
                }
                final List<Line> out = lines;
                main.post(() -> cb.onResult(key, out));
            });
        } catch (RuntimeException ignored) {
            // executor sudah ditutup
        }
    }

    public static String keyOf(String title, String artist, long durMs) {
        return (title + "|" + artist + "|" + (durMs / 1000)).toLowerCase();
    }

    // ---- jaringan ----

    private String download(String title, String artist, String album, long durMs) throws Exception {
        StringBuilder q = new StringBuilder("get?track_name=").append(enc(title)).append("&artist_name=").append(enc(artist));
        if (album != null && !album.isEmpty()) q.append("&album_name=").append(enc(album));
        if (durMs > 0) q.append("&duration=").append(durMs / 1000);
        String body = http(q.toString());
        if (body != null) {
            String s = new JSONObject(body).optString("syncedLyrics", "");
            if (!s.isEmpty() && !s.equals("null")) return s;
        }

        // cadangan: cari berdasarkan judul bersih dan artis pertama
        String cleanTitle = title.replaceAll("\\s*[-(\\[].*$", "").trim();
        String firstArtist = artist.split("\\s*(,|&| feat\\.? | ft\\.? )\\s*")[0].trim();
        if (cleanTitle.isEmpty()) cleanTitle = title;
        body = http("search?track_name=" + enc(cleanTitle) + "&artist_name=" + enc(firstArtist));
        if (body == null) return null;
        JSONArray arr = new JSONArray(body);
        String best = null;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String s = o.optString("syncedLyrics", "");
            if (s.isEmpty() || s.equals("null")) continue;
            long diff = durMs > 0 ? Math.abs((long) (o.optDouble("duration", 0) * 1000) - durMs) : 0;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        // jangan pakai hasil yang durasinya terlalu beda (kemungkinan lagu lain/versi lain)
        if (best != null && durMs > 0 && bestDiff > 8000) return null;
        return best;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String http(String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- cache disk ----

    private File fileFor(String key) {
        return new File(dir, Integer.toHexString(key.hashCode()) + ".lrc");
    }

    private String readDisk(String key) {
        File f = fileFor(key);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeDisk(String key, String lrc) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(fileFor(key))) {
                out.write(lrc.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    // ---- parser LRC ----

    static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            List<Long> stamps = new ArrayList<>();
            int pos = 0;
            while (true) {
                Matcher m = TS.matcher(line);
                m.region(pos, line.length());
                if (!m.lookingAt()) break;
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                String frac = m.group(3);
                if (frac != null) {
                    ms = Long.parseLong(frac);
                    if (frac.length() == 1) ms *= 100;
                    else if (frac.length() == 2) ms *= 10;
                }
                stamps.add(min * 60000 + sec * 1000 + ms);
                pos = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = INLINE_TS.matcher(line.substring(pos)).replaceAll("").trim();
            for (long t : stamps) out.add(new Line(t, text));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.t, b.t));
        return out;
    }
}
