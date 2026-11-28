package git.artdeell.mojo.social;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice chat WebRTC peer-to-peer (mesh), gratis tanpa akun dan tanpa token.
 * Pertukaran sinyal (offer, answer, ICE) lewat Firebase REST di voice/{room}/sig/{uid}.
 * Daftar peserta diambil dari presence voice/{room}/m yang sudah ditulis VoiceService.
 * Pihak dengan uid lebih besar yang memulai offer, jadi tidak pernah terjadi tabrakan offer.
 * Class ini jalan di proses :voice, jadi tidak boleh memakai SocialClient atau Tools.
 */
public class WebRtcVoiceEngine implements VoiceEngine {
    private static final String[] STUN = {
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
    };
    private static final long POLL_MS = 1200, STATS_MS = 500;
    private static final long CONNECT_TIMEOUT_MS = 20000, ABSENT_MS = 20000, COOLDOWN_MS = 4000;
    private static final long STALE_MS = 120000;
    private static final double REMOTE_LEVEL = 0.03;
    private static final double LOCAL_RMS = 700;

    private final Context mContext;
    private final String mDbUrl, mRoom, mKey;
    private final Handler mUi = new Handler(Looper.getMainLooper());

    private Listener mListener;
    private int mUid;
    private volatile boolean mLeft, mMuted;
    private volatile long mLastLoud, mLastGate;
    private volatile boolean mGateOpen = true;
    private volatile int mMicPct = 90;
    private final Map<Integer, Integer> mVolumes = new HashMap<>();

    private HandlerThread mThread;
    private Handler mH;
    private ExecutorService mSendEx, mPollEx;

    private PeerConnectionFactory mFactory;
    private JavaAudioDeviceModule mAdm;
    private AudioSource mSource;
    private AudioTrack mTrack;
    private List<PeerConnection.IceServer> mIce;

    private final Map<Integer, Peer> mPeers = new HashMap<>();
    private final Map<Integer, Long> mCooldown = new HashMap<>();
    private final Set<String> mSeen = new HashSet<>();
    private Set<Integer> mLastSpeaking = new HashSet<>();
    private int mLastRemote = -1;

    private static final class Peer {
        int uid, sid;
        boolean initiator, remoteSet, connected;
        PeerConnection pc;
        long created, lastChange, lastPresent, levelAt;
        double level;
        final List<IceCandidate> pending = new ArrayList<>();
    }

    public WebRtcVoiceEngine(Context context, String dbUrl, String room, String key) {
        mContext = context.getApplicationContext();
        mDbUrl = dbUrl;
        mRoom = room;
        mKey = key;
    }

    @Override
    public void join(String channel, int uid, Listener listener) {
        mListener = listener;
        mUid = uid;
        mLeft = false;
        mThread = new HandlerThread("webrtc-voice");
        mThread.start();
        mH = new Handler(mThread.getLooper());
        mSendEx = Executors.newSingleThreadExecutor();
        mPollEx = Executors.newSingleThreadExecutor();
        mH.post(this::start);
    }

    @Override
    public void setMuted(boolean muted) {
        mMuted = muted;
        Handler h = mH;
        if (h != null) h.post(this::applyTrackEnabled);
    }

    private void applyTrackEnabled() {
        if (mTrack != null) mTrack.setEnabled(!mMuted && mGateOpen);
    }

    @Override
    public void setPeerVolume(int uid, int percent) {
        final int pct = Math.max(0, Math.min(100, percent));
        Handler h = mH;
        if (h != null) h.post(() -> {
            mVolumes.put(uid, pct);
            Peer p = mPeers.get(uid);
            if (p != null) applyVolume(p);
        });
    }

    @Override
    public void setMicSensitivity(int percent) {
        mMicPct = Math.max(0, Math.min(100, percent));
    }

