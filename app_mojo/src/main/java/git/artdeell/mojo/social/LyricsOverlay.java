package git.artdeell.mojo.social;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import java.util.List;
import java.util.Locale;
import java.util.Random;

import git.artdeell.mojo.R;

/**
 * Overlay di dalam game: pill lagu yang sedang diputar (judul, artis, equalizer)
 * dan lirik yang muncul kata per kata di tengah layar.
 *
 * Lagu dibaca dari sesi media Android (Spotify atau pemutar apa pun), jadi butuh
 * akses notifikasi untuk NowPlayingListener. Lirik dari LRCLIB.
 * Ketuk pill untuk menyalakan atau mematikan lirik.
 */
public class LyricsOverlay {

    public static final String PREF_KEY = "lyricsOverlay";
    private static final String WORDS_PREFS = "lyrics_overlay", WORDS_KEY = "words";

    private static final long TICK_MS = 120, OFFSET_MS = 150, HOLD_MS = 1500;
    private static final int MAX_LIVE = 5;
    private static final float[][] SLOTS = {
            {0.30f, 0.28f}, {0.62f, 0.36f}, {0.38f, 0.50f}, {0.66f, 0.54f}, {0.50f, 0.40f}
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

    private final FrameLayout root, wordLayer;
    private LinearLayout pill;
    private ImageView icon;
    private TextView pillText;
    private Equalizer eq;

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

    // lirik yang sedang tampil
    private int curLine = -2, shown, slotIdx, wordCounter;
    private List<Word> curWords;
    private final List<TextView> live = new ArrayList<>();

    public LyricsOverlay(Activity activity, ViewGroup host) {
        act = activity;
        prefs = act.getSharedPreferences(WORDS_PREFS, Context.MODE_PRIVATE);
        wordsOn = prefs.getBoolean(WORDS_KEY, true);
        Typeface f = null;
        try {
            f = ResourcesCompat.getFont(act, R.font.minecraft_ten);
        } catch (Throwable ignored) { }
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
        wordLayer = new FrameLayout(act);
        root.addView(wordLayer, new FrameLayout.LayoutParams(-1, -1));
        buildPill();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(6);
        root.addView(pill, lp);
        pill.setVisibility(View.GONE);
        start();
    }

    // ---- tampilan ----

    private void buildPill() {
        pill = new LinearLayout(act);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(8), dp(5), dp(12), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE60E0E14);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0xFF6B4FD8);
        pill.setBackground(bg);

