package git.artdeell.mojo.fragments;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contentbrowser.ContentApi;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.fabric.FabricVersion;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.progresskeeper.ProgressListener;
import git.artdeell.mojo.tasks.mojson.MoJsonDownloader;
import git.artdeell.mojo.tasks.mojson.MoJsonExtras;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.Fluid;
import git.artdeell.mojo.utils.M3Dialog;

/** Version page: pick a mod loader (Fabric or Quilt) and its API, then install the game. */
public class GameInstallFragment extends Fragment {
    public static final String TAG = "GameInstallFragment";
    public static final String ARG_VERSION = "version";

    private static final int STEP_PENDING = 0;
    private static final int STEP_ACTIVE = 1;
    private static final int STEP_DONE = 2;

    private String mVersionId;
    private EditText mName;
    private Row mFabric;
    private Row mFabricApi;
    private Row mQuilt;
    private Row mQuiltApi;
    private volatile boolean mCancelled;
    private volatile Future<?> mDownload;

    public GameInstallFragment() {
        super(R.layout.fragment_game_install);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        mVersionId = args == null ? null : args.getString(ARG_VERSION);
        view.findViewById(R.id.header_back).setOnClickListener(v -> requireActivity().onBackPressed());
        view.findViewById(R.id.header_home).setOnClickListener(v -> Tools.backToMainMenu(requireActivity()));
        ((TextView) view.findViewById(R.id.header_title)).setText(R.string.m3_title_game);
        if (mVersionId == null) {
            requireActivity().onBackPressed();
            return;
        }

        mName = view.findViewById(R.id.gi_name);
        mName.setText(mVersionId);
        view.findViewById(R.id.gi_download).setOnClickListener(v -> startInstall());

        LinearLayout list = view.findViewById(R.id.gi_list);
        mFabric = new Row(list, R.drawable.ic_fabric, "Fabric");
        mFabricApi = new Row(list, R.drawable.ic_fabric, "Fabric API");
        mQuilt = new Row(list, R.drawable.ic_quilt, "Quilt");
        mQuiltApi = new Row(list, R.drawable.ic_quilt, "Quilted Fabric API");
        for (Row row : new Row[]{mFabric, mFabricApi, mQuilt, mQuiltApi}) {
            row.view.setOnClickListener(v -> pick(row));
        }
        refresh();
        loadLoader(mFabric, FabriclikeUtils.FABRIC_UTILS);
        loadLoader(mQuilt, FabriclikeUtils.QUILT_UTILS);

        Fluid.pressAll(view);
        ViewGroup root = (ViewGroup) view;
        Fluid.stagger(root.getChildAt(0), root.getChildAt(1));
        for (int i = 0; i < list.getChildCount(); i++) Fluid.itemIn(list.getChildAt(i), i);
    }

    // ---------------------------------------------------------------- rows

    private final class Row {
        final View view;
        final ImageView icon;
        final TextView titleView;
        final TextView summary;
        final ImageView arrow;
        final String title;
        final List<String> options = new ArrayList<>();
        List<ContentApi.FileVersion> files = new ArrayList<>();
        int selected = -1;
        boolean loaded;
        boolean requested;
        boolean enabled;