    /** Volume 90% dianggap normal (1.0); WebRTC menerima 0..10 */
    private void applyVolume(Peer p) {
        Integer pct = mVolumes.get(p.uid);
        if (pct == null || p.pc == null) return;
        try {
            for (RtpReceiver r : p.pc.getReceivers()) {
                if (r.track() instanceof AudioTrack) ((AudioTrack) r.track()).setVolume(pct / 90.0);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void leave() {
        if (mLeft) return;
        mLeft = true;
        Handler h = mH;
        if (h == null) return;
        h.removeCallbacksAndMessages(null);
        h.post(() -> {
            try {
                cleanup();
            } catch (Throwable ignored) { }
            mThread.quitSafely();
        });
    }

    // ---- start & cleanup (thread logika) ----

    private void start() {
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(mContext).createInitializationOptions());
            mAdm = JavaAudioDeviceModule.builder(mContext)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    // Pakai volume media supaya suara game tidak ikut mengecil
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setSamplesReadyCallback(this::onSamples)
                    .createAudioDeviceModule();
            mFactory = PeerConnectionFactory.builder().setAudioDeviceModule(mAdm).createPeerConnectionFactory();
            mSource = mFactory.createAudioSource(new MediaConstraints());
            mTrack = mFactory.createAudioTrack("mojo_mic", mSource);
            applyTrackEnabled();
            mIce = new ArrayList<>();
            for (String s : STUN) mIce.add(PeerConnection.IceServer.builder(s).createIceServer());
        } catch (Throwable t) {
            fail("Gagal memulai WebRTC: " + t.getMessage());
            return;
        }
        // Bersihkan sisa sinyal lama milik uid ini
        http(mSendEx, "DELETE", "voice/" + mRoom + "/sig/" + mUid, null);
        mUi.post(() -> {
            if (!mLeft && mListener != null) mListener.onJoined();
        });
        mH.post(mPoll);
        mH.postDelayed(mStats, STATS_MS);
    }

    private void cleanup() {
        for (Peer p : new ArrayList<>(mPeers.values())) closePeer(p.uid);
        mPeers.clear();
        if (mTrack != null) { mTrack.dispose(); mTrack = null; }
        if (mSource != null) { mSource.dispose(); mSource = null; }
        if (mFactory != null) { mFactory.dispose(); mFactory = null; }
        if (mAdm != null) { mAdm.release(); mAdm = null; }
        if (mSendEx != null) {
            http(mSendEx, "DELETE", "voice/" + mRoom + "/sig/" + mUid, null);
            mSendEx.shutdown();
        }
        if (mPollEx != null) mPollEx.shutdownNow();
    }

    private void fail(String msg) {
        mUi.post(() -> {
            if (!mLeft && mListener != null) mListener.onError(msg);
        });
    }

    // ---- polling sinyal & presence ----

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (mLeft) return;
            final Runnable self = this;
            try {
                mPollEx.execute(() -> {
                    String m = httpGet("voice/" + mRoom + "/m");
                    String s = httpGet("voice/" + mRoom + "/sig/" + mUid);
                    Handler h = mH;
                    if (h == null) return;
                    h.post(() -> {
                        if (mLeft) return;
                        try {
                            onPoll(m, s);
                        } catch (Throwable ignored) { }
                        mH.postDelayed(self, POLL_MS);
                    });
                });
            } catch (RuntimeException e) {
                // executor sudah ditutup
            }
        }
    };

    private void onPoll(String mJson, String sJson) throws Exception {
        long now = System.currentTimeMillis();

        // 1. peserta yang hadir
        Set<Integer> present = new HashSet<>();
        boolean presenceOk = mJson != null;
        if (presenceOk && mJson.startsWith("{")) {
            JSONObject m = new JSONObject(mJson);
            Iterator<String> it = m.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.equals(mKey)) continue;
                Object v = m.opt(k);
                long ts = v instanceof JSONObject ? ((JSONObject) v).optLong("t") : m.optLong(k);
                if (now - ts < STALE_MS) present.add(uidOf(k));
            }
        }
        for (int u : present) {
            Peer p = mPeers.get(u);
            if (p != null) p.lastPresent = SystemClockMs();
        }

        // 2. pesan sinyal masuk, urut sesuai waktu kirim
        if (sJson != null && sJson.startsWith("{")) {
            JSONObject s = new JSONObject(sJson);
            TreeSet<String> ids = new TreeSet<>();
            Iterator<String> it = s.keys();
            while (it.hasNext()) ids.add(it.next());
            mSeen.retainAll(ids);
            for (String id : ids) {
                if (!mSeen.add(id)) continue;
                JSONObject msg = s.optJSONObject(id);
                http(mSendEx, "DELETE", "voice/" + mRoom + "/sig/" + mUid + "/" + id, null);
                if (msg != null) {
                    try {
                        handleSignal(msg);
                    } catch (Throwable ignored) { }
                }
            }
        }

        // 3. mulai koneksi ke peserta dengan uid lebih kecil (kita yang menawarkan)
        long t = SystemClockMs();
        if (presenceOk) {
            for (int u : present) {
                if (u >= mUid || mPeers.containsKey(u)) continue;
                Long cd = mCooldown.get(u);
                if (cd != null && t - cd < COOLDOWN_MS) continue;
                startOffer(u);
            }
        }

        // 4. bersihkan peer yang hilang atau gagal tersambung
        for (Peer p : new ArrayList<>(mPeers.values())) {
            boolean gone = presenceOk && !present.contains(p.uid) && t - p.lastPresent > ABSENT_MS;
            boolean stuck = !p.connected && t - p.lastChange > CONNECT_TIMEOUT_MS;
            if (gone || stuck) {
                closePeer(p.uid);
                mCooldown.put(p.uid, t);
            }
        }
        emitRemote();
    }

    private static long SystemClockMs() {
        return android.os.SystemClock.elapsedRealtime();
    }

    private static int uidOf(String key) {
        int u = key.hashCode() & 0x7fffffff;
        return u == 0 ? 1 : u;
    }

    // ---- sinyal ----

    private void handleSignal(JSONObject msg) {
        int from = msg.optInt("f");
        int sid = msg.optInt("s");
        String kind = msg.optString("k");
        String data = msg.optString("d");
        if (from == 0 || from == mUid) return;
        Peer p = mPeers.get(from);

        if ("offer".equals(kind)) {
            if (p != null && p.sid == sid) return;
            if (p != null) closePeer(from);
            final Peer np = newPeer(from, false, sid);
            if (np == null) return;
            np.pc.setRemoteDescription(new Sdp() {
                @Override
                public void onSetSuccess() {
                    mH.post(() -> {
                        if (mLeft || mPeers.get(from) != np) return;
                        np.remoteSet = true;
                        flushIce(np);
                        np.pc.createAnswer(new Sdp() {
                            @Override
                            public void onCreateSuccess(SessionDescription sdp) {
                                mH.post(() -> {
                                    if (mLeft || mPeers.get(from) != np) return;
                                    send(from, sig("answer", np.sid, sdp.description));
                                    np.pc.setLocalDescription(new Sdp(), sdp);
                                });
                            }
                        }, new MediaConstraints());
                    });
                }
            }, new SessionDescription(SessionDescription.Type.OFFER, data));
        } else if ("answer".equals(kind)) {
            if (p == null || p.sid != sid || !p.initiator || p.remoteSet) return;
            final Peer ap = p;
            ap.pc.setRemoteDescription(new Sdp() {
                @Override
                public void onSetSuccess() {
                    mH.post(() -> {
                        if (mLeft || mPeers.get(from) != ap) return;
                        ap.remoteSet = true;
                        flushIce(ap);
                    });
                }
            }, new SessionDescription(SessionDescription.Type.ANSWER, data));
        } else if ("ice".equals(kind)) {
            if (p == null || p.sid != sid) return;
            IceCandidate c = new IceCandidate(msg.optString("m"), msg.optInt("i"), data);
            if (p.remoteSet) p.pc.addIceCandidate(c);
            else p.pending.add(c);
        }
    }

    private void flushIce(Peer p) {
        for (IceCandidate c : p.pending) p.pc.addIceCandidate(c);
        p.pending.clear();
    }

    private void startOffer(int uid) {
        final Peer p = newPeer(uid, true, new Random().nextInt(Integer.MAX_VALUE - 1) + 1);
        if (p == null) return;
        p.pc.createOffer(new Sdp() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                mH.post(() -> {
                    if (mLeft || mPeers.get(uid) != p) return;
                    // offer dikirim lebih dulu dari kandidat ICE (executor kirim berurutan)
                    send(uid, sig("offer", p.sid, sdp.description));
                    p.pc.setLocalDescription(new Sdp(), sdp);
                });
            }
        }, new MediaConstraints());
    }

    private JSONObject sig(String kind, int sid, String data) {
        JSONObject o = new JSONObject();
        try {
            o.put("f", mUid).put("k", kind).put("s", sid).put("d", data);
        } catch (Exception ignored) { }
        return o;
    }

    private void send(int toUid, JSONObject msg) {
        http(mSendEx, "POST", "voice/" + mRoom + "/sig/" + toUid, msg.toString());
    }

    // ---- peer ----

    private Peer newPeer(int uid, boolean initiator, int sid) {
        if (mFactory == null) return null;
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(mIce);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        final Peer p = new Peer();
        p.uid = uid;
        p.sid = sid;
        p.initiator = initiator;
        p.created = p.lastChange = p.lastPresent = SystemClockMs();
        p.pc = mFactory.createPeerConnection(cfg, new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState s) { }

            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                mH.post(() -> {
                    if (mLeft || mPeers.get(uid) != p) return;
                    boolean ok = s == PeerConnection.IceConnectionState.CONNECTED
                            || s == PeerConnection.IceConnectionState.COMPLETED;
                    if (ok != p.connected) {
                        p.connected = ok;
                        p.lastChange = SystemClockMs();
                        if (ok) applyVolume(p);
                    }
                    if (s == PeerConnection.IceConnectionState.FAILED
                            || s == PeerConnection.IceConnectionState.CLOSED) {
                        closePeer(uid);
                        mCooldown.put(uid, SystemClockMs());
                    }
                    emitRemote();
                });
            }

            public void onIceConnectionReceivingChange(boolean b) { }

            public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }

            public void onIceCandidate(IceCandidate c) {
                try {
                    JSONObject o = sig("ice", sid, c.sdp);
                    o.put("m", c.sdpMid).put("i", c.sdpMLineIndex);
                    send(uid, o);
                } catch (Exception ignored) { }
            }

            public void onIceCandidatesRemoved(IceCandidate[] c) { }

            public void onAddStream(MediaStream s) { }

            public void onRemoveStream(MediaStream s) { }

            public void onDataChannel(DataChannel d) { }

            public void onRenegotiationNeeded() { }

            public void onAddTrack(RtpReceiver r, MediaStream[] s) { }
        });
        if (p.pc == null) return null;
        p.pc.addTrack(mTrack, Collections.singletonList("mojo_stream"));
        mPeers.put(uid, p);
        return p;
    }

    private void closePeer(int uid) {
        Peer p = mPeers.remove(uid);
        if (p == null) return;
        try {
            p.pc.close();
            p.pc.dispose();
        } catch (Throwable ignored) { }
    }

    // ---- status ke UI ----

    private void emitRemote() {
        int n = 0;
        for (Peer p : mPeers.values()) if (p.connected) n++;
        if (n == mLastRemote) return;
        mLastRemote = n;
        final int c = n;
        mUi.post(() -> {
            if (!mLeft && mListener != null) mListener.onRemoteCount(c);
        });
    }

    private void onSamples(JavaAudioDeviceModule.AudioSamples samples) {
        byte[] d = samples.getData();
        if (d == null || d.length < 2) return;
        double sum = 0;
        int n = d.length / 2;
        for (int i = 0; i < n; i++) {
            short v = (short) ((d[2 * i] & 0xff) | (d[2 * i + 1] << 8));
            sum += (double) v * v;
        }
        double rms = Math.sqrt(sum / n);
        long now = SystemClockMs();
        if (!mMuted && rms > LOCAL_RMS) mLastLoud = now;
        // Gerbang sensitivitas: suara di bawah ambang dipotong, dengan jeda tahan supaya awal kata tidak hilang
        double gate = (100 - mMicPct) * 12.0;
        if (rms >= gate) mLastGate = now;
        boolean open = gate <= 0 || now - mLastGate < 500;
        if (open != mGateOpen) {
            mGateOpen = open;
            Handler h = mH;
            if (h != null) h.post(this::applyTrackEnabled);
        }
    }

    private final Runnable mStats = new Runnable() {
        @Override
        public void run() {
            if (mLeft) return;
            for (final Peer p : new ArrayList<>(mPeers.values())) {
                if (!p.connected) continue;
                try {
                    p.pc.getStats(report -> {
                        double lvl = 0;
                        for (RTCStats s : report.getStatsMap().values()) {
                            if (!"inbound-rtp".equals(s.getType())) continue;
                            Map<String, Object> mem = s.getMembers();
                            if (!"audio".equals(mem.get("kind"))) continue;
                            Object v = mem.get("audioLevel");
                            if (v instanceof Number) lvl = Math.max(lvl, ((Number) v).doubleValue());
                        }
                        final double l = lvl;
                        mH.post(() -> {
                            p.level = l;
                            p.levelAt = SystemClockMs();
                        });
                    });
                } catch (Throwable ignored) { }
            }
            emitSpeaking();
            mH.postDelayed(this, STATS_MS);
        }
    };

    private void emitSpeaking() {
        long now = SystemClockMs();
        Set<Integer> sp = new HashSet<>();
        if (!mMuted && now - mLastLoud < 500) sp.add(mUid);
        for (Peer p : mPeers.values()) {
            if (p.connected && p.level > REMOTE_LEVEL && now - p.levelAt < 1500) sp.add(p.uid);
        }
        if (sp.equals(mLastSpeaking)) return;
        mLastSpeaking = sp;
        final Set<Integer> out = new HashSet<>(sp);
        mUi.post(() -> {
            if (!mLeft && mListener != null) mListener.onSpeaking(out);
        });
    }

    // ---- HTTP Firebase REST ----

    private String httpGet(String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(mDbUrl + "/" + path + ".json").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void http(ExecutorService ex, final String method, final String path, final String body) {
        if (ex == null || mDbUrl == null || !mDbUrl.startsWith("https://")) return;
        try {
            ex.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(mDbUrl + "/" + path + ".json").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestMethod(method);
                    if (body != null) {
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json");
                        try (OutputStream os = c.getOutputStream()) {
                            os.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    c.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        } catch (RuntimeException ignored) {
            // executor sudah ditutup
        }
    }

    /** SdpObserver dengan perilaku kosong supaya cukup menimpa yang dibutuhkan */
    private static class Sdp implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String s) { }
        @Override public void onSetFailure(String s) { }
    }
}
