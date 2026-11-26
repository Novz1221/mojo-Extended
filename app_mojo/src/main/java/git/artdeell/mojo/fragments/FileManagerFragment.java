package git.artdeell.mojo.fragments;

import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.format.Formatter;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.Fluid;
import git.artdeell.mojo.utils.M3Dialog;

/** Simple file manager for the launcher's own storage folder. */
public class FileManagerFragment extends Fragment {
    public static final String TAG = "FileManagerFragment";

    private static final int SORT_NAME = 0;
    private static final int SORT_DATE = 1;
    private static final int SORT_SIZE = 2;
    private static final long EDIT_LIMIT = 512 * 1024;
    private static final String[] EDITABLE = {"txt", "json", "properties", "cfg", "conf", "toml", "yml", "yaml",
            "log", "md", "xml", "ini", "js", "lang", "mcmeta", "snbt", "csv"};
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private File mRoot;
    private File mCurrent;
    private final ArrayDeque<File> mBack = new ArrayDeque<>();
    private final ArrayDeque<File> mForward = new ArrayDeque<>();
    private final List<File> mEntries = new ArrayList<>();
    private final List<File> mClipboard = new ArrayList<>();
    private boolean mClipboardCut;
    private int mSort = SORT_NAME;
    private boolean mShowHidden = true;
    private String mQuery = "";
    private int mListToken;

    private TextView mPath;
    private TextView mCount;
    private View mBackButton;
    private View mForwardButton;
    private View mParentButton;
    private Adapter mAdapter;

