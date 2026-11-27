package git.artdeell.mojo.prefs.screens;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.R;

import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.game.renderer.RendererCache;
import git.artdeell.mojo.game.renderer.def.Renderers;
import git.artdeell.mojo.game.renderer.extra.GLESProvider;
import git.artdeell.mojo.prefs.CustomSeekBarPreference;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.vulkan.DriverInstaller;
import git.artdeell.mojo.vulkan.DriverManager;
import git.artdeell.mojo.vulkan.driver.BuiltinDriver;
import git.artdeell.mojo.vulkan.driver.Driver;
import git.artdeell.mojo.vulkan.ui.DriverConfigDialog;

/**
 * Fragment for any settings video related
 */
public class LauncherPreferenceVideoFragment extends LauncherPreferenceFragment {
    private Boolean hasAngle = null;

    private DriverConfigDialog mDialogScreen;
    private final ActivityResultLauncher<Object> mInstallDriver =
            registerForActivityResult(new OpenDocumentWithExtension("zip"), data -> {
                if(data != null) DriverInstaller.installDriverFromUri(getContext(), data, this::openDriverDialog);
            });
    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_video);
        int resolution = (int) (LauncherPreferences.PREF_SCALE_FACTOR * 100);

        CustomSeekBarPreference resolutionSeekbar = requirePreference("resolutionRatio",
                CustomSeekBarPreference.class);
        resolutionSeekbar.setSuffix(" %");

        // #724 bug fix
        if (resolution < 25) {
            resolutionSeekbar.setValue(100);
        } else {
            resolutionSeekbar.setValue(resolution);
        }

        // Sustained performance is only available since Nougat
        SwitchPreferenceCompat sustainedPerfSwitch = requirePreference("sustainedPerformance",
                SwitchPreferenceCompat.class);
        sustainedPerfSwitch.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        sustainedPerfSwitch.setChecked(LauncherPreferences.PREF_SUSTAINED_PERFORMANCE);

        requirePreference("alternate_surface", SwitchPreferenceCompat.class).setChecked(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE);
        requirePreference("force_vsync", SwitchPreferenceCompat.class).setChecked(LauncherPreferences.PREF_FORCE_VSYNC);
        requirePreference("show_fps", SwitchPreferenceCompat.class).setChecked(LauncherPreferences.PREF_SHOW_FPS);

        // Show ANGLE switch only if AnglePlugin is available
        if(hasAngle == null) {
            GLESProvider provider = GLESProvider.getGlesProvider(getContext(), true);
            hasAngle = provider instanceof GLESProvider.ExternalAngleProvider || provider instanceof GLESProvider.SystemAngleProvider;
        }

        SwitchPreferenceCompat angleSwitch = requirePreference("use_angle", SwitchPreferenceCompat.class);
        angleSwitch.setVisible(hasAngle);
        angleSwitch.setChecked(LauncherPreferences.PREF_USE_ANGLE);

        ListPreference rendererListPreference = requirePreference("renderer",
                ListPreference.class);
        RendererCache list = RendererCache.getCompatibleRenderers(getContext());
        rendererListPreference.setEntries(list.rendererDisplayNames);
        rendererListPreference.setEntryValues(list.rendererIds.toArray(new String[0]));
        rendererListPreference.setOnPreferenceChangeListener((pref, newValue) -> {
            if (Renderers.MOBILEGLUES_RENDERER.equals(newValue)) askAllFilesAccess();
            return true;
        });


        Preference drivers = requirePreference("manageDrivers");
        drivers.setOnPreferenceClickListener(pref -> {
            openDriverDialog();
            return true;
        });
        SwitchPreferenceCompat customVk = requirePreference("zinkPreferSystemDriver", SwitchPreferenceCompat.class);
        boolean gplay = BuildConfig.BUILD_TYPE.equals("gplay");
        boolean isAdreno = GpuUtils.getGlInfo().isAdreno();
        boolean hasSupport = DriverManager.isSupportedByDevice();
        customVk.setVisible(hasSupport && (!gplay || isAdreno));
        customVk.setChecked(LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER);

        computeVisibility();
    }

    @Override
    public void onResume() {
        super.onResume();
        Activity activity = getActivity();
        if(activity != null) {
            requirePreference("ignoreNotch").setVisible(LauncherPreferences.hasNotch(activity));
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        super.onSharedPreferenceChanged(p, s);
        computeVisibility();
    }

    private void computeVisibility(){
        requirePreference("force_vsync", SwitchPreferenceCompat.class)
                .setVisible(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE);
        updateDriversPref();
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    /**
     * Ask the user to grant all files access, so MobileGlues can read its config from the shared MG folder
     */
    private void askAllFilesAccess() {
        Activity activity = getActivity();
        if (activity == null || hasAllFilesAccess()) return;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.mg_storage_title)
                .setMessage(R.string.mg_storage_message)
                .setPositiveButton(R.string.mg_storage_allow, (d, w) -> openAllFilesSettings(activity))
                .setNegativeButton(R.string.mg_storage_later, null)
                .show();
    }

    private void openAllFilesSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void openDriverDialog(){
        if(mDialogScreen == null) {
            mDialogScreen = new DriverConfigDialog();
            mDialogScreen.prepare(getContext(), mInstallDriver);
            mDialogScreen.onDismiss(this::updateDriversPref);
        }
        mDialogScreen.show();
    }
    private void updateDriversPref(){

        Preference preference = requirePreference("manageDrivers");
        boolean hasSupport = DriverManager.isSupportedByDevice();
        boolean gplay = BuildConfig.BUILD_TYPE.equals("gplay");
        if(LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER || gplay || !hasSupport){
            preference.setVisible(false);
            return;
        }
        Driver driver = DriverManager.getPreferredDriver();
        if(driver instanceof BuiltinDriver)
            preference.setSummary(BuiltinDriver.deviceHasDriver() ? R.string.driver_default_name : R.string.driver_config_pref_subtitle);
        else
            preference.setSummary(driver.getName());
        preference.setVisible(true);
    }
}
