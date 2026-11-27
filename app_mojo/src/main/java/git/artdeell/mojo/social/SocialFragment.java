package git.artdeell.mojo.social;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
            mTabs[i].setBackgroundResource(on ? R.drawable.m3_chip_on : R.drawable.m3_chip_off);
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
            TextView avatar = text(name.substring(0, 1).toUpperCase(Locale.ROOT), 34, R.color.m3_text);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackgroundResource(R.drawable.m3_icon_bg);
            r.addView(avatar, new LinearLayout.LayoutParams(dp(72), dp(72)));
            LinearLayout info = column();
            info.setPadding(dp(16), 0, 0, 0);
            info.addView(text(name, 22, R.color.m3_text));
            info.addView(text("Masuk sebagai " + name, 13, R.color.m3_text_dim));
            r.addView(info);
            c.addView(r);
            TextView out = button("Keluar dari Akun", false, v -> {
                mClient.logout();
                select(TAB_ACCOUNT);
            });
            c.addView(out, new LinearLayout.LayoutParams(-1, dp(48)));
            TextView db = button("Ganti database", false, v -> mBody.show(buildSetup()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
            lp.topMargin = dp(10);
            c.addView(db, lp);
            return c;
        }
        TextView hint = text("Pilih username untuk chat, teman, dan voice room.", 13, R.color.m3_text_dim);
        hint.setPadding(0, dp(6), 0, dp(10));
        c.addView(hint);
        LinearLayout r = row();
        EditText name = input("Username (3-16, huruf/angka/_)");
        Account acc = null;
        try { acc = Accounts.getCurrent(); } catch (Exception ignored) { }
        if (acc != null && acc.username != null) name.setText(acc.username.replaceAll("[^A-Za-z0-9_]", "_"));
        r.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView go = button("Masuk", true, v -> {
            v.setEnabled(false);
            mClient.login(name.getText().toString().trim(), (ok, err) -> {
                v.setEnabled(true);
                if (err != null) toast(err);
                else select(TAB_CHAT);
            });
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.setMarginStart(dp(10));
        r.addView(go, lp);
        c.addView(r);
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

    // ---- voice rooms (presence list only, no audio) ----

    private View buildVoice() {
        LinearLayout c = column();
        c.setPadding(dp(8), dp(8), dp(8), 0);
        c.addView(text("Voice Room", 20, R.color.m3_text));
        TextView hint = text("Daftar room dan siapa yang bergabung. Audio langsung belum tersedia.", 13, R.color.m3_text_dim);
        hint.setPadding(0, dp(4), 0, dp(8));
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
            mClient.put("voice/" + n + "/c", mClient.getUser(), (j, e) -> {
                if (e != null) toast(e);
                else loadVoice(list);
            });
            name.setText("");
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
            long now = System.currentTimeMillis();
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject room = all.optJSONObject(id);
                List<String> members = new ArrayList<>();
                JSONObject mem = room == null ? null : room.optJSONObject("m");
                if (mem != null) {
                    Iterator<String> mi = mem.keys();
                    while (mi.hasNext()) {
                        String u = mi.next();
                        if (now - mem.optLong(u) < VOICE_STALE_MS) members.add(u);
                    }
                }
                LinearLayout card = row();
                card.setBackgroundResource(R.drawable.m3_card);
                card.setPadding(dp(16), dp(12), dp(12), dp(12));
                LinearLayout info = column();
                info.addView(text(id, 16, R.color.m3_text));
                info.addView(text(members.isEmpty() ? "Kosong" : android.text.TextUtils.join(", ", members), 12, R.color.m3_text_dim));
                card.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
                boolean here = id.equals(mVoiceRoom);
                card.addView(button(here ? "Keluar" : "Gabung", !here, v -> toggleVoice(id, list)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(6);
                list.addView(card, lp);
            }
        });
    }

    private void toggleVoice(String id, LinearLayout list) {
        String me = SocialClient.key(mClient.getUser());
        if (id.equals(mVoiceRoom)) {
            mVoiceRoom = null;
            mClient.delete("voice/" + id + "/m/" + me, (j, e) -> loadVoice(list));
            return;
        }
        final String previous = mVoiceRoom;
        if (previous != null) mClient.delete("voice/" + previous + "/m/" + me, (j, e) -> { });
        try {
            JSONObject stamp = new JSONObject().put(".sv", "timestamp");
            mClient.put("voice/" + id + "/m/" + me, stamp, (j, e) -> {
                if (e != null) toast(e);
                else mVoiceRoom = id;
                loadVoice(list);
            });
        } catch (Exception e) {
            toast(String.valueOf(e));
        }
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
