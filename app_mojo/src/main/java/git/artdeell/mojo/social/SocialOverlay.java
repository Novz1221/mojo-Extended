package git.artdeell.mojo.social;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.M3Dialog;

/**
 * Menu sosial di dalam game: bar ikon di kiri atas (teman, voice, chat), panel bergaya piksel,
 * dan deretan avatar di pojok kiri atas yang bercincin hijau saat temanmu bicara.
 * Berjalan di proses :game. Suara tetap dikelola VoiceService di proses :voice,
 * overlay hanya mengirim perintah dan menerima status lewat broadcast.
 */
public class SocialOverlay {
    private static final int PAGE_NONE = 0, PAGE_FRIENDS = 1, PAGE_VOICE = 2, PAGE_CHAT = 3, PAGE_SETTINGS = 4, PAGE_REQUESTS = 5;
    private static final int MAX_VOICE = 6;
    private static final long POLL_MS = 3000, COOLDOWN_MS = 3000, VOICE_STALE_MS = 90000;
    private static final int REQ_MIC = 7712;
    public static final String NOTIF_PREFS = "social_notif", NOTIF_KEY = "on";
    private static final long NOTIF_MS = 6000;
    private static final String CALL_TEXT = "Memanggil kamu lewat voice";

    private static final int C_PANEL = 0xFF1F2128, C_PANEL_EDGE = 0xFF3A3D47, C_ROW = 0xFF474747;
    private static final int C_TOOL = 0xFF1E2028, C_TOOL_BTN = 0xFF2B2E36, C_TOOL_ON = 0xFF3C4A66;
    private static final int C_BLUE = 0xFFA8C7FA, C_BLUE_TEXT = 0xFF1B3A6B, C_TITLE = 0xFFC6C6C6, C_TEXT = 0xFFE6E6E6;

    private final Activity act;
    private final SocialClient client;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Typeface font;
    private final SharedPreferences audioPrefs;
    private final FrameLayout root;
    private final LinearLayout toolbar;
    private final LinearLayout strip;
    private final FrameLayout panel;
    private final ImageView[] toolButtons = new ImageView[3];
    private final SharedPreferences notifPrefs;
    private ImageView bellBtn, closeBtn;

    // notifikasi
    private boolean notifRunning, reqPrimed, bannerCall;
    private int notifCycle;
    private final Map<String, String> notifFriends = new TreeMap<>();
    private final List<String> notifRooms = new ArrayList<>();
    private final Map<String, String> lastMsg = new HashMap<>();
    private final Set<String> seenReq = new HashSet<>();
    private final Set<String> ringing = new HashSet<>();
    private final Set<String> mutual = new HashSet<>();
    private View banner;
    private Runnable bannerHide;
    private Ringtone tone;

    private boolean toolbarOn, running, receiverOn, menuOpen, loginHinted;
    private int page = PAGE_NONE;

    // voice
    private String voiceRoom;
    private boolean muted;
    private Set<Integer> speaking = new HashSet<>();
    private Map<String, Boolean> members = new TreeMap<>();
    private final Map<String, AvatarView> stripAvatars = new HashMap<>();
    private final Map<String, AvatarView> tileAvatars = new HashMap<>();
    private final Map<String, View> tileBoxes = new HashMap<>();
    private LinearLayout callTiles;
    private TextView callCount;
    private ImageView muteBtn;
    private LinearLayout roomList;
    private String roomFilter = "", newRoomName = "", pendingFriend = "", pendingMic = null;

