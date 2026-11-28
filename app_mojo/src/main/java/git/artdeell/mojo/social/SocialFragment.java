package git.artdeell.mojo.social;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.utils.Fluid;
import git.artdeell.mojo.utils.M3Dialog;

/** Social screen: group chat, voice room list, friends and account, backed by SocialClient. */
public class SocialFragment extends Fragment {
    public static final String TAG = "SocialFragment";
    private static final int TAB_CHAT = 0, TAB_VOICE = 1, TAB_FRIENDS = 2, TAB_ACCOUNT = 3;
    private static final long POLL_MS = 3000, COOLDOWN_MS = 3000, VOICE_STALE_MS = 90000;
    /** Batas peserta per room (mesh P2P makin berat kalau terlalu banyak). Keluar otomatis diatur di VoiceService. */
    private static final int MAX_VOICE_MEMBERS = 6;

    private SocialClient mClient;
    private FrameLayoutHolder mBody;
    private final TextView[] mTabs = new TextView[4];
    private int mTab = TAB_CHAT;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mPoll;
    private String mRoom = "umum";
    private long mLastSend;
    private LinearLayout mMsgBox;
    private ScrollView mMsgScroll;
    private TextView mChatInfo;
    private String mLastKey = "";
    private String mVoiceRoom;
    private boolean mMuted;
    private boolean mShowList;
    private Runnable mVoiceView;
    private ImageView mMicView;
    private ActivityResultLauncher<String> mMicLauncher;
    private String mPendingJoin;
    private java.util.Set<Integer> mSpeaking = new java.util.HashSet<>();
    private final java.util.Map<String, AvatarView> mAvatars = new java.util.LinkedHashMap<>();
    private ActivityResultLauncher<String> mPhotoLauncher;

    /** Tiny wrapper so the body container can be swapped */
    private static class FrameLayoutHolder {
        final ViewGroup view;
        FrameLayoutHolder(ViewGroup v) { view = v; }
        void show(View child) {
            view.removeAllViews();
            view.addView(child, new ViewGroup.LayoutParams(-1, -1));
        }
    }

    public SocialFragment() {
        super(R.layout.fragment_social);
    }