    private final ActivityResultLauncher<String[]> mImport =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importUri);

    public FileManagerFragment() {
        super(R.layout.fragment_file_manager);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mRoot = new File(Tools.DIR_GAME_HOME);
        mPath = view.findViewById(R.id.fm_path);
        mCount = view.findViewById(R.id.fm_count);
        mBackButton = view.findViewById(R.id.fm_back);
        mForwardButton = view.findViewById(R.id.fm_forward);
        mParentButton = view.findViewById(R.id.fm_parent);

        RecyclerView list = view.findViewById(R.id.fm_list);
        list.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        mAdapter = new Adapter();
        list.setAdapter(mAdapter);

        view.findViewById(R.id.fm_home).setOnClickListener(v -> Tools.backToMainMenu(requireActivity()));
        view.findViewById(R.id.fm_more).setOnClickListener(v -> showMenu());
        mBackButton.setOnClickListener(v -> goBack());
        mForwardButton.setOnClickListener(v -> goForward());
        mParentButton.setOnClickListener(v -> goParent());
        view.findViewById(R.id.fm_new).setOnClickListener(v -> createNew());

        Fluid.pressAll(view);
        navigate(mRoot, false);
    }

    // ---------------------------------------------------------- navigation

    private boolean isRoot(File dir) {
        return dir.getAbsolutePath().equals(mRoot.getAbsolutePath());
    }

    private void navigate(File dir, boolean record) {
        if (record && mCurrent != null && !mCurrent.equals(dir)) {
            mBack.push(mCurrent);
            mForward.clear();
        }
        mCurrent = dir;
        reload();
    }

    private void goBack() {
        if (mBack.isEmpty()) return;
        mForward.push(mCurrent);
        mCurrent = mBack.pop();
        reload();
    }

    private void goForward() {
        if (mForward.isEmpty()) return;
        mBack.push(mCurrent);
        mCurrent = mForward.pop();
        reload();
    }

    private void goParent() {
        File parent = mCurrent.getParentFile();
        if (parent == null || isRoot(mCurrent)) return;
        navigate(parent, true);
    }

    private void reload() {
        final File dir = mCurrent;
        final int token = ++mListToken;
        final boolean showHidden = mShowHidden;
        final String query = mQuery.toLowerCase(Locale.ROOT);
        final int sort = mSort;
        MojoApplication.sExecutorService.execute(() -> {
            File[] files = dir.listFiles();
            final List<File> result = new ArrayList<>();
            int folders = 0;
            int plain = 0;
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (!showHidden && name.startsWith(".")) continue;
                    if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)) continue;
                    result.add(file);
                    if (file.isDirectory()) folders++;
                    else plain++;
                }
            }
            Collections.sort(result, comparator(sort));
            final int folderCount = folders;
            final int fileCount = plain;
            Tools.runOnUiThread(() -> {
                if (!isAdded() || token != mListToken) return;
                mEntries.clear();
                mEntries.addAll(result);
                mAdapter.notifyDataSetChanged();
                updateHeader(folderCount, fileCount);
            });
        });
    }

    private static Comparator<File> comparator(final int sort) {
        return (a, b) -> {
            boolean dirA = a.isDirectory();
            boolean dirB = b.isDirectory();
            if (dirA != dirB) return dirA ? -1 : 1;
            int result = 0;
            if (sort == SORT_DATE) result = Long.compare(b.lastModified(), a.lastModified());
            else if (sort == SORT_SIZE) result = Long.compare(b.length(), a.length());
            return result != 0 ? result : a.getName().compareToIgnoreCase(b.getName());
        };
    }

    private void updateHeader(int folders, int files) {
        mPath.setText(mCurrent.getAbsolutePath());
        String counts = getString(R.string.fm_counts, folders, files);
        mCount.setText(mQuery.isEmpty() ? counts : counts + "  \"" + mQuery + "\"");
        setLook(mBackButton, !mBack.isEmpty());
        setLook(mForwardButton, !mForward.isEmpty());
        setLook(mParentButton, !isRoot(mCurrent));
    }

    private static void setLook(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    // --------------------------------------------------------------- list

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView info;

            Holder(View view) {
                super(view);
                icon = view.findViewById(R.id.fm_icon);
                name = view.findViewById(R.id.fm_name);
                info = view.findViewById(R.id.fm_info);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_fm_entry, parent, false);
            Fluid.pressAll(view);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final File file = mEntries.get(position);
            final boolean dir = file.isDirectory();
            holder.icon.setBackgroundResource(dir ? R.drawable.fm_icon_folder : R.drawable.fm_icon_file);
            holder.icon.setImageResource(dir ? R.drawable.ic_m3_folder : R.drawable.ic_px_file);
            holder.name.setText(file.getName());
            String date = DATE_FORMAT.format(new Date(file.lastModified()));
            holder.info.setText(dir ? date
                    : Formatter.formatShortFileSize(holder.itemView.getContext(), file.length()) + "  " + date);
            holder.itemView.setOnClickListener(v -> {
                if (file.isDirectory()) navigate(file, true);
                else showActions(file);
            });
            holder.itemView.setOnLongClickListener(v -> {
                showActions(file);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }
    }

    // ---------------------------------------------------------------- menus

    private void showMenu() {
        final List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.fm_refresh));
        actions.add(this::reload);
        labels.add(getString(R.string.fm_import_file));
        actions.add(() -> mImport.launch(new String[]{"*/*"}));
        if (!mClipboard.isEmpty()) {
            labels.add(getString(R.string.fm_paste, mClipboard.size()));
            actions.add(this::paste);
        }
        labels.add(getString(R.string.fm_search));
        actions.add(this::showSearch);
        labels.add(getString(R.string.fm_sort));
        actions.add(this::showSort);
        labels.add(getString(R.string.fm_show_hidden, getString(mShowHidden ? R.string.fm_on : R.string.fm_off)));
        actions.add(() -> {
            mShowHidden = !mShowHidden;
            reload();
        });
        M3Dialog.choose(requireContext(), null, labels.toArray(new CharSequence[0]), -1,
                index -> actions.get(index).run());
    }

    private void showSort() {
        CharSequence[] items = {getString(R.string.fm_sort_name), getString(R.string.fm_sort_date),
                getString(R.string.fm_sort_size)};
        M3Dialog.choose(requireContext(), getString(R.string.fm_sort), items, mSort, index -> {
            mSort = index;
            reload();
        });
    }

    private void showSearch() {
        M3Dialog.input(requireContext(), getString(R.string.fm_search), getString(R.string.fm_name_hint),
                mQuery, getString(R.string.fm_search), text -> {
                    mQuery = text.trim();
                    reload();
                    return true;
                });
    }

    private void showActions(final File file) {
        final List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (isEditable(file)) {
            labels.add(getString(R.string.fm_edit));
            actions.add(() -> edit(file));
        }
        labels.add(getString(R.string.fm_rename));
        actions.add(() -> rename(file));
        labels.add(getString(R.string.fm_compress));
        actions.add(() -> compress(file));
        labels.add(getString(R.string.fm_copy));
        actions.add(() -> toClipboard(file, false));
        labels.add(getString(R.string.fm_cut));
        actions.add(() -> toClipboard(file, true));
        labels.add(getString(R.string.fm_delete));
        actions.add(() -> delete(file));
        labels.add(getString(R.string.fm_properties));
        actions.add(() -> showProperties(file));
        M3Dialog.choose(requireContext(), file.getName(), labels.toArray(new CharSequence[0]), -1,
                index -> actions.get(index).run());
    }

    // ----------------------------------------------------------- operations

    private void toast(String text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void createNew() {
        CharSequence[] items = {getString(R.string.fm_new_folder), getString(R.string.fm_new_file)};
        M3Dialog.choose(requireContext(), getString(R.string.fm_new), items, -1, index -> {
            final boolean folder = index == 0;
            M3Dialog.input(requireContext(), getString(R.string.fm_new), getString(R.string.fm_name_hint),
                    null, getString(R.string.fm_create), text -> {
                        String name = text.trim();
                        if (!validName(name)) {
                            toast(getString(R.string.fm_invalid_name));
                            return false;
                        }
                        File target = new File(mCurrent, name);
                        if (target.exists()) {
                            toast(getString(R.string.fm_exists));
                            return false;
                        }
                        boolean ok;
                        try {
                            ok = folder ? target.mkdirs() : target.createNewFile();
                        } catch (IOException e) {
                            ok = false;
                        }
                        if (!ok) {
                            toast(getString(R.string.fm_failed, name));
                            return false;
                        }
                        reload();
                        return true;
                    });
        });
    }

    private void rename(final File file) {
        M3Dialog.input(requireContext(), getString(R.string.fm_rename), getString(R.string.fm_name_hint),
                file.getName(), getString(R.string.fm_rename), text -> {
                    String name = text.trim();
                    if (!validName(name)) {
                        toast(getString(R.string.fm_invalid_name));
                        return false;
                    }
                    File target = new File(file.getParentFile(), name);
                    if (target.exists()) {
                        toast(getString(R.string.fm_exists));
                        return false;
                    }
                    if (!file.renameTo(target)) {
                        toast(getString(R.string.fm_failed, name));
                        return false;
                    }
                    reload();
                    return true;
                });
    }

    private void delete(final File file) {
        M3Dialog.confirm(requireContext(), getString(R.string.fm_delete_title, file.getName()),
                getString(R.string.fm_delete_message), getString(R.string.fm_delete),
                () -> runBusy(() -> deleteRecursive(file), null));
    }

    private void toClipboard(File file, boolean cut) {
        mClipboard.clear();
        mClipboard.add(file);
        mClipboardCut = cut;
        toast(getString(cut ? R.string.fm_cut_done : R.string.fm_copied));
    }

    private void paste() {
        final List<File> sources = new ArrayList<>(mClipboard);
        final boolean cut = mClipboardCut;
        final File target = mCurrent;
        runBusy(() -> {
            for (File source : sources) {
                if (cut && target.equals(source.getParentFile())) continue;
                File dest = uniqueDest(target, source.getName());
                if (source.isDirectory()
                        && dest.getAbsolutePath().startsWith(source.getAbsolutePath() + File.separator)) {
                    throw new IOException("Cannot copy a folder into itself");
                }
                if (cut) {
                    if (!source.renameTo(dest)) {
                        copyRecursive(source, dest);
                        deleteRecursive(source);
                    }
                } else {
                    copyRecursive(source, dest);
                }
            }
        }, () -> {
            if (cut) mClipboard.clear();
        });
    }

    private void compress(final File file) {
        final File zip = uniqueDest(file.getParentFile(), file.getName() + ".zip");
        runBusy(() -> {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                addToZip(out, file, file.getName());
            }
        }, null);
    }

    private void importUri(@Nullable final Uri uri) {
        if (uri == null) return;
        final Context context = requireContext();
        final File target = mCurrent;
        runBusy(() -> {
            ContentResolver resolver = context.getContentResolver();
            String name = null;
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0) name = cursor.getString(column);
                }
            }
            if (name == null || !validName(name)) name = "imported_file";
            File dest = uniqueDest(target, name);
            try (InputStream in = resolver.openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new IOException("Cannot open the selected file");
                copyStream(in, out);
            }
        }, null);
    }

    private interface IoTask {
        void run() throws IOException;
    }

    private void runBusy(final IoTask task, @Nullable final Runnable onSuccess) {
        final Context context = requireContext();
        final Dialog busy = showBusy(context);
        MojoApplication.sExecutorService.execute(() -> {
            IOException error = null;
            try {
                task.run();
            } catch (IOException e) {
                error = e;
            } catch (RuntimeException e) {
                error = new IOException(e);
            }
            final IOException failure = error;
            Tools.runOnUiThread(() -> {
                if (busy.isShowing()) busy.dismiss();
                if (failure != null) {
                    Toast.makeText(context.getApplicationContext(),
                            context.getString(R.string.fm_failed, String.valueOf(failure.getMessage())),
                            Toast.LENGTH_LONG).show();
                }
                if (isAdded()) {
                    reload();
                    if (failure == null && onSuccess != null) onSuccess.run();
                }
            });
        });
    }

    private Dialog showBusy(Context context) {
        LinearLayout card = M3Dialog.card(context);
        TextView text = M3Dialog.label(context, context.getString(R.string.fm_working), 17, R.color.m3_text);
        text.setGravity(Gravity.CENTER);
        card.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.CENTER_HORIZONTAL;
        barParams.topMargin = M3Dialog.dp(context, 12);
        card.addView(new ProgressBar(context), barParams);
        Dialog dialog = M3Dialog.show(context, card, 0.35f);
        dialog.setCancelable(false);
        return dialog;
    }

    // --------------------------------------------------------- edit / info

    private void edit(final File file) {
        final Context context = requireContext();
        String content;
        try {
            content = readText(file);
        } catch (IOException e) {
            toast(getString(R.string.fm_failed, String.valueOf(e.getMessage())));
            return;
        }
        LinearLayout card = M3Dialog.card(context);
        card.addView(M3Dialog.label(context, file.getName(), 19, R.color.m3_text));

        final EditText editor = new EditText(context);
        editor.setText(content);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setTextColor(ContextCompat.getColor(context, R.color.m3_text));
        editor.setBackgroundResource(R.drawable.m3_input);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setPadding(M3Dialog.dp(context, 12), M3Dialog.dp(context, 10),
                M3Dialog.dp(context, 12), M3Dialog.dp(context, 10));
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, M3Dialog.dp(context, 200));
        editorParams.topMargin = M3Dialog.dp(context, 12);
        card.addView(editor, editorParams);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = M3Dialog.button(context, context.getString(android.R.string.cancel), false);
        TextView save = M3Dialog.button(context, context.getString(R.string.fm_save), true);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        saveParams.setMarginStart(M3Dialog.dp(context, 10));
        row.addView(cancel, cancelParams);
        row.addView(save, saveParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = M3Dialog.dp(context, 14);
        card.addView(row, rowParams);

        final Dialog dialog = M3Dialog.show(context, card, 0.8f);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                toast(getString(R.string.fm_failed, String.valueOf(e.getMessage())));
                return;
            }
            dialog.dismiss();
            reload();
        });
    }

    private TextView addProperty(Context context, LinearLayout card, int titleRes, String value) {
        TextView title = M3Dialog.label(context, context.getString(titleRes), 13, R.color.m3_text_dim);
        title.setPadding(0, M3Dialog.dp(context, 10), 0, 0);
        card.addView(title);
        TextView text = M3Dialog.label(context, value, 15, R.color.m3_text);
        card.addView(text);
        return text;
    }

    private void showProperties(final File file) {
        final Context context = requireContext();
        final boolean dir = file.isDirectory();
        LinearLayout card = M3Dialog.card(context);
        card.addView(M3Dialog.label(context, file.getName(), 19, R.color.m3_text));
        addProperty(context, card, R.string.fm_prop_path, file.getAbsolutePath());
        final TextView size = addProperty(context, card, R.string.fm_prop_size,
                dir ? context.getString(R.string.fm_calculating) : Formatter.formatFileSize(context, file.length()));
        addProperty(context, card, R.string.fm_prop_modified, DATE_FORMAT.format(new Date(file.lastModified())));
        final TextView contains = dir ? addProperty(context, card, R.string.fm_prop_contains,
                context.getString(R.string.fm_calculating)) : null;
        final Dialog dialog = M3Dialog.show(context, card, 0.55f);
        if (!dir) return;
        MojoApplication.sExecutorService.execute(() -> {
            long[] totals = new long[3];
            scan(file, totals);
            Tools.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                size.setText(Formatter.formatFileSize(context, totals[0]));
                contains.setText(context.getString(R.string.fm_counts, (int) totals[1], (int) totals[2]));
            });
        });
    }

    // -------------------------------------------------------------- helpers

    private static boolean validName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }

    private static boolean isEditable(File file) {
        if (!file.isFile() || file.length() > EDIT_LIMIT) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String extension = name.substring(dot + 1);
        for (String allowed : EDITABLE) {
            if (allowed.equals(extension)) return true;
        }
        return false;
    }

    private static File uniqueDest(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int counter = 1;
        while (dest.exists()) {
            dest = new File(dir, base + " (" + counter + ")" + extension);
            counter++;
        }
        return dest;
    }

    private static boolean isSymlink(File file) throws IOException {
        File parent = file.getParentFile();
        File canonical = parent == null ? file : new File(parent.getCanonicalFile(), file.getName());
        return !canonical.getCanonicalFile().equals(canonical.getAbsoluteFile());
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    private static void copyRecursive(File source, File dest) throws IOException {
        if (source.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException("Cannot create " + dest.getName());
            File[] children = source.listFiles();
            if (children == null) return;
            for (File child : children) copyRecursive(child, new File(dest, child.getName()));
        } else {
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(dest)) {
                copyStream(in, out);
            }
        }
    }

    private static void deleteRecursive(File file) throws IOException {
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        if (!file.delete() && file.exists()) throw new IOException("Cannot delete " + file.getName());
    }

    private static void addToZip(ZipOutputStream out, File file, String name) throws IOException {
        if (file.isDirectory()) {
            if (isSymlink(file)) return;
            File[] children = file.listFiles();
            if (children == null || children.length == 0) {
                out.putNextEntry(new ZipEntry(name + "/"));
                out.closeEntry();
                return;
            }
            for (File child : children) addToZip(out, child, name + "/" + child.getName());
        } else {
            out.putNextEntry(new ZipEntry(name));
            try (InputStream in = new FileInputStream(file)) {
                copyStream(in, out);
            }
            out.closeEntry();
        }
    }

    private static void scan(File file, long[] totals) {
        try {
            if (!file.isDirectory() || isSymlink(file)) return;
        } catch (IOException e) {
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                totals[1]++;
                scan(child, totals);
            } else {
                totals[2]++;
                totals[0] += child.length();
            }
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copyStream(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
