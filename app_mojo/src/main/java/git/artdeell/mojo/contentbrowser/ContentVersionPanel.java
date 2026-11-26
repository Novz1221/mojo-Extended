package git.artdeell.mojo.contentbrowser;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.Fluid;
import git.artdeell.mojo.utils.M3Dialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The version picker shown after tapping a mod, modpack, resource pack, shader pack or save.
 * Files are grouped by Minecraft version and loader, every group expands to its files.
 */
public final class ContentVersionPanel {

    public interface Host {
        /** Called on a background thread. @return {gameVersion, loader} of the selected instance, entries may be null */
        String[] preferredTarget();

        void onFilePicked(ContentApi.Item item, ContentApi.FileVersion file);

        void openUrl(String url);

        void onVisibilityChanged(boolean open);
    }

    private static final Pattern MC_RELEASE = Pattern.compile("^\\d+\\.\\d+(\\.\\d+)?$");
    private static final Pattern NUMBER_SPLIT = Pattern.compile("[^0-9]+");

    private static final int TYPE_GROUP = 0, TYPE_FILE = 1;

    private static final class Group {
        final String mc;
        final String loaderKey;
        final String loaderLabel;
        final List<ContentApi.FileVersion> files = new ArrayList<>();
        boolean expanded;

        Group(String mc, String loaderKey, String loaderLabel) {
            this.mc = mc;
            this.loaderKey = loaderKey;
            this.loaderLabel = loaderLabel;
        }
    }

    private static final class Row {
        final Group group;
        final ContentApi.FileVersion file;
        final boolean last;

        Row(Group group, ContentApi.FileVersion file, boolean last) {
            this.group = group;
            this.file = file;
            this.last = last;
        }
    }

    private final Fragment mFragment;
    private final View mRoot;
    private final Host mHost;
    private final TextView mChip, mStatus, mCardType, mCardPlatform, mCardTitle, mCardDesc, mLinksTitle;
    private final EditText mSearch;
    private final ImageView mCardIcon;
    private final LinearLayout mLinks;
    private final RecyclerView mList;
    private final Adapter mAdapter = new Adapter();

    private final List<Group> mGroups = new ArrayList<>();
    private final List<Group> mShown = new ArrayList<>();
    private final List<Row> mRows = new ArrayList<>();

    private ContentApi.Item mItem;
    private boolean mReleasesOnly = true;
    private boolean mOpen;
    private int mGeneration;
    private String mPrefVersion, mPrefLoader;

    public ContentVersionPanel(Fragment fragment, View root, Host host) {
        mFragment = fragment;
        mRoot = root;
        mHost = host;
        mChip = root.findViewById(R.id.ver_chip_release);
        mSearch = root.findViewById(R.id.ver_search);
        mStatus = root.findViewById(R.id.ver_status);
        mList = root.findViewById(R.id.ver_list);
        mCardType = root.findViewById(R.id.ver_card_type);
        mCardPlatform = root.findViewById(R.id.ver_card_platform);
        mCardIcon = root.findViewById(R.id.ver_card_icon);
        mCardTitle = root.findViewById(R.id.ver_card_title);
        mCardDesc = root.findViewById(R.id.ver_card_desc);
        mLinksTitle = root.findViewById(R.id.ver_links_title);
        mLinks = root.findViewById(R.id.ver_links);

        mList.setLayoutManager(new LinearLayoutManager(root.getContext()));
        mList.setAdapter(mAdapter);
        mChip.setOnClickListener(v -> {
            mReleasesOnly = !mReleasesOnly;
            updateChip();
            Fluid.pop(mChip);
            applyFilter();
        });
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        updateChip();
    }

    public boolean isOpen() {
        return mOpen;
    }

    public void hide() {
        if (!mOpen) return;
        mOpen = false;
        mGeneration++;
        mRoot.setVisibility(View.GONE);
        mHost.onVisibilityChanged(false);
    }

