package git.artdeell.mojo.fragments;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.pickafile.FileListView;
import com.kdt.pickafile.FileSelectedListener;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.utils.Fluid;
import git.artdeell.mojo.utils.M3Dialog;

import java.io.File;
public class FileSelectorFragment extends Fragment {
    public static final String TAG = "FileSelectorFragment";
    public static final String BUNDLE_SELECT_FOLDER = "select_folder";
    public static final String BUNDLE_SELECT_FILE = "select_file";
    public static final String BUNDLE_SHOW_FILE = "show_file";
    public static final String BUNDLE_SHOW_FOLDER = "show_folder";
    public static final String BUNDLE_ROOT_PATH = "root_path";

    private TextView mSelectFolderButton, mCreateFolderButton;
    private FileListView mFileListView;
    private TextView mFilePathView;

    private boolean mSelectFolder = true;
    private boolean mShowFiles = true;
    private boolean mShowFolders = true;
    private String mRootPath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? Tools.DIR_GAME_NEW
            : Environment.getExternalStorageDirectory().getAbsolutePath();


    public FileSelectorFragment(){
        super(R.layout.fragment_file_selector);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);
        parseBundle();
        if(!mSelectFolder) mSelectFolderButton.setVisibility(View.GONE);
        else mSelectFolderButton.setVisibility(View.VISIBLE);

        mFileListView.setShowFiles(mShowFiles);
        mFileListView.setShowFolders(mShowFolders);
        mFileListView.lockPathAt(new File(mRootPath));
        mFileListView.setDialogTitleListener((title)->mFilePathView.setText(removeLockPath(title)));
        mFileListView.refreshPath();
        Fluid.pressAll(view);

        mCreateFolderButton.setOnClickListener(v -> M3Dialog.input(requireContext(),
                getString(R.string.folder_dialog_insert_name), getString(R.string.m3_folder_name_hint), null,
                getString(R.string.folder_dialog_create), text -> {
                    String name = text.trim();
                    if (name.isEmpty()) return false;
                    File folder = new File(mFileListView.getFullPath(), name);
                    if (folder.mkdir()) mFileListView.listFileAt(folder);
                    else mFileListView.refreshPath();
                    return true;
                }));

        mSelectFolderButton.setOnClickListener(v -> {
            ExtraCore.setValue(ExtraConstants.FILE_SELECTOR, removeLockPath(mFileListView.getFullPath().getAbsolutePath()));
            Tools.removeCurrentFragment(requireActivity());
        });

        mFileListView.setFileSelectedListener(new FileSelectedListener() {
            @Override
            public void onFileSelected(File file, String path) {
                ExtraCore.setValue(ExtraConstants.FILE_SELECTOR, removeLockPath(path));
                Tools.removeCurrentFragment(requireActivity());
            }
        });
    }

    private String removeLockPath(String path){
        return path.replace(mRootPath, ".");
    }

    private void parseBundle(){
        Bundle bundle = getArguments();
        if(bundle == null) return;
        mSelectFolder = bundle.getBoolean(BUNDLE_SELECT_FOLDER, mSelectFolder);
        mShowFiles = bundle.getBoolean(BUNDLE_SHOW_FILE, mShowFiles);
        mShowFolders = bundle.getBoolean(BUNDLE_SHOW_FOLDER, mShowFolders);
        mRootPath = bundle.getString(BUNDLE_ROOT_PATH, mRootPath);
    }

    private void bindViews(@NonNull View view){
        view.findViewById(R.id.header_back).setOnClickListener(v -> requireActivity().onBackPressed());
        view.findViewById(R.id.header_home).setOnClickListener(v -> Tools.backToMainMenu(requireActivity()));
        mSelectFolderButton = view.findViewById(R.id.file_selector_select_folder);
        mCreateFolderButton = view.findViewById(R.id.file_selector_create_folder);
        mFileListView = view.findViewById(R.id.file_selector);
        mFilePathView = view.findViewById(R.id.header_title);
    }
}
