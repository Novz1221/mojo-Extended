package git.artdeell.mojo.game;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.File;
import java.nio.ByteBuffer;

import git.artdeell.mojo.CallbackBridge;

/**
 * In-game screen recorder. Two sources: only the game surface (frames copied from the game's
 * TextureView / SurfaceView) or the whole screen (MediaProjection virtual display).
 * Audio is optional and uses internal playback capture (Android 10+).
 * Output goes to Movies/MJRecorder.
 */
public final class ScreenRecorder implements Runnable {
    public static final int REQ_PROJECTION = 7791;
    public static final int REQ_AUDIO = 7792;

    public static final int SRC_GAME = 0, SRC_FULL = 1;
    public static final int AUD_OFF = 0, AUD_ALL = 1, AUD_GAME = 2;

    private static final int[] RES = {480, 720, 1080, 0};
    private static final int[] FPS = {24, 30, 45, 60};

    private static ScreenRecorder sInst;

    private final Activity act;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
    private Runnable onChange;

    public int srcMode = SRC_GAME, audMode = AUD_OFF, resIdx = 1, fpsIdx = 1;
    private boolean rec, fin, muxOn, full, pendingStart;
    private MediaProjection proj;
    private MediaCodec enc;
    private Surface surf;
    private MediaMuxer mux;
    private VirtualDisplay vd;
    private Bitmap bmp;
    private View src;
    private RecAudio aud;
    private String path;
    private int w, hh, track = -1, aTrack = -1;
    private long interval, baseUs, startMs;

    private ScreenRecorder(Activity act) {
        this.act = act;
    }

    // ---- static glue used by GameActivity ----

    public static ScreenRecorder attach(Activity act) {
        sInst = new ScreenRecorder(act);
        return sInst;
    }