        Row(ViewGroup parent, int iconRes, String title) {
            this.title = title;
            view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gi_loader, parent, false);
            icon = view.findViewById(R.id.gi_icon);
            titleView = view.findViewById(R.id.gi_title);
            summary = view.findViewById(R.id.gi_summary);
            arrow = view.findViewById(R.id.gi_arrow);
            icon.setImageResource(iconRes);
            titleView.setText(title);
            parent.addView(view);
        }
    }

    private void bind(Row row, @Nullable String blocked) {
        String text;
        boolean enabled;
        if (blocked != null) {
            text = blocked;
            enabled = false;
        } else if (!row.loaded) {
            text = getString(R.string.gi_loading);
            enabled = false;
        } else if (row.options.isEmpty()) {
            text = getString(R.string.gi_unavailable);
            enabled = false;
        } else if (row.selected >= 0) {
            text = row.options.get(row.selected);
            enabled = true;
        } else {
            text = getString(R.string.gi_available);
            enabled = true;
        }
        row.summary.setText(text);
        row.enabled = enabled;
        float alpha = enabled ? 1f : 0.5f;
        row.icon.setAlpha(alpha);
        row.titleView.setAlpha(alpha);
        row.summary.setAlpha(alpha);
        row.arrow.setAlpha(alpha);
        row.arrow.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
    }

    private void refresh() {
        bind(mFabric, mQuilt.selected >= 0 ? getString(R.string.gi_incompatible, "Quilt") : null);
        bind(mQuilt, mFabric.selected >= 0 ? getString(R.string.gi_incompatible, "Fabric") : null);
        bind(mFabricApi, mFabric.selected < 0 ? getString(R.string.gi_select_first, "Fabric") : null);
        bind(mQuiltApi, mQuilt.selected < 0 ? getString(R.string.gi_select_first, "Quilt") : null);
    }

    private void pick(Row row) {
        if (!row.enabled || row.options.isEmpty()) return;
        CharSequence[] items = new CharSequence[row.options.size() + 1];
        items[0] = getString(R.string.gi_none);
        for (int i = 0; i < row.options.size(); i++) items[i + 1] = row.options.get(i);
        M3Dialog.choose(requireContext(), row.title, items, row.selected + 1, index -> {
            row.selected = index - 1;
            Fluid.pop(row.icon);
            if (row == mFabric && row.selected >= 0 && !mFabricApi.requested) {
                loadApi(mFabricApi, "fabric-api", "fabric");
            } else if (row == mQuilt && row.selected >= 0 && !mQuiltApi.requested) {
                loadApi(mQuiltApi, "qfapi", "quilt");
            }
            refresh();
        });
    }

    private void loadLoader(Row row, FabriclikeUtils utils) {
        final String gameVersion = mVersionId;
        MojoApplication.sExecutorService.execute(() -> {
            FabricVersion[] versions = null;
            try {
                versions = utils.downloadLoaderVersions(gameVersion);
            } catch (IOException ignored) {
            }
            final List<String> names = new ArrayList<>();
            if (versions != null) {
                boolean anyStable = false;
                for (FabricVersion v : versions) {
                    if (v.stable) {
                        anyStable = true;
                        break;
                    }
                }
                for (FabricVersion v : versions) {
                    if (!anyStable || v.stable) names.add(v.version);
                }
            }
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                row.options.addAll(names);
                row.loaded = true;
                refresh();
            });
        });
    }

    private void loadApi(Row row, String slug, String loader) {
        row.requested = true;
        final String gameVersion = mVersionId;
        MojoApplication.sExecutorService.execute(() -> {
            ContentApi.Item item = new ContentApi.Item();
            item.platform = ContentApi.PLATFORM_MODRINTH;
            item.type = ContentApi.TYPE_MOD;
            item.id = slug;
            List<ContentApi.FileVersion> found = ContentApi.versions(null, item, gameVersion, loader);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                row.files = found == null ? new ArrayList<>() : found;
                row.options.clear();
                for (ContentApi.FileVersion file : row.files) row.options.add(file.name);
                row.selected = row.options.isEmpty() ? -1 : 0; // newest version by default
                row.loaded = true;
                refresh();
            });
        });
    }

    // ------------------------------------------------------------- install

    private void startInstall() {
        final Context context = requireContext();
        if (!ProgressLayout.ensureNoOngoingTasks(context)) return;
        final Context appContext = context.getApplicationContext();
        final String typed = mName.getText().toString().trim();
        final String displayName = typed.isEmpty() ? mVersionId : typed;

        final FabriclikeUtils utils;
        final String loaderVersion;
        final Row apiRow;
        if (mFabric.selected >= 0) {
            utils = FabriclikeUtils.FABRIC_UTILS;
            loaderVersion = mFabric.options.get(mFabric.selected);
            apiRow = mFabricApi;
        } else if (mQuilt.selected >= 0) {
            utils = FabriclikeUtils.QUILT_UTILS;
            loaderVersion = mQuilt.options.get(mQuilt.selected);
            apiRow = mQuiltApi;
        } else {
            utils = null;
            loaderVersion = null;
            apiRow = null;
        }
        final ContentApi.FileVersion apiFile =
                apiRow != null && apiRow.selected >= 0 && apiRow.selected < apiRow.files.size()
                        ? apiRow.files.get(apiRow.selected) : null;

        mCancelled = false;
        mDownload = null;
        final String label = utils == null ? mVersionId : mVersionId + " + " + utils.getName();
        final InstallUi ui = new InstallUi(context, label);
        final ProgressListener listener = ui.listener(appContext);
        ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_GAME, listener);
        ui.cancel.setOnClickListener(v -> {
            mCancelled = true;
            Future<?> running = mDownload;
            if (running != null) running.cancel(true);
            cleanup(ui, listener);
        });

        MojoApplication.sExecutorService.execute(() -> {
            try {
                ui.setStep(0, STEP_ACTIVE);
                String id = mVersionId;
                if (utils != null) {
                    id = utils.install(mVersionId, loaderVersion);
                    if (id == null) throw new IOException("Could not read the loader metadata");
                }
                final String versionId = id;
                if (mCancelled) return;
                ui.setStep(0, STEP_DONE);
                ui.setStep(1, STEP_ACTIVE);
                final String normalized = MoJsonExtras.normalizeVersionId(versionId);
                JVersionList.Version listed = MoJsonExtras.getListedVersion(normalized);
                mDownload = new MoJsonDownloader().start(appContext.getAssets(), listed, normalized,
                        new MoJsonExtras.DoneListener() {
                            @Override
                            public void onDownloadDone(File[] classpath) {
                                finishInstall(appContext, ui, listener, versionId, displayName, utils, apiFile);
                            }

                            @Override
                            public void onDownloadFailed(Throwable throwable) {
                                fail(appContext, ui, listener, throwable);
                            }
                        });
            } catch (Exception e) {
                fail(appContext, ui, listener, e);
            }
        });
    }

    private void finishInstall(Context appContext, InstallUi ui, ProgressListener listener, String versionId,
                               String displayName, @Nullable FabriclikeUtils utils,
                               @Nullable ContentApi.FileVersion apiFile) {
        try {
            if (mCancelled) return;
            ui.setStep(1, STEP_DONE);
            ui.setStep(2, STEP_ACTIVE);
            Instance instance = Instances.createInstance(created -> {
                created.name = displayName;
                if (utils != null) created.icon = utils.getIconName();
                created.versionId = versionId;
                created.sharedData = false; // game folder lives inside the instance
            }, versionId);
            if (apiFile != null) {
                String fileName = apiFile.fileName == null || apiFile.fileName.isEmpty()
                        ? "api.jar" : FileUtils.escapeFileName(apiFile.fileName);
                File target = new File(new File(instance.getGameDirectory(), "mods"), fileName);
                DownloadUtils.downloadFileMonitored(apiFile.url, target, null, (current, max) -> {
                    int percent = max > 0 ? (int) ((current * 100L) / max) : 0;
                    ui.setProgress(percent, appContext.getString(R.string.m3_downloading, apiFile.name));
                });
            }
            Instances.setSelectedInstance(instance);
            ui.setStep(2, STEP_DONE);
            Tools.runOnUiThread(() -> {
                cleanup(ui, listener);
                Toast.makeText(appContext, appContext.getString(R.string.gi_done, displayName), Toast.LENGTH_LONG).show();
                if (isAdded()) Tools.backToMainMenu(requireActivity());
            });
        } catch (Exception e) {
            fail(appContext, ui, listener, e);
        }
    }

    private void fail(Context appContext, InstallUi ui, ProgressListener listener, Throwable throwable) {
        Tools.runOnUiThread(() -> cleanup(ui, listener));
        if (!mCancelled) Tools.showErrorRemote(appContext.getString(R.string.mc_download_failed), throwable);
    }

    private void cleanup(InstallUi ui, ProgressListener listener) {
        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_GAME, listener);
        ui.dismiss();
    }

    // -------------------------------------------------------------- dialog

    private final class InstallUi {
        final Dialog dialog;
        final ImageView[] icons = new ImageView[3];
        final TextView detail;
        final TextView percent;
        final ProgressBar bar;
        final TextView cancel;

        InstallUi(Context context, String versionLabel) {
            LinearLayout card = M3Dialog.card(context);
            TextView title = M3Dialog.label(context, context.getString(R.string.gi_install_title), 19, R.color.m3_text);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 0, 0, M3Dialog.dp(context, 12));
            card.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            card.addView(stepRow(context, 0, context.getString(R.string.gi_step_prepare)));
            card.addView(stepRow(context, 1, context.getString(R.string.gi_step_download, versionLabel)));

            LinearLayout block = new LinearLayout(context);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(M3Dialog.dp(context, 36), 0, 0, M3Dialog.dp(context, 6));
            detail = M3Dialog.label(context, "", 13, R.color.m3_text_dim);
            block.addView(detail);
            LinearLayout barRow = new LinearLayout(context);
            barRow.setGravity(Gravity.CENTER_VERTICAL);
            bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgressDrawable(ContextCompat.getDrawable(context, R.drawable.m3_seek_progress));
            barRow.addView(bar, new LinearLayout.LayoutParams(0, M3Dialog.dp(context, 8), 1f));
            percent = M3Dialog.label(context, "0%", 13, R.color.m3_primary);
            percent.setPadding(M3Dialog.dp(context, 10), 0, 0, 0);
            barRow.addView(percent);
            block.addView(barRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(block, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            card.addView(stepRow(context, 2, context.getString(R.string.gi_step_install)));

            cancel = M3Dialog.button(context, context.getString(R.string.gi_cancel), true);
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cancelParams.topMargin = M3Dialog.dp(context, 14);
            card.addView(cancel, cancelParams);

            dialog = M3Dialog.show(context, card, 0.6f);
            dialog.setCancelable(false);
        }

        private LinearLayout stepRow(Context context, int index, String text) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, M3Dialog.dp(context, 8), 0, M3Dialog.dp(context, 8));
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.ic_m3_next);
            icon.setColorFilter(ContextCompat.getColor(context, R.color.m3_text_dim));
            icons[index] = icon;
            row.addView(icon, new LinearLayout.LayoutParams(M3Dialog.dp(context, 24), M3Dialog.dp(context, 24)));
            TextView label = M3Dialog.label(context, text, 16, R.color.m3_text);
            label.setPadding(M3Dialog.dp(context, 12), 0, 0, 0);
            row.addView(label);
            return row;
        }

        void setStep(int index, int state) {
            Tools.runOnUiThread(() -> {
                Context context = dialog.getContext();
                ImageView icon = icons[index];
                if (state == STEP_DONE) {
                    icon.setImageResource(R.drawable.ic_m3_check);
                    icon.setColorFilter(ContextCompat.getColor(context, R.color.m3_primary));
                } else if (state == STEP_ACTIVE) {
                    icon.setImageResource(R.drawable.ic_m3_download);
                    icon.setColorFilter(ContextCompat.getColor(context, R.color.m3_text));
                } else {
                    icon.setImageResource(R.drawable.ic_m3_next);
                    icon.setColorFilter(ContextCompat.getColor(context, R.color.m3_text_dim));
                }
                Fluid.pop(icon);
            });
        }

        void setProgress(int progress, @Nullable String text) {
            Tools.runOnUiThread(() -> {
                int value = Math.max(progress, 0);
                bar.setProgress(value);
                percent.setText(value + "%");
                if (text != null) detail.setText(text);
            });
        }

        void dismiss() {
            Tools.runOnUiThread(() -> {
                if (dialog.isShowing()) dialog.dismiss();
            });
        }

        ProgressListener listener(Context appContext) {
            return new ProgressListener() {
                @Override
                public void onProgressStarted() {
                }

                @Override
                public void onProgressUpdated(int progress, int resid, Object... va) {
                    String text = null;
                    if (resid > 0) {
                        try {
                            text = appContext.getString(resid, va);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    setProgress(progress, text);
                }

                @Override
                public void onProgressEnded() {
                }
            };
        }
    }
}
