package git.artdeell.mojo.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import git.artdeell.mojo.R;

/** Binds the "command speed" slider in the quick settings dialog (progress 0..100 = 50%..300%). */
public final class CommandSpeedBinder {
    private CommandSpeedBinder() {
    }

    private static int toSpeed(int progress) {
        return progress * 5 / 2 + CommandMacro.MIN_SPEED;
    }

    public static void bind(View root) {
        if (root == null) return;
        SeekBar bar = root.findViewById(R.id.cmdSpeed_seekbar);
        TextView text = root.findViewById(R.id.cmdSpeed_textView_percent);
        if (bar == null || text == null) return;
        SharedPreferences prefs = root.getContext().getSharedPreferences(CommandMacro.PREFS, Context.MODE_PRIVATE);
        int speed = prefs.getInt(CommandMacro.KEY_SPEED, CommandMacro.DEFAULT_SPEED);
        int progress = Math.max(0, Math.min(100, (speed - CommandMacro.MIN_SPEED) * 2 / 5));
        bar.setMax(100);
        bar.setProgress(progress);
        text.setText(speed + "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                int s = toSpeed(p);
                text.setText(s + "%");
                prefs.edit().putInt(CommandMacro.KEY_SPEED, s).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }
}
