package git.artdeell.mojo.social;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.res.ResourcesCompat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import git.artdeell.mojo.R;

/**
 * Overlay di dalam game: pill lagu (judul/artis, atau baris lirik dengan kata
 * yang sedang dinyanyikan disorot) dan lirik melengkung yang muncul per kata
 * di atas layar, lalu hilang saat baris berganti.
 *
 * Semua kata digambar di SATU View lewat Canvas (tanpa TextView/animator per
 * kata) dan seluruh pekerjaan diberi jeda (throttle) supaya tidak membebani
 * thread UI game dan tidak gampang crash.
 *
 * Lagu dibaca dari sesi media Android (Spotify atau pemutar apa pun), jadi butuh
 * akses notifikasi untuk NowPlayingListener. Lirik dari LRCLIB.
 * Ketuk pill untuk menyalakan atau mematikan lirik.
 */
public class LyricsOverlay {

    public static final String PREF_KEY = "lyricsOverlay";
    private static final String WORDS_PREFS = "lyrics_overlay", WORDS_KEY = "words";

    // ---- pengaturan delay / batas (naikkan angka delay kalau masih berat) ----
    /** jeda ticker logika lirik */
    private static final long TICK_MS = 150;
    /** jeda antar frame gambar kata (33 ms = ~30 fps) */
    private static final long FRAME_MS = 33;
    /** jarak minimal antar kemunculan kata */
    private static final long MIN_WORD_GAP_MS = 110;
    /** tunda ambil lirik saat lagu ganti cepat (skip berkali-kali) */
    private static final long FETCH_DEBOUNCE_MS = 450;
    private static final long OFFSET_MS = 150;
    private static final long FADE_IN_MS = 170, FADE_OUT_MS = 260;
    /** kata tetap tampil sampai baris berakhir, ditambah sedikit jeda ini */
    private static final long LINGER_MS = 700;
    private static final int MAX_CHUNKS = 6;
    /** baris lirik terakhir tetap tampil selama jeda instrumental, lalu kembali ke judul lagu */
    private static final long GAP_HOLD_MS = 2500;
    private static final int MAX_WORDS_PER_TICK = 2;
    private static final int CHUNK_MAX_CHARS = 11, CHUNK_MAX_WORDS = 3;

    private static final int HILITE_BG = 0xFF8B5CF6;
    /** kata melengkung di layar. false = hanya lirik di pill (set true untuk menyalakan lagi) */
    private static final boolean SHOW_FLOATING_WORDS = false;

    // slot: {kiri (fraksi lebar), baseline (fraksi tinggi), rotasi derajat, ukuran sp}
    private static final float[][] SLOTS = {
            {0.04f, 0.22f, 9f, 22f},
            {0.31f, 0.30f, 4f, 22f},
            {0.60f, 0.29f, -5f, 16f},
            {0.04f, 0.45f, 8f, 25f},
            {0.30f, 0.53f, 2f, 19f},
            {0.58f, 0.47f, -4f, 17f}
    };

    private static final class Word {
        final String text;
        final long start;

        Word(String text, long start) {
            this.text = text;
            this.start = start;
        }
    }

    private final Activity act;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Typeface font;
    private final LyricsClient client;
    private final Random rnd = new Random();

    private final FrameLayout root;
    private final WordsView wv;
    private LinearLayout pill;
    private ImageView icon;
    private TextView pillText;
    private Equalizer eq;
    private LyricLineView lineView;
    private int pillMaxW;
    private String pillKey = "";

    // sesi media
    private MediaSessionManager msm;
    private ComponentName listener;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsCb;
    private MediaController ctrl;
    private MediaController.Callback ctrlCb;
    private boolean started, ticking, wordsOn;

    // lagu
    private String title = "", artist = "", album = "", lyricsKey;
    private long durMs, basePos, baseAt;
    private float speed = 1f;
    private boolean playing;
    private List<LyricsClient.Line> lines;
    private Runnable pendingFetch;