    /** Status dari VoiceService (proses :voice): aktif atau tidak, room, mute, siapa yang bicara */
    private final BroadcastReceiver mVoiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent i) {
            if (!isAdded()) return;
            boolean active = i.getBooleanExtra("active", false);
            String room = i.getStringExtra("room");
            String error = i.getStringExtra("error");
            if (error != null) toast(error);
            boolean changed = active != (mVoiceRoom != null)
                    || (active && room != null && !room.equals(mVoiceRoom));
            mVoiceRoom = active ? room : null;
            mMuted = i.getBooleanExtra("muted", false);
            mClient.setVoiceHint(active);
            java.util.Set<Integer> sp = new java.util.HashSet<>();
            int[] arr = i.getIntArrayExtra("speaking");
            if (arr != null) for (int u : arr) sp.add(u);
            mSpeaking = sp;
            if (changed) {
                if (mTab == TAB_VOICE) select(TAB_VOICE);
            } else {
                applySpeaking();
                if (mMicView != null) mMicView.setAlpha(mMuted ? 0.4f : 1f);
            }
        }
    };

    private void registerVoiceReceiver() {
        IntentFilter f = new IntentFilter(VoiceService.ACTION_STATE);
        Context c = requireContext().getApplicationContext();
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(mVoiceReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else c.registerReceiver(mVoiceReceiver, f);
    }

    private void unregisterVoiceReceiver() {
        try {
            requireContext().getApplicationContext().unregisterReceiver(mVoiceReceiver);
        } catch (IllegalArgumentException ignored) { }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPhotoLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) uploadPhoto(uri);
        });
        mMicLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            String id = mPendingJoin;
            mPendingJoin = null;
            if (id == null || !isAdded()) return;
            if (granted) joinVoice(id);
            else toast("Izin mikrofon ditolak, voice chat butuh mikrofon");
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mClient = new SocialClient(requireContext());
        ((TextView) view.findViewById(R.id.header_title)).setText("Sosial");
        view.findViewById(R.id.header_back).setOnClickListener(v -> requireActivity().onBackPressed());
        view.findViewById(R.id.header_home).setOnClickListener(v -> Tools.backToMainMenu(requireActivity()));
        mBody = new FrameLayoutHolder(view.findViewById(R.id.social_body));
        mTabs[TAB_CHAT] = view.findViewById(R.id.social_tab_chat);
        mTabs[TAB_VOICE] = view.findViewById(R.id.social_tab_voice);
        mTabs[TAB_FRIENDS] = view.findViewById(R.id.social_tab_friends);
        mTabs[TAB_ACCOUNT] = view.findViewById(R.id.social_tab_account);
        for (int i = 0; i < 4; i++) {
            final int t = i;
            mTabs[i].setOnClickListener(v -> select(t));
        }
        Fluid.pressAll(view);
        registerVoiceReceiver();
        if (mClient.isVoiceHint()) VoiceService.query(requireContext());
        select(mClient.isConfigured() && mClient.isLoggedIn() ? TAB_CHAT : TAB_ACCOUNT);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mTab == TAB_CHAT) startPolling();
    }

    @Override
    public void onPause() {
        stopPolling();
        super.onPause();
    }

    // ---- helpers ----

    private int dp(float v) {
        return M3Dialog.dp(requireContext(), v);
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private TextView text(CharSequence s, float sp, int colorRes) {
        return M3Dialog.label(requireContext(), s, sp, colorRes);
    }

    private TextView button(CharSequence s, boolean primary, View.OnClickListener l) {
        TextView b = new TextView(requireContext());
        b.setText(s);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(primary ? R.drawable.m3_button_primary : R.drawable.m3_button_tonal);
        b.setTextColor(color(primary ? R.color.m3_on_primary : R.color.m3_text));
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        Fluid.pressAll(b);
        return b;
    }

    private EditText input(String hint) {
        EditText e = new EditText(requireContext());
        e.setHint(hint);
        e.setHintTextColor(color(R.color.m3_outline));
        e.setTextColor(color(R.color.m3_text));
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setBackgroundResource(R.drawable.m3_card);
        e.setPadding(dp(16), dp(10), dp(16), dp(10));
        return e;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(requireContext());
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(requireContext());
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private void toast(String s) {
        if (isAdded()) Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }

    private void select(int tab) {
        stopPolling();
        mTab = tab;
        for (int i = 0; i < 4; i++) {
            boolean on = i == tab;
            mTabs[i].setBackgroundResource(on ? R.drawable.m3_button_primary : R.drawable.m3_chip_off);
            mTabs[i].setTextColor(color(on ? R.color.m3_on_primary : R.color.m3_text));
        }
        if (!mClient.isConfigured()) {
            mBody.show(buildSetup());
            return;
        }
        if (tab != TAB_ACCOUNT && !mClient.isLoggedIn()) {
            mBody.show(buildAccount());
            return;
        }
        switch (tab) {
            case TAB_CHAT: mBody.show(buildChat()); startPolling(); break;
            case TAB_VOICE: mBody.show(buildVoice()); break;
            case TAB_FRIENDS: mBody.show(buildFriends()); break;
            default: mBody.show(buildAccount());
        }
    }

    private void startPolling() {
        stopPolling();
        mPoll = new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) return;
                loadMessages();
                mHandler.postDelayed(this, POLL_MS);
            }
        };
        mHandler.post(mPoll);
    }

    private void stopPolling() {
        if (mPoll != null) mHandler.removeCallbacks(mPoll);
        mPoll = null;
        if (mVoiceView != null) mHandler.removeCallbacks(mVoiceView);
        mVoiceView = null;
    }

    @Override
    public void onDestroyView() {
        stopPolling();
        unregisterVoiceReceiver();
        mMicView = null;
        super.onDestroyView();
    }

    // ---- setup ----

    private View buildSetup() {
        LinearLayout c = column();
        c.setPadding(dp(8), dp(8), dp(8), 0);
        c.addView(text("Hubungkan database", 20, R.color.m3_text));
        TextView hint = text("Tempel URL Firebase Realtime Database (gratis), contoh: https://nama-project-default-rtdb.firebaseio.com. Langkahnya ada di SOSIAL_SETUP.md.", 13, R.color.m3_text_dim);
        hint.setPadding(0, dp(6), 0, dp(10));
        c.addView(hint);
        LinearLayout r = row();
        EditText url = input("https://...firebaseio.com");
        url.setText(mClient.getUrl());
        r.addView(url, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView save = button("Simpan", true, v -> {
            String u = url.getText().toString().trim();
            if (!u.startsWith("https://")) {
                toast("URL harus diawali https://");
                return;
            }
            mClient.setUrl(u);
            select(TAB_ACCOUNT);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.setMarginStart(dp(10));
        r.addView(save, lp);
        c.addView(r);
        return c;
    }

    // ---- account ----

    private View buildAccount() {
        LinearLayout c = column();
        c.setPadding(dp(8), dp(8), dp(8), 0);
        c.addView(text("Akun Sosial", 20, R.color.m3_text));
        if (mClient.isLoggedIn()) {
            String name = mClient.getUser();
            LinearLayout r = row();
            r.setPadding(0, dp(14), 0, dp(14));
            final String myKey = SocialClient.key(name);
            final AvatarView avatar = new AvatarView(requireContext());
            avatar.setPerson(myKey, name);
            Avatars.fetch(mClient, myKey, () -> avatar.setPhoto(Avatars.photo(myKey)));
            r.addView(avatar, new LinearLayout.LayoutParams(dp(72), dp(72)));
            LinearLayout info = column();
            info.setPadding(dp(16), 0, 0, 0);
            info.addView(text(name, 22, R.color.m3_text));
            info.addView(text("Masuk sebagai " + name, 13, R.color.m3_text_dim));
            r.addView(info);
            c.addView(r);
            TextView photo = button("Ganti foto profil", true, v -> mPhotoLauncher.launch("image/*"));
            c.addView(photo, new LinearLayout.LayoutParams(-1, dp(48)));
            TextView out = button("Keluar dari Akun", false, v -> {
                mClient.logout();
                select(TAB_ACCOUNT);
            });
            LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(-1, dp(48));
            outLp.topMargin = dp(10);
            c.addView(out, outLp);
            TextView db = button("Ganti database", false, v -> mBody.show(buildSetup()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
            lp.topMargin = dp(10);
            c.addView(db, lp);
            return c;
        }
        TextView hint = text("Daftar akun baru, atau masuk kalau sudah punya, untuk chat, teman, dan voice room.", 13, R.color.m3_text_dim);
        hint.setPadding(0, dp(6), 0, dp(10));
        c.addView(hint);
        final EditText name = input("Username (3-16, huruf/angka/_)");
        Account acc = null;
        try { acc = Accounts.getCurrent(); } catch (Exception ignored) { }
        if (acc != null && acc.username != null) name.setText(acc.username.replaceAll("[^A-Za-z0-9_]", "_"));
        c.addView(name, new LinearLayout.LayoutParams(-1, dp(48)));
        final EditText pass = input("Password (minimal " + SocialClient.MIN_PASSWORD + " karakter)");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(48));
        pl.topMargin = dp(8);
        c.addView(pass, pl);
        final EditText pass2 = input("Ulangi password (hanya untuk Daftar)");
        pass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(pass2, pl);

        LinearLayout r = row();
        TextView reg = button("Daftar", false, v -> {
            String pw = pass.getText().toString();
            if (!pw.equals(pass2.getText().toString())) {
                toast("Password dan ulangi password tidak sama");
                return;
            }
            v.setEnabled(false);
            mClient.register(name.getText().toString().trim(), pw, (ok, err) -> {
                v.setEnabled(true);
                if (!isAdded()) return;
                if (err != null) toast(err);
                else {
                    toast("Akun dibuat, kamu sudah masuk");
                    select(TAB_CHAT);
                }
            });
        });
        TextView go = button("Masuk", true, v -> {
            v.setEnabled(false);
            mClient.login(name.getText().toString().trim(), pass.getText().toString(), (ok, err) -> {
                v.setEnabled(true);
                if (!isAdded()) return;
                if (err != null) toast(err);
                else select(TAB_CHAT);
            });
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, dp(48), 1f);
        r.addView(reg, rl);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, dp(48), 1f);
        gl.setMarginStart(dp(10));
        r.addView(go, gl);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(12);
        c.addView(r, rowLp);
        TextView note = text("Password tidak bisa dipulihkan kalau lupa, jadi catat di tempat aman.", 12, R.color.m3_text_dim);
        note.setPadding(0, dp(10), 0, 0);
        c.addView(note);
        return c;
    }

    // ---- chat ----

    private View buildChat() {
        LinearLayout root = row();
        root.setGravity(Gravity.TOP);
        mLastKey = "";

        LinearLayout side = column();
        side.setPadding(0, 0, dp(10), 0);
        LinearLayout roomList = column();
        ScrollView rs = new ScrollView(requireContext());
        rs.addView(roomList);
        side.addView(rs, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView add = button("+ Chat Baru", false, v -> newRoomDialog());
        side.addView(add, new LinearLayout.LayoutParams(-1, dp(44)));
        root.addView(side, new LinearLayout.LayoutParams(dp(150), -1));
        fillRooms(roomList);

        LinearLayout main = column();
        mChatInfo = text("", 12, R.color.m3_text_dim);
        TextView title = text("# " + mRoom, 18, R.color.m3_text);
        main.addView(title);
        main.addView(mChatInfo);
        mMsgBox = column();
        mMsgScroll = new ScrollView(requireContext());
        mMsgScroll.addView(mMsgBox);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = dp(6);
        main.addView(mMsgScroll, sp);
        LinearLayout bar = row();
        EditText msg = input("Tulis pesan...");
        bar.addView(msg, new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView send = button("Kirim", true, v -> sendMessage(msg));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(44));
        bp.setMarginStart(dp(8));
        bar.addView(send, bp);
        LinearLayout.LayoutParams barp = new LinearLayout.LayoutParams(-1, -2);
        barp.topMargin = dp(6);
        main.addView(bar, barp);
        root.addView(main, new LinearLayout.LayoutParams(0, -1, 1f));
        return root;
    }

    private void fillRooms(LinearLayout list) {
        list.removeAllViews();
        List<String> rooms = new ArrayList<>();
        rooms.add("umum");
        mClient.get("rooms", (json, err) -> {
            if (json instanceof JSONObject) {
                Iterator<String> it = ((JSONObject) json).keys();
                List<String> extra = new ArrayList<>();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!k.equals("umum")) extra.add(k);
                }
                Collections.sort(extra);
                rooms.addAll(extra);
            }
            if (!isAdded()) return;
            list.removeAllViews();
            for (String r : rooms) {
                TextView t = text(r, 14, R.color.m3_text);
                t.setBackgroundResource(r.equals(mRoom) ? R.drawable.m3_chip_on : R.drawable.m3_chip_off);
                t.setTextColor(color(r.equals(mRoom) ? R.color.m3_on_primary : R.color.m3_text));
                t.setPadding(dp(14), dp(10), dp(14), dp(10));
                t.setSingleLine(true);
                t.setClickable(true);
                t.setOnClickListener(v -> {
                    mRoom = r;
                    select(TAB_CHAT);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(6);
                list.addView(t, lp);
            }
        });
    }

    private void newRoomDialog() {
        M3Dialog.input(requireContext(), "Chat baru", "Nama room (huruf/angka/_)", null, "Buat", name -> {
            String n = SocialClient.key(name.trim());
            if (n.length() < 2 || n.length() > 20) {
                toast("Nama 2-20 karakter");
                return false;
            }
            mClient.put("rooms/" + n, Boolean.TRUE, (j, e) -> {
                if (e != null) toast(e);
                else {
                    mRoom = n;
                    select(TAB_CHAT);
                }
            });
            return true;
        });
    }

    private void loadMessages() {
        String path = "messages/" + mRoom + "?orderBy=" + SocialClient.q("$key") + "&limitToLast=60";
        final String room = mRoom;
        mClient.get(path, (json, err) -> {
            if (!isAdded() || mMsgBox == null || !room.equals(mRoom)) return;
            if (err != null) {
                mChatInfo.setText(err);
                return;
            }
            mChatInfo.setText("60 pesan terakhir · cooldown 3 dtk");
            if (!(json instanceof JSONObject)) return;
            JSONObject o = (JSONObject) json;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            if (!keys.isEmpty() && keys.get(keys.size() - 1).equals(mLastKey)) return;
            mMsgBox.removeAllViews();
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String me = mClient.getUser();
            for (String k : keys) {
                JSONObject m = o.optJSONObject(k);
                if (m == null) continue;
                String u = m.optString("u");
                LinearLayout bubble = column();
                bubble.setBackgroundResource(R.drawable.m3_card);
                bubble.setPadding(dp(14), dp(8), dp(14), dp(8));
                bubble.addView(text(u, 12, u.equalsIgnoreCase(me) ? R.color.m3_primary : R.color.m3_text_dim));
                bubble.addView(text(m.optString("t"), 15, R.color.m3_text));
                long ts = m.optLong("ts");
                if (ts > 0) bubble.addView(text(f.format(new Date(ts)), 11, R.color.m3_outline));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = dp(6);
                lp.gravity = u.equalsIgnoreCase(me) ? Gravity.END : Gravity.START;
                mMsgBox.addView(bubble, lp);
            }
            mLastKey = keys.isEmpty() ? "" : keys.get(keys.size() - 1);
            mMsgScroll.post(() -> mMsgScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void sendMessage(EditText field) {
        String t = field.getText().toString().trim();
        if (t.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - mLastSend < COOLDOWN_MS) {
            toast("Tunggu 3 detik antar pesan");
            return;
        }
        if (t.length() > 300) t = t.substring(0, 300);
        try {
            JSONObject o = new JSONObject();
            o.put("u", mClient.getUser());
            o.put("t", t);
            o.put("ts", new JSONObject().put(".sv", "timestamp"));
            mLastSend = now;
            field.setText("");
            mClient.post("messages/" + mRoom, o, (j, e) -> {
                if (e != null) toast(e);
                else loadMessages();
            });
        } catch (Exception e) {
            toast(String.valueOf(e));
        }
    }

    // ---- voice rooms: presence lewat Firebase, audio lewat VoiceEngine (WebRTC P2P) ----

    private View buildVoice() {
        return mVoiceRoom != null && !mShowList ? buildCall() : buildVoiceList();
    }

    private View buildVoiceList() {
        LinearLayout c = column();
        c.setPadding(dp(8), dp(4), dp(8), 0);
        c.addView(text("Voice Room", 20, R.color.m3_text));
        TextView hint = text("Gabung room untuk bicara langsung lewat mikrofon. Maksimal " + MAX_VOICE_MEMBERS + " orang per room.", 13, R.color.m3_text_dim);
        hint.setPadding(0, dp(2), 0, dp(6));
        c.addView(hint);
        LinearLayout list = column();
        ScrollView sv = new ScrollView(requireContext());
        sv.addView(list);
        c.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout bar = row();
        EditText name = input("Nama room baru");
        bar.addView(name, new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView create = button("Buat Room", true, v -> {
            String n = SocialClient.key(name.getText().toString().trim());
            if (n.length() < 2 || n.length() > 20) {
                toast("Nama 2-20 karakter");
                return;
            }
            name.setText("");
            mClient.put("voice/" + n + "/c", mClient.getUser(), (j, e) -> {
                if (e != null) toast(e);
                else loadVoice(list);
            });
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(44));
        bp.setMarginStart(dp(8));
        bar.addView(create, bp);
        LinearLayout.LayoutParams barp = new LinearLayout.LayoutParams(-1, -2);
        barp.topMargin = dp(6);
        c.addView(bar, barp);
        loadVoice(list);
        return c;
    }

    private void loadVoice(LinearLayout list) {
        mClient.get("voice", (json, err) -> {
            if (!isAdded()) return;
            list.removeAllViews();
            if (err != null) {
                list.addView(text(err, 13, R.color.m3_text_dim));
                return;
            }
            if (!(json instanceof JSONObject)) {
                list.addView(text("Belum ada room. Buat satu di bawah.", 14, R.color.m3_text_dim));
                return;
            }
            JSONObject all = (JSONObject) json;
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject room = all.optJSONObject(id);
                List<String> members = new ArrayList<>(membersOf(room).keySet());
                LinearLayout card = row();
                card.setBackgroundResource(R.drawable.m3_card);
                card.setPadding(dp(16), dp(10), dp(12), dp(10));
                LinearLayout info = column();
                info.addView(text(id, 16, R.color.m3_text));
                info.addView(text(members.isEmpty() ? "Kosong" : android.text.TextUtils.join(", ", members), 12, R.color.m3_text_dim));
                card.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
                boolean here = id.equals(mVoiceRoom);
                card.addView(button(here ? "Buka" : "Gabung", true, v -> {
                    if (here) {
                        mShowList = false;
                        select(TAB_VOICE);
                    } else {
                        tryJoinVoice(id);
                    }
                }));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(6);
                list.addView(card, lp);
            }
        });
    }

    /** Active members of a room as name to muted flag, dropping stale presence */
    private java.util.Map<String, Boolean> membersOf(JSONObject room) {
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        JSONObject mem = room == null ? null : room.optJSONObject("m");
        if (mem == null) return out;
        long now = System.currentTimeMillis();
        Iterator<String> mi = mem.keys();
        while (mi.hasNext()) {
            String u = mi.next();
            Object v = mem.opt(u);
            long ts;
            boolean muted = false;
            if (v instanceof JSONObject) {
                ts = ((JSONObject) v).optLong("t");
                muted = ((JSONObject) v).optBoolean("x");
            } else {
                ts = mem.optLong(u);
            }
            if (now - ts < VOICE_STALE_MS) out.put(u, muted);
        }
        return out;
    }

    /** Cek kapasitas room, dan izin mikrofon sebelum benar-benar masuk */
    private void tryJoinVoice(String id) {
        mClient.get("voice/" + id, (json, err) -> {
            if (!isAdded()) return;
            if (err != null) {
                toast(err);
                return;
            }
            java.util.Map<String, Boolean> now = membersOf(json instanceof JSONObject ? (JSONObject) json : null);
            boolean already = now.containsKey(SocialClient.key(mClient.getUser()));
            if (!already && now.size() >= MAX_VOICE_MEMBERS) {
                toast("Room penuh (maksimal " + MAX_VOICE_MEMBERS + " orang)");
                return;
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                joinVoice(id);
            } else {
                mPendingJoin = id;
                mMicLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });
    }

    /** uid suara stabil dari nama (sama di semua HP), tidak pernah 0 */
    private static int uidFor(String name) {
        int u = SocialClient.key(name).hashCode() & 0x7fffffff;
        return u == 0 ? 1 : u;
    }

    /** Voice berjalan di VoiceService, jadi tetap hidup saat layar ini ditutup atau game dimulai */
    private void joinVoice(String id) {
        String user = mClient.getUser();
        boolean ok = VoiceService.join(requireContext(), id, SocialClient.key(user), uidFor(user), mClient.getUrl());
        if (!ok) {
            toast("Tidak bisa memulai layanan suara");
            return;
        }
        mVoiceRoom = id;
        mMuted = false;
        mShowList = false;
        mSpeaking = new java.util.HashSet<>();
        mClient.setVoiceHint(true);
        if (mTab == TAB_VOICE) select(TAB_VOICE);
    }

    private void leaveVoice(boolean refresh) {
        if (mVoiceRoom == null) return;
        mVoiceRoom = null;
        mSpeaking = new java.util.HashSet<>();
        mAvatars.clear();
        mClient.setVoiceHint(false);
        VoiceService.leave(requireContext());
        if (refresh && isAdded()) select(TAB_VOICE);
    }

    /** Kecilkan foto jadi 96x96, lalu simpan di avatars/{key} supaya terlihat oleh temanmu */
    private void uploadPhoto(android.net.Uri uri) {
        if (!mClient.isLoggedIn()) return;
        final Context app = requireContext().getApplicationContext();
        final String key = SocialClient.key(mClient.getUser());
        toast("Mengunggah foto...");
        git.artdeell.mojo.MojoApplication.sExecutorService.execute(() -> {
            String b64 = null;
            String error = null;
            try {
                b64 = Avatars.encode(app, uri);
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final String data = b64;
            final String er = error;
            git.artdeell.mojo.Tools.runOnUiThread(() -> {
                if (er != null || data == null) {
                    if (isAdded()) toast("Foto gagal dibaca: " + er);
                    return;
                }
                mClient.put("avatars/" + key, data, (j, e) -> {
                    if (!isAdded()) return;
                    if (e != null) {
                        toast("Gagal unggah foto: " + e + ". Pastikan aturan database sudah diperbarui.");
                        return;
                    }
                    Avatars.put(key, Avatars.decode(data));
                    toast("Foto profil diperbarui");
                    select(TAB_ACCOUNT);
                });
            });
        });
    }

    private View buildCall() {
        LinearLayout card = column();
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF121316);
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout top = row();
        ImageView collapse = roundIcon(R.drawable.ic_m3_down_small, 0xFF1E1F23, v -> {
            mShowList = true;
            select(TAB_VOICE);
        });
        top.addView(collapse, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titleBox = column();
        titleBox.setPadding(dp(12), dp(4), dp(12), dp(4));
        rounded(titleBox, 0xFF1E1F23, 14);
        TextView title = text(mVoiceRoom, 15, R.color.m3_text);
        title.setSingleLine(true);
        TextView count = text("", 11, R.color.m3_text_dim);
        titleBox.addView(title);
        titleBox.addView(count);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(8));
        top.addView(titleBox, tp);
        View spacer = new View(requireContext());
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView invite = roundIcon(R.drawable.ic_m3_link, 0xFF1E1F23, v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("room", mVoiceRoom));
            toast("Nama room disalin, kirim ke temanmu");
        });
        top.addView(invite, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView chat = text("Chat", 13, R.color.m3_text);
        chat.setGravity(Gravity.CENTER);
        rounded(chat, 0xFF1E1F23, 14);
        chat.setClickable(true);
        chat.setOnClickListener(v -> {
            mRoom = mVoiceRoom;
            select(TAB_CHAT);
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(56), dp(36));
        cp.setMarginStart(dp(8));
        top.addView(chat, cp);
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(requireContext());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tiles = row();
        tiles.setGravity(Gravity.CENTER);
        hs.setFillViewport(true);
        hs.addView(tiles, new ViewGroup.LayoutParams(-1, -1));
        card.addView(hs, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = row();
        rounded(bar, 0xFF1E1F23, 26);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        ImageView mic = roundIcon(R.drawable.ic_microphone, 0xFF121316, null);
        ImageView hang = roundIcon(R.drawable.ic_m3_close, 0xFFD93A3F, v -> leaveVoice(true));
        bar.addView(mic, new LinearLayout.LayoutParams(dp(60), dp(42)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(72), dp(42));
        hp.setMarginStart(dp(8));
        bar.addView(hang, hp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(bar, bp);
        mic.setOnClickListener(v -> {
            mMuted = !mMuted;
            mic.setAlpha(mMuted ? 0.4f : 1f);
            VoiceService.setMuted(requireContext(), mMuted);
        });
        mic.setAlpha(mMuted ? 0.4f : 1f);
        mMicView = mic;
        Fluid.pressAll(card);

        mVoiceView = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || mVoiceRoom == null) return;
                refreshCall(tiles, count);
                mHandler.postDelayed(this, POLL_MS);
            }
        };
        mHandler.post(mVoiceView);
        return card;
    }

    private void refreshCall(LinearLayout tiles, TextView count) {
        if (mVoiceRoom == null) return;
        mClient.get("voice/" + mVoiceRoom, (json, err) -> {
            if (!isAdded() || mVoiceRoom == null || err != null) return;
            java.util.Map<String, Boolean> members = membersOf(json instanceof JSONObject ? (JSONObject) json : null);
            count.setText(members.size() + " di room");
            tiles.removeAllViews();
            mAvatars.clear();
            for (java.util.Map.Entry<String, Boolean> m : members.entrySet()) {
                LinearLayout t = column();
                t.setGravity(Gravity.CENTER_HORIZONTAL);
                final String mk = m.getKey();
                final AvatarView av = new AvatarView(requireContext());
                av.setPerson(mk, mk);
                av.setSpeaking(mSpeaking.contains(uidFor(mk)));
                Avatars.fetch(mClient, mk, () -> av.setPhoto(Avatars.photo(mk)));
                mAvatars.put(mk, av);
                t.addView(av, new LinearLayout.LayoutParams(dp(56), dp(56)));
                TextView n = text(m.getKey(), 12, R.color.m3_text);
                n.setSingleLine(true);
                n.setMaxWidth(dp(80));
                n.setPadding(0, dp(4), 0, 0);
                t.addView(n);
                if (m.getValue()) t.addView(text("mic mati", 10, R.color.m3_badge));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(84), -2);
                tiles.addView(t, lp);
            }
        });
    }

    private void applySpeaking() {
        if (!isAdded()) return;
        for (java.util.Map.Entry<String, AvatarView> e : mAvatars.entrySet()) {
            e.getValue().setSpeaking(mSpeaking.contains(uidFor(e.getKey())));
        }
    }

    private ImageView roundIcon(int iconRes, int bgColor, View.OnClickListener l) {
        ImageView v = new ImageView(requireContext());
        rounded(v, bgColor, 14);
        v.setImageResource(iconRes);
        v.setColorFilter(0xFFFFFFFF);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setPadding(dp(9), dp(9), dp(9), dp(9));
        v.setClickable(true);
        v.setFocusable(true);
        if (l != null) v.setOnClickListener(l);
        return v;
    }

    private void rounded(View v, int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        v.setBackground(g);
    }

    // ---- friends ----

    private View buildFriends() {
        LinearLayout c = column();
        c.setPadding(dp(8), dp(8), dp(8), 0);
        c.addView(text("Teman", 20, R.color.m3_text));
        LinearLayout r = row();
        EditText name = input("Username teman");
        r.addView(name, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout list = column();
        TextView add = button("Tambah", true, v -> {
            String n = name.getText().toString().trim();
            if (!SocialClient.validName(n)) {
                toast("Username tidak valid");
                return;
            }
            addFriend(n, list);
            name.setText("");
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(44));
        bp.setMarginStart(dp(8));
        r.addView(add, bp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        c.addView(r, rp);
        ScrollView sv = new ScrollView(requireContext());
        sv.addView(list);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = dp(8);
        c.addView(sv, sp);
        loadFriends(list);
        return c;
    }

    private void addFriend(String n, LinearLayout list) {
        String me = SocialClient.key(mClient.getUser());
        String fk = SocialClient.key(n);
        if (fk.equals(me)) {
            toast("Itu akunmu sendiri");
            return;
        }
        mClient.get("users/" + fk + "/n", (json, err) -> {
            if (err != null) {
                toast(err);
                return;
            }
            if (!(json instanceof String)) {
                toast("User tidak ditemukan");
                return;
            }
            mClient.put("friends/" + me + "/" + fk, (String) json, (j, e) -> {
                if (e != null) toast(e);
                loadFriends(list);
            });
        });
    }

    private void loadFriends(LinearLayout list) {
        String me = SocialClient.key(mClient.getUser());
        mClient.get("friends/" + me, (json, err) -> {
            if (!isAdded()) return;
            list.removeAllViews();
            if (err != null) {
                list.addView(text(err, 13, R.color.m3_text_dim));
                return;
            }
            JSONObject o = json instanceof JSONObject ? (JSONObject) json : new JSONObject();
            list.addView(text("Teman (" + o.length() + ")", 14, R.color.m3_text_dim));
            if (o.length() == 0) {
                TextView empty = text("Belum ada teman. Tambahkan lewat username mereka.", 14, R.color.m3_text_dim);
                empty.setPadding(0, dp(10), 0, 0);
                list.addView(empty);
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String fk = it.next();
                LinearLayout card = row();
                card.setBackgroundResource(R.drawable.m3_card);
                card.setPadding(dp(16), dp(10), dp(10), dp(10));
                card.addView(text(o.optString(fk, fk), 16, R.color.m3_text), new LinearLayout.LayoutParams(0, -2, 1f));
                card.addView(button("Hapus", false, v ->
                        mClient.delete("friends/" + me + "/" + fk, (j, e) -> loadFriends(list))));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(6);
                list.addView(card, lp);
            }
        });
    }
}
