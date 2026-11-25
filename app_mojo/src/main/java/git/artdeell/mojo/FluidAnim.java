package git.artdeell.mojo;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.recyclerview.widget.RecyclerView;

/** Small helpers for the fluid motion of the settings UI */
public final class FluidAnim {
    private FluidAnim() {}

    /** Quick squash followed by a springy release (rail buttons) */
    public static void bounce(View v) {
        v.animate().cancel();
        v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(new OvershootInterpolator(3f))
                        .setDuration(260).start())
                .start();
    }

    /** Card shrinks slightly while pressed and springs back on release */
    @SuppressLint("ClickableViewAccessibility")
    public static void pressable(View card) {
        card.animate().cancel();
        card.setScaleX(1f);
        card.setScaleY(1f);
        if (!card.isClickable()) {
            card.setOnTouchListener(null);
            return;
        }
        card.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(280)
                            .setInterpolator(new OvershootInterpolator(2.5f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Rows slide in one after another the first time the list is shown */
    public static void animateListIn(RecyclerView list) {
        list.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(list.getContext(), R.anim.m3_list_layout));
        list.scheduleLayoutAnimation();
    }
}
