package git.artdeell.mojo.social;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.Set;

import io.agora.rtc2.ChannelMediaOptions;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;

/**
 * Implementasi VoiceEngine dengan Agora Voice SDK.
 * Memakai mode testing (App ID saja, tanpa token). Kalau project Agora memakai mode secured,
 * token harus dibuat server dan diisi pada joinChannel.
 */
public final class AgoraVoiceEngine implements VoiceEngine {
    private static final int SPEAK_VOLUME = 25; // skala 0-255

    private final Context mContext;
    private final String mAppId;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Set<Integer> mRemote = new HashSet<>();
    private RtcEngine mEngine;
    private Listener mListener;
    private boolean mLeft;

    public AgoraVoiceEngine(Context context, String appId) {
        mContext = context.getApplicationContext();
        mAppId = appId;
    }

    private final IRtcEngineEventHandler mHandler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
            mUi.post(() -> {
                if (!mLeft && mListener != null) mListener.onJoined();
            });
        }

        @Override
        public void onUserJoined(int uid, int elapsed) {
            mUi.post(() -> {
                mRemote.add(uid);
                notifyRemote();
            });
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            mUi.post(() -> {
                mRemote.remove(uid);
                notifyRemote();
            });
        }

        @Override
        public void onAudioVolumeIndication(AudioVolumeInfo[] speakers, int totalVolume) {
            if (speakers == null) return;
            final Set<Integer> talking = new HashSet<>();
            for (AudioVolumeInfo s : speakers) {
                // uid 0 artinya suara lokal; kita petakan ke uid lokal di UI lewat mLocalUid
                if (s.volume >= SPEAK_VOLUME) talking.add(s.uid);
            }
            mUi.post(() -> {
                if (mLeft || mListener == null) return;
                Set<Integer> mapped = new HashSet<>();
                for (int u : talking) mapped.add(u == 0 ? mLocalUid : u);
                mListener.onSpeaking(mapped);
            });
        }

        @Override
        public void onConnectionStateChanged(int state, int reason) {
            if (state != Constants.CONNECTION_STATE_FAILED) return;
            mUi.post(() -> {
                if (!mLeft && mListener != null) {
                    mListener.onError("Gagal tersambung ke server suara (cek App ID dan mode project Agora)");
                }
            });
        }
    };

    private int mLocalUid;

    private void notifyRemote() {
        if (!mLeft && mListener != null) mListener.onRemoteCount(mRemote.size());
    }

    @Override
    public void join(String channel, int uid, Listener listener) throws Exception {
        mListener = listener;
        mLocalUid = uid;
        mLeft = false;
        RtcEngineConfig cfg = new RtcEngineConfig();
        cfg.mContext = mContext;
        cfg.mAppId = mAppId;
        cfg.mEventHandler = mHandler;
        cfg.mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION;
        // Skenario game: memakai volume media supaya suara game tidak ikut mengecil
        cfg.mAudioScenario = Constants.AUDIO_SCENARIO_GAME_STREAMING;
        mEngine = RtcEngine.create(cfg);
        mEngine.enableAudioVolumeIndication(400, 3, true);
        mEngine.setDefaultAudioRoutetoSpeakerphone(true);

        ChannelMediaOptions o = new ChannelMediaOptions();
        o.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
        o.channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION;
        o.publishMicrophoneTrack = true;
        o.autoSubscribeAudio = true;
        int r = mEngine.joinChannel(null, channel, uid, o);
        if (r != 0) {
            leave();
            throw new IllegalStateException("joinChannel gagal, kode " + r);
        }
    }

    @Override
    public void setMuted(boolean muted) {
        if (mEngine != null) mEngine.muteLocalAudioStream(muted);
    }

    @Override
    public void leave() {
        mLeft = true;
        mRemote.clear();
        final RtcEngine e = mEngine;
        mEngine = null;
        mListener = null;
        if (e == null) return;
        // destroy() memblokir, jadi jangan dijalankan di UI thread
        new Thread(() -> {
            try {
                e.leaveChannel();
            } catch (Exception ignored) { }
            RtcEngine.destroy();
        }, "agora-leave").start();
    }
}
