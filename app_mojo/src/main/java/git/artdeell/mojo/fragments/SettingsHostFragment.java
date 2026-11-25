package git.artdeell.mojo.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.R;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceControlFragment;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceExperimentalFragment;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceFragment;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceJavaFragment;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceMiscellaneousFragment;
import git.artdeell.mojo.prefs.screens.LauncherPreferenceVideoFragment;

/**
 * Settings host: an icon rail on the left, the selected preference screen on the right.
 */
public class SettingsHostFragment extends Fragment {
    private static final String STATE_TAB = "settings_tab";
    private static final String CHILD_TAG = "SETTINGS_CHILD";

    private static final class Tab {
        final int buttonId;
        final int titleRes;
        final Class<? extends Fragment> fragmentClass;

        Tab(int buttonId, @StringRes int titleRes, Class<? extends Fragment> fragmentClass) {
            this.buttonId = buttonId;
            this.titleRes = titleRes;
            this.fragmentClass = fragmentClass;
        }
    }

    private static final Tab[] TABS = {
            new Tab(R.id.settings_tab_video, R.string.settings_tab_video, LauncherPreferenceVideoFragment.class),
            new Tab(R.id.settings_tab_control, R.string.settings_tab_control, LauncherPreferenceControlFragment.class),
            new Tab(R.id.settings_tab_java, R.string.settings_tab_java, LauncherPreferenceJavaFragment.class),
            new Tab(R.id.settings_tab_misc, R.string.settings_tab_misc, LauncherPreferenceMiscellaneousFragment.class),
            new Tab(R.id.settings_tab_experimental, R.string.settings_tab_experimental, LauncherPreferenceExperimentalFragment.class),
            new Tab(R.id.settings_tab_general, R.string.settings_tab_general, LauncherPreferenceFragment.class),
    };

    private final View[] mButtons = new View[TABS.length];
    private int mCurrent = -1;

    public SettingsHostFragment() {
        super(R.layout.fragment_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            mButtons[i] = view.findViewById(TABS[i].buttonId);
            mButtons[i].setOnClickListener(v -> {
                if (index != mCurrent) { git.artdeell.mojo.utils.Fluid.pop(v); showTab(index, true); }
            });
        }

        if (savedInstanceState == null) {
            showTab(0, true);
        } else {
            // The child fragment is restored by the child fragment manager
            showTab(savedInstanceState.getInt(STATE_TAB, 0), false);
        }
    }

    private void showTab(int index, boolean replace) {
        mCurrent = index;
        for (int i = 0; i < mButtons.length; i++) mButtons[i].setSelected(i == index);
        updateTitle();
        if (!replace) return;
        getChildFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .replace(R.id.settings_container, TABS[index].fragmentClass, null, CHILD_TAG)
                .commit();
    }

    private void updateTitle() {
        TextView title = requireActivity().findViewById(R.id.bar_title);
        if (title == null || mCurrent < 0) return;
        title.setText(getString(R.string.settings_title_format, getString(TABS[mCurrent].titleRes)));
    }

    @Override
    public void onResume() {
        super.onResume();
        updateTitle();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, Math.max(mCurrent, 0));
    }

    @Override
    public void onDestroyView() {
        TextView title = requireActivity().findViewById(R.id.bar_title);
        if (title != null) title.setText("");
        super.onDestroyView();
    }
}