        icon = new ImageView(act);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF1DB954);
        icon.setBackground(dot);
        icon.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        icon.setClipToOutline(true);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        pillText = new TextView(act);
        pillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pillText.setSingleLine(true);
        pillText.setEllipsize(TextUtils.TruncateAt.END);
        pillText.setMaxWidth(dp(200));
        if (font != null) pillText.setTypeface(font);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(8));
        pill.addView(pillText, tp);

        eq = new Equalizer(act);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(14), dp(14));
        ep.setMarginStart(dp(8));
        pill.addView(eq, ep);

        pill.setOnClickListener(v -> {
            wordsOn = !wordsOn;
            prefs.edit().putBoolean(WORDS_KEY, wordsOn).apply();
            if (!wordsOn) fadeAllWords();
            curLine = -2;
            applyTint();
            Toast.makeText(act, wordsOn ? "Lirik: nyala" : "Lirik: mati", Toast.LENGTH_SHORT).show();
        });
        applyTint();
    }

    private void applyTint() {
        pillText.setAlpha(wordsOn ? 1f : 0.55f);
        eq.setColor(wordsOn ? 0xFFB58CFF : 0xFF777777);
    }

    private void updatePill() {
        if (title.isEmpty()) {
            pill.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(title);
        sb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!artist.isEmpty()) {
            int s = sb.length();
            sb.append("  ").append(artist);
            sb.setSpan(new ForegroundColorSpan(0xFFA8A8B8), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        pillText.setText(sb);
        try {
            if (ctrl != null) {
                Drawable d = act.getPackageManager().getApplicationIcon(ctrl.getPackageName());
                icon.setImageDrawable(d);
            }
        } catch (Throwable ignored) {
            icon.setImageDrawable(null);
        }
        pill.setVisibility(View.VISIBLE);
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

    private void readMeta() {
        MediaMetadata m = ctrl == null ? null : ctrl.getMetadata();
        if (m == null) return;
        title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        artist = nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (artist.isEmpty()) artist = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        album = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
        durMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        updatePill();

        String key = LyricsClient.keyOf(title, artist, durMs);
        if (key.equals(lyricsKey)) return;
        lyricsKey = key;
        lines = null;
        curLine = -2;
        fadeAllWords();
        if (title.isEmpty()) return;
        client.fetch(title, artist, album, durMs, (k, ls) -> {
            if (!k.equals(lyricsKey)) return;
            lines = ls;
            curLine = -2;
            updateTicker();
        });
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
            eq.step();
            onTick();
            h.postDelayed(this, TICK_MS);
        }
    };

    private boolean shouldTick() {
        return started && playing && root.getWindowVisibility() == View.VISIBLE;
    }

    private void updateTicker() {
        eq.setPlaying(playing);
        if (!playing) fadeAllWords();
        if (shouldTick() && !ticking) {
            ticking = true;
            h.post(tick);
        }
    }

    private void onTick() {
        if (!wordsOn || lines == null || lines.isEmpty()) return;
        long pos = position() + OFFSET_MS;
        int idx = lineIndexAt(pos);
        if (idx != curLine) {
            enterLine(idx, pos);
        } else if (curWords != null && shown > 0 && pos < curWords.get(shown - 1).start - 500) {
            enterLine(idx, pos); // geser mundur
        }
        if (curWords == null) return;
        while (shown < curWords.size() && curWords.get(shown).start <= pos) {
            showWord(curWords.get(shown).text, wordCounter++);
            shown++;
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
        fadeAllWords();
        curLine = idx;
        curWords = null;
        shown = 0;
        if (idx < 0) return;
        LyricsClient.Line ln = lines.get(idx);
        String txt = ln.text.trim();
        if (txt.isEmpty()) return;
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
        int already = 0;
        for (int i = 0; i < ws.size(); i++) if (ws.get(i).start <= pos) already = i + 1;
        // masuk di tengah baris (misalnya setelah geser lagu): tampilkan kata terbaru saja
        shown = (pos - ln.t > 1200) ? Math.max(already - 1, 0) : 0;
    }

    // ---- kata ----

    private void showWord(String text, int k) {
        final TextView tv = new TextView(act);
        String w = text.toUpperCase(Locale.getDefault());
        tv.setText(w);
        if (font != null) tv.setTypeface(font);
        float sz = 24 + (k % 3) * 4;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz);
        tv.setTextColor(0xFFE6D9FF);
        tv.setShadowLayer(0.01f, dp(3), dp(2), 0xAA9B6BFF);
        tv.setSingleLine(true);
        tv.setAlpha(0f);
        tv.setScaleX(0.85f);
        tv.setScaleY(0.85f);

        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int W = root.getWidth() > 0 ? root.getWidth() : dm.widthPixels;
        int H = root.getHeight() > 0 ? root.getHeight() : dm.heightPixels;
        float[] slot = SLOTS[slotIdx++ % SLOTS.length];
        float estW = w.length() * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sz, dm) * 0.62f;
        int x = (int) (slot[0] * W - estW / 2 + (rnd.nextFloat() - 0.5f) * 0.06f * W);
        int y = (int) (slot[1] * H + (rnd.nextFloat() - 0.5f) * 0.06f * H);
        x = Math.max(0, Math.min(x, Math.max(0, (int) (W - estW))));
        y = Math.max(0, Math.min(y, Math.max(0, H - dp(48))));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = x;
        lp.topMargin = y;
        wordLayer.addView(tv, lp);
        live.add(tv);
        while (live.size() > MAX_LIVE) fadeOut(live.get(0), 80);

        tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(110).start();
        h.postDelayed(() -> fadeOut(tv, 300), HOLD_MS);
    }

    private void fadeOut(final TextView tv, long ms) {
        if (!live.remove(tv)) return;
        tv.animate().alpha(0f).setDuration(ms).withEndAction(() -> wordLayer.removeView(tv)).start();
    }

    private void fadeAllWords() {
        for (TextView tv : new ArrayList<>(live)) fadeOut(tv, 200);
    }

    // ---- siklus hidup ----

    public void destroy() {
        ticking = false;
        started = false;
        h.removeCallbacksAndMessages(null);
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
