package git.artdeell.mojo.fragments;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contentbrowser.ContentApi;
import git.artdeell.mojo.contentbrowser.ImageLoader;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.tasks.AsyncVersionList;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.M3Dialog;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Download hub: game versions, modpacks, mods, resource packs, saves and shader packs. */
public class DownloadFragment extends Fragment {
    public static final String TAG = "DownloadFragment";
    public static final String ARG_MODE = "mode";

    private static final int MODE_GAME = 0, MODE_MOD = 1, MODE_RESOURCE = 2, MODE_SAVES = 3, MODE_SHADER = 4;
    private static final int CHIP_RELEASE = 0, CHIP_SNAPSHOT = 1, CHIP_APRIL = 2, CHIP_OLD = 3;
    private static final Set<String> APRIL_FOOLS = new HashSet<>(Arrays.asList(
            "15w14a", "1.rv-pre1", "3d shareware v1.34", "20w14infinite", "22w13oneblockatatime",
            "23w13a_or_b", "24w14potato", "25w14craftmine"));
    private static final Pattern VERSION_TOKEN = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    private int mMode = MODE_GAME;
    private View mPanelGame, mPanelContent;
    private final int[] mNavIds = {R.id.nav_game, R.id.nav_modpack, R.id.nav_mod, R.id.nav_resource, R.id.nav_saves, R.id.nav_shader};

    // Game list state
    private int mChip = CHIP_RELEASE;
    private TextView[] mChips;
    private EditText mGameSearch;
    private TextView mGameStatus;
    private RecyclerView mGameList;
    private final List<JVersionList.Version> mGameShown = new ArrayList<>();
    private GameAdapter mGameAdapter;

    // Content list state
    private int mPlatform = ContentApi.PLATFORM_MODRINTH;
    private int mSort = 0;
    private int mPage = 1;
    private int mTotalPages = 0;
    private int mGeneration = 0;
    private EditText mFilterName, mFilterVersion;
    private TextView mPageText, mContentStatus, mPlatformValue, mSortValue;
    private RecyclerView mContentList;
    private ContentAdapter mContentAdapter;
    private final List<ContentApi.Item> mItems = new ArrayList<>();
    private TextView mTitle;

    public DownloadFragment() {
        super(R.layout.fragment_download);
    }

    private static String type(int mode) {
        switch (mode) {
            case MODE_RESOURCE:
                return ContentApi.TYPE_RESOURCEPACK;
            case MODE_SHADER:
                return ContentApi.TYPE_SHADER;
            case MODE_SAVES:
                return ContentApi.TYPE_SAVES;
            default:
                return ContentApi.TYPE_MOD;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mTitle = view.findViewById(R.id.header_title);
        view.findViewById(R.id.header_back).setOnClickListener(v -> requireActivity().onBackPressed());
        view.findViewById(R.id.header_home).setOnClickListener(v -> Tools.backToMainMenu(requireActivity()));
        mPanelGame = view.findViewById(R.id.panel_game);
        mPanelContent = view.findViewById(R.id.panel_content);

        view.findViewById(R.id.nav_game).setOnClickListener(v -> setMode(MODE_GAME));
        view.findViewById(R.id.nav_modpack).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));
        view.findViewById(R.id.nav_mod).setOnClickListener(v -> setMode(MODE_MOD));
        view.findViewById(R.id.nav_resource).setOnClickListener(v -> setMode(MODE_RESOURCE));
        view.findViewById(R.id.nav_saves).setOnClickListener(v -> setMode(MODE_SAVES));
        view.findViewById(R.id.nav_shader).setOnClickListener(v -> setMode(MODE_SHADER));

        setupGamePanel(view);
        setupContentPanel(view);

