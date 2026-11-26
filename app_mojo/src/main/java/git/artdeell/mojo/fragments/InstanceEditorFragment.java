package git.artdeell.mojo.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.game.renderer.RendererCache;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.multirt.Runtime;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;
import git.artdeell.mojo.utils.CropperUtils;
import git.artdeell.mojo.utils.M3Dialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InstanceEditorFragment extends Fragment implements CropperUtils.CropperReceiver {
    public static final String TAG = "InstanceEditorFragment";

    private Instance mInstance;
    private String mSelectedControlLayout;
    private String mVersionId;
    private View mSaveButton, mDeleteButton;
    private LoaderRow mVersionRow, mControlRow, mRuntimeRow, mRendererRow;
    private View mDataRow;
    private TextView mDataSummary;
    private EditText mDefaultName, mDefaultJvmArgument;
    private ImageView mInstanceIcon;
    private SwitchCompat mSharedDataSwitch;
    private int mRecommendedIconSize;
    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);

    private List<Runtime> mRuntimes;
    private int mRuntimeIndex;
    private int mRendererIndex;
    private List<String> mRendererLabels;

    /** One selectable row of the editor, made from item_gi_loader */
    private static final class LoaderRow {
        final View view;
        final TextView summary;

        LoaderRow(View root, int iconRes, int titleRes) {
            view = root;
            ((ImageView) root.findViewById(R.id.gi_icon)).setImageResource(iconRes);
            ((TextView) root.findViewById(R.id.gi_title)).setText(titleRes);
            summary = root.findViewById(R.id.gi_summary);
        }

        void setSummary(CharSequence text) {
            summary.setText(text);
        }
    }

    private List<String> mRenderNames;

    public InstanceEditorFragment(){
        super(R.layout.fragment_instance_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Paths, which can be changed
        String value = (String) ExtraCore.consumeValue(ExtraConstants.FILE_SELECTOR);
        if(value != null){
            mSelectedControlLayout = value;
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);

        // The screen draws its own header, so the activity hides its back/home bar
        ((TextView) view.findViewById(R.id.header_title)).setText(R.string.m3_edit_instance);
        view.findViewById(R.id.header_back).setOnClickListener(v -> requireActivity().onBackPressed());
        view.findViewById(R.id.header_home).setOnClickListener(v -> Tools.backToMainMenu(requireActivity()));

        RendererCache list = RendererCache.getCompatibleRenderers(view.getContext());
        mRenderNames = list.rendererIds;
        mRendererLabels = new ArrayList<>(list.rendererDisplayNames.length + 1);
        mRendererLabels.addAll(Arrays.asList(list.rendererDisplayNames));
        mRendererLabels.add(view.getContext().getString(R.string.global_default));

        // Set up behaviors
        mSaveButton.setOnClickListener(v -> {
            InstanceIconProvider.dropIcon(mInstance);
            save();
            Tools.backToMainMenu(requireActivity());
        });

        mDeleteButton.setOnClickListener(v -> {
            DeleteConfirmDialogFragment dialogFragment = new DeleteConfirmDialogFragment();
            dialogFragment.show(getChildFragmentManager(), "delete_dialog_confirm");
        });

        mControlRow.view.setOnClickListener(getControlSelectListener());
        mVersionRow.view.setOnClickListener(getVersionSelectListener());

        mRuntimeRow.view.setOnClickListener(v -> {
            CharSequence[] names = new CharSequence[mRuntimes.size()];
            for (int i = 0; i < names.length; i++) names[i] = getRuntimeLabel(i);
            M3Dialog.choose(v.getContext(), getString(R.string.pedit_java_runtime), names, mRuntimeIndex, index -> {
                mRuntimeIndex = index;
                mRuntimeRow.setSummary(getRuntimeLabel(index));
            });
        });

        mRendererRow.view.setOnClickListener(v ->
                M3Dialog.choose(v.getContext(), getString(R.string.pedit_renderer),
                        mRendererLabels.toArray(new CharSequence[0]), mRendererIndex, index -> {
                            mRendererIndex = index;
                            mRendererRow.setSummary(mRendererLabels.get(index));
                        }));

        // Set up the icon change click listener
        mInstanceIcon.setOnClickListener(v -> {
            // Fill recommended size on click to ge the most up to date data
            mRecommendedIconSize = Math.max(v.getWidth(), v.getHeight());
            CropperUtils.startCropper(mCropperLauncher);
        });

        // The whole row toggles the switch, the switch itself is not clickable
        mDataRow.setOnClickListener(v -> mSharedDataSwitch.setChecked(!mSharedDataSwitch.isChecked()));
        mSharedDataSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (mInstance != null) mInstance.sharedData = checked;
            mDataSummary.setText(checked ? R.string.instance_shared_data_on : R.string.instance_shared_data_off);
        });

        Instance selectedInstance = Instances.loadSelectedInstance();
        Context context = view.getContext();
        if(selectedInstance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            getParentFragmentManager().popBackStack();
        }else {
            loadValues(selectedInstance, context);
        }
    }

    private View.OnClickListener getControlSelectListener() {
        return v -> {
            Bundle bundle = new Bundle(3);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, false);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.CTRLMAP_PATH);

            Tools.swapFragment(requireActivity(),
                    FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getVersionSelectListener() {
        return v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot) -> {
            mVersionId = id;
            mVersionRow.setSummary(id);
        });
    }

    private static String nullToEmpty(String in) {
        if(in == null) return "";
        return in;
    }

    private void loadValues(@NonNull Instance instance, @NonNull Context context){
        mInstance = instance;
        mInstanceIcon.setImageDrawable(
                InstanceIconProvider.fetchIcon(getResources(), instance)
        );

        // Runtime
        mRuntimes = MultiRTUtils.getRuntimes();
        mRuntimes.add(new Runtime("<Default>")); // "use the default runtime" entry, always last
        int jvmIndex = -1;
        if(instance.selectedRuntime != null) {
            jvmIndex = mRuntimes.indexOf(new Runtime(instance.selectedRuntime));
        }
        mRuntimeIndex = jvmIndex == -1 ? mRuntimes.size() - 1 : jvmIndex;
        mRuntimeRow.setSummary(getRuntimeLabel(mRuntimeIndex));

        // Renderer
        int rendererIndex = mRenderNames.indexOf(instance.getLaunchRenderer());
        mRendererIndex = rendererIndex == -1 ? mRendererLabels.size() - 1 : rendererIndex;
        mRendererRow.setSummary(mRendererLabels.get(mRendererIndex));

        mVersionId = instance.versionId;
        mVersionRow.setSummary(nullToEmpty(mVersionId));
        mDefaultJvmArgument.setText(nullToEmpty(instance.jvmArgs));
        mDefaultName.setText(nullToEmpty(instance.name));
        mSelectedControlLayout = mSelectedControlLayout == null ? instance.controlLayout : mSelectedControlLayout;
        updateControlSummary();
        mSharedDataSwitch.setChecked(instance.sharedData);
        mDataSummary.setText(instance.sharedData ? R.string.instance_shared_data_on : R.string.instance_shared_data_off);
    }

    private String getRuntimeLabel(int index) {
        Runtime runtime = mRuntimes.get(index);
        if (index == mRuntimes.size() - 1) return getString(R.string.global_default);
        return String.format("%s - %s", runtime.name.replace(".tar.xz", ""),
                runtime.versionString == null ? getString(R.string.multirt_runtime_corrupt) : runtime.versionString);
    }

    private void updateControlSummary() {
        boolean empty = mSelectedControlLayout == null || mSelectedControlLayout.isEmpty();
        mControlRow.setSummary(empty ? getString(R.string.use_global_default) : mSelectedControlLayout);
    }

    private void bindViews(@NonNull View view){
        mVersionRow = new LoaderRow(view.findViewById(R.id.vprof_editor_version_row),
                R.drawable.ic_grass_block, R.string.profiles_profile_version);
        mControlRow = new LoaderRow(view.findViewById(R.id.vprof_editor_ctrl_row),
                R.drawable.ic_m3_game, R.string.default_control);
        mRuntimeRow = new LoaderRow(view.findViewById(R.id.vprof_editor_runtime_row),
                R.drawable.ic_m3_settings, R.string.pedit_java_runtime);
        mRendererRow = new LoaderRow(view.findViewById(R.id.vprof_editor_renderer_row),
                R.drawable.ic_m3_bulb, R.string.pedit_renderer);

        mDefaultName = view.findViewById(R.id.vprof_editor_instance_name);
        mDefaultJvmArgument = view.findViewById(R.id.vprof_editor_jre_args);

        mSaveButton = view.findViewById(R.id.vprof_editor_save_button);
        mDeleteButton = view.findViewById(R.id.vprof_editor_delete_button);
        mInstanceIcon = view.findViewById(R.id.vprof_editor_instance_icon);
        mDataRow = view.findViewById(R.id.vprof_editor_data_row);
        mDataSummary = view.findViewById(R.id.vprof_editor_data_summary);
        mSharedDataSwitch = view.findViewById(R.id.vprof_editor_data_checkbox_container);
    }

    private void save(){
        //First, check for potential issues in the inputs
        mInstance.versionId = nullToEmpty(mVersionId);
        mInstance.controlLayout = nullToEmpty(mSelectedControlLayout);
        mInstance.jvmArgs = mDefaultJvmArgument.getText().toString();

        String newName = mDefaultName.getText().toString();
        if(mInstance.controlLayout.isEmpty()) mInstance.controlLayout = null;
        if(mInstance.jvmArgs.isEmpty()) mInstance.jvmArgs = null;

        Runtime selectedRuntime = mRuntimes.get(mRuntimeIndex);
        mInstance.selectedRuntime = (selectedRuntime.name.equals("<Default>") || selectedRuntime.versionString == null)
                ? null : selectedRuntime.name;

        if(mRendererIndex == mRenderNames.size()) mInstance.renderer = null;
        else mInstance.renderer = mRenderNames.get(mRendererIndex);

        try {
            if(!newName.isEmpty() && !newName.equals(mInstance.name))
                Instances.renameInstanceDirectory(mInstance, newName);
            mInstance.name = newName;
            mInstance.write();
        }catch (Exception e) {
            Tools.showErrorRemote(e);
        }
    }

    @Override
    public float getAspectRatio() {
        return 1f;
    }

    @Override
    public int getTargetMaxSide() {
        return mRecommendedIconSize;
    }

    @Override
    public void onCropped(Bitmap contentBitmap) {
        mInstanceIcon.setImageBitmap(contentBitmap);
        Log.i("bitmap", "w="+contentBitmap.getWidth() +" h="+contentBitmap.getHeight());
        try {
            mInstance.encodeNewIcon(contentBitmap);
        }catch (IOException e) {
            Tools.showErrorRemote(e);
        }
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }
}