    public void open(ContentApi.Item item) {
        Context context = mRoot.getContext();
        mItem = item;
        mOpen = true;
        final int generation = ++mGeneration;
        mGroups.clear();
        mShown.clear();
        mRows.clear();
        mAdapter.notifyDataSetChanged();
        mSearch.setText("");
        mStatus.setText(R.string.m3_loading);
        mStatus.setVisibility(View.VISIBLE);
        fillCard(item);
        mRoot.setVisibility(View.VISIBLE);
        mHost.onVisibilityChanged(true);
        Fluid.enter(mRoot, 0);

        final String key = context.getString(R.string.curseforge_api_key);
        MojoApplication.sExecutorService.execute(() -> {
            ContentApi.Links links = ContentApi.links(key, item);
            Tools.runOnUiThread(() -> {
                if (alive(generation)) fillLinks(links);
            });
        });
        MojoApplication.sExecutorService.execute(() -> {
            String[] target = mHost.preferredTarget();
            List<ContentApi.FileVersion> files = ContentApi.allVersions(key, item);
            Tools.runOnUiThread(() -> {
                if (!alive(generation)) return;
                if (files == null) {
                    mStatus.setText(R.string.m3_load_failed);
                    return;
                }
                mPrefVersion = target != null && target.length > 0 ? target[0] : null;
                mPrefLoader = target != null && target.length > 1 ? target[1] : null;
                buildGroups(files);
                expandPreferred();
                applyFilter();
                scrollToExpanded();
            });
        });
    }

    private boolean alive(int generation) {
        return mOpen && generation == mGeneration && mFragment.isAdded() && mFragment.getView() != null;
    }

    // ---------------------------------------------------------------- Detail card

    private void fillCard(ContentApi.Item item) {
        Context context = mRoot.getContext();
        mCardType.setText(typeLabel(item.type));
        mCardPlatform.setText(item.platform == ContentApi.PLATFORM_MODRINTH
                ? R.string.m3_platform_modrinth : R.string.m3_platform_curseforge);
        mCardTitle.setText(item.title);
        mCardDesc.setText(item.description);
        ImageLoader.load(mCardIcon, item.iconUrl, M3Dialog.dp(context, 96));
        mLinks.removeAllViews();
        mLinksTitle.setVisibility(View.GONE);
    }

    private static int typeLabel(String type) {
        switch (type) {
            case ContentApi.TYPE_MODPACK:
                return R.string.m3_nav_modpack;
            case ContentApi.TYPE_RESOURCEPACK:
                return R.string.m3_nav_resource;
            case ContentApi.TYPE_SHADER:
                return R.string.m3_nav_shader;
            case ContentApi.TYPE_SAVES:
                return R.string.m3_nav_saves;
            default:
                return R.string.m3_nav_mod;
        }
    }