    // chat
    private String chatRoom = "umum", lastKey = "";
    private LinearLayout msgBox;
    private ScrollView msgScroll;
    private long lastSend;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent i) {
            boolean active = i.getBooleanExtra("active", false);
            String room = i.getStringExtra("room");
            String error = i.getStringExtra("error");
            if (error != null) toast(error);
            boolean changed = active != (voiceRoom != null) || (active && room != null && !room.equals(voiceRoom));
            voiceRoom = active ? room : null;
            muted = i.getBooleanExtra("muted", false);
            Set<Integer> sp = new HashSet<>();
            int[] arr = i.getIntArrayExtra("speaking");
            if (arr != null) for (int u : arr) sp.add(u);
            speaking = sp;
            if (changed) {
                members.clear();
                if (voiceRoom != null) applyStoredAudio();
                if (page == PAGE_VOICE) showPage(PAGE_VOICE);
                rebuildStrip();
                ensureTick();
                refreshMembers();
            } else {
                applySpeaking();
                if (muteBtn != null) muteBtn.setImageResource(muted ? R.drawable.ic_social_mic_off : R.drawable.ic_microphone);
            }
        }
    };

    public SocialOverlay(Activity activity, ViewGroup host) {
        act = activity;
        client = new SocialClient(activity);
        Typeface f = null;
        try {
            f = ResourcesCompat.getFont(activity, R.font.minecraft_ten);
        } catch (Throwable ignored) { }
        font = f;
        audioPrefs = activity.getSharedPreferences("social_audio", Context.MODE_PRIVATE);
        notifPrefs = activity.getApplicationContext().getSharedPreferences(NOTIF_PREFS, Context.MODE_PRIVATE);

        root = new FrameLayout(activity);
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));

        strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        sp.leftMargin = dp(8);
        sp.topMargin = dp(8);
        root.addView(strip, sp);

        toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setBackground(bg(C_TOOL, 14));
        toolbar.setPadding(dp(5), dp(5), dp(5), dp(5));
        toolbar.setClickable(true);
        toolbar.setVisibility(View.GONE);
        int[] icons = {R.drawable.ic_social_people, R.drawable.ic_social_phone, R.drawable.ic_social_chat};
        final int[] pages = {PAGE_FRIENDS, PAGE_VOICE, PAGE_CHAT};
        for (int i = 0; i < 3; i++) {
            final int p = pages[i];
            ImageView b = new ImageView(activity);
            b.setImageResource(icons[i]);
            b.setColorFilter(0xFFFFFFFF);
            b.setPadding(dp(10), dp(10), dp(10), dp(10));
            b.setBackground(bg(C_TOOL_BTN, 10));
            b.setClickable(true);
            b.setOnClickListener(v -> togglePage(p));
            toolButtons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            if (i > 0) lp.topMargin = dp(5);
            toolbar.addView(b, lp);
        }
        bellBtn = new ImageView(activity);
        bellBtn.setImageResource(notifOn() ? R.drawable.ic_social_bell : R.drawable.ic_social_bell_off);
        bellBtn.setColorFilter(0xFFFFFFFF);
        bellBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        bellBtn.setBackground(bg(C_TOOL_BTN, 10));
        bellBtn.setClickable(true);
        bellBtn.setOnClickListener(v -> toggleNotif());
        LinearLayout.LayoutParams bellLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        bellLp.topMargin = dp(5);
        toolbar.addView(bellBtn, bellLp);
        closeBtn = new ImageView(activity);
        closeBtn.setImageResource(R.drawable.ic_m3_close);
        closeBtn.setColorFilter(0xFFFFFFFF);
        closeBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        closeBtn.setBackground(bg(0xFFB3261E, 10));
        closeBtn.setClickable(true);
        closeBtn.setVisibility(View.GONE);
        closeBtn.setOnClickListener(v -> closePage());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        closeLp.topMargin = dp(5);
        toolbar.addView(closeBtn, closeLp);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.leftMargin = dp(6);
        tp.topMargin = dp(6);
        root.addView(toolbar, tp);

        panel = new FrameLayout(activity);
        GradientDrawable pb = bg(C_PANEL, 18);
        pb.setStroke(dp(3), C_PANEL_EDGE);
        panel.setBackground(pb);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.setClickable(true);
        panel.setVisibility(View.GONE);
        int pw = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.56f);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(pw, -1, Gravity.TOP | Gravity.START);
        pl.leftMargin = dp(66);
        pl.topMargin = dp(6);
        pl.bottomMargin = dp(6);
        root.addView(panel, pl);

        IntentFilter f2 = new IntentFilter(VoiceService.ACTION_STATE);
        Context app = activity.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, f2, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, f2);
        receiverOn = true;
        VoiceService.query(activity);
        ensureNotif();
        ChatCleanup.runIfDue(activity, client);
    }

    // ---- API untuk GameActivity ----

    public boolean isToolbarOn() {
        return toolbarOn;
    }

    /** Dipanggil GameActivity saat menu samping game terbuka/tertutup. */
    public void setMenuOpen(boolean open) {
        if (menuOpen == open) return;
        menuOpen = open;
        if (open && !loginHinted && !client.isLoggedIn()) {
            loginHinted = true;
            toast("Masuk dulu di menu Sosial pada launcher");
        }
        syncToolbar();
    }

    /** Toolbar tampil selama menu terbuka atau selama ada panel yang terbuka. */
    private void syncToolbar() {
        boolean want = menuOpen || page != PAGE_NONE;
        if (want == toolbarOn) return;
        toolbarOn = want;
        toolbar.animate().cancel();
        if (want) {
            toolbar.setVisibility(View.VISIBLE);
            toolbar.setAlpha(0f);
            toolbar.setTranslationX(-dp(34));
            toolbar.animate().alpha(1f).translationX(0f).setDuration(320)
                    .setInterpolator(new OvershootInterpolator(0.8f)).start();
            for (int i = 0; i < toolButtons.length; i++) {
                ImageView b = toolButtons[i];
                b.animate().cancel();
                b.setAlpha(0f);
                b.setScaleX(0.6f);
                b.setScaleY(0.6f);
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L + i * 55L).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(1.4f)).start();
            }
        } else {
            toolbar.animate().alpha(0f).translationX(-dp(34)).setDuration(200)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (!toolbarOn) toolbar.setVisibility(View.GONE);
                    }).start();
        }
        positionStrip();
        ensureTick();
    }

    private void openPanel() {
        boolean fresh = panel.getVisibility() != View.VISIBLE;
        if (!fresh && panel.getAlpha() >= 1f && panel.getScaleX() >= 1f) return;
        panel.animate().cancel();
        panel.setVisibility(View.VISIBLE);
        if (fresh) {
            panel.setPivotX(0f);
            panel.setPivotY(dp(40));
            panel.setAlpha(0f);
            panel.setScaleX(0.9f);
            panel.setScaleY(0.9f);
            panel.setTranslationX(-dp(24));
        }
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(2.4f)).start();
    }

    private void closePanel() {
        if (panel.getVisibility() != View.VISIBLE) return;
        panel.animate().cancel();
        panel.setPivotX(0f);
        panel.setPivotY(dp(40));
        panel.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).translationX(-dp(16)).setDuration(210)
                .setInterpolator(new AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    if (page == PAGE_NONE) {
                        panel.setVisibility(View.GONE);
                        positionStrip();
                    }
                }).start();
    }

    private void fadeIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(8));
        v.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    public void onPermissionResult(int requestCode, int[] grants) {
        if (requestCode != REQ_MIC) return;
        String room = pendingMic;
        pendingMic = null;
        if (room == null) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) joinVoice(room);
        else toast("Izin mikrofon ditolak, voice chat butuh mikrofon");
    }

    public void destroy() {
        h.removeCallbacksAndMessages(null);
        stopTone();
        if (receiverOn) {
            try {
                act.getApplicationContext().unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) { }
            receiverOn = false;
        }
        ViewGroup p = (ViewGroup) root.getParent();
        if (p != null) p.removeView(root);
    }

    // ---- helpers tampilan ----

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView label(CharSequence s, float sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (font != null) t.setTypeface(font);
        return t;
    }

    private TextView plain(CharSequence s, float sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView pill(CharSequence s, int fill, int textColor, float sp, View.OnClickListener l) {
        TextView t = label(s, sp, textColor);
        t.setGravity(Gravity.CENTER);
        t.setBackground(bg(fill, 12));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setSingleLine(true);
        t.setClickable(true);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private ImageView iconBtn(int res, int tint, int fill, int sizeDp, View.OnClickListener l) {
        ImageView b = new ImageView(act);
        b.setImageResource(res);
        b.setColorFilter(tint);
        b.setPadding(dp(sizeDp * 0.25f), dp(sizeDp * 0.25f), dp(sizeDp * 0.25f), dp(sizeDp * 0.25f));
        if (fill != 0) b.setBackground(bg(fill, 12));
        b.setClickable(true);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    private static int uidFor(String name) {
        int u = SocialClient.key(name).hashCode() & 0x7fffffff;
        return u == 0 ? 1 : u;
    }

    private String me() {
        return SocialClient.key(client.getUser());
    }

    private static String dmId(String a, String b) {
        return a.compareTo(b) <= 0 ? "dm_" + a + "_" + b : "dm_" + b + "_" + a;
    }

    // ---- halaman ----

    private void togglePage(int p) {
        if (page == p && panel.getVisibility() == View.VISIBLE) {
            closePage();
            return;
        }
        showPage(p);
    }

    private void closePage() {
        page = PAGE_NONE;
        closePanel();
        highlight();
        syncToolbar();
        positionStrip();
    }

    private void showPage(int p) {
        page = p;
        syncToolbar();
        callTiles = null;
        callCount = null;
        muteBtn = null;
        roomList = null;
        msgBox = null;
        boolean wasOpen = panel.getVisibility() == View.VISIBLE && panel.getAlpha() >= 1f;
        panel.removeAllViews();
        openPanel();
        if (!client.isLoggedIn()) {
            LinearLayout c = column();
            c.addView(label("SOSIAL", 26, C_TITLE));
            TextView t = plain("Kamu belum masuk. Buka menu Sosial di launcher, pilih username, lalu jalankan game lagi.", 14, C_TEXT);
            t.setPadding(0, dp(12), 0, 0);
            c.addView(t);
            panel.addView(c, new FrameLayout.LayoutParams(-1, -1));
            addPanelClose(c);
            if (wasOpen) fadeIn(c);
            highlight();
            positionStrip();
            return;
        }
        View v;
        switch (p) {
            case PAGE_FRIENDS: v = buildFriends(); break;
            case PAGE_REQUESTS: v = buildRequests(); break;
            case PAGE_CHAT: lastKey = ""; v = buildChat(); break;
            case PAGE_SETTINGS: v = buildSettings(); break;
            default: v = voiceRoom != null ? buildCall() : buildRoomList(); break;
        }
        panel.addView(v, new FrameLayout.LayoutParams(-1, -1));
        addPanelClose(v);
        if (wasOpen) fadeIn(v);
        highlight();
        positionStrip();
        ensureTick();
        if (p == PAGE_VOICE) {
            if (voiceRoom != null) refreshMembers();
            else loadRooms();
        } else if (p == PAGE_CHAT) {
            loadChatTabs();
            loadMessages();
        }
    }

    /** Tombol X di pojok kanan atas panel; baris judul diberi ruang supaya tidak tertimpa. */
    private void addPanelClose(View content) {
        if (content instanceof LinearLayout && ((LinearLayout) content).getChildCount() > 0) {
            View first = ((LinearLayout) content).getChildAt(0);
            ViewGroup.LayoutParams flp = first.getLayoutParams();
            if (flp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) flp).setMarginEnd(dp(40));
                first.setLayoutParams(flp);
            }
        }
        ImageView x = iconBtn(R.drawable.ic_m3_close, 0xFFFFFFFF, C_ROW, 32, v -> closePage());
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
        xl.topMargin = -dp(4);
        xl.setMarginEnd(-dp(6));
        panel.addView(x, xl);
    }

    private void highlight() {
        if (closeBtn != null) closeBtn.setVisibility(page != PAGE_NONE ? View.VISIBLE : View.GONE);
        int active = page == PAGE_FRIENDS || page == PAGE_REQUESTS ? 0 : page == PAGE_VOICE || page == PAGE_SETTINGS ? 1 : page == PAGE_CHAT ? 2 : -1;
        for (int i = 0; i < 3; i++) toolButtons[i].setBackground(bg(i == active ? C_TOOL_ON : C_TOOL_BTN, 10));
    }

    // ---- teman ----

    private View buildFriends() {
        LinearLayout c = column();
        LinearLayout head = row();
        head.addView(label("TEMAN", 26, C_TITLE));
        TextView req = pill("REQUEST", C_ROW, C_TITLE, 14, v -> showPage(PAGE_REQUESTS));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.setMarginStart(dp(14));
        head.addView(req, rp);
        c.addView(head);

        LinearLayout add = row();
        final TextView field = label(pendingFriend.isEmpty() ? "USERNAME" : pendingFriend, 18, 0xFFB0B0B0);
        field.setBackground(bg(C_ROW, 12));
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setSingleLine(true);
        field.setClickable(true);
        field.setOnClickListener(v -> M3Dialog.input(act, "Tambah teman", "Username teman", pendingFriend, "OK", text -> {
            pendingFriend = text.trim();
            field.setText(pendingFriend.isEmpty() ? "USERNAME" : pendingFriend);
            return true;
        }));
        add.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView addBtn = pill("ADD", C_BLUE, C_BLUE_TEXT, 18, v -> sendFriendRequest(pendingFriend));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.setMarginStart(dp(8));
        add.addView(addBtn, ap);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, -2);
        addLp.topMargin = dp(10);
        c.addView(add, addLp);

        final LinearLayout list = column();
        ScrollView sv = new ScrollView(act);
        sv.addView(list);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sl.topMargin = dp(10);
        c.addView(sv, sl);
        loadFriends(list);
        return c;
    }

    private void loadFriends(LinearLayout list) {
        final String my = me();
        client.get("friends/" + my, (json, err) -> {
            if (page != PAGE_FRIENDS) return;
            list.removeAllViews();
            if (err != null) {
                list.addView(plain(err, 13, C_TEXT));
                return;
            }
            if (!(json instanceof JSONObject) || ((JSONObject) json).length() == 0) {
                list.addView(plain("Belum ada teman. Tulis username lalu tekan ADD.", 13, C_TEXT));
                return;
            }
            JSONObject o = (JSONObject) json;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            for (String fk : keys) {
                final String name = o.optString(fk, fk);
                LinearLayout r = row();
                r.setBackground(bg(C_ROW, 12));
                r.setPadding(dp(10), dp(6), dp(8), dp(6));
                final AvatarView av = new AvatarView(act);
                av.setPerson(fk, name);
                Avatars.fetch(client, fk, () -> av.setPhoto(Avatars.photo(fk)));
                r.addView(av, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView n = label(name, 18, C_TITLE);
                n.setSingleLine(true);
                n.setPadding(dp(10), 0, 0, 0);
                r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
                final String dm = dmId(my, fk);
                final ImageView muteIc = iconBtn(isMuted(dm) ? R.drawable.ic_social_bell_off : R.drawable.ic_social_bell,
                        isMuted(dm) ? 0xFF8A8A8A : 0xFFFFFFFF, 0, 40, null);
                muteIc.setOnClickListener(v -> {
                    boolean m = !isMuted(dm);
                    setMuted(dm, m);
                    muteIc.setImageResource(m ? R.drawable.ic_social_bell_off : R.drawable.ic_social_bell);
                    muteIc.setColorFilter(m ? 0xFF8A8A8A : 0xFFFFFFFF);
                    toast(m ? name + " dimute: chat dan panggilan tidak memunculkan notifikasi" : name + " tidak dimute lagi");
                });
                r.addView(muteIc, new LinearLayout.LayoutParams(dp(40), dp(40)));
                r.addView(iconBtn(R.drawable.ic_social_chat, 0xFFFFFFFF, 0, 40, v -> {
                    chatRoom = dm;
                    showPage(PAGE_CHAT);
                }), new LinearLayout.LayoutParams(dp(40), dp(40)));
                r.addView(iconBtn(R.drawable.ic_social_phone, 0xFFFFFFFF, 0, 40, v -> callFriend(my, fk, name)), new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(6);
                list.addView(r, lp);
            }
        });
    }

    private void callFriend(String my, String fk, String name) {
        final String room = dmId(my, fk);
        verifyDm(room, () -> {
            postMessage(room, CALL_TEXT + ". Tekan ikon telepon di namaku pada daftar teman.");
            doTryJoin(room);
        });
    }

    private void sendFriendRequest(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            toast("Tulis username dulu");
            return;
        }
        final String fk = SocialClient.key(n);
        final String my = me();
        if (fk.equals(my)) {
            toast("Itu username kamu sendiri");
            return;
        }
        client.get("users/" + fk + "/n", (json, err) -> {
            if (err != null) {
                toast(err);
                return;
            }
            if (!(json instanceof String)) {
                toast("Username tidak ditemukan");
                return;
            }
            client.put("requests/" + fk + "/" + my, client.getUser(), (j, e) -> {
                if (e != null) toast(e);
                else {
                    toast("Permintaan dikirim ke " + json);
                    pendingFriend = "";
                    showPage(PAGE_FRIENDS);
                }
            });
        });
    }

    private View buildRequests() {
        LinearLayout c = column();
        LinearLayout head = row();
        head.addView(iconBtn(R.drawable.ic_social_people, 0xFFFFFFFF, C_ROW, 40, v -> showPage(PAGE_FRIENDS)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = label("REQUEST", 26, C_TITLE);
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        c.addView(head);
        final LinearLayout list = column();
        ScrollView sv = new ScrollView(act);
        sv.addView(list);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sl.topMargin = dp(10);
        c.addView(sv, sl);
        final String my = me();
        client.get("requests/" + my, (json, err) -> {
            if (page != PAGE_REQUESTS) return;
            list.removeAllViews();
            if (err != null) {
                list.addView(plain(err, 13, C_TEXT));
                return;
            }
            if (!(json instanceof JSONObject) || ((JSONObject) json).length() == 0) {
                list.addView(plain("Tidak ada permintaan pertemanan.", 13, C_TEXT));
                return;
            }
            JSONObject o = (JSONObject) json;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                final String fk = it.next();
                final String name = o.optString(fk, fk);
                LinearLayout r = row();
                r.setBackground(bg(C_ROW, 12));
                r.setPadding(dp(10), dp(6), dp(8), dp(6));
                final AvatarView av = new AvatarView(act);
                av.setPerson(fk, name);
                Avatars.fetch(client, fk, () -> av.setPhoto(Avatars.photo(fk)));
                r.addView(av, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView n = label(name, 18, C_TITLE);
                n.setPadding(dp(10), 0, 0, 0);
                r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
                r.addView(iconBtn(R.drawable.ic_social_check, 0xFF4CD964, 0, 40, v -> {
                    client.put("friends/" + my + "/" + fk, name, (j, e) -> {
                        if (e != null) {
                            toast(e);
                            return;
                        }
                        client.put("friends/" + fk + "/" + my, client.getUser(), (j2, e2) ->
                                client.delete("requests/" + my + "/" + fk, (j3, e3) -> showPage(PAGE_REQUESTS)));
                    });
                }), new LinearLayout.LayoutParams(dp(40), dp(40)));
                r.addView(iconBtn(R.drawable.ic_m3_close, 0xFFE57373, 0, 40, v ->
                        client.delete("requests/" + my + "/" + fk, (j, e) -> showPage(PAGE_REQUESTS))), new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(6);
                list.addView(r, lp);
            }
        });
        return c;
    }

    // ---- daftar voice room ----

    private View buildRoomList() {
        LinearLayout c = column();
        LinearLayout head = row();
        head.addView(label("VOICE ROOM", 26, C_TITLE));
        final TextView search = pill(roomFilter.isEmpty() ? "SEARCH" : roomFilter, C_ROW, C_TITLE, 16, null);
        search.setOnClickListener(v -> M3Dialog.input(act, "Cari room", "Nama room", roomFilter, "Cari", text -> {
            roomFilter = SocialClient.key(text.trim());
            if (roomFilter.equals("_")) roomFilter = "";
            search.setText(roomFilter.isEmpty() ? "SEARCH" : roomFilter);
            loadRooms();
            return true;
        }));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
        sp.setMarginStart(dp(14));
        head.addView(search, sp);
        c.addView(head);

        roomList = column();
        ScrollView sv = new ScrollView(act);
        sv.addView(roomList);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sl.topMargin = dp(10);
        c.addView(sv, sl);

        LinearLayout create = row();
        create.setBackground(bg(0xFF2A2C33, 14));
        create.setPadding(dp(8), dp(8), dp(8), dp(8));
        final TextView field = label(newRoomName.isEmpty() ? "NAMA ROOM" : newRoomName, 18, 0xFFE0E0E0);
        field.setBackground(bg(0xFF14151A, 10));
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setSingleLine(true);
        field.setClickable(true);
        field.setOnClickListener(v -> M3Dialog.input(act, "Buat voice room", "Nama room (huruf/angka/_)", newRoomName, "OK", text -> {
            newRoomName = SocialClient.key(text.trim());
            field.setText(newRoomName.isEmpty() ? "NAMA ROOM" : newRoomName);
            return true;
        }));
        create.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = pill("CREATE", C_BLUE, C_BLUE_TEXT, 16, v -> createRoom());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.setMarginStart(dp(8));
        create.addView(go, gp);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(10);
        c.addView(create, cl);
        return c;
    }

    private void loadRooms() {
        if (roomList == null) return;
        client.get("voice", (json, err) -> {
            if (roomList == null || page != PAGE_VOICE || voiceRoom != null) return;
            roomList.removeAllViews();
            if (err != null) {
                roomList.addView(plain(err, 13, C_TEXT));
                return;
            }
            if (!(json instanceof JSONObject)) {
                roomList.addView(plain("Belum ada room. Buat satu di bawah.", 13, C_TEXT));
                return;
            }
            JSONObject all = (JSONObject) json;
            List<String> ids = new ArrayList<>();
            Iterator<String> it = all.keys();
            while (it.hasNext()) ids.add(it.next());
            Collections.sort(ids);
            for (String id : ids) {
                if (id.startsWith("dm_")) continue;
                if (!roomFilter.isEmpty() && !id.contains(roomFilter)) continue;
                int n = membersOf(all.optJSONObject(id)).size();
                LinearLayout r = row();
                r.setBackground(bg(C_ROW, 12));
                r.setPadding(dp(14), dp(8), dp(8), dp(8));
                LinearLayout info = column();
                TextView name = label(id, 20, 0xFFFFFFFF);
                name.setSingleLine(true);
                info.addView(name);
                info.addView(label(n + "/" + MAX_VOICE, 12, 0xFFB0B0B0));
                r.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
                r.addView(pill("JOIN", C_BLUE, C_BLUE_TEXT, 18, v -> tryJoin(id)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(6);
                roomList.addView(r, lp);
            }
            if (roomList.getChildCount() == 0) roomList.addView(plain("Tidak ada room yang cocok.", 13, C_TEXT));
        });
    }

    private void createRoom() {
        String n = newRoomName;
        if (n.length() < 2 || n.length() > 20 || n.startsWith("dm_")) {
            toast("Nama room 2-20 karakter, tidak diawali dm_");
            return;
        }
        client.put("voice/" + n + "/c", client.getUser(), (j, e) -> {
            if (e != null) {
                toast(e);
                return;
            }
            newRoomName = "";
            tryJoin(n);
        });
    }

    private Map<String, Boolean> membersOf(JSONObject room) {
        Map<String, Boolean> out = new TreeMap<>();
        JSONObject mem = room == null ? null : room.optJSONObject("m");
        if (mem == null) return out;
        long now = System.currentTimeMillis();
        Iterator<String> mi = mem.keys();
        while (mi.hasNext()) {
            String u = mi.next();
            Object v = mem.opt(u);
            long ts;
            boolean m = false;
            if (v instanceof JSONObject) {
                ts = ((JSONObject) v).optLong("t");
                m = ((JSONObject) v).optBoolean("x");
            } else {
                ts = mem.optLong(u);
            }
            if (now - ts < VOICE_STALE_MS) out.put(u, m);
        }
        return out;
    }

    /** Panggilan hanya boleh ke teman yang permintaannya sudah diterima kedua pihak. */
    private void verifyDm(String id, Runnable ok) {
        final String my = me();
        client.get("friends/" + my, (fj, fe) -> {
            String friend = null;
            if (fj instanceof JSONObject) {
                Iterator<String> it = ((JSONObject) fj).keys();
                while (it.hasNext()) {
                    String fk = it.next();
                    if (dmId(my, fk).equals(id)) {
                        friend = fk;
                        break;
                    }
                }
            }
            if (friend == null) {
                toast("Telepon hanya untuk teman yang sudah menerima permintaan");
                return;
            }
            final String f = friend;
            client.get("friends/" + f + "/" + my, (rj, re) -> {
                if (re != null || rj == null) {
                    toast("Temanmu belum menerima permintaan pertemanan");
                    return;
                }
                ok.run();
            });
        });
    }

    private void tryJoin(String id) {
        if (id.startsWith("dm_")) verifyDm(id, () -> doTryJoin(id));
        else doTryJoin(id);
    }

    private void doTryJoin(String id) {
        client.get("voice/" + id, (json, err) -> {
            if (err != null) {
                toast(err);
                return;
            }
            Map<String, Boolean> now = membersOf(json instanceof JSONObject ? (JSONObject) json : null);
            if (!now.containsKey(me()) && now.size() >= MAX_VOICE) {
                toast("Room penuh (maksimal " + MAX_VOICE + " orang)");
                return;
            }
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                joinVoice(id);
            } else {
                pendingMic = id;
                ActivityCompat.requestPermissions(act, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
        });
    }

    private void joinVoice(String id) {
        String user = client.getUser();
        if (!VoiceService.join(act, id, SocialClient.key(user), uidFor(user), client.getUrl())) {
            toast("Tidak bisa memulai layanan suara");
            return;
        }
        voiceRoom = id;
        muted = false;
        speaking = new HashSet<>();
        members.clear();
        applyStoredAudio();
        rebuildStrip();
        ensureTick();
        showPage(PAGE_VOICE);
    }

    private void leaveVoice() {
        VoiceService.leave(act);
        if (voiceRoom != null) ringing.add(voiceRoom);
        voiceRoom = null;
        members.clear();
        speaking = new HashSet<>();
        rebuildStrip();
        showPage(PAGE_VOICE);
    }

    // ---- panggilan ----

    private View buildCall() {
        LinearLayout c = column();
        LinearLayout head = row();
        TextView title = label(voiceRoom, 20, C_TITLE);
        title.setSingleLine(true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        callCount = label("", 14, 0xFFB0B0B0);
        head.addView(callCount);
        head.addView(iconBtn(R.drawable.ic_m3_link, 0xFFFFFFFF, 0, 40, v -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("room", voiceRoom));
            toast("Nama room disalin");
        }), new LinearLayout.LayoutParams(dp(40), dp(40)));
        c.addView(head);

        callTiles = column();
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, 0, 1f);
        tl.topMargin = dp(6);
        c.addView(callTiles, tl);

        LinearLayout bar = row();
        bar.addView(iconBtn(R.drawable.ic_m3_close, 0xFFFFFFFF, 0xFFE8003B, 44, v -> leaveVoice()), new LinearLayout.LayoutParams(dp(56), dp(44)));
        muteBtn = iconBtn(muted ? R.drawable.ic_social_mic_off : R.drawable.ic_microphone, 0xFFFFFFFF, 0xFF3A3A3A, 44, v -> {
            muted = !muted;
            muteBtn.setImageResource(muted ? R.drawable.ic_social_mic_off : R.drawable.ic_microphone);
            VoiceService.setMuted(act, muted);
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(56), dp(44));
        mp.setMarginStart(dp(8));
        bar.addView(muteBtn, mp);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(dp(56), dp(44));
        kp.setMarginStart(dp(8));
        bar.addView(iconBtn(R.drawable.ic_social_speaker, 0xFFFFFFFF, 0xFF3A3A3A, 44, v -> showPage(PAGE_SETTINGS)), kp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(8);
        c.addView(bar, bp);
        rebuildTiles();
        return c;
    }

    private int tint(int color) {
        int r = (color >> 16) & 0xff, g = (color >> 8) & 0xff, b = color & 0xff;
        r = (r + 255 * 2) / 3;
        g = (g + 255 * 2) / 3;
        b = (b + 255 * 2) / 3;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void rebuildTiles() {
        if (callTiles == null) return;
        callTiles.removeAllViews();
        tileAvatars.clear();
        tileBoxes.clear();
        if (callCount != null) callCount.setText(members.size() + "/" + MAX_VOICE);
        List<String> keys = new ArrayList<>(members.keySet());
        int idx = 0;
        for (int rowI = 0; rowI < 2; rowI++) {
            LinearLayout r = row();
            for (int col = 0; col < 3; col++) {
                FrameLayout tile = new FrameLayout(act);
                String key = idx < keys.size() ? keys.get(idx) : null;
                idx++;
                if (key == null) {
                    tile.setBackground(bg(0xFF2A2C33, 16));
                } else {
                    final String k = key;
                    tile.setBackground(tileBg(k, speaking.contains(uidFor(k))));
                    LinearLayout inner = column();
                    inner.setGravity(Gravity.CENTER);
                    AvatarView av = new AvatarView(act);
                    av.setPerson(k, k);
                    av.setSpeaking(speaking.contains(uidFor(k)));
                    tileAvatars.put(k, av);
                    tileBoxes.put(k, tile);
                    inner.addView(av, new LinearLayout.LayoutParams(dp(54), dp(54)));
                    TextView n = label(k, 15, 0xFF111111);
                    n.setSingleLine(true);
                    n.setGravity(Gravity.CENTER);
                    n.setPadding(dp(4), dp(4), dp(4), 0);
                    inner.addView(n, new LinearLayout.LayoutParams(-1, -2));
                    if (Boolean.TRUE.equals(members.get(k))) inner.addView(label("MIC MATI", 11, 0xFFB3261E));
                    tile.addView(inner, new FrameLayout.LayoutParams(-1, -1));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                r.addView(tile, lp);
            }
            callTiles.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
    }

    private GradientDrawable tileBg(String key, boolean talking) {
        GradientDrawable g = bg(tint(Avatars.colorFor(key)), 16);
        if (talking) g.setStroke(dp(3), Avatars.RING_ON);
        return g;
    }

    // ---- pengaturan suara ----

    private View buildSettings() {
        LinearLayout c = column();
        LinearLayout head = row();
        head.addView(iconBtn(R.drawable.ic_social_phone, 0xFFFFFFFF, C_ROW, 40, v -> showPage(PAGE_VOICE)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = label("SUARA", 26, C_TITLE);
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        c.addView(head);

        LinearLayout list = column();
        ScrollView sv = new ScrollView(act);
        sv.addView(list);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sl.topMargin = dp(8);
        c.addView(sv, sl);

        String my = me();
        boolean any = false;
        for (String k : members.keySet()) {
            if (k.equals(my)) continue;
            any = true;
            final String key = k;
            list.addView(label(k, 18, C_TITLE));
            list.addView(slider(audioPrefs.getInt("vol_" + k, 90), pct -> {
                audioPrefs.edit().putInt("vol_" + key, pct).apply();
                VoiceService.setPeerVolume(act, uidFor(key), pct);
            }));
        }
        if (!any) list.addView(plain("Belum ada teman lain di room ini.", 13, C_TEXT));
        TextView mic = label("MIC SETTINGS", 22, C_TITLE);
        mic.setPadding(0, dp(18), 0, 0);
        list.addView(mic);
        list.addView(plain("Sensitivitas: makin kecil, suara pelan dan noise makin dipotong.", 12, 0xFFB0B0B0));
        list.addView(slider(audioPrefs.getInt("mic", 90), pct -> {
            audioPrefs.edit().putInt("mic", pct).apply();
            VoiceService.setMicSensitivity(act, pct);
        }));
        return c;
    }

    private interface PercentListener {
        void changed(int pct);
    }

    private View slider(int initial, PercentListener l) {
        LinearLayout r = row();
        r.setPadding(0, dp(4), 0, dp(4));
        final TextView pct = label(initial + "%", 18, C_TITLE);
        SeekBar sb = new SeekBar(act);
        sb.setMax(100);
        sb.setProgress(initial);
        sb.setProgressTintList(ColorStateList.valueOf(C_BLUE));
        sb.setThumbTintList(ColorStateList.valueOf(C_BLUE));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF555555));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                pct.setText(p + "%");
                if (fromUser) l.changed(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) { }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(60), -2);
        pp.setMarginStart(dp(8));
        pct.setGravity(Gravity.END);
        r.addView(pct, pp);
        return r;
    }

    /** Kirim ulang volume tersimpan ke VoiceService setelah masuk room */
    private void applyStoredAudio() {
        VoiceService.setMicSensitivity(act, audioPrefs.getInt("mic", 90));
        for (Map.Entry<String, ?> e : audioPrefs.getAll().entrySet()) {
            if (e.getKey().startsWith("vol_") && e.getValue() instanceof Integer) {
                VoiceService.setPeerVolume(act, uidFor(e.getKey().substring(4)), (Integer) e.getValue());
            }
        }
    }

    // ---- chat ----

    private View buildChat() {
        LinearLayout c = column();
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        tabs.setTag("tabs");
        hs.addView(tabs);
        c.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        msgScroll = new ScrollView(act);
        msgBox = column();
        msgScroll.addView(msgBox);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, 0, 1f);
        ml.topMargin = dp(8);
        c.addView(msgScroll, ml);

        TextView newChat = pill("+NEW CHAT", C_ROW, C_BLUE, 18, v -> M3Dialog.input(act, "Chat baru", "Nama room (huruf/angka/_)", null, "Buat", name -> {
            String n = SocialClient.key(name.trim());
            if (n.length() < 2 || n.length() > 20) {
                toast("Nama 2-20 karakter");
                return false;
            }
            client.put("rooms/" + n, Boolean.TRUE, (j, e) -> {
                if (e != null) toast(e);
                else {
                    chatRoom = n;
                    showPage(PAGE_CHAT);
                }
            });
            return true;
        }));
        final String roomNow = chatRoom;
        final TextView muteRoom = pill(isMuted(roomNow) ? "UNMUTE" : "MUTE", C_ROW, isMuted(roomNow) ? 0xFFE57373 : C_TITLE, 18, null);
        muteRoom.setOnClickListener(v -> {
            boolean m = !isMuted(roomNow);
            setMuted(roomNow, m);
            muteRoom.setText(m ? "UNMUTE" : "MUTE");
            muteRoom.setTextColor(m ? 0xFFE57373 : C_TITLE);
            toast(m ? "Chat ini dimute, tidak ada notifikasi" : "Notifikasi chat ini dinyalakan");
            loadChatTabs();
        });
        LinearLayout btnRow = row();
        btnRow.addView(newChat);
        LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(-2, -2);
        mrp.setMarginStart(dp(8));
        btnRow.addView(muteRoom, mrp);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = dp(8);
        c.addView(btnRow, nl);

        LinearLayout input = row();
        TextView field = label("KETIK PESAN DI SINI", 16, 0xFFB0B0B0);
        field.setBackground(bg(C_ROW, 12));
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setSingleLine(true);
        field.setClickable(true);
        field.setOnClickListener(v -> compose());
        input.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(56), dp(44));
        sp.setMarginStart(dp(8));
        input.addView(iconBtn(R.drawable.ic_social_send, C_BLUE_TEXT, C_BLUE, 44, v -> compose()), sp);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = dp(8);
        c.addView(input, il);
        return c;
    }

    private void compose() {
        M3Dialog.input(act, "Pesan ke " + chatTitle(chatRoom), "Ketik pesan", null, "Kirim", text -> {
            String t = text.trim();
            if (t.isEmpty()) return false;
            long now = System.currentTimeMillis();
            if (now - lastSend < COOLDOWN_MS) {
                toast("Tunggu 3 detik antar pesan");
                return false;
            }
            lastSend = now;
            postMessage(chatRoom, t.length() > 300 ? t.substring(0, 300) : t);
            return true;
        });
    }

    private String chatTitle(String room) {
        return room.startsWith("dm_") ? "teman" : room;
    }

    private void postMessage(String room, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("u", client.getUser());
            o.put("t", text);
            o.put("ts", new JSONObject().put(".sv", "timestamp"));
            client.post("messages/" + room, o, (j, e) -> {
                if (e != null) toast(e);
                else if (page == PAGE_CHAT) loadMessages();
            });
        } catch (Exception e) {
            toast(String.valueOf(e));
        }
    }

    private void loadChatTabs() {
        final String my = me();
        final List<String[]> tabs = new ArrayList<>(); // id, label
        tabs.add(new String[]{"umum", "UMUM"});
        client.get("rooms", (rj, re) -> {
            if (rj instanceof JSONObject) {
                List<String> extra = new ArrayList<>();
                Iterator<String> it = ((JSONObject) rj).keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!k.equals("umum")) extra.add(k);
                }
                Collections.sort(extra);
                for (String k : extra) tabs.add(new String[]{k, k.toUpperCase(Locale.ROOT)});
            }
            client.get("friends/" + my, (fj, fe) -> {
                if (fj instanceof JSONObject) {
                    JSONObject o = (JSONObject) fj;
                    Iterator<String> it = o.keys();
                    while (it.hasNext()) {
                        String fk = it.next();
                        tabs.add(new String[]{dmId(my, fk), "@" + o.optString(fk, fk)});
                    }
                }
                if (page != PAGE_CHAT || panel.getChildCount() == 0) return;
                LinearLayout bar = (LinearLayout) panel.findViewWithTag("tabs");
                if (bar == null) return;
                bar.removeAllViews();
                for (String[] t : tabs) {
                    boolean on = t[0].equals(chatRoom);
                    TextView tv = pill(t[1], on ? 0xFF4A5470 : 0xFF3A3D47, isMuted(t[0]) ? 0xFF8A8A8A : 0xFFFFFFFF, 16, v -> {
                        chatRoom = t[0];
                        lastKey = "";
                        showPage(PAGE_CHAT);
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.setMarginEnd(dp(6));
                    bar.addView(tv, lp);
                }
            });
        });
    }

    private void loadMessages() {
        if (msgBox == null) return;
        final String room = chatRoom;
        String path = "messages/" + room + "?orderBy=" + SocialClient.q("$key") + "&limitToLast=60";
        client.get(path, (json, err) -> {
            if (msgBox == null || page != PAGE_CHAT || !room.equals(chatRoom) || err != null) return;
            if (!(json instanceof JSONObject)) {
                if (lastKey.isEmpty()) msgBox.removeAllViews();
                return;
            }
            JSONObject o = (JSONObject) json;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            if (!keys.isEmpty() && keys.get(keys.size() - 1).equals(lastKey)) return;
            msgBox.removeAllViews();
            SimpleDateFormat df = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String mine = client.getUser();
            for (String k : keys) {
                JSONObject m = o.optJSONObject(k);
                if (m == null) continue;
                String u = m.optString("u");
                boolean self = u.equalsIgnoreCase(mine);
                LinearLayout bubble = column();
                bubble.setBackground(bg(0xFF2B2E36, 12));
                bubble.setPadding(dp(12), dp(6), dp(12), dp(8));
                bubble.addView(label(u, 12, self ? C_BLUE : 0xFFB0B0B0));
                bubble.addView(plain(m.optString("t"), 15, 0xFFFFFFFF));
                long ts = m.optLong("ts");
                if (ts > 0) bubble.addView(plain(df.format(new Date(ts)), 10, 0xFF8A8A8A));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = dp(6);
                lp.gravity = self ? Gravity.END : Gravity.START;
                msgBox.addView(bubble, lp);
            }
            lastKey = keys.isEmpty() ? "" : keys.get(keys.size() - 1);
            msgScroll.post(() -> msgScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ---- avatar pojok kiri atas ----

    private void positionStrip() {
        boolean show = voiceRoom != null && panel.getVisibility() != View.VISIBLE && !members.isEmpty();
        strip.setVisibility(show ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) strip.getLayoutParams();
        lp.leftMargin = toolbarOn ? dp(66) : dp(8);
        lp.topMargin = dp(8);
        strip.setLayoutParams(lp);
    }

    private void rebuildStrip() {
        strip.removeAllViews();
        stripAvatars.clear();
        if (voiceRoom != null) {
            for (String k : members.keySet()) {
                AvatarView av = new AvatarView(act);
                av.setPerson(k, k);
                av.setSpeaking(speaking.contains(uidFor(k)));
                stripAvatars.put(k, av);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
                lp.bottomMargin = dp(6);
                strip.addView(av, lp);
            }
        }
        positionStrip();
    }

    private void applySpeaking() {
        for (Map.Entry<String, AvatarView> e : stripAvatars.entrySet()) {
            e.getValue().setSpeaking(speaking.contains(uidFor(e.getKey())));
        }
        for (Map.Entry<String, AvatarView> e : tileAvatars.entrySet()) {
            boolean s = speaking.contains(uidFor(e.getKey()));
            e.getValue().setSpeaking(s);
            View box = tileBoxes.get(e.getKey());
            if (box != null) box.setBackground(tileBg(e.getKey(), s));
        }
    }

    private void applyPhotos() {
        for (Map.Entry<String, AvatarView> e : stripAvatars.entrySet()) e.getValue().setPhoto(Avatars.photo(e.getKey()));
        for (Map.Entry<String, AvatarView> e : tileAvatars.entrySet()) e.getValue().setPhoto(Avatars.photo(e.getKey()));
    }

    // ---- polling ----

    private boolean shouldRun() {
        return toolbarOn || voiceRoom != null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!shouldRun()) {
                running = false;
                return;
            }
            refreshMembers();
            if (page == PAGE_VOICE && voiceRoom == null) loadRooms();
            if (page == PAGE_CHAT) loadMessages();
            h.postDelayed(this, POLL_MS);
        }
    };

    private void ensureTick() {
        if (!running && shouldRun()) {
            running = true;
            h.post(tick);
        }
    }

    private void refreshMembers() {
        if (voiceRoom == null || !client.isLoggedIn()) return;
        final String room = voiceRoom;
        client.get("voice/" + room, (json, err) -> {
            if (err != null || voiceRoom == null || !room.equals(voiceRoom)) return;
            Map<String, Boolean> now = membersOf(json instanceof JSONObject ? (JSONObject) json : null);
            if (!now.containsKey(me())) now.put(me(), muted);
            boolean same = now.equals(members);
            members = now;
            for (String k : members.keySet()) Avatars.fetch(client, k, this::applyPhotos);
            if (!same) {
                rebuildStrip();
                if (page == PAGE_VOICE) rebuildTiles();
            }
        });
    }

    // ---- notifikasi (chat, permintaan teman, panggilan) ----

    private boolean isMuted(String room) {
        return notifPrefs.getStringSet("muted", new HashSet<>()).contains(room);
    }

    private void setMuted(String room, boolean m) {
        Set<String> cur = new HashSet<>(notifPrefs.getStringSet("muted", new HashSet<>()));
        if (m) cur.add(room);
        else cur.remove(room);
        notifPrefs.edit().putStringSet("muted", cur).apply();
    }

    private boolean notifOn() {
        return notifPrefs.getBoolean(NOTIF_KEY, true);
    }

    private void toggleNotif() {
        boolean on = !notifOn();
        notifPrefs.edit().putBoolean(NOTIF_KEY, on).apply();
        bellBtn.setImageResource(on ? R.drawable.ic_social_bell : R.drawable.ic_social_bell_off);
        toast(on ? "Notifikasi dinyalakan" : "Notifikasi dimatikan");
        if (on) {
            lastMsg.clear();
            seenReq.clear();
            reqPrimed = false;
            ringing.clear();
            ensureNotif();
        } else {
            dismissBanner();
        }
    }

    private final Runnable notifTick = new Runnable() {
        @Override
        public void run() {
            if (!notifOn() || !client.isLoggedIn()) {
                notifRunning = false;
                return;
            }
            pollNotifs();
            h.postDelayed(this, NOTIF_MS);
        }
    };

    private void ensureNotif() {
        if (!notifRunning && notifOn() && client.isLoggedIn()) {
            notifRunning = true;
            h.postDelayed(notifTick, 2000);
        }
    }

    private void pollNotifs() {
        final String my = me();
        if (notifCycle++ % 10 == 0) {
            client.get("friends/" + my, (j, e) -> {
                if (!(j instanceof JSONObject)) return;
                notifFriends.clear();
                JSONObject o = (JSONObject) j;
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String fk = it.next();
                    notifFriends.put(fk, o.optString(fk, fk));
                }
                mutual.retainAll(notifFriends.keySet());
                for (String fk : notifFriends.keySet()) {
                    client.get("friends/" + fk + "/" + my, (rj, re) -> {
                        if (re != null) return;
                        if (rj != null) mutual.add(fk);
                        else mutual.remove(fk);
                    });
                }
            });
            client.get("rooms", (j, e) -> {
                if (!(j instanceof JSONObject)) return;
                notifRooms.clear();
                Iterator<String> it = ((JSONObject) j).keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!k.equals("umum")) notifRooms.add(k);
                }
            });
        }

        // permintaan pertemanan
        client.get("requests/" + my, (j, e) -> {
            if (e != null || !notifOn()) return;
            List<String> fresh = new ArrayList<>();
            Set<String> now = new HashSet<>();
            if (j instanceof JSONObject) {
                JSONObject o = (JSONObject) j;
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    now.add(k);
                    if (!seenReq.contains(k)) fresh.add(o.optString(k, k));
                }
            }
            seenReq.clear();
            seenReq.addAll(now);
            boolean first = !reqPrimed;
            reqPrimed = true;
            if (fresh.isEmpty()) return;
            String body = fresh.size() == 1 && !first ? fresh.get(0) + " ingin berteman denganmu"
                    : "Ada " + fresh.size() + " permintaan pertemanan";
            showBanner("Permintaan teman", body, R.drawable.ic_social_people, false,
                    () -> showPage(PAGE_REQUESTS), null);
        });

        // pesan baru
        List<String> rooms = new ArrayList<>();
        rooms.add("umum");
        rooms.addAll(notifRooms);
        for (String fk : notifFriends.keySet()) if (mutual.contains(fk)) rooms.add(dmId(my, fk));
        for (String room : rooms) {
            client.get("messages/" + room + "?orderBy=" + SocialClient.q("$key") + "&limitToLast=1", (j, e) -> {
                if (e != null || !notifOn()) return;
                if (!(j instanceof JSONObject) || ((JSONObject) j).length() == 0) {
                    if (!lastMsg.containsKey(room)) lastMsg.put(room, "");
                    return;
                }
                JSONObject o = (JSONObject) j;
                String newest = null;
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (newest == null || k.compareTo(newest) > 0) newest = k;
                }
                String prev = lastMsg.get(room);
                lastMsg.put(room, newest);
                if (prev == null || prev.equals(newest) || isMuted(room)) return;
                JSONObject m = o.optJSONObject(newest);
                if (m == null) return;
                String u = m.optString("u");
                String t = m.optString("t");
                if (u.equalsIgnoreCase(client.getUser()) || t.startsWith(CALL_TEXT)) return;
                if (page == PAGE_CHAT && room.equals(chatRoom) && panel.getVisibility() == View.VISIBLE) return;
                String where = room.startsWith("dm_") ? u : u + " di " + room;
                showBanner(where, t, R.drawable.ic_social_chat, false, () -> {
                    chatRoom = room;
                    showPage(PAGE_CHAT);
                }, null);
            });
        }

        // panggilan masuk
        client.get("voice", (j, e) -> {
            if (e != null || !notifOn()) return;
            JSONObject all = j instanceof JSONObject ? (JSONObject) j : null;
            Set<String> active = new HashSet<>();
            if (all != null) {
                for (Map.Entry<String, String> f : notifFriends.entrySet()) {
                    String id = dmId(my, f.getKey());
                    Map<String, Boolean> mem = membersOf(all.optJSONObject(id));
                    if (mutual.contains(f.getKey()) && !isMuted(id) && mem.containsKey(f.getKey()) && !mem.containsKey(my)) {
                        active.add(id);
                        if (voiceRoom == null && !ringing.contains(id)) {
                            ringing.add(id);
                            final String name = f.getValue();
                            showBanner("Panggilan dari " + name, "Voice chat masuk", R.drawable.ic_social_phone, true,
                                    () -> tryJoin(id), () -> { });
                        }
                    }
                }
            }
            ringing.retainAll(active);
        });
    }

    private void stopTone() {
        try {
            if (tone != null) tone.stop();
        } catch (Throwable ignored) { }
        tone = null;
    }

    private void playTone(boolean ring) {
        stopTone();
        try {
            Uri u = RingtoneManager.getDefaultUri(ring ? RingtoneManager.TYPE_RINGTONE : RingtoneManager.TYPE_NOTIFICATION);
            tone = RingtoneManager.getRingtone(act, u);
            if (tone != null) tone.play();
        } catch (Throwable ignored) { }
        try {
            Vibrator vb = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (vb != null) {
                if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createOneShot(ring ? 400 : 60, VibrationEffect.DEFAULT_AMPLITUDE));
                else vb.vibrate(ring ? 400 : 60);
            }
        } catch (Throwable ignored) { }
    }

    private void dismissBanner() {
        if (bannerHide != null) h.removeCallbacks(bannerHide);
        bannerHide = null;
        stopTone();
        final View b = banner;
        banner = null;
        bannerCall = false;
        if (b == null) return;
        b.animate().cancel();
        b.animate().alpha(0f).translationY(-dp(70)).setDuration(220)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    ViewGroup p = (ViewGroup) b.getParent();
                    if (p != null) p.removeView(b);
                }).start();
    }

    /** Banner notifikasi di tengah atas: turun dengan animasi, hilang sendiri. Panggilan punya tombol Terima/Tolak. */
    private void showBanner(String title, String body, int icon, boolean call, Runnable onTap, Runnable onDecline) {
        if (!notifOn()) return;
        if (bannerCall && !call && banner != null) return;
        dismissBanner();
        LinearLayout card = row();
        GradientDrawable cb = bg(C_PANEL, 18);
        cb.setStroke(dp(2), C_PANEL_EDGE);
        card.setBackground(cb);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        card.setClickable(true);
        card.setElevation(dp(8));

        ImageView ic = new ImageView(act);
        ic.setImageResource(icon);
        ic.setColorFilter(C_BLUE);
        card.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout col = column();
        TextView t = label(title, 14, C_TITLE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(260));
        col.addView(t);
        TextView bd = plain(body, 13, C_TEXT);
        bd.setMaxLines(2);
        bd.setEllipsize(TextUtils.TruncateAt.END);
        bd.setMaxWidth(dp(260));
        col.addView(bd);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.setMarginStart(dp(10));
        card.addView(col, cl);

        if (call) {
            TextView no = pill("TOLAK", 0xFFB3261E, 0xFFFFFFFF, 13, v -> {
                dismissBanner();
                if (onDecline != null) onDecline.run();
            });
            TextView yes = pill("TERIMA", C_BLUE, C_BLUE_TEXT, 13, v -> {
                dismissBanner();
                if (onTap != null) onTap.run();
            });
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
            nl.setMarginStart(dp(12));
            card.addView(no, nl);
            LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(-2, -2);
            yl.setMarginStart(dp(6));
            card.addView(yes, yl);
        } else {
            card.setOnClickListener(v -> {
                dismissBanner();
                if (onTap != null) onTap.run();
            });
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(8);
        root.addView(card, lp);
        card.setAlpha(0f);
        card.setTranslationY(-dp(70));
        card.animate().alpha(1f).translationY(0f).setDuration(340)
                .setInterpolator(new OvershootInterpolator(1.0f)).start();
        banner = card;
        bannerCall = call;
        playTone(call);
        if (call) h.postDelayed(this::stopTone, 6000);
        bannerHide = this::dismissBanner;
        h.postDelayed(bannerHide, call ? 25000 : 4500);
    }
}
