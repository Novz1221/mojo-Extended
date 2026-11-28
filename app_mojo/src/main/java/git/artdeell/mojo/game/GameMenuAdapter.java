package git.artdeell.mojo.game;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import git.artdeell.mojo.R;
import git.artdeell.mojo.prefs.LauncherPreferences;

/** Material 3 styled in-game side menu: the original actions plus screen-recorder rows. */
public class GameMenuAdapter extends BaseAdapter {
    public static final int POS_REC = 5, POS_SOURCE = 6, POS_AUDIO = 7, POS_RES = 8, POS_FPS = 9, POS_SHOW_FPS = 10, POS_LYRICS = 11;

    private final Context context;
    private final String[] actions;
    private final ScreenRecorder recorder;

    public GameMenuAdapter(Context context, String[] actions, ScreenRecorder recorder) {
        this.context = context;
        this.actions = actions;
        this.recorder = recorder;
        recorder.setOnChange(this::notifyDataSetChanged);
    }

    @Override
    public int getCount() {
        return actions.length + 7;
    }

    @Override
    public Object getItem(int position) {
        return position;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView tv = (TextView) (convertView != null ? convertView
                : LayoutInflater.from(context).inflate(R.layout.item_drawer_m3, parent, false));
        boolean rec = position == POS_REC;
        tv.setBackgroundResource(rec ? R.drawable.m3_drawer_item_rec : R.drawable.m3_drawer_item);
        tv.setTextColor(ContextCompat.getColor(context,
                rec ? (recorder.isRecording() ? R.color.m3_on_badge : R.color.m3_on_primary) : R.color.m3_text));
        tv.setText(label(position));
        return tv;
    }

    private boolean lyricsOn() {
        try {
            return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(git.artdeell.mojo.social.LyricsOverlay.PREF_KEY, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private String label(int position) {
        if (position < actions.length) return actions[position];
        switch (position) {
            case POS_REC:
                return recorder.isRecording() ? "■ Berhenti rekam" : "● Rekam layar";
            case POS_SOURCE:
                return recorder.sourceLabel();
            case POS_AUDIO:
                return recorder.audioLabel();
            case POS_RES:
                return recorder.resolutionLabel();
            case POS_SHOW_FPS:
                return "Tampilkan FPS: " + (LauncherPreferences.PREF_SHOW_FPS ? "On" : "Off");
            case POS_LYRICS:
                return "Lirik: " + (lyricsOn() ? "On" : "Off");
            default:
                return recorder.fpsLabel();
        }
    }
}