    private void fillLinks(ContentApi.Links links) {
        mLinks.removeAllViews();
        addLink(R.string.m3_link_page, links.page);
        addLink(R.string.m3_link_source, links.source);
        addLink(R.string.m3_link_issues, links.issues);
        addLink(R.string.m3_link_wiki, links.wiki);
        addLink(R.string.m3_link_discord, links.discord);
        mLinksTitle.setVisibility(mLinks.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addLink(int labelRes, String url) {
        if (url == null || url.isEmpty()) return;
        Context context = mRoot.getContext();
        TextView view = M3Dialog.label(context, context.getString(labelRes), 15, R.color.m3_primary);
        view.setPadding(0, M3Dialog.dp(context, 9), 0, M3Dialog.dp(context, 9));
        view.setClickable(true);
        view.setOnClickListener(v -> mHost.openUrl(url));
        mLinks.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------------------------------------------------------------- Grouping

    private static String loaderLabel(String loader) {
        switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric":
                return "Fabric";
            case "forge":
                return "Forge";
            case "neoforge":
                return "NeoForge";
            case "quilt":
                return "Quilt";
            case "iris":
                return "Iris";
            case "optifine":
                return "OptiFine";
            case "minecraft":
            case "datapack":
                return "";
            default:
                return loader.isEmpty() ? "" : loader.substring(0, 1).toUpperCase(Locale.ROOT) + loader.substring(1);
        }
    }

    private void buildGroups(List<ContentApi.FileVersion> files) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (ContentApi.FileVersion file : files) {
            List<String> versions = file.gameVersions.isEmpty()
                    ? Collections.singletonList("?") : file.gameVersions;
            List<String> loaders = new ArrayList<>();
            for (String loader : file.loaders) {
                if (!loaderLabel(loader).isEmpty() && !loaders.contains(loader)) loaders.add(loader);
            }
            if (loaders.isEmpty()) loaders.add("");
            for (String mc : versions) {
                for (String loader : loaders) {
                    String key = mc + "|" + loader;
                    Group group = map.get(key);
                    if (group == null) {
                        group = new Group(mc, loader, loaderLabel(loader));
                        map.put(key, group);
                    }
                    group.files.add(file);
                }
            }
        }
        mGroups.clear();
        mGroups.addAll(map.values());
        Collections.sort(mGroups, GROUP_ORDER);
        for (Group group : mGroups) {
            Collections.sort(group.files, (a, b) -> Long.compare(b.published, a.published));
        }
    }

    private static final Comparator<Group> GROUP_ORDER = (a, b) -> {
        boolean ra = MC_RELEASE.matcher(a.mc).matches();
        boolean rb = MC_RELEASE.matcher(b.mc).matches();
        if (ra != rb) return ra ? -1 : 1;
        int result = ra ? -compareVersions(a.mc, b.mc) : -a.mc.compareTo(b.mc);
        if (result != 0) return result;
        return a.loaderLabel.compareTo(b.loaderLabel);
    };

    private static int compareVersions(String a, String b) {
        String[] pa = NUMBER_SPLIT.split(a);
        String[] pb = NUMBER_SPLIT.split(b);
        int length = Math.max(pa.length, pb.length);
        for (int i = 0; i < length; i++) {
            long va = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0;
            long vb = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;
            if (va != vb) return Long.compare(va, vb);
        }
        return 0;
    }

    private void expandPreferred() {
        if (mPrefVersion == null || mPrefVersion.isEmpty()) return;
        Group fallback = null;
        boolean opened = false;
        for (Group group : mGroups) {
            if (!group.mc.equals(mPrefVersion)) continue;
            if (mPrefLoader == null) {
                group.expanded = true;
                opened = true;
                continue;
            }
            if (group.loaderKey.isEmpty() || group.loaderKey.equalsIgnoreCase(mPrefLoader)) {
                group.expanded = true;
                return;
            }
            if (fallback == null) fallback = group;
        }
        if (!opened && fallback != null) fallback.expanded = true;
    }

    private void applyFilter() {
        String query = mSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        mShown.clear();
        for (Group group : mGroups) {
            if (mReleasesOnly && !MC_RELEASE.matcher(group.mc).matches()) continue;
            if (!query.isEmpty() && !group.mc.toLowerCase(Locale.ROOT).contains(query)
                    && !group.loaderLabel.toLowerCase(Locale.ROOT).contains(query)) continue;
            mShown.add(group);
        }
        rebuildRows();
        mAdapter.notifyDataSetChanged();
        if (mGroups.isEmpty()) {
            mStatus.setText(R.string.m3_no_results);
            mStatus.setVisibility(View.VISIBLE);
        } else if (mShown.isEmpty()) {
            mStatus.setText(R.string.m3_ver_no_versions);
            mStatus.setVisibility(View.VISIBLE);
        } else {
            mStatus.setVisibility(View.GONE);
        }
    }

    private void rebuildRows() {
        mRows.clear();
        for (Group group : mShown) {
            mRows.add(new Row(group, null, false));
            if (!group.expanded) continue;
            int count = group.files.size();
            for (int i = 0; i < count; i++) {
                mRows.add(new Row(group, group.files.get(i), i == count - 1));
            }
        }
    }

    private void scrollToExpanded() {
        for (int i = 0; i < mRows.size(); i++) {
            Row row = mRows.get(i);
            if (row.file == null && row.group.expanded) {
                ((LinearLayoutManager) mList.getLayoutManager()).scrollToPositionWithOffset(i, 0);
                return;
            }
        }
    }

    private void toggle(int position) {
        if (position == RecyclerView.NO_POSITION || position >= mRows.size()) return;
        Group group = mRows.get(position).group;
        int count = group.files.size();
        group.expanded = !group.expanded;
        if (group.expanded) {
            for (int i = 0; i < count; i++) {
                mRows.add(position + 1 + i, new Row(group, group.files.get(i), i == count - 1));
            }
            mAdapter.notifyItemRangeInserted(position + 1, count);
        } else {
            for (int i = 0; i < count; i++) mRows.remove(position + 1);
            mAdapter.notifyItemRangeRemoved(position + 1, count);
        }
        mAdapter.notifyItemChanged(position);
        ((LinearLayoutManager) mList.getLayoutManager()).scrollToPositionWithOffset(position, 0);
    }

    // ---------------------------------------------------------------- Chip

    private void updateChip() {
        Context context = mRoot.getContext();
        mChip.setBackgroundResource(mReleasesOnly ? R.drawable.m3_chip_on : R.drawable.m3_chip_off);
        if (mReleasesOnly) {
            Drawable check = ContextCompat.getDrawable(context, R.drawable.ic_m3_check);
            if (check != null) {
                check = DrawableCompat.wrap(check.mutate());
                DrawableCompat.setTint(check, ContextCompat.getColor(context, R.color.m3_text));
                mChip.setCompoundDrawablesWithIntrinsicBounds(check, null, null, null);
                mChip.setCompoundDrawablePadding(M3Dialog.dp(context, 8));
            }
        } else {
            mChip.setCompoundDrawables(null, null, null, null);
        }
    }

    // ---------------------------------------------------------------- Adapter

    private static String ago(Context context, long time) {
        if (time <= 0) return "";
        Resources res = context.getResources();
        long minutes = Math.max(0, System.currentTimeMillis() - time) / 60000L;
        if (minutes < 1) return res.getString(R.string.m3_ago_now);
        if (minutes < 60) return res.getQuantityString(R.plurals.m3_ago_minutes, (int) minutes, (int) minutes);
        long hours = minutes / 60;
        if (hours < 24) return res.getQuantityString(R.plurals.m3_ago_hours, (int) hours, (int) hours);
        long days = hours / 24;
        if (days < 30) return res.getQuantityString(R.plurals.m3_ago_days, (int) days, (int) days);
        long months = days / 30;
        if (months < 12) return res.getQuantityString(R.plurals.m3_ago_months, (int) months, (int) months);
        long years = Math.max(1, days / 365);
        return res.getQuantityString(R.plurals.m3_ago_years, (int) years, (int) years);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).file == null ? TYPE_GROUP : TYPE_FILE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_GROUP) {
                return new GroupHolder(inflater.inflate(R.layout.item_dl_vgroup, parent, false));
            }
            return new FileHolder(inflater.inflate(R.layout.item_dl_vfile, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            Context context = holder.itemView.getContext();
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            if (holder instanceof GroupHolder) {
                GroupHolder h = (GroupHolder) holder;
                Group group = row.group;
                h.title.setText(group.mc);
                h.badge.setText(group.loaderLabel);
                h.badge.setVisibility(group.loaderLabel.isEmpty() ? View.GONE : View.VISIBLE);
                h.itemView.setBackgroundResource(group.expanded
                        ? R.drawable.m3_pref_card_top : R.drawable.m3_pref_card_single);
                h.arrow.setRotation(group.expanded ? 180f : 0f);
                params.bottomMargin = M3Dialog.dp(context, group.expanded ? 2 : 8);
                h.itemView.setOnClickListener(v -> toggle(h.getAdapterPosition()));
            } else {
                FileHolder h = (FileHolder) holder;
                final ContentApi.FileVersion file = row.file;
                h.title.setText(file.name);
                h.downloads.setText(ContentApi.compact(file.downloads));
                h.date.setText(ago(context, file.published));
                int labelRes;
                String letter;
                int bg;
                int fg;
                switch (file.releaseType) {
                    case ContentApi.RELEASE_ALPHA:
                        labelRes = R.string.m3_ver_alpha;
                        letter = "A";
                        bg = 0xFF5A2D33;
                        fg = 0xFFFF7B7B;
                        break;
                    case ContentApi.RELEASE_BETA:
                        labelRes = R.string.m3_ver_beta;
                        letter = "B";
                        bg = 0xFF5B4531;
                        fg = 0xFFF5A24B;
                        break;
                    default:
                        labelRes = R.string.m3_ver_stable;
                        letter = "R";
                        bg = 0xFF234A38;
                        fg = 0xFF1BD96A;
                        break;
                }
                h.type.setText(labelRes);
                h.letter.setText(letter);
                h.letter.setTextColor(fg);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(bg);
                h.letter.setBackground(circle);
                h.itemView.setBackgroundResource(row.last ? R.drawable.m3_pref_card_bottom : R.drawable.m3_pref_card_mid);
                params.bottomMargin = M3Dialog.dp(context, row.last ? 8 : 2);
                h.itemView.setOnClickListener(v -> mHost.onFilePicked(mItem, file));
            }
            holder.itemView.setLayoutParams(params);
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }

    private static final class GroupHolder extends RecyclerView.ViewHolder {
        final TextView title, badge;
        final ImageView arrow;

        GroupHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.vg_title);
            badge = itemView.findViewById(R.id.vg_badge);
            arrow = itemView.findViewById(R.id.vg_arrow);
        }
    }

    private static final class FileHolder extends RecyclerView.ViewHolder {
        final TextView letter, title, downloads, date, type;

        FileHolder(@NonNull View itemView) {
            super(itemView);
            letter = itemView.findViewById(R.id.vf_letter);
            title = itemView.findViewById(R.id.vf_title);
            downloads = itemView.findViewById(R.id.vf_downloads);
            date = itemView.findViewById(R.id.vf_date);
            type = itemView.findViewById(R.id.vf_type);
        }
    }
}
