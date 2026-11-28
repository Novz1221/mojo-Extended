package git.artdeell.mojo.utils;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import git.artdeell.mojo.R;

/** Small helper that builds the dark rounded dialogs used by the launcher screens. */
public final class M3Dialog {
    private M3Dialog() {}

    public interface TextCallback {
        /** @return true when the dialog should be dismissed */
        boolean onText(String text);
    }

    public interface IndexCallback {
        void onIndex(int index);
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout card(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.m3_dialog);
        int padding = dp(context, 18);
        layout.setPadding(padding, padding, padding, padding);
        return layout;
    }

    public static TextView label(Context context, CharSequence text, float sp, int colorRes) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(sp);
        textView.setTextColor(ContextCompat.getColor(context, colorRes));
        return textView;
    }

    public static TextView button(Context context, CharSequence text, boolean primary) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinHeight(dp(context, 44));
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setTextColor(ContextCompat.getColor(context, primary ? R.color.m3_on_primary : R.color.m3_text));
        button.setBackgroundResource(primary ? R.drawable.m3_button_primary : R.drawable.m3_button_tonal);
        return button;
    }

    public static Dialog show(Context context, View content, float widthFraction) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setWindowAnimations(R.style.M3DialogAnim);
            window.setDimAmount(0.6f);
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int width = Math.min((int) (metrics.widthPixels * widthFraction), dp(context, 520));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        // Listeners are attached right after show(), so wait one frame before adding press feedback
        content.post(() -> Fluid.pressAll(content));
        return dialog;
    }

    private static LinearLayout.LayoutParams weightParams(Context context, int marginStartDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(dp(context, marginStartDp));
        return params;
    }

    public static Dialog confirm(Context context, CharSequence title, @Nullable CharSequence message,
                                 CharSequence positive, Runnable onConfirm) {
        LinearLayout card = card(context);
        card.addView(label(context, title, 19, R.color.m3_text));
        if (message != null) {
            TextView messageView = label(context, message, 14, R.color.m3_text_dim);
            messageView.setPadding(0, dp(context, 10), 0, 0);
            card.addView(messageView);
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(context, 18);
        card.addView(row, rowParams);

        TextView cancel = button(context, context.getString(android.R.string.cancel), false);
        TextView ok = button(context, positive, true);
        row.addView(cancel, weightParams(context, 0));
        row.addView(ok, weightParams(context, 10));

        Dialog dialog = show(context, card, 0.5f);
        cancel.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });
        return dialog;
    }

    public static Dialog input(Context context, CharSequence title, CharSequence hint,
                               @Nullable CharSequence initial, CharSequence positive, TextCallback callback) {
        return input(context, title, hint, initial, positive, InputType.TYPE_CLASS_TEXT, callback);
    }

    /** Dialog isian password: teks tersamar. */
    public static Dialog inputPassword(Context context, CharSequence title, CharSequence hint,
                                       CharSequence positive, TextCallback callback) {
        return input(context, title, hint, null, positive,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, callback);
    }

    public static Dialog input(Context context, CharSequence title, CharSequence hint,
                               @Nullable CharSequence initial, CharSequence positive, int inputType, TextCallback callback) {
        LinearLayout card = card(context);
        card.addView(label(context, title, 19, R.color.m3_text));

        EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setTextSize(15);
        editText.setTextColor(ContextCompat.getColor(context, R.color.m3_text));
        editText.setHintTextColor(ContextCompat.getColor(context, R.color.m3_text_dim));
        editText.setBackgroundResource(R.drawable.m3_input);
        editText.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        if (initial != null) editText.setText(initial);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editParams.topMargin = dp(context, 14);
        card.addView(editText, editParams);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(context, 16);
        card.addView(row, rowParams);

        TextView cancel = button(context, context.getString(android.R.string.cancel), false);
        TextView ok = button(context, positive, true);
        row.addView(cancel, weightParams(context, 0));
        row.addView(ok, weightParams(context, 10));

        Dialog dialog = show(context, card, 0.5f);
        cancel.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            if (callback.onText(editText.getText().toString())) dialog.dismiss();
        });
        return dialog;
    }

    public static Dialog choose(Context context, @Nullable CharSequence title, CharSequence[] items,
                                int checked, IndexCallback callback) {
        LinearLayout card = card(context);
        if (title != null) {
            TextView titleView = label(context, title, 19, R.color.m3_text);
            titleView.setPadding(0, 0, 0, dp(context, 8));
            card.addView(titleView);
        }
        ScrollView scrollView = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list);
        card.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog dialog = show(context, card, 0.55f);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView row = label(context, items[i], 15, i == checked ? R.color.m3_primary : R.color.m3_text);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(context, 46));
            row.setPadding(dp(context, 12), 0, dp(context, 12), 0);
            row.setBackgroundResource(R.drawable.m3_nav_off);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                callback.onIndex(index);
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return dialog;
    }
}
