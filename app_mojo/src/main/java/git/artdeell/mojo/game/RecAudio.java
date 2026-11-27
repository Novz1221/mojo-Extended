package git.artdeell.mojo.game;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Process;

import androidx.annotation.RequiresApi;

import java.nio.ByteBuffer;

/** Internal audio capture (AudioPlaybackCapture) encoded to AAC and fed to the recorder's muxer. */
@RequiresApi(29)
final class RecAudio implements Runnable {
    private static final int RATE = 44100;
    private static final int BYTES_PER_SEC = RATE * 4;

    private final ScreenRecorder owner;
    private final AudioRecord record;
    private final MediaCodec codec;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final Thread thread;
    private volatile boolean running = true;
    private int track = -1;

    private RecAudio(ScreenRecorder owner, AudioRecord record, MediaCodec codec) {
        this.owner = owner;
        this.record = record;
        this.codec = codec;
        this.thread = new Thread(this, "MJRecAudio");
    }

    @SuppressLint("MissingPermission")
    static RecAudio make(ScreenRecorder owner, MediaProjection proj, boolean gameOnly) throws Exception {
        AudioPlaybackCaptureConfiguration.Builder cfg = new AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN);
        if (gameOnly) cfg.addMatchingUid(Process.myUid());
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        AudioRecord ar = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg.build())
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(65536)
                .build();
        MediaFormat mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
        mf.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
        mf.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        mc.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mc.start();
        RecAudio a = new RecAudio(owner, ar, mc);
        a.thread.start();
        return a;
    }

    @Override
    public void run() {
        long startUs = System.nanoTime() / 1000 - owner.baseUs();
        long bytes = 0;
        byte[] buf = new byte[2048];
        try {
            record.startRecording();
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n <= 0) continue;
                int i = codec.dequeueInputBuffer(10000);
                if (i >= 0) {
                    ByteBuffer in = codec.getInputBuffer(i);
                    in.clear();
                    in.put(buf, 0, n);
                    long pts = Math.max(0, startUs) + bytes * 1000000L / BYTES_PER_SEC;
                    codec.queueInputBuffer(i, 0, n, pts, 0);
                    bytes += n;
                }
                drain(false);
            }
            int i = codec.dequeueInputBuffer(10000);
            if (i >= 0) {
                codec.queueInputBuffer(i, 0, 0, Math.max(0, startUs) + bytes * 1000000L / BYTES_PER_SEC,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drain(true);
        } catch (Throwable ignored) {
        } finally {
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
            record.release();
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            codec.release();
        }
    }

    private void drain(boolean eos) {
        int waits = 0;
        while (true) {
            int i = codec.dequeueOutputBuffer(info, eos ? 10000 : 0);
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || ++waits > 50) return;
                continue;
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                owner.addAudioTrack(codec.getOutputFormat());
                track = 1;
                continue;
            }
            if (i < 0) continue;
            ByteBuffer out = codec.getOutputBuffer(i);
            if (out != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                out.position(info.offset);
                out.limit(info.offset + info.size);
                owner.putAudio(out, info);
            }
            codec.releaseOutputBuffer(i, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    void stopJoin() {
        running = false;
        try {
            thread.join(2000);
        } catch (InterruptedException ignored) {
        }
    }
}