    // lirik yang sedang tampil
    private int curLine = -2, shown, slotIdx;
    private List<Word> curWords;
    private String[] curToks;
    private long lineEndAt, lastWordAt, gapAt;
    private WordsView.Chunk curChunk;
    private int curChunkWords;

    public LyricsOverlay(Activity activity, ViewGroup host) {
        act = activity;
        prefs = act.getSharedPreferences(WORDS_PREFS, Context.MODE_PRIVATE);
        // status lama dari tombol di pill (bisa tersimpan "mati") dibuang; sekarang hanya menu samping yang mengatur
        wordsOn = true;
        try {
            prefs.edit().remove(WORDS_KEY).apply();
        } catch (Throwable ignored) { }
        Typeface f = null;
        try {
            f = ResourcesCompat.getFont(act, R.font.noto_sans_bold);
        } catch (Throwable ignored) { }
        if (f == null) {
            try {
                f = ResourcesCompat.getFont(act, R.font.minecraft_ten);
            } catch (Throwable ignored) { }
        }
        font = f;
        client = new LyricsClient(act);

        root = new FrameLayout(act) {
            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                updateTicker();
            }
        };
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));
        wv = new WordsView(act, font);
        root.addView(wv, new FrameLayout.LayoutParams(-1, -1));

        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        pillMaxW = (int) Math.min(dp(420), dm.widthPixels * 0.66f);
        buildPill();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(6);
        root.addView(pill, lp);
        pill.setVisibility(View.GONE);
        start();
    }

    // ---- tampilan pill ----

    private void buildPill() {
        pill = new LinearLayout(act);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(8), dp(5), dp(12), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE60E0E14);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0xFF3A3358);
        pill.setBackground(bg);

        icon = new ImageView(act);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF1DB954);
        icon.setBackground(dot);
        icon.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        icon.setClipToOutline(true);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        pillText = new TextView(act);
        pillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pillText.setSingleLine(true);
        pillText.setEllipsize(TextUtils.TruncateAt.END);
        pillText.setMaxWidth(pillMaxW);
        if (font != null) pillText.setTypeface(font);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(8));
        pill.addView(pillText, tp);

        lineView = new LyricLineView(act, font, pillMaxW);
        LinearLayout.LayoutParams lvp = new LinearLayout.LayoutParams(pillMaxW, dp(24));
        lvp.setMarginStart(dp(8));
        pill.addView(lineView, lvp);
        lineView.setVisibility(View.GONE);

        eq = new Equalizer(act);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(14), dp(14));
        ep.setMarginStart(dp(8));
        pill.addView(eq, ep);

        applyTint();
    }

    private void applyTint() {
        pillText.setAlpha(1f);
        eq.setColor(0xFFB58CFF);
    }

    /** Pill mode judul + artis. */
    private void updatePill() {
        if (title.isEmpty()) {
            lineView.stop();
            pill.setVisibility(View.GONE);
            pillKey = "";
            return;
        }
        if (pillKey.startsWith("L|")) animateModeChange();
        lineView.stop();
        lineView.setVisibility(View.GONE);
        pillText.setVisibility(View.VISIBLE);
        String key = "T|" + title + "|" + artist;
        if (!key.equals(pillKey)) {
            pillKey = key;
            SpannableStringBuilder sb = new SpannableStringBuilder(title);
            sb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!artist.isEmpty()) {
                int s = sb.length();
                sb.append("  ").append(artist);
                sb.setSpan(new ForegroundColorSpan(0xFFA8A8B8), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            pillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pillText.setText(sb);
            try {
                if (ctrl != null) {
                    Drawable d = act.getPackageManager().getApplicationIcon(ctrl.getPackageName());
                    icon.setImageDrawable(d);
                }
            } catch (Throwable ignored) {
                icon.setImageDrawable(null);
            }
        }
        pill.setVisibility(View.VISIBLE);
    }

    /** Animasi lebar pill saat pindah antara mode judul dan mode lirik. */
    private void animateModeChange() {
        try {
            AutoTransition t = new AutoTransition();
            t.setDuration(240);
            TransitionManager.beginDelayedTransition(root, t);
        } catch (Throwable ignored) { }
    }

    /** Pill mode lirik: kata aktif dihitung tiap frame oleh LyricLineView dari waktu lagu. */
    private void updatePillLine(String[] toks, long[] starts) {
        if (toks == null || toks.length == 0) {
            updatePill();
            return;
        }
        String key = "L|" + curLine;
        if (key.equals(pillKey)) return;
        boolean wasLyric = pillKey.startsWith("L|");
        pillKey = key;
        animateModeChange(); // lebar pill ikut berubah tiap baris
        pillText.setVisibility(View.GONE);
        lineView.setVisibility(View.VISIBLE);
        pill.setVisibility(View.VISIBLE);
        lineView.setLine(toks, starts, () -> position() + OFFSET_MS);
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- sesi media ----

    private boolean hasAccess() {
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(act).contains(act.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void start() {
        if (!hasAccess()) {
            Toast.makeText(act, "Lirik: beri akses notifikasi dulu di Pengaturan > Lainnya", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            msm = (MediaSessionManager) act.getSystemService(Context.MEDIA_SESSION_SERVICE);
            listener = new ComponentName(act, NowPlayingListener.class);
            sessionsCb = this::pick;
            msm.addOnActiveSessionsChangedListener(sessionsCb, listener, h);
            started = true;
            pick(msm.getActiveSessions(listener));
        } catch (Throwable t) {
            started = false;
        }
    }

    private void pick(List<MediaController> list) {
        MediaController chosen = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING && c.getMetadata() != null) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) {
                for (MediaController c : list) {
                    if (c.getMetadata() != null) {
                        chosen = c;
                        break;
                    }
                }
            }
        }
        if (chosen != null && ctrl != null && chosen.getSessionToken().equals(ctrl.getSessionToken())) return;

        if (ctrl != null && ctrlCb != null) {
            try {
                ctrl.unregisterCallback(ctrlCb);
            } catch (Throwable ignored) { }
        }
        ctrl = chosen;
        if (ctrl == null) {
            playing = false;
            title = artist = album = "";
            lyricsKey = null;
            lines = null;
            cancelFetch();
            clearWords();
            pillKey = "";
            updatePill();
            updateTicker();
            return;
        }
        ctrlCb = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                readMeta();
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                readState(state);
            }

            @Override
            public void onSessionDestroyed() {
                try {
                    pick(msm.getActiveSessions(listener));
                } catch (Throwable ignored) { }
            }
        };
        ctrl.registerCallback(ctrlCb, h);
        readMeta();
        readState(ctrl.getPlaybackState());
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private void cancelFetch() {
        if (pendingFetch != null) {
            h.removeCallbacks(pendingFetch);
            pendingFetch = null;
        }
    }

    private void readMeta() {
        MediaMetadata m = ctrl == null ? null : ctrl.getMetadata();
        if (m == null) return;
        title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        artist = nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (artist.isEmpty()) artist = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        album = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
        durMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);

        String key = LyricsClient.keyOf(title, artist, durMs);
        if (key.equals(lyricsKey)) {
            updatePill();
            return;
        }
        lyricsKey = key;
        lines = null;
        curLine = -2;
        clearWords();
        pillKey = "";
        updatePill();
        cancelFetch();
        if (title.isEmpty()) return;

        // tunda sebentar: kalau user skip lagu berkali-kali, hanya yang terakhir diambil
        final String fTitle = title, fArtist = artist, fAlbum = album;
        final long fDur = durMs;
        final String fKey = key;
        pendingFetch = () -> {
            pendingFetch = null;
            if (!fKey.equals(lyricsKey)) return;
            try {
                client.fetch(fTitle, fArtist, fAlbum, fDur, (k, ls) -> {
                    if (!k.equals(lyricsKey)) return;
                    lines = ls;
                    curLine = -2;
                    updateTicker();
                });
            } catch (Throwable ignored) { }
        };
        h.postDelayed(pendingFetch, FETCH_DEBOUNCE_MS);
    }

    private void readState(PlaybackState s) {
        boolean wasPlaying = playing;
        long predicted = position();
        if (s == null) {
            playing = false;
        } else {
            playing = s.getState() == PlaybackState.STATE_PLAYING;
            basePos = Math.max(0, s.getPosition());
            baseAt = s.getLastPositionUpdateTime();
            if (baseAt <= 0) baseAt = SystemClock.elapsedRealtime();
            speed = s.getPlaybackSpeed() == 0f ? 1f : s.getPlaybackSpeed();
        }
        // hanya muat ulang baris kalau status putar berubah atau lagu digeser jauh
        if (playing != wasPlaying || Math.abs(position() - predicted) > 1500) curLine = -2;
        updateTicker();
    }

    private long position() {
        if (!playing) return basePos;
        return Math.max(0, basePos + (long) ((SystemClock.elapsedRealtime() - baseAt) * speed));
    }

    // ---- ticker (hanya jalan saat lagu diputar dan game terlihat) ----

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!shouldTick()) {
                ticking = false;
                return;
            }
            try {
                eq.step();
                onTick();
            } catch (Throwable ignored) {
                // jangan sampai overlay lirik membuat game crash
            }
            h.postDelayed(this, TICK_MS);
        }
    };

    private boolean shouldTick() {
        return started && playing && root.getWindowVisibility() == View.VISIBLE;
    }

    private void updateTicker() {
        eq.setPlaying(playing);
        if (!playing) {
            clearWords();
            pillKey = "";
            updatePill();
        }
        if (shouldTick() && !ticking) {
            ticking = true;
            h.post(tick);
        }
    }

    private void onTick() {
        if (!wordsOn || lines == null || lines.isEmpty()) {
            if (!pillKey.startsWith("T|")) updatePill();
            return;
        }
        long pos = position() + OFFSET_MS;
        int idx = lineIndexAt(pos);
        if (idx != curLine) {
            enterLine(idx, pos);
        } else if (curWords != null && shown > 0 && pos < curWords.get(shown - 1).start - 500) {
            enterLine(idx, pos); // geser mundur
        }
        if (curWords == null) {
            // jeda instrumental: baris terakhir tetap tampil sebentar, lalu pill kembali ke judul
            if (gapAt > 0 && SystemClock.uptimeMillis() - gapAt > GAP_HOLD_MS && pillKey.startsWith("L|")) {
                gapAt = 0;
                pillKey = "";
                updatePill();
            }
            return;
        }

        long now = SystemClock.uptimeMillis();
        int n = 0;
        while (shown < curWords.size() && curWords.get(shown).start <= pos
                && n < MAX_WORDS_PER_TICK && now - lastWordAt >= MIN_WORD_GAP_MS) {
            showWord(curWords.get(shown).text);
            shown++;
            n++;
            lastWordAt = now;
            now += MIN_WORD_GAP_MS; // dua kata dalam satu tick tetap diberi jarak
        }

        // baris sudah selesai -> kata memudar setelah jeda singkat
        if (shown >= curWords.size() && pos > lineEndAt + LINGER_MS) {
            wv.fadeAll();
            curChunk = null;
        }
    }

    private int lineIndexAt(long pos) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).t <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private void enterLine(int idx, long pos) {
        wv.fadeAll();
        curChunk = null;
        curChunkWords = 0;
        slotIdx = 0;
        curLine = idx;
        curWords = null;
        curToks = null;
        shown = 0;
        if (idx < 0) {
            gapAt = 0;
            pillKey = "";
            updatePill();
            return;
        }
        LyricsClient.Line ln = lines.get(idx);
        String txt = ln.text.trim();
        if (txt.isEmpty()) {
            // baris kosong (jeda): biarkan baris sebelumnya tetap tampil dulu
            gapAt = SystemClock.uptimeMillis();
            return;
        }
        gapAt = 0;
        String[] toks = txt.split("\\s+");
        long next = idx + 1 < lines.size() ? lines.get(idx + 1).t : ln.t + 4000;
        long dur = Math.max(800, Math.min(next - ln.t, 6000));
        double span = dur * 0.8;
        double total = 0;
        for (String t : toks) total += t.length() + 2;
        List<Word> ws = new ArrayList<>();
        double acc = 0;
        for (String t : toks) {
            ws.add(new Word(t, ln.t + (long) (span * acc / total)));
            acc += t.length() + 2;
        }
        curWords = ws;
        curToks = toks;
        lineEndAt = ln.t + dur;
        int already = 0;
        for (int i = 0; i < ws.size(); i++) if (ws.get(i).start <= pos) already = i + 1;
        // masuk di tengah baris (misalnya setelah geser lagu): mulai dari kata terbaru saja
        shown = (pos - ln.t > 1200) ? Math.max(already - 1, 0) : 0;
        long[] st = new long[ws.size()];
        for (int i = 0; i < st.length; i++) st[i] = ws.get(i).start;
        updatePillLine(toks, st);
    }

    // ---- kata ----

    private void showWord(String text) {
        if (!SHOW_FLOATING_WORDS) return;
        String w = text.toUpperCase(Locale.getDefault());
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int W = root.getWidth() > 0 ? root.getWidth() : dm.widthPixels;
        int H = root.getHeight() > 0 ? root.getHeight() : dm.heightPixels;

        // gabungkan ke potongan yang sama kalau masih muat (seperti "NOW I WON'T")
        if (curChunk != null && curChunk.alive() && curChunkWords < CHUNK_MAX_WORDS
                && curChunk.text.length() + 1 + w.length() <= CHUNK_MAX_CHARS) {
            wv.setChunkText(curChunk, curChunk.text + " " + w, W);
            curChunkWords++;
            return;
        }
        float[] slot = SLOTS[slotIdx++ % SLOTS.length];
        float jx = (rnd.nextFloat() - 0.5f) * 0.03f * W;
        float jy = (rnd.nextFloat() - 0.5f) * 0.03f * H;
        float sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, slot[3], dm);
        float curve = (rnd.nextBoolean() ? 1f : -1f) * 0.22f;
        curChunk = wv.add(w, slot[0] * W + jx, slot[1] * H + jy, slot[2], sizePx, curve, W);
        curChunkWords = 1;
    }

    private void clearWords() {
        wv.fadeAll();
        curChunk = null;
        curChunkWords = 0;
        curWords = null;
        curToks = null;
        shown = 0;
    }

    // ---- siklus hidup ----

    public void destroy() {
        ticking = false;
        started = false;
        h.removeCallbacksAndMessages(null);
        wv.stop();
        if (ctrl != null && ctrlCb != null) {
            try {
                ctrl.unregisterCallback(ctrlCb);
            } catch (Throwable ignored) { }
        }
        if (msm != null && sessionsCb != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsCb);
            } catch (Throwable ignored) { }
        }
        client.shutdown();
        ViewGroup p = (ViewGroup) root.getParent();
        if (p != null) p.removeView(root);
    }

    // ---- kotak ungu penyorot kata di pill ----

    private static final class ChipSpan extends ReplacementSpan {
        private final int bg, radius, pad;
        private final RectF r = new RectF();

        ChipSpan(int bg, int radius, int pad) {
            this.bg = bg;
            this.radius = radius;
            this.pad = pad;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return (int) (paint.measureText(text, start, end) + pad * 2);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            float w = paint.measureText(text, start, end);
            int old = paint.getColor();
            r.set(x, top, x + w + pad * 2, bottom);
            paint.setColor(bg);
            canvas.drawRoundRect(r, radius, radius, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawText(text, start, end, x + pad, y, paint);
            paint.setColor(old);
        }
    }

    // ---- satu baris lirik di pill (meniru video):
    //  - kata yang sudah dinyanyikan putih, yang belum redup
    //  - kotak ungu meluncur halus ke kata yang sedang dinyanyikan (dihitung tiap frame dari waktu lagu)
    //  - baris panjang bergeser halus ke kiri mengikuti kotak
    //  - ganti baris: baris lama memudar naik, baris baru memudar masuk dari bawah

    private static final class LyricLineView extends View {

        interface Clock {
            long pos();
        }

        private static final class Ln {
            String text = "";
            int[] s = new int[0], e = new int[0];
            long[] t = new long[0];
            float textW, off;
        }

        private static final int COL_DIM = 0xFFA9A3C4;

        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();
        private final RectF rect = new RectF();
        private final float pad, slide, radius, minW;
        private final int maxW;
        private float vw;
        private int viewW;

        private Ln cur = new Ln(), prev;
        private Clock clock;
        private int hi = -1;
        private float chipL, chipR, tgtL, tgtR, chipA, scroll, tgtScroll, lineA = 1f;
        private boolean chipOn, running, tracking;
        private long fadeStart, lastFrame;

        LyricLineView(Context c, Typeface tf, int widthPx) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            float d = dm.density;
            maxW = widthPx;
            viewW = widthPx;
            minW = 90f * d;
            pad = 4f * d;
            slide = 6f * d;
            radius = 5f * d;
            vw = widthPx - 2f * pad;
            if (tf != null) tp.setTypeface(tf);
            tp.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, dm));
            chip.setColor(HILITE_BG);
        }

        /** Mulai baris baru. starts = waktu mulai tiap kata (ms, sama dengan skala clock). */
        void setLine(String[] toks, long[] starts, Clock c) {
            Ln n = new Ln();
            StringBuilder sb = new StringBuilder();
            n.s = new int[toks.length];
            n.e = new int[toks.length];
            n.t = starts;
            for (int i = 0; i < toks.length; i++) {
                if (i > 0) sb.append(' ');
                n.s[i] = sb.length();
                sb.append(toks[i].toUpperCase(Locale.getDefault()));
                n.e[i] = sb.length();
            }
            n.text = sb.toString();
            n.textW = tp.measureText(n.text);
            // lebar pill menyesuaikan panjang lirik (maksimal maxW)
            viewW = (int) Math.max(minW, Math.min(maxW, Math.ceil(n.textW + pad * 4f)));
            vw = viewW - 2f * pad;
            n.off = n.textW <= vw ? (vw - n.textW) / 2f : 0f;

            prev = cur.text.isEmpty() ? null : cur;
            cur = n;
            clock = c;
            hi = -1;
            chipOn = false;
            chipA = 0f;
            scroll = tgtScroll = 0f;
            lineA = 0f;
            fadeStart = SystemClock.uptimeMillis();
            tracking = true;
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null && lp.width != viewW) {
                lp.width = viewW;
                setLayoutParams(lp);
            }
            kick();
        }

        void stop() {
            tracking = false;
        }

        private void kick() {
            if (!running) {
                running = true;
                lastFrame = SystemClock.uptimeMillis();
                postOnAnimation(step);
            }
        }

        private void retarget() {
            if (hi >= 0 && hi < cur.s.length) {
                tgtL = tp.measureText(cur.text, 0, cur.s[hi]);
                tgtR = tgtL + tp.measureText(cur.text, cur.s[hi], cur.e[hi]);
                if (!chipOn) {
                    chipL = tgtL;
                    chipR = tgtR;
                    chipOn = true;
                }
                tgtScroll = cur.textW <= vw ? 0f
                        : Math.max(0f, Math.min((tgtL + tgtR) / 2f - vw * 0.55f, cur.textW - vw));
            } else {
                chipOn = false;
            }
        }

        private final Runnable step = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                float dt = Math.max(1f, Math.min(64f, now - lastFrame));
                lastFrame = now;

                boolean changed = false;
                if (tracking && clock != null && cur.t.length > 0) {
                    long p = clock.pos();
                    int nh = -1;
                    for (int i = 0; i < cur.t.length; i++) {
                        if (cur.t[i] <= p) nh = i;
                        else break;
                    }
                    if (nh != hi) {
                        hi = nh;
                        retarget();
                        changed = true;
                    }
                }

                lineA = Math.min(1f, (now - fadeStart) / 260f);
                if (lineA >= 1f) prev = null;
                float k1 = 1f - (float) Math.exp(-dt / 55f);   // kotak
                float k2 = 1f - (float) Math.exp(-dt / 150f);  // geser teks
                float k3 = 1f - (float) Math.exp(-dt / 90f);   // kotak muncul/hilang
                chipL += (tgtL - chipL) * k1;
                chipR += (tgtR - chipR) * k1;
                scroll += (tgtScroll - scroll) * k2;
                float goal = chipOn ? 1f : 0f;
                chipA += (goal - chipA) * k3;

                boolean busy = lineA < 1f
                        || Math.abs(tgtL - chipL) > 0.4f || Math.abs(tgtR - chipR) > 0.4f
                        || Math.abs(tgtScroll - scroll) > 0.4f
                        || Math.abs(goal - chipA) > 0.02f;
                if (busy || changed) invalidate();
                if (busy) {
                    postOnAnimation(this);
                } else {
                    chipL = tgtL;
                    chipR = tgtR;
                    scroll = tgtScroll;
                    chipA = goal;
                    if (tracking) {
                        postDelayed(this, 40); // cek kata berikutnya tanpa menggambar ulang
                    } else {
                        running = false;
                        invalidate();
                    }
                }
            }
        };

        @Override
        protected void onDraw(Canvas canvas) {
            if (cur.text.isEmpty()) return;
            tp.getFontMetrics(fm);
            float y = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());

            if (prev != null && lineA < 1f) {
                canvas.save();
                canvas.translate(0f, -lineA * slide);
                tp.setColor(0xFFFFFFFF);
                tp.setAlpha((int) (255 * (1f - lineA)));
                canvas.drawText(prev.text, pad + prev.off, y, tp);
                canvas.restore();
            }

            canvas.save();
            canvas.translate(0f, (1f - lineA) * slide);
            float x0 = pad + cur.off - scroll;
            if (chipA > 0.02f && chipR > chipL) {
                rect.set(x0 + chipL - pad, y + fm.ascent - pad * 0.25f,
                        x0 + chipR + pad, y + fm.descent + pad * 0.25f);
                chip.setAlpha((int) (255 * lineA * chipA));
                canvas.drawRoundRect(rect, radius, radius, chip);
            }
            int len = cur.text.length();
            int a = hi < 0 ? 0 : cur.s[Math.min(hi, cur.s.length - 1)];
            int b = hi < 0 ? 0 : cur.e[Math.min(hi, cur.e.length - 1)];
            // sudah dinyanyikan + kata aktif: putih
            if (a > 0 || b > 0) {
                tp.setColor(0xFFFFFFFF);
                tp.setAlpha((int) (255 * lineA));
                canvas.drawText(cur.text, 0, b, x0, y, tp);
            }
            // belum dinyanyikan: redup
            if (b < len) {
                tp.setColor(COL_DIM);
                tp.setAlpha((int) (200 * lineA));
                canvas.drawText(cur.text, b, len, x0 + tp.measureText(cur.text, 0, b), y, tp);
            }
            canvas.restore();
            canvas.restore();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            running = false;
            removeCallbacks(step);
        }
    }

    // ---- satu View untuk semua kata: melengkung, memudar, tanpa view/animator per kata ----

    private static final class WordsView extends View {

        static final class Chunk {
            String text;
            float x, y, rot, sizePx, curve, w;
            long born, dieAt;
            final Path path = new Path();

            boolean alive() {
                return dieAt == 0;
            }

        }

        private final List<Chunk> chunks = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix mx = new Matrix();
        private boolean running;

        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (chunks.isEmpty()) {
                    running = false;
                    return;
                }
                invalidate();
                postDelayed(this, FRAME_MS);
            }
        };

        WordsView(Context c, Typeface tf) {
            super(c);
            if (tf != null) paint.setTypeface(tf);
            paint.setColor(0xFFFFFFFF);
            float d = c.getResources().getDisplayMetrics().density;
            paint.setShadowLayer(8f * d, 0f, 0f, 0xAAFFFFFF);
            setWillNotDraw(false);
        }

        Chunk add(String text, float x, float y, float rot, float sizePx, float curve, int viewW) {
            // batasi jumlah potongan: yang paling lama dipudarkan
            int live = 0;
            for (Chunk c : chunks) if (c.alive()) live++;
            if (live >= MAX_CHUNKS) {
                for (Chunk c : chunks) {
                    if (c.alive()) {
                        c.dieAt = SystemClock.uptimeMillis();
                        break;
                    }
                }
            }
            Chunk ch = new Chunk();
            ch.text = text;
            ch.x = x;
            ch.y = y;
            ch.rot = rot;
            ch.sizePx = sizePx;
            ch.curve = curve;
            ch.born = SystemClock.uptimeMillis();
            layoutChunk(ch, viewW);
            chunks.add(ch);
            kick();
            return ch;
        }

        void setChunkText(Chunk c, String t, int viewW) {
            c.text = t;
            layoutChunk(c, viewW);
        }

        private void layoutChunk(Chunk c, int viewW) {
            paint.setTextSize(c.sizePx);
            c.w = paint.measureText(c.text);
            // jaga agar tidak keluar layar di kanan
            float maxX = viewW - c.w - c.sizePx * 0.4f;
            if (c.x > maxX) c.x = Math.max(0, maxX);
            c.path.reset();
            c.path.moveTo(0, 0);
            c.path.quadTo(c.w / 2f, -c.w * c.curve * 0.35f, c.w, 0);
            mx.reset();
            mx.postRotate(c.rot, 0, 0);
            mx.postTranslate(c.x, c.y);
            c.path.transform(mx);
            kick();
        }

        void fadeAll() {
            long now = SystemClock.uptimeMillis();
            for (Chunk c : chunks) if (c.alive()) c.dieAt = now;
            kick();
        }

        void stop() {
            running = false;
            removeCallbacks(frame);
            chunks.clear();
        }

        private void kick() {
            if (!running) {
                running = true;
                postOnAnimation(frame);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (chunks.isEmpty()) return;
            long now = SystemClock.uptimeMillis();
            Iterator<Chunk> it = chunks.iterator();
            while (it.hasNext()) {
                Chunk c = it.next();
                float a = Math.min(1f, (now - c.born) / (float) FADE_IN_MS);
                float s = 0.88f + 0.12f * a;
                if (c.dieAt != 0) {
                    float o = 1f - (now - c.dieAt) / (float) FADE_OUT_MS;
                    if (o <= 0f) {
                        it.remove();
                        continue;
                    }
                    a *= o;
                }
                paint.setTextSize(c.sizePx);
                paint.setAlpha((int) (255 * a));
                canvas.save();
                canvas.scale(s, s, c.x + c.w / 2f, c.y);
                canvas.drawTextOnPath(c.text, c.path, 0f, 0f, paint);
                canvas.restore();
            }
            paint.setAlpha(255);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            running = false;
            removeCallbacks(frame);
        }
    }

    // ---- equalizer kecil, digerakkan oleh ticker (tanpa animator terpisah) ----

    private static final class Equalizer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float[] lv = {0.3f, 0.3f, 0.3f};
        private final Random r = new Random();
        private boolean playing;

        Equalizer(Context c) {
            super(c);
            paint.setColor(0xFFB58CFF);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setPlaying(boolean p) {
            playing = p;
            if (!p) {
                for (int i = 0; i < lv.length; i++) lv[i] = 0.3f;
            }
            invalidate();
        }

        void step() {
            if (!playing) return;
            for (int i = 0; i < lv.length; i++) {
                float target = 0.2f + r.nextFloat() * 0.8f;
                lv[i] += (target - lv[i]) * 0.6f;
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), hh = getHeight();
            float bw = w / 5f;
            for (int i = 0; i < lv.length; i++) {
                float left = i * bw * 2f;
                float top = hh - hh * lv[i];
                rect.set(left, top, left + bw, hh);
                canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
            }
        }
    }
}