    public static void onResult(int requestCode, int resultCode, Intent data) {
        ScreenRecorder r = sInst;
        if (r == null || requestCode != REQ_PROJECTION) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            r.pendingStart = false;
            r.toast("Izin rekam layar ditolak");
            if (r.srcMode == SRC_GAME) r.go(null);
            return;
        }
        Intent i = new Intent(r.act, RecService.class);
        i.putExtra("rc", resultCode);
        i.putExtra("d", data);
        try {
            if (Build.VERSION.SDK_INT >= 26) r.act.startForegroundService(i);
            else r.act.startService(i);
        } catch (Exception e) {
            r.toast("Service rekam gagal: " + e);
            r.pendingStart = false;
        }
    }

    public static void onPermission(int requestCode, int[] results) {
        ScreenRecorder r = sInst;
        if (r == null || requestCode != REQ_AUDIO) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            r.toast("Izin audio diterima, ketuk Rekam lagi");
        } else {
            r.audMode = AUD_OFF;
            r.toast("Izin audio ditolak, rekam tanpa suara");
            r.changed();
        }
    }

    public static void stopNow() {
        ScreenRecorder r = sInst;
        if (r != null && r.rec && !r.fin) {
            r.h.removeCallbacks(r);
            r.finish();
        }
    }

    /** Called by RecService once the projection is ready. */
    static void onProjection(MediaProjection p) {
        ScreenRecorder r = sInst;
        if (r == null) {
            if (p != null) p.stop();
            return;
        }
        r.h.post(() -> r.go(p));
    }

    // ---- UI-facing API ----

    public void setOnChange(Runnable r) {
        onChange = r;
    }

    public boolean isRecording() {
        return rec;
    }

    public String sourceLabel() {
        return srcMode == SRC_FULL ? "Layar: Full screen" : "Layar: Minecraft only";
    }

    public String audioLabel() {
        if (audMode == AUD_OFF) return "Audio: Off";
        return audMode == AUD_ALL ? "Audio: Game + Musik" : "Audio: Game only";
    }

    public String resolutionLabel() {
        int r = RES[resIdx];
        return "Resolusi: " + (r == 0 ? "Asli" : r + "p");
    }

    public String fpsLabel() {
        return "FPS: " + FPS[fpsIdx];
    }

    public boolean cycleSource() {
        if (!canChange()) return false;
        srcMode = (srcMode + 1) % 2;
        changed();
        return true;
    }

    public boolean cycleAudio() {
        if (!canChange()) return false;
        if (Build.VERSION.SDK_INT < 29) {
            toast("Rekam audio internal butuh Android 10+");
            return false;
        }
        audMode = (audMode + 1) % 3;
        changed();
        return true;
    }

    public boolean cycleResolution() {
        if (!canChange()) return false;
        resIdx = (resIdx + 1) % RES.length;
        changed();
        return true;
    }

    public boolean cycleFps() {
        if (!canChange()) return false;
        fpsIdx = (fpsIdx + 1) % FPS.length;
        changed();
        return true;
    }

    private boolean canChange() {
        if (rec) {
            toast("Hentikan rekaman dulu untuk ganti pengaturan");
            return false;
        }
        return true;
    }

    public void toggle() {
        if (rec) {
            h.removeCallbacks(this);
            finish();
            return;
        }
        if (pendingStart) return;
        boolean needProj = srcMode == SRC_FULL || audMode != AUD_OFF;
        if (audMode != AUD_OFF && Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Izinkan akses audio, lalu ketuk Rekam lagi");
            act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!needProj) {
            go(null);
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) act.getSystemService("media_projection");
        pendingStart = true;
        act.startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    // ---- recording ----

    private void go(MediaProjection p) {
        pendingStart = false;
        proj = p;
        if (srcMode == SRC_FULL && proj == null) {
            toast("Layar penuh butuh izin rekam layar, beralih ke Minecraft only");
            srcMode = SRC_GAME;
        }
        try {
            if (srcMode == SRC_FULL) beginFull();
            else beginGame();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void beginGame() throws Exception {
        src = findSrc(act.getWindow().getDecorView());
        if (src == null) {
            toast("Surface game tidak ditemukan");
            releaseProjection();
            return;
        }
        int sw = CallbackBridge.windowWidth, sh = CallbackBridge.windowHeight;
        if (sw <= 0 || sh <= 0) {
            sw = src.getWidth();
            sh = src.getHeight();
        }
        if (sw < 16 || sh < 16) {
            toast("Ukuran surface game belum siap");
            releaseProjection();
            return;
        }
        fitSize(sw, sh);
        open();
        full = false;
        markStarted("Rekam dimulai (hanya tampilan game)");
    }

    private void beginFull() throws Exception {
        DisplayMetrics dm = new DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        fitSize(dm.widthPixels, dm.heightPixels);
        open();
        proj.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                h.post(() -> stopNow());
            }
        }, h);
        vd = proj.createVirtualDisplay("MJRec", w, hh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null);
        full = true;
        markStarted("Rekam dimulai (layar penuh)");
    }

    private void fitSize(int sw, int sh) {
        int target = RES[resIdx] == 0 ? sh : RES[resIdx];
        int nw = (sw * target / sh + 8) & ~15;
        int nh = (target + 8) & ~15;
        w = Math.max(nw, 16);
        hh = Math.max(nh, 16);
        int fps = FPS[fpsIdx];
        interval = 1000 / fps;
    }

    private void open() throws Exception {
        File dir = dir();
        path = new File(dir, "MJ_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        int fps = FPS[fpsIdx];
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, hh);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, (w * hh * fps) >> 3);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc = MediaCodec.createEncoderByType("video/avc");
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surf = enc.createInputSurface();
        enc.start();
        mux = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        track = -1;
        aTrack = -1;
        muxOn = false;
        bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        baseUs = System.nanoTime() / 1000;
        startMs = System.currentTimeMillis();
        aud = null;
        if (audMode != AUD_OFF && proj != null && Build.VERSION.SDK_INT >= 29) {
            try {
                aud = RecAudio.make(this, proj, audMode == AUD_GAME);
            } catch (Throwable t) {
                toast("Rekam TANPA suara (izin audio ditolak/tidak didukung)");
                aud = null;
            }
        }
    }

    private void markStarted(String msg) {
        rec = true;
        fin = false;
        changed();
        toast(msg);
        h.post(this);
    }

    /** Frame tick, runs on the main thread. */
    @Override
    public void run() {
        if (!rec || fin) return;
        try {
            if (!muxOn && aud != null && System.currentTimeMillis() - startMs > 3000 && track >= 0) {
                aud.stopJoin();
                aud = null;
                startMux();
            }
            if (full) {
                drain(false);
            } else {
                capture();
            }
        } catch (Throwable t) {
            fail(t);
            return;
        }
        h.postDelayed(this, interval);
    }

    private void capture() {
        if (src instanceof TextureView) {
            ((TextureView) src).getBitmap(bmp);
            draw();
        } else if (src instanceof SurfaceView && Build.VERSION.SDK_INT >= 24) {
            PixelCopy.request((SurfaceView) src, bmp, result -> {
                if (result == PixelCopy.SUCCESS && rec && !fin) draw();
            }, h);
        }
    }

    private void draw() {
        Surface s = surf;
        if (s == null || !s.isValid()) return;
        Canvas c = Build.VERSION.SDK_INT >= 26 ? s.lockHardwareCanvas() : s.lockCanvas(null);
        c.drawBitmap(bmp, 0, 0, null);
        s.unlockCanvasAndPost(c);
        drain(false);
    }

    private void drain(boolean eos) {
        MediaCodec e = enc;
        if (e == null) return;
        int waits = 0;
        while (true) {
            int i = e.dequeueOutputBuffer(bi, eos ? 10000 : 0);
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || ++waits > 50) break;
                continue;
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (this) {
                    track = mux.addTrack(e.getOutputFormat());
                    tryStart();
                }
                continue;
            }
            if (i < 0) continue;
            ByteBuffer buf = e.getOutputBuffer(i);
            if (buf != null && bi.size > 0 && (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                bi.presentationTimeUs = Math.max(0, bi.presentationTimeUs - baseUs);
                put(track, buf, bi);
            }
            e.releaseOutputBuffer(i, false);
            if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    // ---- muxer access, shared with RecAudio ----

    synchronized void addAudioTrack(MediaFormat format) {
        if (mux == null || muxOn) return;
        aTrack = mux.addTrack(format);
        tryStart();
    }

    synchronized void putAudio(ByteBuffer buf, MediaCodec.BufferInfo info) {
        info.presentationTimeUs = Math.max(0, info.presentationTimeUs);
        put(aTrack, buf, info);
    }

    synchronized void put(int t, ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (!muxOn || mux == null || t < 0) return;
        try {
            mux.writeSampleData(t, buf, info);
        } catch (Exception ignored) {
        }
    }

    long baseUs() {
        return baseUs;
    }

    private synchronized void tryStart() {
        if (muxOn || track < 0) return;
        if (aud != null && aTrack < 0) return;
        startMux();
    }

    private synchronized void startMux() {
        if (muxOn || mux == null || track < 0) return;
        mux.start();
        muxOn = true;
    }

    // ---- finishing ----

    private void finish() {
        fin = true;
        try {
            if (enc != null) {
                enc.signalEndOfInputStream();
                drain(true);
            }
        } catch (Throwable ignored) {
        }
        if (aud != null) {
            aud.stopJoin();
            aud = null;
        }
        boolean wrote = muxOn;
        release();
        rec = false;
        changed();
        String p = path;
        if (!wrote) {
            new File(p).delete();
            toast("Tidak ada frame yang terekam (game belum tampil?)");
            return;
        }
        toast("Tersimpan: " + p);
        MediaScannerConnection.scanFile(act, new String[]{p}, null, null);
    }

    private void release() {
        try {
            if (vd != null) vd.release();
        } catch (Throwable ignored) {
        }
        vd = null;
        try {
            if (enc != null) {
                enc.stop();
                enc.release();
            }
        } catch (Throwable ignored) {
        }
        enc = null;
        if (surf != null) surf.release();
        surf = null;
        synchronized (this) {
            try {
                if (mux != null) {
                    if (muxOn) mux.stop();
                    mux.release();
                }
            } catch (Throwable ignored) {
            }
            mux = null;
            muxOn = false;
        }
        bmp = null;
        src = null;
        releaseProjection();
    }

    private void releaseProjection() {
        if (proj != null) {
            try {
                proj.stop();
            } catch (Throwable ignored) {
            }
            proj = null;
        }
        act.stopService(new Intent(act, RecService.class));
    }

    private void fail(Throwable t) {
        h.removeCallbacks(this);
        fin = true;
        try {
            if (aud != null) aud.stopJoin();
        } catch (Throwable ignored) {
        }
        aud = null;
        release();
        rec = false;
        changed();
        if (path != null) new File(path).delete();
        toast("Rekam gagal: " + t);
    }

    // ---- helpers ----

    private File dir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MJRecorder");
        d.mkdirs();
        File probe = new File(d, ".probe");
        boolean ok;
        try {
            probe.createNewFile();
            ok = probe.exists();
        } catch (Exception e) {
            ok = false;
        }
        probe.delete();
        if (ok) return d;
        File fallback = act.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (fallback == null) fallback = act.getFilesDir();
        fallback.mkdirs();
        return fallback;
    }

    private static View findSrc(View v) {
        if (v instanceof SurfaceView || v instanceof TextureView) return v;
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = findSrc(g.getChildAt(i));
            if (r != null) return r;
        }
        return null;
    }

    void toast(String m) {
        h.post(() -> Toast.makeText(act, m, Toast.LENGTH_SHORT).show());
    }

    private void changed() {
        if (onChange != null) h.post(onChange);
    }
}
