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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private boolean toolbarOn, running, receiverOn;
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
    }

    // ---- API untuk GameActivity ----

    public boolean isToolbarOn() {
        return toolbarOn;
    }

    public void toggleToolbar() {
        toolbarOn = !toolbarOn;
        toolbar.setVisibility(toolbarOn ? View.VISIBLE : View.GONE);
        if (!toolbarOn) {
            page = PAGE_NONE;
            panel.setVisibility(View.GONE);
        }
        positionStrip();
        ensureTick();
        if (toolbarOn && !client.isLoggedIn()) {
            toast("Masuk dulu di menu Sosial pada launcher");
        }
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
            page = PAGE_NONE;
            panel.setVisibility(View.GONE);
            highlight();
            positionStrip();
            return;
        }
        showPage(p);
    }

    private void showPage(int p) {
        page = p;
        callTiles = null;
        callCount = null;
        muteBtn = null;
        roomList = null;
        msgBox = null;
        panel.removeAllViews();
        panel.setVisibility(View.VISIBLE);
        if (!client.isLoggedIn()) {
            LinearLayout c = column();
            c.addView(label("SOSIAL", 26, C_TITLE));
            TextView t = plain("Kamu belum masuk. Buka menu Sosial di launcher, pilih username, lalu jalankan game lagi.", 14, C_TEXT);
            t.setPadding(0, dp(12), 0, 0);
            c.addView(t);
            panel.addView(c, new FrameLayout.LayoutParams(-1, -1));
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

    private void highlight() {
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
                r.addView(iconBtn(R.drawable.ic_social_chat, 0xFFFFFFFF, 0, 40, v -> {
                    chatRoom = dmId(my, fk);
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
        String room = dmId(my, fk);
        postMessage(room, "Memanggil kamu lewat voice. Tekan ikon telepon di namaku pada daftar teman.");
        tryJoin(room);
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

    private void tryJoin(String id) {
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
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = dp(8);
        c.addView(newChat, nl);

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
                    TextView tv = pill(t[1], on ? 0xFF4A5470 : 0xFF3A3D47, 0xFFFFFFFF, 16, v -> {
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
}
