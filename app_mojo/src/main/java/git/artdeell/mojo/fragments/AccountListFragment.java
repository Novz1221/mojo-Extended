package git.artdeell.mojo.fragments;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.authenticator.accounts.Wardrobe;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.utils.Fluid;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.extra.ExtraListener;
import git.artdeell.mojo.utils.M3Dialog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Account list with the add-account dialog, wardrobe, copy UUID and delete actions. */
public class AccountListFragment extends Fragment {
    public static final String TAG = "AccountListFragment";
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]*$");

    private final List<Account> mAccounts = new ArrayList<>();
    private int mSelected = 0;
    private boolean mSkipItemAnim = false;
    private RecyclerView mList;
    private TextView mEmpty;
    private ImageView mPreview;
    private AccountAdapter mAdapter;

    private Account mWardrobeAccount;
    private Bitmap mWardrobePending;
    private ImageView mWardrobePreview;

    private final ExtraListener<Boolean> mChangedListener = (key, value) -> {
        Tools.runOnUiThread(this::reload);
        return false;
    };

    private final ActivityResultLauncher<String> mSkinPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onSkinPicked);

    public AccountListFragment() {
        super(R.layout.fragment_account_list);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ((TextView) view.findViewById(R.id.header_title)).setText(R.string.m3_account_list);
        view.findViewById(R.id.header_back).setOnClickListener(v -> requireActivity().onBackPressed());
        view.findViewById(R.id.header_home).setOnClickListener(v -> Tools.backToMainMenu(requireActivity()));

        mPreview = view.findViewById(R.id.acc_preview);
        mEmpty = view.findViewById(R.id.acc_empty);
        mList = view.findViewById(R.id.acc_list);
        mAdapter = new AccountAdapter();
        mList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mList.setAdapter(mAdapter);
        view.findViewById(R.id.acc_add).setOnClickListener(v -> showAddDialog());

        ExtraCore.addExtraListener(ExtraConstants.ACCOUNTS_CHANGED, mChangedListener);
        mPreview.setImageBitmap(Wardrobe.body(requireContext(), null));
        reload();

        Fluid.pressAll(view);
        ViewGroup root = (ViewGroup) view;
        ViewGroup body = (ViewGroup) root.getChildAt(1);
        Fluid.stagger(root.getChildAt(0), body.getChildAt(0), body.getChildAt(1));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.ACCOUNTS_CHANGED, mChangedListener);
    }

    private void reload() {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                Accounts accounts = Accounts.load();
                if (!accounts.accounts.isEmpty() && Accounts.getCurrent() == null) {
                    Accounts.setCurrent(accounts.accounts.get(0));
                }
                Tools.runOnUiThread(() -> apply(accounts));
            } catch (IOException e) {
                Tools.showErrorRemote(e);
            }
        });
    }

    private void apply(Accounts accounts) {
        if (!isAdded() || getView() == null) return;
        mAccounts.clear();
        mAccounts.addAll(accounts.accounts);
        mSelected = Math.max(0, Math.min(accounts.selectionIndex, mAccounts.size() - 1));
        mAdapter.notifyDataSetChanged();
        mEmpty.setVisibility(mAccounts.isEmpty() ? View.VISIBLE : View.GONE);
        updatePreview();
    }

    private void updatePreview() {
        Account account = mAccounts.isEmpty() ? null : mAccounts.get(mSelected);
        mPreview.setImageBitmap(Wardrobe.body(requireContext(), account));
    }

    private void select(int position) {
        if (position < 0 || position >= mAccounts.size()) return;
        mSelected = position;
        Accounts.setCurrent(mAccounts.get(position));
        mSkipItemAnim = true;
        mAdapter.notifyDataSetChanged();
        mList.post(() -> mSkipItemAnim = false);
        updatePreview();
        Fluid.pop(mPreview);
    }

    private void showAddDialog() {
        Context context = requireContext();
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_m3_add_account, null);
        Dialog dialog = M3Dialog.show(context, content, 0.6f);
        content.findViewById(R.id.add_microsoft).setOnClickListener(v -> {
            dialog.dismiss();
            startLogin(MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG);
        });
        content.findViewById(R.id.add_auth).setOnClickListener(v -> {
            dialog.dismiss();
            startLogin(ElyByLoginFragment.class, ElyByLoginFragment.TAG);
        });
        content.findViewById(R.id.add_offline).setOnClickListener(v -> {
            dialog.dismiss();
            showOfflineDialog();
        });
        content.findViewById(R.id.add_close).setOnClickListener(v -> dialog.dismiss());
    }

    private void startLogin(Class<? extends Fragment> fragmentClass, String tag) {
        if (!ProgressLayout.ensureNoOngoingTasks(requireContext(), ProgressLayout.AUTHENTICATE)) return;
        Tools.swapFragment(requireActivity(), fragmentClass, tag, null);
    }

    private void showOfflineDialog() {
        Context context = requireContext();
        M3Dialog.input(context, context.getString(R.string.m3_offline_title),
                context.getString(R.string.m3_username_hint), null,
                context.getString(android.R.string.ok), text -> {
                    String name = text.trim();
                    if (name.length() < 3 || name.length() > 16 || !USERNAME.matcher(name).matches()) {
                        Tools.dialog(context, context.getString(R.string.local_login_bad_username_title),
                                context.getString(R.string.local_login_bad_username_text));
                        return false;
                    }
                    ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{name, ""});
                    return true;
                });
    }

    private void showMenu(View anchor, int position) {
        Context context = requireContext();
        View content = LayoutInflater.from(context).inflate(R.layout.popup_m3_account_menu, null);
        Dialog dialog = M3Dialog.show(context, content, 0.35f);
        Account account = mAccounts.get(position);
        content.findViewById(R.id.pm_wardrobe).setOnClickListener(v -> {
            dialog.dismiss();
            showWardrobe(account);
        });
        content.findViewById(R.id.pm_copy).setOnClickListener(v -> {
            dialog.dismiss();
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("UUID", Wardrobe.displayUuid(account)));
                Toast.makeText(context, R.string.m3_uuid_copied, Toast.LENGTH_SHORT).show();
            }
        });
        content.findViewById(R.id.pm_delete).setOnClickListener(v -> {
            dialog.dismiss();
            M3Dialog.confirm(context, context.getString(R.string.global_delete),
                    context.getString(R.string.warning_remove_account),
                    context.getString(R.string.global_delete), () -> {
                        Accounts.delete(account);
                        ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
                        reload();
                    });
        });
    }

    private void showWardrobe(Account account) {
        Context context = requireContext();
        mWardrobeAccount = account;
        mWardrobePending = null;
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_m3_wardrobe, null);
        mWardrobePreview = content.findViewById(R.id.wd_preview);
        mWardrobePreview.setImageBitmap(Wardrobe.body(context, account));
        TextView confirm = content.findViewById(R.id.wd_confirm);
        confirm.setAlpha(0.5f);
        Dialog dialog = M3Dialog.show(context, content, 0.62f);
        content.findViewById(R.id.wd_change).setOnClickListener(v -> mSkinPicker.launch("image/*"));
        content.findViewById(R.id.wd_cancel).setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            if (mWardrobePending == null) return;
            try {
                Wardrobe.saveSkin(context, mWardrobeAccount, mWardrobePending);
                Toast.makeText(context, R.string.m3_skin_saved, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                updatePreview();
                ExtraCore.setValue(ExtraConstants.ACCOUNTS_CHANGED, true);
            } catch (IOException e) {
                Tools.showError(context, e);
            }
        });
        dialog.setOnDismissListener(d -> mWardrobePreview = null);
    }

    private void onSkinPicked(@Nullable Uri uri) {
        if (uri == null || mWardrobePreview == null) return;
        Context context = requireContext();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = in == null ? null : BitmapFactory.decodeStream(in);
            boolean validShape = bitmap != null && bitmap.getWidth() >= 64
                    && (bitmap.getWidth() == bitmap.getHeight() || bitmap.getWidth() == bitmap.getHeight() * 2);
            if (!validShape) {
                Toast.makeText(context, R.string.m3_skin_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            mWardrobePending = bitmap;
            mWardrobePreview.setImageBitmap(git.artdeell.mojo.authenticator.accounts.SkinPreview.body(bitmap, 20));
            View root = mWardrobePreview.getRootView();
            View confirm = root.findViewById(R.id.wd_confirm);
            if (confirm != null) confirm.setAlpha(1f);
        } catch (IOException e) {
            Tools.showError(context, e);
        }
    }

    private void refreshAccount(Account account) {
        Context context = requireContext();
        MojoApplication.sExecutorService.execute(() -> {
            account.updateSkinFace();
            ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
            Tools.runOnUiThread(() -> {
                Toast.makeText(context, R.string.m3_refreshed, Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    private class AccountAdapter extends RecyclerView.Adapter<AccountAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_account_m3, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Account account = mAccounts.get(position);
            holder.name.setText(account.username);
            holder.type.setText(Wardrobe.typeLabel(account));
            holder.radio.setChecked(position == mSelected);
            holder.head.setImageBitmap(Wardrobe.head(holder.head.getContext(), account, 96));
            holder.itemView.setOnClickListener(v -> select(holder.getAdapterPosition()));
            holder.refresh.setOnClickListener(v -> refreshAccount(account));
            holder.more.setOnClickListener(v -> showMenu(v, holder.getAdapterPosition()));
            Fluid.pressAll(holder.itemView);
            if (!mSkipItemAnim) Fluid.itemIn(holder.itemView, position);
        }

        @Override
        public int getItemCount() {
            return mAccounts.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final RadioButton radio;
            final ImageView head;
            final TextView name, type;
            final ImageButton refresh, more;

            Holder(@NonNull View itemView) {
                super(itemView);
                radio = itemView.findViewById(R.id.acc_radio);
                head = itemView.findViewById(R.id.acc_head);
                name = itemView.findViewById(R.id.acc_name);
                type = itemView.findViewById(R.id.acc_type);
                refresh = itemView.findViewById(R.id.acc_refresh);
                more = itemView.findViewById(R.id.acc_more);
            }
        }
    }
}
