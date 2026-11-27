package git.artdeell.mojo.fragments;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.utils.Fluid;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.instances.MrpackExporter;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.M3Dialog;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Installed versions (instances) with filters, folder tools and cleanup. */
public class VersionListFragment extends Fragment {
    public static final String TAG = "VersionListFragment";

    private static final int FILTER_ALL = 0, FILTER_VANILLA = 1, FILTER_MODDABLE = 2;
    private static final String[] LOADER_WORDS = {"fabric", "forge", "quilt", "neoforge", "optifine", "liteloader", "cleanroom"};

    private final List<Instance> mAll = new ArrayList<>();
    private final List<Instance> mShown = new ArrayList<>();
    private String mSelectedId = "";
    private int mFilter = FILTER_ALL;
    private boolean mSkipItemAnim = false;
    private RecyclerView mList;
    private TextView mEmpty, mFilterAll, mFilterVanilla, mFilterModdable;
    private InstanceAdapter mAdapter;

    private final ActivityResultLauncher<Object> mJarLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), data -> {
                if (data != null) Tools.launchModInstaller(requireContext(), data);
            });

    private Instance mExportInstance;
    private final ActivityResultLauncher<String> mMrpackLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(), this::exportMrpackTo);

    public VersionListFragment() {
        super(R.layout.fragment_version_list);
    }

    /** Name shown for an instance in the launcher. */
    public static String displayName(Context context, Instance instance) {
        String name = Tools.validOrNullString(instance.name);
        String version = Tools.validOrNullString(instance.versionId);
        if (Instance.VERSION_LATEST_RELEASE.equalsIgnoreCase(version)) {
            version = context.getString(R.string.profiles_latest_release);
        } else if (Instance.VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(version)) {
            version = context.getString(R.string.profiles_latest_snapshot);
        }
        if (name != null) return name;
        if (version != null) return version;
        return context.getString(R.string.m3_no_versions);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ((TextView) view.findViewById(R.id.header_title)).setText(R.string.m3_version_list);
        view.findViewById(R.id.header_back).setOnClickListener(v -> requireActivity().onBackPressed());
        view.findViewById(R.id.header_home).setOnClickListener(v -> Tools.backToMainMenu(requireActivity()));

        mList = view.findViewById(R.id.vl_list);
        mEmpty = view.findViewById(R.id.vl_empty);
        mFilterAll = view.findViewById(R.id.vl_filter_all);
        mFilterVanilla = view.findViewById(R.id.vl_filter_vanilla);
        mFilterModdable = view.findViewById(R.id.vl_filter_moddable);
        mAdapter = new InstanceAdapter();
        mList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mList.setAdapter(mAdapter);

        TextView path = view.findViewById(R.id.vl_folder_path);
        String gameHome = Tools.DIR_GAME_HOME == null ? "" : Tools.DIR_GAME_HOME;
        int androidIndex = gameHome.indexOf("Android/");
        path.setText(androidIndex >= 0 ? gameHome.substring(androidIndex) : gameHome);

        mFilterAll.setOnClickListener(v -> setFilter(FILTER_ALL));
        mFilterVanilla.setOnClickListener(v -> setFilter(FILTER_VANILLA));
        mFilterModdable.setOnClickListener(v -> setFilter(FILTER_MODDABLE));
        view.findViewById(R.id.vl_refresh).setOnClickListener(v -> reload());
        view.findViewById(R.id.vl_install_new).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), DownloadFragment.class, DownloadFragment.TAG, null));
        view.findViewById(R.id.vl_folder_more).setOnClickListener(v -> showFolderMenu());
        view.findViewById(R.id.vl_export_mrpack).setOnClickListener(v -> startMrpackExport());
        updateFilterChips();

        Fluid.pressAll(view);
        ViewGroup root = (ViewGroup) view;
        ViewGroup body = (ViewGroup) root.getChildAt(1);
        Fluid.stagger(root.getChildAt(0), body.getChildAt(0), body.getChildAt(1));
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private boolean isVanilla(String versionId) {
        if (versionId == null) return false;
        if (Instance.VERSION_LATEST_RELEASE.equals(versionId) || Instance.VERSION_LATEST_SNAPSHOT.equals(versionId)) {
            return true;
        }
        Object table = ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (table instanceof JVersionList && ((JVersionList) table).versions != null) {
            for (JVersionList.Version version : ((JVersionList) table).versions) {
                if (versionId.equals(version.id)) return true;
            }
        }
        String lower = versionId.toLowerCase(Locale.ROOT);
        for (String word : LOADER_WORDS) {
            if (lower.contains(word)) return false;
        }
        return table == null;
    }

    private void reload() {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<Instance> instances = Instances.loadAllInstances();
                Instance selected = Instances.loadSelectedInstance();
                String selectedId = selected == null ? "" : selected.getId();
                Tools.runOnUiThread(() -> apply(instances, selectedId));
            } catch (IOException e) {
                Tools.showErrorRemote(e);
            }
        });
    }

    private void apply(List<Instance> instances, String selectedId) {
        if (!isAdded() || getView() == null) return;
        mAll.clear();
        mAll.addAll(instances);
        mSelectedId = selectedId;
        updateFilterChips();
        applyFilter();
    }

    private void setFilter(int filter) {
        mFilter = filter;
        Fluid.pop(filter == FILTER_ALL ? mFilterAll : filter == FILTER_VANILLA ? mFilterVanilla : mFilterModdable);
        updateFilterChips();
        applyFilter();
    }

    private void applyFilter() {
        mShown.clear();
        for (Instance instance : mAll) {
            boolean vanilla = isVanilla(instance.versionId);
            if (mFilter == FILTER_ALL || (mFilter == FILTER_VANILLA) == vanilla) mShown.add(instance);
        }
        mAdapter.notifyDataSetChanged();
        mEmpty.setVisibility(mShown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateFilterChips() {
        int vanilla = 0;
        for (Instance instance : mAll) {
            if (isVanilla(instance.versionId)) vanilla++;
        }
        mFilterAll.setText(getString(R.string.m3_filter_all, mAll.size()));
        mFilterVanilla.setText(getString(R.string.m3_filter_vanilla, vanilla));
        mFilterModdable.setText(getString(R.string.m3_filter_moddable, mAll.size() - vanilla));
        styleChip(mFilterAll, mFilter == FILTER_ALL);
        styleChip(mFilterVanilla, mFilter == FILTER_VANILLA);
        styleChip(mFilterModdable, mFilter == FILTER_MODDABLE);
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.m3_nav_on : R.drawable.m3_nav_off);
        chip.setTextColor(ContextCompat.getColor(requireContext(), selected ? R.color.m3_text : R.color.m3_text_dim));
    }

    private void showFolderMenu() {
        Context context = requireContext();
        CharSequence[] items = {
                context.getString(R.string.mcl_button_open_directory),
                context.getString(R.string.main_share_logs),
                context.getString(R.string.main_install_jar_file),
                context.getString(R.string.m3_gamepad_mapper)
        };
        M3Dialog.choose(context, context.getString(R.string.m3_default_game), items, -1, index -> {
            switch (index) {
                case 0:
                    openGameDirectory(context);
                    break;
                case 1:
                    Tools.shareLog(context);
                    break;
                case 2:
                    if (ProgressLayout.ensureNoOngoingTasks(context)) mJarLauncher.launch(null);
                    break;
                default:
                    Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
                    break;
            }
        });
    }

    private void startMrpackExport() {
        Context context = requireContext();
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        if (!ProgressLayout.ensureNoOngoingTasks(context)) return;
        mExportInstance = instance;
        mMrpackLauncher.launch(MrpackExporter.suggestedFileName(displayName(context, instance)));
    }

    private void exportMrpackTo(@Nullable Uri uri) {
        Instance instance = mExportInstance;
        mExportInstance = null;
        if (uri == null || instance == null || !isAdded()) return;
        Context context = requireContext().getApplicationContext();
        String name = displayName(context, instance);
        Toast.makeText(context, R.string.m3_export_mrpack_busy, Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot open output");
                MrpackExporter.export(instance, name, out);
                Tools.runOnUiThread(() -> Toast.makeText(context, R.string.m3_export_mrpack_done, Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                Tools.runOnUiThread(() -> Toast.makeText(context,
                        context.getString(R.string.m3_export_mrpack_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openGameDirectory(Context context) {
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDirectory = instance.getGameDirectory();
        if (FileUtils.ensureDirectorySilently(gameDirectory)) {
            Tools.openPath(context, gameDirectory, false);
        } else {
            Toast.makeText(context, R.string.gamedir_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void select(Instance instance) {
        Instances.setSelectedInstance(instance);
        mSelectedId = instance.getId();
        mSkipItemAnim = true;
        mAdapter.notifyDataSetChanged();
        mList.post(() -> mSkipItemAnim = false);
    }

    private void edit(Instance instance) {
        Instances.setSelectedInstance(instance);
        Tools.swapFragment(requireActivity(), InstanceEditorFragment.class, InstanceEditorFragment.TAG, new Bundle(1));
    }

    private void delete(Instance instance) {
        Context context = requireContext();
        M3Dialog.confirm(context, context.getString(R.string.global_delete),
                displayName(context, instance), context.getString(R.string.global_delete), () ->
                        MojoApplication.sExecutorService.execute(() -> {
                            try {
                                boolean wasSelected = instance.getId().equals(mSelectedId);
                                Instances.removeInstance(instance);
                                if (wasSelected) {
                                    for (Instance other : Instances.loadAllInstances()) {
                                        Instances.setSelectedInstance(other);
                                        break;
                                    }
                                }
                                Tools.runOnUiThread(this::reload);
                            } catch (IOException e) {
                                Tools.showErrorRemote(e);
                            }
                        }));
    }

    private class InstanceAdapter extends RecyclerView.Adapter<InstanceAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_version_instance, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Instance instance = mShown.get(position);
            Context context = holder.itemView.getContext();
            boolean selected = instance.getId().equals(mSelectedId);
            holder.icon.setImageDrawable(InstanceIconProvider.fetchIcon(context.getResources(), instance));
            holder.name.setText(displayName(context, instance));
            String version = Tools.validOrNullString(instance.versionId);
            String kind = context.getString(isVanilla(instance.versionId) ? R.string.m3_kind_vanilla : R.string.m3_kind_modded);
            String data = context.getString(instance.sharedData ? R.string.m3_data_shared : R.string.m3_data_own);
            holder.sub.setText((version == null ? "" : version + "  ·  ") + kind + "  ·  " + data);
            holder.selected.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            holder.root.setBackgroundResource(selected ? R.drawable.m3_card_selected : R.drawable.m3_card_ripple);
            holder.root.setOnClickListener(v -> select(instance));
            holder.edit.setOnClickListener(v -> edit(instance));
            holder.delete.setOnClickListener(v -> delete(instance));
            Fluid.pressAll(holder.itemView);
            if (!mSkipItemAnim) Fluid.itemIn(holder.itemView, position);
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final View root;
            final ImageView icon, selected;
            final TextView name, sub;
            final ImageButton edit, delete;

            Holder(@NonNull View itemView) {
                super(itemView);
                root = itemView.findViewById(R.id.vi_root);
                icon = itemView.findViewById(R.id.vi_icon);
                selected = itemView.findViewById(R.id.vi_selected);
                name = itemView.findViewById(R.id.vi_name);
                sub = itemView.findViewById(R.id.vi_sub);
                edit = itemView.findViewById(R.id.vi_edit);
                delete = itemView.findViewById(R.id.vi_delete);
            }
        }
    }
}
