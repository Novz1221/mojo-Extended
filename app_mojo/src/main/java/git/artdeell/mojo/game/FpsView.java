package git.artdeell.mojo.game;

import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import git.artdeell.mojo.game.render.FpsCounter;

/** Small FPS readout in the top-left corner of the game screen. */
public class FpsView extends TextView {
    private static final long INTERVAL_MS = 500;

    private int mLastFrames;
    private long mLastTime;
    private final Runnable mUpdate = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            int frames = FpsCounter.totalFrames();
            long elapsed = now - mLastTime;
            if (elapsed > 0) {
                int fps = Math.round((frames - mLastFrames) * 1000f / elapsed);
                setText("FPS: " + fps);
            }
            mLastFrames = frames;
            mLastTime = now;
            postDelayed(this, INTERVAL_MS);
        }
    };

    public FpsView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(4 * density);
        setPadding(pad * 2, pad, pad * 2, pad);
        setTextColor(Color.WHITE);
        setBackgroundColor(0x88000000);
        setTextSize(13);
        setText("FPS: 0");
        setGravity(Gravity.CENTER);
        setFocusable(false);
        setClickable(false);
        setTranslationZ(1 * density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        int margin = Math.round(8 * density);
        params.setMargins(margin, margin, 0, 0);
        setLayoutParams(params);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mLastFrames = FpsCounter.totalFrames();
        mLastTime = SystemClock.elapsedRealtime();
        postDelayed(mUpdate, INTERVAL_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mUpdate);
        super.onDetachedFromWindow();
    }
}
