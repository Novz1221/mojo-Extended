package git.artdeell.mojo.utils;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

/** Spring-like motion helpers shared by all launcher screens. */
public final class Fluid {
    private Fluid() {}

    /** Springy press feedback on every clickable view below root (and root itself). */
    public static void pressAll(View root) {
        if (root == null) return;
        if (root.isClickable() && root.getTag(R.id.fluid_pressed) == null && isPressTarget(root)) {
            root.setTag(R.id.fluid_pressed, Boolean.TRUE);
            root.setOnTouchListener(Fluid::onPressTouch);
        }
        if (root instanceof ViewGroup && !(root instanceof RecyclerView)) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) pressAll(group.getChildAt(i));
        }
    }

    private static boolean isPressTarget(View view) {
        return !(view instanceof EditText || view instanceof AbsListView
                || view instanceof RecyclerView || view instanceof ScrollView);
    }

    private static boolean onPressTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                view.animate().cancel();
                view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(110)
                        .setInterpolator(new DecelerateInterpolator()).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                view.animate().cancel();
                view.animate().scaleX(1f).scaleY(1f).setDuration(380)
                        .setInterpolator(new OvershootInterpolator(2.4f)).start();
                break;
            default:
                break;
        }
        return false;
    }

    /** Fade, slide up and spring into place after index * 60ms. */
    public static void enter(View view, int index) {
        if (view == null) return;
        float shift = 20f * view.getResources().getDisplayMetrics().density;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(shift);
        view.setScaleX(0.93f);
        view.setScaleY(0.93f);
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(Math.min(index, 10) * 60L).setDuration(480)
                .setInterpolator(new OvershootInterpolator(0.9f)).start();
    }

    public static void stagger(View... views) {
        for (int i = 0; i < views.length; i++) enter(views[i], i);
    }

    /** Animates the children of a group one after another. */
    public static void staggerChildren(ViewGroup group, int firstIndex) {
        for (int i = 0; i < group.getChildCount(); i++) enter(group.getChildAt(i), firstIndex + i);
    }

    /** Entrance for list rows. */
    public static void itemIn(View view, int position) {
        float shift = 28f * view.getResources().getDisplayMetrics().density;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(shift);
        view.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(position, 8) * 35L)
                .setDuration(420).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    /**
     * Puts a recycled list row back to its resting state. A row that was recycled halfway through
     * its entrance would otherwise stay transparent or shifted.
     */
    public static void settle(View view) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationY(0f);
    }

    /** Quick springy pulse, used when something becomes selected. */
    public static void pop(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.78f);
        view.setScaleY(0.78f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    /** Fades a view in or out and updates its visibility around the animation. */
    public static void fade(View view, boolean show) {
        if (view == null) return;
        view.animate().cancel();
        if (show) {
            if (view.getVisibility() != View.VISIBLE) view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setDuration(260).start();
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(200).withEndAction(() -> view.setVisibility(View.GONE)).start();
        }
    }
}
