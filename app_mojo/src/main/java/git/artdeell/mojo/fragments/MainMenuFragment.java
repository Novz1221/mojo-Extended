package git.artdeell.mojo.fragments;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.CustomControlsActivity;
import git.artdeell.mojo.LauncherActivity;
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
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.instances.Instances;

/** Landscape home screen: menu tiles on the left, account and launch card on the right. */
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private ImageView mHead, mAddIcon, mVersionIcon;
    private TextView mAccountName, mAccountType, mVersionText;

    private final ExtraListener<Object> mRefreshListener = (key, value) -> {
        Tools.runOnUiThread(this::refresh);
        return false;
    };

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mHead = view.findViewById(R.id.home_account_head);
        mAddIcon = view.findViewById(R.id.home_add_icon);
        mVersionIcon = view.findViewById(R.id.home_version_icon);
        mAccountName = view.findViewById(R.id.home_account_name);
        mAccountType = view.findViewById(R.id.home_account_type);
        mVersionText = view.findViewById(R.id.home_version_text);

        view.findViewById(R.id.tile_settings).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), SettingsHostFragment.class,
                        LauncherActivity.SETTING_FRAGMENT_TAG, null));
        view.findViewById(R.id.tile_download).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), DownloadFragment.class, DownloadFragment.TAG, null));
        view.findViewById(R.id.tile_versions).setOnClickListener(v -> openFiles());
        View controls = view.findViewById(R.id.tile_controls);
        controls.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        controls.setOnLongClickListener(v -> {
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });

        view.findViewById(R.id.home_share_log).setOnClickListener(v -> Tools.shareLog(requireContext()));

        view.findViewById(R.id.home_account_area).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), AccountListFragment.class, AccountListFragment.TAG, null));
        view.findViewById(R.id.home_version_row).setOnClickListener(v -> openVersions());
        view.findViewById(R.id.play_button).setOnClickListener(v ->
                ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));

        ExtraCore.addExtraListener(ExtraConstants.ACCOUNTS_CHANGED, mRefreshListener);
        ExtraCore.addExtraListener(ExtraConstants.REFRESH_VERSION_SPINNER, mRefreshListener);

        Fluid.pressAll(view);
        android.view.ViewGroup root = (android.view.ViewGroup) view;
        android.view.ViewGroup left = (android.view.ViewGroup) root.getChildAt(0);
        android.view.ViewGroup right = (android.view.ViewGroup) root.getChildAt(2);
        android.view.ViewGroup panel = (android.view.ViewGroup) left.getChildAt(1);
        Fluid.stagger(left.getChildAt(0), panel.getChildAt(0), panel.getChildAt(1), left.getChildAt(2),
                right.getChildAt(0), right.getChildAt(1));
    }

    private void openFiles() {
        Tools.swapFragment(requireActivity(), FileManagerFragment.class, FileManagerFragment.TAG, null);
    }

    private void openVersions() {
        Tools.swapFragment(requireActivity(), VersionListFragment.class, VersionListFragment.TAG, null);
    }

    @Override
    public void onResume() {
        super.onResume();
        ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
        refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.ACCOUNTS_CHANGED, mRefreshListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.REFRESH_VERSION_SPINNER, mRefreshListener);
    }

    private void refresh() {
        MojoApplication.sExecutorService.execute(() -> {
            Account account = null;
            Instance instance = null;
            try {
                account = Accounts.getCurrent();
            } catch (Exception ignored) {
                // Treated as no account
            }
            try {
                instance = Instances.loadSelectedInstance();
            } catch (Exception ignored) {
                // Treated as no instance
            }
            final Account finalAccount = account;
            final Instance finalInstance = instance;
            Tools.runOnUiThread(() -> apply(finalAccount, finalInstance));
        });
    }

    private void apply(@Nullable Account account, @Nullable Instance instance) {
        if (!isAdded() || getView() == null) return;
        if (account == null) {
            mHead.setVisibility(View.GONE);
            mAddIcon.setVisibility(View.VISIBLE);
            mAccountName.setText(R.string.m3_add_account);
            mAccountType.setVisibility(View.GONE);
        } else {
            Bitmap head = Wardrobe.head(requireContext(), account, 128);
            mHead.setImageBitmap(head);
            mHead.setVisibility(View.VISIBLE);
            mAddIcon.setVisibility(View.GONE);
            mAccountName.setText(account.username);
            mAccountType.setText(Wardrobe.typeLabel(account));
            mAccountType.setVisibility(View.VISIBLE);
        }

        if (instance == null) {
            mVersionIcon.setImageResource(R.drawable.ic_grass_block);
            mVersionText.setText(R.string.m3_no_versions);
            return;
        }
        mVersionIcon.setImageDrawable(InstanceIconProvider.fetchIcon(getResources(), instance));
        mVersionText.setText(VersionListFragment.displayName(requireContext(), instance));
    }
}