        int startMode = getArguments() == null ? MODE_GAME : getArguments().getInt(ARG_MODE, MODE_GAME);
        setMode(startMode);
    }

    private void setMode(int mode) {
        mMode = mode;
        boolean game = mode == MODE_GAME;
        mPanelGame.setVisibility(game ? View.VISIBLE : View.GONE);
        mPanelContent.setVisibility(game ? View.GONE : View.VISIBLE);
        int titleRes;
        int selectedNav;
        switch (mode) {
            case MODE_MOD:
                titleRes = R.string.m3_title_mod;
                selectedNav = R.id.nav_mod;
                break;
            case MODE_RESOURCE:
                titleRes = R.string.m3_title_resource;
                selectedNav = R.id.nav_resource;
                break;
            case MODE_SAVES:
                titleRes = R.string.m3_title_saves;
                selectedNav = R.id.nav_saves;
                break;
            case MODE_SHADER:
                titleRes = R.string.m3_title_shader;
                selectedNav = R.id.nav_shader;
                break;
            default:
                titleRes = R.string.m3_title_game;
                selectedNav = R.id.nav_game;
                break;
        }
        mTitle.setText(titleRes);
        updateNav(selectedNav);
        if (game) {
            loadGameVersions(false);
        } else {
            if (mode == MODE_SAVES && mPlatform == ContentApi.PLATFORM_MODRINTH
                    && ContentApi.hasCurseforgeKey(getString(R.string.curseforge_api_key))) {
                mPlatform = ContentApi.PLATFORM_CURSEFORGE;
            }
            updatePlatformAndSort();
            mPage = 1;
            searchContent();
        }
    }

    private void updateNav(int selectedId) {
        View root = getView();
        if (root == null) return;
        Context context = requireContext();
        for (int id : mNavIds) {
            View item = root.findViewById(id);
            boolean selected = id == selectedId;
            View pill = item.findViewById(getPillId(id));
            pill.setBackgroundResource(selected ? R.drawable.m3_nav_on : R.drawable.m3_nav_off);
            ImageView icon = item.findViewById(getIconId(id));
            icon.setColorFilter(ContextCompat.getColor(context, selected ? R.color.m3_text : R.color.m3_text_dim));
        }
    }

    private int getPillId(int navId) {
        if (navId == R.id.nav_game) return R.id.nav_game_pill;
        if (navId == R.id.nav_modpack) return R.id.nav_modpack_pill;
        if (navId == R.id.nav_mod) return R.id.nav_mod_pill;
        if (navId == R.id.nav_resource) return R.id.nav_resource_pill;
        if (navId == R.id.nav_saves) return R.id.nav_saves_pill;
        return R.id.nav_shader_pill;
    }

    private int getIconId(int navId) {
        if (navId == R.id.nav_game) return R.id.nav_game_icon;
        if (navId == R.id.nav_modpack) return R.id.nav_modpack_icon;
        if (navId == R.id.nav_mod) return R.id.nav_mod_icon;
        if (navId == R.id.nav_resource) return R.id.nav_resource_icon;
        if (navId == R.id.nav_saves) return R.id.nav_saves_icon;
        return R.id.nav_shader_icon;
    }

    // ---------------------------------------------------------------- Game versions

    private void setupGamePanel(View view) {
        mChips = new TextView[]{
                view.findViewById(R.id.chip_release), view.findViewById(R.id.chip_snapshot),
                view.findViewById(R.id.chip_april), view.findViewById(R.id.chip_old)};
        for (int i = 0; i < mChips.length; i++) {
            final int chip = i;
            mChips[i].setOnClickListener(v -> {
                mChip = chip;
                updateChips();
                applyGameFilter();
            });
        }
        mGameSearch = view.findViewById(R.id.dl_game_search);
        mGameStatus = view.findViewById(R.id.dl_game_status);
        mGameList = view.findViewById(R.id.dl_game_list);
        mGameAdapter = new GameAdapter();
        mGameList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mGameList.setAdapter(mGameAdapter);
        mGameSearch.setOnEditorActionListener((v, actionId, event) -> {
            applyGameFilter();
            return false;
        });
        mGameSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyGameFilter();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        view.findViewById(R.id.dl_game_refresh).setOnClickListener(v -> loadGameVersions(true));
        updateChips();
    }

    private void updateChips() {
        Context context = requireContext();
        for (int i = 0; i < mChips.length; i++) {
            boolean selected = i == mChip;
            mChips[i].setBackgroundResource(selected ? R.drawable.m3_chip_on : R.drawable.m3_chip_off);
            if (selected) {
                Drawable check = ContextCompat.getDrawable(context, R.drawable.ic_m3_check);
                if (check != null) {
                    check = DrawableCompat.wrap(check.mutate());
                    DrawableCompat.setTint(check, ContextCompat.getColor(context, R.color.m3_text));
                    mChips[i].setCompoundDrawablesWithIntrinsicBounds(check, null, null, null);
                    mChips[i].setCompoundDrawablePadding(M3Dialog.dp(context, 4));
                }
            } else {
                mChips[i].setCompoundDrawables(null, null, null, null);
            }
        }
    }

    private void loadGameVersions(boolean force) {
        Object table = ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (!force && table instanceof JVersionList && ((JVersionList) table).versions != null) {
            applyGameFilter();
            return;
        }
        mGameStatus.setText(R.string.m3_loading);
        mGameStatus.setVisibility(View.VISIBLE);
        new AsyncVersionList().getVersionList(versions -> {
            if (versions != null) ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions);
            Tools.runOnUiThread(() -> {
                if (!isAdded() || getView() == null) return;
                if (versions == null) {
                    mGameStatus.setText(R.string.m3_load_failed);
                } else {
                    applyGameFilter();
                }
            });
        });
    }

    private static boolean isAprilFools(JVersionList.Version version) {
        String id = version.id == null ? "" : version.id.toLowerCase(Locale.ROOT);
        return APRIL_FOOLS.contains(id) || id.contains("infinite") || id.contains("potato") || id.contains("craftmine");
    }

    private void applyGameFilter() {
        if (!isAdded() || getView() == null) return;
        mGameShown.clear();
        Object table = ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (table instanceof JVersionList && ((JVersionList) table).versions != null) {
            String query = mGameSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (JVersionList.Version version : ((JVersionList) table).versions) {
                if (version.id == null) continue;
                String type = version.type == null ? "" : version.type;
                boolean april = isAprilFools(version);
                boolean match;
                switch (mChip) {
                    case CHIP_RELEASE:
                        match = type.equals("release");
                        break;
                    case CHIP_SNAPSHOT:
                        match = type.equals("snapshot") && !april;
                        break;
                    case CHIP_APRIL:
                        match = april;
                        break;
                    default:
                        match = type.startsWith("old");
                        break;
                }
                if (match && (query.isEmpty() || version.id.toLowerCase(Locale.ROOT).contains(query))) {
                    mGameShown.add(version);
                }
            }
        }
        mGameAdapter.notifyDataSetChanged();
        mGameStatus.setText(R.string.m3_no_results);
        mGameStatus.setVisibility(mGameShown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static String formatDate(String isoTime) {
        if (isoTime == null || isoTime.length() < 19) return isoTime == null ? "" : isoTime;
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = in.parse(isoTime.substring(0, 19));
            if (date == null) return isoTime;
            return new SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.getDefault()).format(date);
        } catch (java.text.ParseException e) {
            return isoTime;
        }
    }

    private void installGameVersion(JVersionList.Version version) {
        Context context = requireContext();
        M3Dialog.confirm(context, version.id, context.getString(R.string.m3_install_version_message),
                context.getString(R.string.m3_install), () -> {
                    try {
                        Instance instance = Instances.createInstance(created -> {
                            created.sharedData = true;
                            created.versionId = version.id;
                        }, null);
                        Instances.setSelectedInstance(instance);
                        Toast.makeText(context, R.string.m3_version_added, Toast.LENGTH_LONG).show();
                        Tools.backToMainMenu(requireActivity());
                    } catch (IOException e) {
                        Tools.showError(context, e);
                    }
                });
    }

    private class GameAdapter extends RecyclerView.Adapter<GameAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dl_version, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            JVersionList.Version version = mGameShown.get(position);
            holder.id.setText(version.id);
            String type = version.type == null ? "" : version.type;
            String label;
            if (isAprilFools(version)) label = getString(R.string.m3_chip_april);
            else if (type.equals("release")) label = getString(R.string.m3_type_release);
            else if (type.equals("snapshot")) label = getString(R.string.m3_type_snapshot);
            else label = getString(R.string.m3_chip_old);
            holder.type.setText(label);
            holder.date.setText(formatDate(version.releaseTime));
            holder.itemView.setOnClickListener(v -> installGameVersion(version));
            holder.link.setOnClickListener(v -> Tools.openURL(requireActivity(),
                    "https://minecraft.wiki/w/Java_Edition_" + version.id.replace(' ', '_')));
        }

        @Override
        public int getItemCount() {
            return mGameShown.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView id, type, date;
            final View link;

            Holder(@NonNull View itemView) {
                super(itemView);
                id = itemView.findViewById(R.id.dlv_id);
                type = itemView.findViewById(R.id.dlv_type);
                date = itemView.findViewById(R.id.dlv_date);
                link = itemView.findViewById(R.id.dlv_link);
            }
        }
    }

    // ---------------------------------------------------------------- Mods, packs, saves

    private void setupContentPanel(View view) {
        mFilterName = view.findViewById(R.id.dl_filter_name);
        mFilterVersion = view.findViewById(R.id.dl_filter_version);
        mPageText = view.findViewById(R.id.dl_page_text);
        mContentStatus = view.findViewById(R.id.dl_content_status);
        mPlatformValue = view.findViewById(R.id.dl_platform_value);
        mSortValue = view.findViewById(R.id.dl_sort_value);
        mContentList = view.findViewById(R.id.dl_content_list);
        mContentAdapter = new ContentAdapter();
        mContentList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mContentList.setAdapter(mContentAdapter);

        TextView.OnEditorActionListener searchAction = (v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                mPage = 1;
                searchContent();
            }
            return false;
        };
        mFilterName.setOnEditorActionListener(searchAction);
        mFilterVersion.setOnEditorActionListener(searchAction);
        view.findViewById(R.id.dl_filter_name_btn).setOnClickListener(v -> {
            mPage = 1;
            searchContent();
        });
        view.findViewById(R.id.dl_filter_version_btn).setOnClickListener(v ->
                VersionSelectorDialog.open(requireContext(), true, (id, snapshot) -> {
                    mFilterVersion.setText(id);
                    mPage = 1;
                    searchContent();
                }));
        view.findViewById(R.id.dl_page_prev).setOnClickListener(v -> {
            if (mPage > 1) {
                mPage--;
                searchContent();
            }
        });
        view.findViewById(R.id.dl_page_next).setOnClickListener(v -> {
            if (mPage < mTotalPages) {
                mPage++;
                searchContent();
            }
        });
        view.findViewById(R.id.dl_platform_card).setOnClickListener(v -> {
            Context context = requireContext();
            CharSequence[] items = {context.getString(R.string.m3_platform_modrinth), context.getString(R.string.m3_platform_curseforge)};
            M3Dialog.choose(context, context.getString(R.string.m3_target_platform), items, mPlatform, index -> {
                mPlatform = index;
                updatePlatformAndSort();
                mPage = 1;
                searchContent();
            });
        });
        view.findViewById(R.id.dl_sort_card).setOnClickListener(v -> {
            Context context = requireContext();
            CharSequence[] items = sortLabels(context);
            M3Dialog.choose(context, context.getString(R.string.m3_sort_by), items, mSort, index -> {
                mSort = index;
                updatePlatformAndSort();
                mPage = 1;
                searchContent();
            });
        });
        updatePlatformAndSort();
    }

    private CharSequence[] sortLabels(Context context) {
        return new CharSequence[]{
                context.getString(R.string.m3_sort_relevance), context.getString(R.string.m3_sort_downloads),
                context.getString(R.string.m3_sort_follows), context.getString(R.string.m3_sort_newest),
                context.getString(R.string.m3_sort_updated)};
    }

    private void updatePlatformAndSort() {
        mPlatformValue.setText(mPlatform == ContentApi.PLATFORM_MODRINTH
                ? R.string.m3_platform_modrinth : R.string.m3_platform_curseforge);
        mSortValue.setText(sortLabels(requireContext())[mSort]);
    }

    private void searchContent() {
        if (!isAdded() || getView() == null) return;
        String key = getString(R.string.curseforge_api_key);
        if (mPlatform == ContentApi.PLATFORM_CURSEFORGE && !ContentApi.hasCurseforgeKey(key)) {
            showContentMessage(getString(R.string.m3_no_cf_key));
            return;
        }
        if (mMode == MODE_SAVES && mPlatform == ContentApi.PLATFORM_MODRINTH) {
            showContentMessage(getString(R.string.m3_saves_curseforge_only));
            return;
        }
        final int generation = ++mGeneration;
        final int platform = mPlatform;
        final String type = type(mMode);
        final String query = mFilterName.getText().toString().trim();
        final String version = mFilterVersion.getText().toString().trim();
        final int sort = mSort;
        final int page = mPage;
        mItems.clear();
        mContentAdapter.notifyDataSetChanged();
        mContentStatus.setText(R.string.m3_loading);
        mContentStatus.setVisibility(View.VISIBLE);
        MojoApplication.sExecutorService.execute(() -> {
            ContentApi.Page result = ContentApi.search(key, platform, type, query, version, sort, page);
            Tools.runOnUiThread(() -> {
                if (!isAdded() || getView() == null || generation != mGeneration) return;
                if (result == null) {
                    showContentMessage(getString(R.string.m3_load_failed));
                    return;
                }
                mItems.addAll(result.items);
                mContentAdapter.notifyDataSetChanged();
                mContentList.scrollToPosition(0);
                mTotalPages = (result.total + ContentApi.PAGE_SIZE - 1) / ContentApi.PAGE_SIZE;
                mPageText.setText(mTotalPages == 0 ? getString(R.string.m3_page_empty) : mPage + " / " + mTotalPages);
                mContentStatus.setText(R.string.m3_no_results);
                mContentStatus.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void showContentMessage(String message) {
        mGeneration++;
        mItems.clear();
        mContentAdapter.notifyDataSetChanged();
        mTotalPages = 0;
        mPageText.setText(R.string.m3_page_empty);
        mContentStatus.setText(message);
        mContentStatus.setVisibility(View.VISIBLE);
    }

    private static String detectLoader(String versionId) {
        if (versionId == null) return null;
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("fabric")) return "fabric";
        if (lower.contains("quilt")) return "quilt";
        if (lower.contains("forge")) return "forge";
        return null;
    }

    private static String detectGameVersion(String versionId) {
        if (versionId == null) return null;
        Object table = ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        Matcher matcher = VERSION_TOKEN.matcher(versionId);
        String first = null;
        while (matcher.find()) {
            String token = matcher.group();
            if (first == null) first = token;
            if (table instanceof JVersionList && ((JVersionList) table).versions != null) {
                for (JVersionList.Version version : ((JVersionList) table).versions) {
                    if (token.equals(version.id)) return token;
                }
            }
        }
        return first;
    }

    private static String folderFor(String type) {
        switch (type) {
            case ContentApi.TYPE_RESOURCEPACK:
                return "resourcepacks";
            case ContentApi.TYPE_SHADER:
                return "shaderpacks";
            case ContentApi.TYPE_SAVES:
                return "saves";
            default:
                return "mods";
        }
    }

    private void openItem(ContentApi.Item item) {
        Context context = requireContext();
        if (!ProgressLayout.ensureNoOngoingTasks(context)) return;
        Toast.makeText(context, R.string.m3_loading, Toast.LENGTH_SHORT).show();
        final String key = getString(R.string.curseforge_api_key);
        final String typedVersion = mFilterVersion.getText().toString().trim();
        MojoApplication.sExecutorService.execute(() -> {
            Instance instance = Instances.loadSelectedInstance();
            if (instance == null) {
                Tools.runOnUiThread(() -> Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show());
                return;
            }
            String loader = detectLoader(instance.versionId);
            String gameVersion = detectGameVersion(instance.versionId);
            if (gameVersion == null) gameVersion = typedVersion;
            List<ContentApi.FileVersion> files = ContentApi.versions(key, item, gameVersion, loader);
            if (files != null && files.isEmpty() && gameVersion != null && !gameVersion.isEmpty()) {
                files = ContentApi.versions(key, item, null, null);
            }
            final List<ContentApi.FileVersion> finalFiles = files;
            final File destination = new File(instance.getGameDirectory(), folderFor(item.type));
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (finalFiles == null || finalFiles.isEmpty()) {
                    Toast.makeText(context, R.string.m3_no_files, Toast.LENGTH_LONG).show();
                    return;
                }
                CharSequence[] names = new CharSequence[finalFiles.size()];
                for (int i = 0; i < names.length; i++) names[i] = finalFiles.get(i).name;
                M3Dialog.choose(context, item.title, names, -1, index ->
                        downloadFile(item, finalFiles.get(index), destination));
            });
        });
    }

    private void downloadFile(ContentApi.Item item, ContentApi.FileVersion file, File destinationDir) {
        Context context = requireContext();
        if (!ProgressLayout.ensureNoOngoingTasks(context)) return;
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.m3_downloading, item.title);
        MojoApplication.sExecutorService.execute(() -> {
            try {
                String fileName = file.fileName == null || file.fileName.isEmpty() ? item.id + ".jar" : file.fileName;
                fileName = FileUtils.escapeFileName(fileName);
                boolean isWorld = ContentApi.TYPE_SAVES.equals(item.type);
                File target = isWorld ? new File(Tools.DIR_CACHE, fileName) : new File(destinationDir, fileName);
                DownloadUtils.downloadFileMonitored(file.url, target, null, (current, max) -> {
                    int percent = max > 0 ? (int) ((current * 100L) / max) : 0;
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, percent, R.string.m3_downloading, item.title);
                });
                if (isWorld) {
                    try {
                        extractZip(target, destinationDir);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                    }
                }
                Tools.runOnUiThread(() -> Toast.makeText(context,
                        context.getString(R.string.m3_installed_to, folderFor(item.type)), Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                Tools.showErrorRemote(e);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            }
        });
    }

    private static void extractZip(File zip, File destinationDir) throws IOException {
        String rootPath = destinationDir.getCanonicalPath() + File.separator;
        FileUtils.ensureDirectory(destinationDir);
        try (InputStream raw = new FileInputStream(zip); ZipInputStream zis = new ZipInputStream(raw)) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destinationDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(rootPath)) throw new IOException("Bad zip entry: " + entry.getName());
                if (entry.isDirectory()) {
                    FileUtils.ensureDirectory(out);
                    continue;
                }
                FileUtils.ensureParentDirectory(out);
                try (OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) os.write(buffer, 0, read);
                }
            }
        }
    }

    private class ContentAdapter extends RecyclerView.Adapter<ContentAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dl_content, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ContentApi.Item item = mItems.get(position);
            holder.title.setText(item.title);
            holder.platform.setText(item.platform == ContentApi.PLATFORM_MODRINTH
                    ? R.string.m3_platform_modrinth : R.string.m3_platform_curseforge);
            holder.author.setText(item.author);
            holder.downloads.setText(ContentApi.compact(item.downloads));
            holder.follows.setText(ContentApi.compact(item.follows));
            holder.desc.setText(item.description);
            holder.tags.setText(item.tags);
            ImageLoader.load(holder.icon, item.iconUrl, M3Dialog.dp(holder.icon.getContext(), 64));
            holder.itemView.setOnClickListener(v -> openItem(item));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title, platform, author, downloads, follows, desc, tags;

            Holder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.dlc_icon);
                title = itemView.findViewById(R.id.dlc_title);
                platform = itemView.findViewById(R.id.dlc_platform);
                author = itemView.findViewById(R.id.dlc_author);
                downloads = itemView.findViewById(R.id.dlc_downloads);
                follows = itemView.findViewById(R.id.dlc_follows);
                desc = itemView.findViewById(R.id.dlc_desc);
                tags = itemView.findViewById(R.id.dlc_tags);
            }
        }
    }
}
