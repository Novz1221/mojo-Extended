package git.artdeell.mojo.prefs.screens;


import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.annotation.SuppressLint;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.R;
import git.artdeell.mojo.prefs.BackButtonPreference;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Preference for the main screen, any sub-screen should inherit this class for consistent behavior,
 * overriding only onCreatePreferences
 */
public class LauncherPreferenceFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    protected Runnable mVisibilityUpdater = () -> {};

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.setBackgroundColor(getResources().getColor(R.color.m3_bg));
        super.onViewCreated(view, savedInstanceState);

        float density = getResources().getDisplayMetrics().density;
        setDivider(new ColorDrawable(0));
        setDividerHeight(Math.round(3 * density));
        RecyclerView list = getListView();
        list.setClipToPadding(false);
        list.setPadding(Math.round(4 * density), Math.round(4 * density),
                Math.round(12 * density), Math.round(16 * density));
    }

    /** The rail in the settings host replaces the back entries inside every screen */
    @Override
    public void setPreferenceScreen(PreferenceScreen screen) {
        super.setPreferenceScreen(screen);
        if (screen != null) hideBackEntries(screen);
    }

    private void hideBackEntries(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof BackButtonPreference) preference.setVisible(false);
            else if (preference instanceof PreferenceGroup) hideBackEntries((PreferenceGroup) preference);
        }
    }

    @NonNull
    @Override
    protected RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen screen) {
        return new CardGroupAdapter(screen);
    }

    /** Picks the card shape for every row depending on the neighbours in the list */
    @SuppressLint("RestrictedApi")
    private static class CardGroupAdapter extends PreferenceGroupAdapter {
        private final long mCreatedAt = android.os.SystemClock.uptimeMillis();
        CardGroupAdapter(PreferenceGroup group) {
            super(group);
        }

        @Override
        public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
            super.onBindViewHolder(holder, position);
            Preference preference = getItem(position);
            if (preference == null || preference instanceof PreferenceCategory) return;

            Preference previous = position > 0 ? getItem(position - 1) : null;
            Preference next = position < getItemCount() - 1 ? getItem(position + 1) : null;
            boolean first = previous == null || previous instanceof PreferenceCategory;
            boolean last = next == null || next instanceof PreferenceCategory;

            int background;
            if (first && last) background = R.drawable.m3_pref_card_single;
            else if (first) background = R.drawable.m3_pref_card_top;
            else if (last) background = R.drawable.m3_pref_card_bottom;
            else background = R.drawable.m3_pref_card_mid;
            holder.itemView.setBackgroundResource(background);
            View item = holder.itemView;
            item.animate().cancel();
            item.setScaleX(1f);
            item.setScaleY(1f);
            if (item.isClickable()) {
                git.artdeell.mojo.utils.Fluid.pressAll(item);
            } else {
                item.setOnTouchListener(null);
                item.setTag(R.id.fluid_pressed, null);
            }
            if (android.os.SystemClock.uptimeMillis() - mCreatedAt < 900) {
                git.artdeell.mojo.utils.Fluid.itemIn(item, position);
            }
        }
    }

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        mVisibilityUpdater = this::updateVisibility;
        addPreferencesFromResource(R.xml.pref_main);
        setupNotificationRequestPreference();
    }

    private void updateVisibility(){
        requirePreference("notification_permission_request").setVisible(!getLauncherActivity().checkForPermission(33, Manifest.permission.POST_NOTIFICATIONS));
    }

    private void setupNotificationRequestPreference() {
        Preference mRequestNotificationPermissionPreference = requirePreference("notification_permission_request");
        Activity activity = getActivity();
        if(activity instanceof LauncherActivity) {
            mRequestNotificationPermissionPreference.setOnPreferenceClickListener(preference -> {
                ((LauncherActivity) activity).askForPermission(33, Manifest.permission.POST_NOTIFICATIONS);
                return true;
            });
        }else{
            mRequestNotificationPermissionPreference.setVisible(false);
        }
        updateVisibility();
    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if(sharedPreferences != null) sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        mVisibilityUpdater.run();
    }

    @Override
    public void onPause() {
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if(sharedPreferences != null) sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        LauncherPreferences.loadPreferences(getContext());
    }

    protected Preference requirePreference(CharSequence key) {
        Preference preference = findPreference(key);
        if(preference != null) return preference;
        throw new IllegalStateException("Preference "+key+" is null");
    }
    @SuppressWarnings("unchecked")
    protected <T extends Preference> T requirePreference(CharSequence key, Class<T> preferenceClass) {
        Preference preference = requirePreference(key);
        if(preferenceClass.isInstance(preference)) return (T)preference;
        throw new IllegalStateException("Preference "+key+" is not an instance of "+preferenceClass.getSimpleName());
    }
    protected LauncherActivity getLauncherActivity(){
        return ((LauncherActivity) getActivity());
    }
}
