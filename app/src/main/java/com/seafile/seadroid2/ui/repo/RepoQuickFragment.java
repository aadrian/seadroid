package com.seafile.seadroid2.ui.repo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.work.WorkInfo;

import com.blankj.utilcode.util.CollectionUtils;
import com.blankj.utilcode.util.TimeUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.Maps;
import com.seafile.seadroid2.R;
import com.seafile.seadroid2.SeafException;
import com.seafile.seadroid2.account.Account;
import com.seafile.seadroid2.account.SupportAccountManager;
import com.seafile.seadroid2.bottomsheetmenu.BottomSheetHelper;
import com.seafile.seadroid2.bottomsheetmenu.BottomSheetMenuFragment;
import com.seafile.seadroid2.config.Constants;
import com.seafile.seadroid2.context.CopyMoveContext;
import com.seafile.seadroid2.context.NavContext;
import com.seafile.seadroid2.databinding.LayoutFrameSwipeRvBinding;
import com.seafile.seadroid2.framework.data.db.entities.DirentModel;
import com.seafile.seadroid2.framework.data.db.entities.EncKeyCacheEntity;
import com.seafile.seadroid2.framework.data.db.entities.FileTransferEntity;
import com.seafile.seadroid2.framework.data.db.entities.RepoModel;
import com.seafile.seadroid2.framework.data.model.BaseModel;
import com.seafile.seadroid2.framework.datastore.DataManager;
import com.seafile.seadroid2.framework.util.Objs;
import com.seafile.seadroid2.framework.util.SLogs;
import com.seafile.seadroid2.framework.util.Utils;
import com.seafile.seadroid2.framework.worker.BackgroundJobManagerImpl;
import com.seafile.seadroid2.framework.worker.DownloadWorker;
import com.seafile.seadroid2.framework.worker.SupportWorkManager;
import com.seafile.seadroid2.framework.worker.TransferEvent;
import com.seafile.seadroid2.framework.worker.TransferWorker;
import com.seafile.seadroid2.framework.worker.UploadFileManuallyWorker;
import com.seafile.seadroid2.framework.worker.UploadFolderFileAutomaticallyWorker;
import com.seafile.seadroid2.framework.worker.UploadMediaFileAutomaticallyWorker;
import com.seafile.seadroid2.ui.WidgetUtils;
import com.seafile.seadroid2.ui.base.fragment.BaseFragmentWithVM;
import com.seafile.seadroid2.ui.dialog_fragment.CopyMoveDialogFragment;
import com.seafile.seadroid2.ui.dialog_fragment.DeleteFileDialogFragment;
import com.seafile.seadroid2.ui.dialog_fragment.DeleteRepoDialogFragment;
import com.seafile.seadroid2.ui.dialog_fragment.PasswordDialogFragment;
import com.seafile.seadroid2.ui.dialog_fragment.RenameDialogFragment;
import com.seafile.seadroid2.ui.dialog_fragment.listener.OnRefreshDataListener;
import com.seafile.seadroid2.ui.dialog_fragment.listener.OnResultListener;
import com.seafile.seadroid2.ui.file.FileActivity;
import com.seafile.seadroid2.ui.main.MainViewModel;
import com.seafile.seadroid2.ui.markdown.MarkdownActivity;
import com.seafile.seadroid2.ui.media.image_preview.ImagePreviewActivity;
import com.seafile.seadroid2.ui.media.player.exoplayer.CustomExoVideoPlayerActivity;
import com.seafile.seadroid2.ui.selector.ObjSelectorActivity;
import com.seafile.seadroid2.ui.webview.SeaWebViewActivity;
import com.seafile.seadroid2.view.TipsViews;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.functions.Consumer;

public class RepoQuickFragment extends BaseFragmentWithVM<RepoViewModel> {
    private static final String KEY_REPO_SCROLL_POSITION = "repo_scroll_position";
    private static final String KEY_REPO_LIST = "key_in_repo_list";

    private final HashMap<String, Long> mRefreshStatusExpireTimeMap = new HashMap<>();
    private boolean isFirstLoadData = true;
    private LayoutFrameSwipeRvBinding binding;
    private RepoQuickAdapter adapter;
    private LinearLayoutManager rvManager;

    private MainViewModel mainViewModel;
    private final Map<String, ScrollState> scrollPositions = Maps.newHashMap();
    private AppCompatActivity activity;
    private ActionMode actionMode;

    public static RepoQuickFragment newInstance() {
        Bundle args = new Bundle();
        RepoQuickFragment fragment = new RepoQuickFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private NavContext getNavContext() {
        return mainViewModel.getNavContext();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        activity = (AppCompatActivity) context;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = LayoutFrameSwipeRvBinding.inflate(inflater, container, false);
        binding.swipeRefreshLayout.setOnRefreshListener(() -> loadData(true));
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        init();

        initAdapter();

        initViewModel();

        initWorkerListener();
    }

    @Override
    public void onResume() {
        super.onResume();
        d("load data：onResume");
        if (isFirstLoadData) {
            isFirstLoadData = false;
            d("load data：isFirstLoadData");
            loadData(true);
        } else {
            if (isForce()) {
                loadData(true);
            }
        }
    }

    private void init() {
        rvManager = (LinearLayoutManager) binding.rv.getLayoutManager();
    }

    private void initAdapter() {
        adapter = new RepoQuickAdapter();

        adapter.setOnItemClickListener((baseQuickAdapter, view, i) -> {
            if (adapter.getActionMode()) {
                //toggle
                toggleAdapterItemSelectedOnLongClick(i);

                //update bar title
                updateContextualActionBar();
                return;
            }

            BaseModel baseModel = adapter.getItems().get(i);
            if (baseModel instanceof RepoModel) {
                RepoModel repoModel = (RepoModel) baseModel;
                if (repoModel.encrypted) {
                    decrypt(repoModel);
                } else {
                    navTo(baseModel);
                }
                return;
            }

            navTo(baseModel);

        });

        adapter.setOnItemLongClickListener((baseQuickAdapter, view, i) -> {
            if (getNavContext().isInRepo()) {

                //return
                if (adapter.getActionMode()) {
                    return true;
                }

                //toggle
                toggleAdapterItemSelectedOnLongClick(i);

                //start action mode
                startContextualActionMode();

                //It's actually updating the title of the ActionBar
                updateContextualActionBar();

                return true;
            } else {
                return false;
            }
        });

        adapter.addOnItemChildClickListener(R.id.expandable_toggle_button, (baseQuickAdapter, view, i) -> {

            //when ActionMode is On, return
            if (adapter.getActionMode()) {
                return;
            }

            //open bottom sheet dialog
            if (getNavContext().isInRepo()) {
                DirentModel direntModel = (DirentModel) adapter.getItems().get(i);
                if (direntModel.isDir()) {
                    showDirNewBottomSheet(direntModel);
                } else {
                    showFileBottomSheet(direntModel);
                }
            } else {
                showRepoBottomSheet((RepoModel) adapter.getItems().get(i));
            }
        });

        binding.rv.setAdapter(createMuiltAdapterHelper(adapter).getAdapter());
    }

    private void initViewModel() {
        getViewModel().getRefreshLiveData().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                binding.swipeRefreshLayout.setRefreshing(aBoolean);
            }
        });

        getViewModel().getSeafExceptionLiveData().observe(getViewLifecycleOwner(), this::showAdapterTipView);

        getViewModel().getStarLiveData().observe(getViewLifecycleOwner(), aBoolean -> {
            if (aBoolean) {
                loadData(true);
            }

            mainViewModel.getOnForceRefreshStarredListLiveData().setValue(true);
        });

        getViewModel().getObjsListLiveData().observe(getViewLifecycleOwner(), repoModels -> {

            //notify navContext changed
            mainViewModel.getOnNavContextChangeListenerLiveData().setValue(true);

            notifyDataChanged(repoModels);

            restoreScrollPosition();
        });

        mainViewModel.getOnResortListLiveData().observe(getViewLifecycleOwner(), a -> {
            SLogs.d("resort: " + a);
            loadData();
        });

        mainViewModel.getOnForceRefreshRepoListLiveData().observe(getViewLifecycleOwner(), aBoolean -> {
            loadData(true);
        });

    }

    private void initWorkerListener() {

        //UploadFileManuallyWorker
        SupportWorkManager.getWorkManager()
                .getWorkInfoByIdLiveData(UploadFileManuallyWorker.UID)
                .observe(getViewLifecycleOwner(), new Observer<WorkInfo>() {
                    @Override
                    public void onChanged(WorkInfo workInfo) {
                        checkWorkInfo(workInfo);
                    }
                });

        //UploadFolderFileAutomaticallyWorker
        SupportWorkManager.getWorkManager()
                .getWorkInfoByIdLiveData(UploadFolderFileAutomaticallyWorker.UID)
                .observe(getViewLifecycleOwner(), new Observer<WorkInfo>() {
                    @Override
                    public void onChanged(WorkInfo workInfo) {
                        checkWorkInfo(workInfo);
                    }
                });

        //UploadMediaFileAutomaticallyWorker
        SupportWorkManager.getWorkManager()
                .getWorkInfoByIdLiveData(UploadMediaFileAutomaticallyWorker.UID)
                .observe(getViewLifecycleOwner(), new Observer<WorkInfo>() {
                    @Override
                    public void onChanged(WorkInfo workInfo) {
                        checkWorkInfo(workInfo);
                    }
                });

        //DownloadWorker
        SupportWorkManager.getWorkManager()
                .getWorkInfoByIdLiveData(DownloadWorker.UID)
                .observe(getViewLifecycleOwner(), new Observer<WorkInfo>() {
                    @Override
                    public void onChanged(WorkInfo workInfo) {
                        checkWorkInfo(workInfo);
                    }
                });
    }

    private void checkWorkInfo(WorkInfo workInfo) {
        if (workInfo != null && workInfo.getState().isFinished()) {
            String transferState = workInfo.getOutputData().getString(TransferWorker.KEY_DATA_EVENT);
            if (TransferEvent.EVENT_TRANSFERRED_WITH_DATA.equals(transferState)) {
                loadData(true);
            }
        }
    }

    public void clearExpireRefreshMap() {
        mRefreshStatusExpireTimeMap.clear();
    }

    private boolean isForce() {
        long now = TimeUtils.getNowMills();
        Long expire;
        if (getNavContext().isInRepoList()) {
            expire = mRefreshStatusExpireTimeMap.get(KEY_REPO_LIST);
        } else {
            String k = getNavContext().getRepoModel().repo_id + getNavContext().getNavPath();
            expire = mRefreshStatusExpireTimeMap.get(k);
        }
        return expire == null || now > expire;
    }

    public void loadData() {
        loadData(false);
    }

    public void loadData(boolean forceRefresh) {
        if (forceRefresh) {
            long now = TimeUtils.getNowMills();
            now += 1000 * 60 * 10;//10min
            if (getNavContext().isInRepoList()) {
                mRefreshStatusExpireTimeMap.put(KEY_REPO_LIST, now);
            } else {
                String k = getNavContext().getRepoModel().repo_id + getNavContext().getNavPath();
                mRefreshStatusExpireTimeMap.put(k, now);
            }

        }

        //
        saveScrollPosition();

        getViewModel().loadData(getNavContext(), forceRefresh);
    }


    private void notifyDataChanged(List<BaseModel> repoModels) {
        if (CollectionUtils.isEmpty(repoModels)) {
            showEmptyTip();
        } else {
            adapter.notifyDataChanged(repoModels);
        }
    }

    private void showEmptyTip() {
        showAdapterTipView(R.string.no_repo);
    }

    private void showAdapterTipView(SeafException seafException) {

        ToastUtils.showLong(seafException.getMessage());

        int strInt = !getNavContext().isInRepo() ? R.string.error_when_load_repos : R.string.error_when_load_dirents;
        showAdapterTipView(strInt);
    }

    private void showAdapterTipView(int textRes) {
        adapter.submitList(null);
        TextView tipView = TipsViews.getTipTextView(requireContext());
        tipView.setText(textRes);
        tipView.setOnClickListener(v -> loadData(true));
        adapter.setStateView(tipView);
        adapter.setStateViewEnable(true);
    }

    private void navTo(BaseModel model) {
        //save
        saveScrollPosition();

        if (getNavContext().isInRepoList()) {
            getNavContext().push(model);
            loadData(isForce());
        } else if (model instanceof DirentModel) {
            DirentModel direntModel = (DirentModel) model;
            if (direntModel.isDir()) {
                getNavContext().push(direntModel);
                loadData(isForce());
            } else {
                open(direntModel);
            }
        }
    }

    public boolean backTo() {
        if (getNavContext().isInRepo()) {
            if (adapter.getActionMode()) {
                adapter.setActionModeOn(false);
            } else {
                //
                removeScrollPosition();
                //
                getNavContext().pop();
                getViewModel().loadData(getNavContext(), false);

                //notify navContext changed
                mainViewModel.getOnNavContextChangeListenerLiveData().setValue(true);
            }
            return true;
        }
        return false;
    }

    private void decrypt(RepoModel repoModel) {
        getViewModel().getEncCacheDB(repoModel.repo_id, new Consumer<EncKeyCacheEntity>() {
            @Override
            public void accept(EncKeyCacheEntity encKeyCacheEntity) throws Exception {
                if (encKeyCacheEntity == null) {
                    showPasswordDialog(repoModel);
                    return;
                }

                long now = TimeUtils.getNowMills();
                if (encKeyCacheEntity.expire_time_long == 0) {
                    showPasswordDialog(repoModel);
                } else if (now < encKeyCacheEntity.expire_time_long) {
                    navTo(repoModel);
                } else {
                    showPasswordDialog(repoModel);
                }
            }
        });
    }

    private void showPasswordDialog(RepoModel repoModel) {
        PasswordDialogFragment dialogFragment = PasswordDialogFragment.newInstance();
        dialogFragment.initData(repoModel.repo_id, repoModel.repo_name);
        dialogFragment.setResultListener(new OnResultListener<RepoModel>() {
            @Override
            public void onResultData(RepoModel uRepoModel) {
                navTo(uRepoModel);
            }
        });

        dialogFragment.show(getChildFragmentManager(), PasswordDialogFragment.class.getSimpleName());
    }

    private void saveScrollPosition() {
        View vi = binding.rv.getChildAt(0);
        int top = (vi == null) ? 0 : vi.getTop();
        final int index = rvManager.findFirstVisibleItemPosition();
        final ScrollState state = new ScrollState(index, top);

        removeScrollPosition();

        if (!getNavContext().isInRepo()) {
            scrollPositions.put(KEY_REPO_SCROLL_POSITION, state);
        } else {
            String k = getNavContext().getNavPath();
            scrollPositions.put(k, state);
        }
    }

    private void removeScrollPosition() {
        if (!getNavContext().isInRepo()) {
            scrollPositions.remove(KEY_REPO_SCROLL_POSITION);
        } else {
            String k = getNavContext().getNavPath();
            scrollPositions.remove(k);
        }
    }

    private void restoreScrollPosition() {
        ScrollState state;
        if (!getNavContext().isInRepo()) {
            state = scrollPositions.get(KEY_REPO_SCROLL_POSITION);
        } else {
            state = scrollPositions.get(getNavContext().getNavPath());
        }

        if (state != null) {
            rvManager.scrollToPositionWithOffset(state.index, state.top);
        } else {
            rvManager.scrollToPosition(0);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();

        if (actionMode != null) {
            actionMode.finish();
        }
    }

    private void toggleAdapterItemSelectedOnLongClick(int i) {
        //action mode on
        if (!adapter.getActionMode()) {
            adapter.setActionModeOn(true);
        }

        DirentModel direntModel = (DirentModel) adapter.getItems().get(i);
        direntModel.is_selected = !direntModel.is_selected;
        adapter.getItems().set(i, direntModel);
        adapter.notifyItemChanged(i);
    }

    public void closeActionMode() {
        if (adapter.getActionMode()) {
            adapter.setActionModeOn(false);

            actionMode.finish();
            actionMode = null;
        }
    }

    public void startContextualActionMode() {
        if (!getNavContext().isInRepo()) return;

        if (actionMode == null) {
            // start the actionMode
            actionMode = activity.startSupportActionMode(new ActionModeCallback());
        }
    }

    /**
     * update state of contextual action bar (CAB)
     */
    public void updateContextualActionBar() {
        if (!getNavContext().isInRepo()) return;

        if (actionMode == null) {
            // there are some selected items, start the actionMode
            actionMode = activity.startSupportActionMode(new ActionModeCallback());
        } else {
            int count = adapter.getSelectedList().size();
            actionMode.setTitle(getResources().getQuantityString(R.plurals.transfer_list_items_selected, count, count));
        }

    }

    /**
     * Represents a contextual mode of the user interface.
     * Action modes can be used to provide alternative interaction modes and replace parts of the normal UI until finished.
     * A Callback configures and handles events raised by a user's interaction with an action mode.
     */
    private final class ActionModeCallback implements ActionMode.Callback {
        private boolean allItemsSelected;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Inflate the menu for the contextual action bar (CAB)
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.repos_fragment_menu, menu);
            if (adapter == null) return true;

            // to hidden  "r" permissions  files or folder
            if (!getNavContext().hasWritePermissionWithRepo()) {
                menu.findItem(R.id.action_mode_delete).setVisible(false);
                menu.findItem(R.id.action_mode_move).setVisible(false);
            }
            return true;
        }

        @SuppressLint("NewApi")
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            /*
             * The ActionBarPolicy determines how many action button to place in the ActionBar
             * and the default amount is 2.
             */
            menu.findItem(R.id.action_mode_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.findItem(R.id.action_mode_copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.findItem(R.id.action_mode_select_all).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            // Here you can perform updates to the contextual action bar (CAB) due to
            // an invalidate() request
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            //check data
            List<DirentModel> selectedDirents = adapter.getSelectedList();
            if (CollectionUtils.isEmpty(selectedDirents) || !getNavContext().isInRepo()) {
                if (item.getItemId() != R.id.action_mode_select_all) {
                    ToastUtils.showLong(R.string.action_mode_no_items_selected);
                    return true;
                }
            }
            int itemId = item.getItemId();
            if (itemId == R.id.action_mode_select_all) {
                adapter.setItemSelected(!allItemsSelected);
                updateContextualActionBar();

                allItemsSelected = !allItemsSelected;
            } else if (itemId == R.id.action_mode_delete) {
                deleteDirents(selectedDirents);
            } else if (itemId == R.id.action_mode_copy) {
                DirentModel dirent = selectedDirents.get(0);
                copyFiles(dirent.repo_id, dirent.repo_name, dirent.parent_dir, selectedDirents);
            } else if (itemId == R.id.action_mode_move) {
                DirentModel dirent = selectedDirents.get(0);
                moveFiles(dirent.repo_id, dirent.repo_name, dirent.parent_dir, selectedDirents);
            } else if (itemId == R.id.action_mode_download) {
                downloadDirents(selectedDirents);
            } else {
                return false;
            }

            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (adapter == null) return;

            adapter.setActionModeOn(false);

            // Here you can make any necessary updates to the activity when
            // the contextual action bar (CAB) is removed. By default, selected items are deselected/unchecked.
            if (actionMode != null) {
                actionMode = null;
            }
        }

    }

    public void showRepoBottomSheet(RepoModel model) {

        int rid = R.menu.bottom_sheet_op_repo;
        BottomSheetMenuFragment.Builder builder = BottomSheetHelper.buildSheet(getActivity(), rid, menuItem -> {
            if (menuItem.getItemId() == R.id.star || menuItem.getItemId() == R.id.unstar) {
                starOrNot(model);
            } else if (menuItem.getItemId() == R.id.rename_repo) {
                rename(model.repo_id, null, model.repo_name, "repo");
            } else if (menuItem.getItemId() == R.id.delete_repo) {
                deleteRepo(model.repo_id);
            }
        });

        if (model.starred) {
            builder.removeMenu(R.id.star);
        } else {
            builder.removeMenu(R.id.unstar);
        }

        if (!model.hasWritePermission()) {
            builder.removeMenu(R.id.delete_repo);
            builder.removeMenu(R.id.rename_repo);
        }
        builder.show(getChildFragmentManager());
    }

    public void showDirNewBottomSheet(DirentModel dirent) {
        int rid = R.menu.bottom_sheet_op_dir;
        BottomSheetMenuFragment.Builder builder = BottomSheetHelper.buildSheet(getActivity(), rid, menuItem -> {
            int itemId = menuItem.getItemId();

            if (itemId == R.id.star || itemId == R.id.unstar) {
                starOrNot(dirent);
            } else if (itemId == R.id.share) {
                showShareDialog(dirent);
            } else if (itemId == R.id.delete) {
                deleteDirent(dirent);
            } else if (itemId == R.id.copy) {
                copyFiles(dirent.repo_id, dirent.repo_name, dirent.parent_dir, CollectionUtils.newArrayList(dirent));
            } else if (itemId == R.id.move) {
                moveFiles(dirent.repo_id, dirent.repo_name, dirent.parent_dir, CollectionUtils.newArrayList(dirent));
            } else if (itemId == R.id.rename) {
                rename(dirent.repo_id, dirent.full_path, dirent.name, "dir");
            } else if (itemId == R.id.download) {
                BackgroundJobManagerImpl.getInstance().scheduleOneTimeFilesDownloadScanWorker(new String[]{dirent.uid});
            }
        });

        if (dirent.starred) {
            builder.removeMenu(R.id.star);
        } else {
            builder.removeMenu(R.id.unstar);
        }

        if (!dirent.hasWritePermission()) {
            builder.removeMenu(R.id.rename);
            builder.removeMenu(R.id.delete);
            builder.removeMenu(R.id.move);
        }

        String repoId = getNavContext().getRepoModel().repo_id;
        getViewModel().getRepoModelFromDB(repoId, new Consumer<RepoModel>() {
            @Override
            public void accept(RepoModel repoModel) throws Exception {
                if (repoModel != null && repoModel.encrypted) {
                    builder.removeMenu(R.id.share);
                }
                builder.show(getChildFragmentManager());
            }
        });
    }

    private void showFileBottomSheet(DirentModel dirent) {
        int rid = R.menu.bottom_sheet_op_file;
        BottomSheetMenuFragment.Builder builder = BottomSheetHelper.buildSheet(getActivity(), rid, menuItem -> {
            int itemId = menuItem.getItemId();

            if (itemId == R.id.share) {
                showShareDialog(dirent);
            } else if (itemId == R.id.open) {
                openWith(dirent);
            } else if (itemId == R.id.delete) {
                deleteDirent(dirent);
            } else if (itemId == R.id.copy) {
                copyFiles(dirent.repo_id, dirent.repo_name, dirent.parent_dir, CollectionUtils.newArrayList(dirent));
            } else if (itemId == R.id.move) {
                moveFiles(dirent.repo_id, dirent.repo_name, dirent.parent_dir, CollectionUtils.newArrayList(dirent));
            } else if (itemId == R.id.rename) {
                rename(dirent.repo_id, dirent.full_path, dirent.name, "file");
            } else if (itemId == R.id.update) {
                addUploadTask(dirent, true);
            } else if (itemId == R.id.download) {
                download(dirent);
            } else if (itemId == R.id.export) {
                exportFile(dirent);
            } else if (itemId == R.id.star || itemId == R.id.unstar) {
                starOrNot(dirent);
            }
        });

        if (dirent.starred) {
            builder.removeMenu(R.id.star);
        } else {
            builder.removeMenu(R.id.unstar);
        }


        if (!dirent.hasWritePermission()) {
            builder.removeMenu(R.id.rename);
            builder.removeMenu(R.id.delete);
            builder.removeMenu(R.id.move);
            builder.removeMenu(R.id.update);
        }

//        if (!Utils.isTextMimeType(dirent.name)) {
//            builder.removeMenu(R.id.open);
//        }

        if (getNavContext().getRepoModel().encrypted) {
            builder.removeMenu(R.id.share);
        }

        Account account = SupportAccountManager.getInstance().getCurrentAccount();

        File f = DataManager.getLocalRepoFile(account, getNavContext().getRepoModel().repo_id, getNavContext().getRepoModel().repo_name, dirent.full_path);
        if (!f.exists()) {
            builder.removeMenu(R.id.update);
        }
        builder.show(getChildFragmentManager());
    }


    /************ Files ************/

    @OptIn(markerClass = UnstableApi.class)
    private void open(DirentModel dirent) {
        String fileName = dirent.name;
        String filePath = dirent.full_path;

        RepoModel repoModel = getNavContext().getRepoModel();

        // Encrypted repo does not support gallery,
        // because pic thumbnail under encrypted repo was not supported at the server side
        if (Utils.isViewableImage(fileName) && !repoModel.encrypted) {

            Intent getIntent = ImagePreviewActivity.startThisFromRepo(requireContext(), dirent);
            imagePreviewActivityLauncher.launch(getIntent);

            return;
        }

        if (fileName.endsWith(Constants.Format.DOT_SDOC)) {
            SeaWebViewActivity.openSdoc(getContext(), repoModel.repo_name, repoModel.repo_id, dirent.parent_dir + dirent.name);
            return;
        }

        if (Utils.isVideoFile(fileName)) {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
            builder.setItems(R.array.video_download_array, (dialog, which) -> {
                if (which == 0) {
                    CustomExoVideoPlayerActivity.startThis(getContext(), fileName, repoModel.repo_id, filePath);
                } else if (which == 1) {
                    Intent intent = FileActivity.start(requireContext(), dirent, "video_download");
                    fileActivityLauncher.launch(intent);
                }
            }).show();
            return;
        }

        if (Utils.isTextMimeType(fileName)) {
            File local = getLocalDestinationFile(dirent.repo_id, dirent.repo_name, dirent.full_path);

            //check need to update
            if (local.exists() && local.length() == dirent.size) {
                MarkdownActivity.start(requireContext(), local.getAbsolutePath(), dirent.repo_id, dirent.full_path);
            } else {
                Intent intent = FileActivity.start(requireContext(), dirent, "open_text_mime");
                fileActivityLauncher.launch(intent);
            }

            return;
        }

        //Open with another app
        openWith(dirent);
    }

    private File getLocalDestinationFile(String repoId, String repoName, String fullPathInRepo) {
        Account account = SupportAccountManager.getInstance().getCurrentAccount();

        return DataManager.getLocalRepoFile(account, repoId, repoName, fullPathInRepo);
    }

    private void openWith(DirentModel direntModel) {
        File local = getLocalDestinationFile(direntModel.repo_id, direntModel.repo_name, direntModel.full_path);
        if (local.exists() && local.length() == direntModel.size) {
            WidgetUtils.openWith(requireActivity(), local);
        } else {
            Intent intent = FileActivity.start(requireActivity(), direntModel, "open_with");
            fileActivityLauncher.launch(intent);
        }
    }

    public void download(DirentModel direntModel) {
//        Intent intent = FileActivity.start(requireContext(), direntModel, "no_action");
//        fileActivityLauncher.launch(intent);
        BackgroundJobManagerImpl.getInstance().scheduleOneTimeFilesDownloadScanWorker(new String[]{direntModel.uid});
    }

    public void rename(String repoID, String curPath, String curName, String type) {
        RenameDialogFragment dialogFragment = RenameDialogFragment.newInstance();
        dialogFragment.initData(curName, curPath, repoID, type);
        dialogFragment.setRefreshListener(new OnRefreshDataListener() {
            @Override
            public void onActionStatus(boolean isDone) {
                if (isDone) {
                    mainViewModel.getOnForceRefreshRepoListLiveData().setValue(true);
                }
            }
        });
        dialogFragment.show(getChildFragmentManager(), RenameDialogFragment.class.getSimpleName());
    }

    public void deleteRepo(String repoID) {
        DeleteRepoDialogFragment dialogFragment = DeleteRepoDialogFragment.newInstance(repoID);
        dialogFragment.setRefreshListener(isDone -> {
            if (isDone) {
                ToastUtils.showLong(R.string.delete_successful);

                loadData(true);
            }
        });
        dialogFragment.show(getChildFragmentManager(), DeleteRepoDialogFragment.class.getSimpleName());
    }

    public void deleteDirent(DirentModel dirent) {
        deleteDirents(CollectionUtils.newArrayList(dirent));
    }

    public void deleteDirents(List<DirentModel> dirents) {
        DeleteFileDialogFragment dialogFragment = DeleteFileDialogFragment.newInstance();
        dialogFragment.initData(dirents);
        dialogFragment.setRefreshListener(new OnRefreshDataListener() {
            @Override
            public void onActionStatus(boolean isDone) {
                if (isDone) {
                    ToastUtils.showLong(R.string.delete_successful);

                    closeActionMode();

                    loadData(true);
                }
            }
        });
        dialogFragment.show(getChildFragmentManager(), DeleteFileDialogFragment.class.getSimpleName());
    }

    /**
     * Share a file. Generating a file share link and send the link or file to someone
     * through some app.
     */
    public void showShareDialog(DirentModel direntModel) {
        MaterialAlertDialogBuilder mBuilder = new MaterialAlertDialogBuilder(requireContext());

        boolean inChina = Utils.isInChina();
        String[] strings;
        //if user  in China ，system  add  WeChat  share
        if (inChina) {
            strings = getResources().getStringArray(R.array.file_action_share_array_zh);
        } else {
            strings = getResources().getStringArray(R.array.file_action_share_array);
        }

        mBuilder.setItems(strings, (dialog, which) -> {
            if (!inChina) {
                which++;
            }
            if (which == 0) {
                shareFile(direntModel);
            } else if (which == 1) {
                Objs.showCreateShareLinkDialog(requireContext(), getChildFragmentManager(), direntModel, false);
            } else if (which == 2) {
                Objs.showCreateShareLinkDialog(requireContext(), getChildFragmentManager(), direntModel, true);
            }
        }).show();
    }

    /**
     * Copy multiple files
     */
    public void copyFiles(String srcRepoId, String srcRepoName, String srcDir, List<DirentModel> dirents) {
        chooseCopyMoveDestForMultiFiles(srcRepoId, srcRepoName, srcDir, dirents, CopyMoveContext.OP.COPY);
    }


    /**
     * Move multiple files
     */
    public void moveFiles(String srcRepoId, String srcRepoName, String srcDir, List<DirentModel> dirents) {
        chooseCopyMoveDestForMultiFiles(srcRepoId, srcRepoName, srcDir, dirents, CopyMoveContext.OP.MOVE);
    }

    /**
     * Move multiple files
     */
    public void downloadDirents(List<DirentModel> dirents) {
        String[] uids = dirents.stream().map(m -> m.uid).toArray(String[]::new);
        BackgroundJobManagerImpl.getInstance().scheduleOneTimeFilesDownloadScanWorker(uids);

        closeActionMode();
    }

    private CopyMoveContext copyMoveContext = null;

    /**
     * Choose copy/move destination for multiple files
     */
    private void chooseCopyMoveDestForMultiFiles(String repoID, String repoName,
                                                 String dirPath, List<DirentModel> dirents,
                                                 CopyMoveContext.OP op) {
        copyMoveContext = new CopyMoveContext(repoID, repoName, dirPath, dirents, op);

        Intent intent = new Intent(requireContext(), ObjSelectorActivity.class);
        intent.putExtra(ObjSelectorActivity.DATA_ACCOUNT, SupportAccountManager.getInstance().getCurrentAccount());
        copyMoveLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> copyMoveLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
        @Override
        public void onActivityResult(ActivityResult o) {
            if (o.getResultCode() != Activity.RESULT_OK || o.getData() == null) {
                return;
            }

            String dstRepoId = o.getData().getStringExtra(ObjSelectorActivity.DATA_REPO_ID);
            String dstDir = o.getData().getStringExtra(ObjSelectorActivity.DATA_DIR);
            String disRepoName = o.getData().getStringExtra(ObjSelectorActivity.DATA_REPO_NAME);

            copyMoveContext.setDest(dstRepoId, dstDir, disRepoName);

            doCopyMove();
        }
    });

    private void doCopyMove() {
        if (copyMoveContext == null) {
            return;
        }

        if (!copyMoveContext.checkCopyMoveToSubfolder()) {
            ToastUtils.showLong(copyMoveContext.isCopy()
                    ? R.string.cannot_copy_folder_to_subfolder
                    : R.string.cannot_move_folder_to_subfolder);
            return;
        }

        CopyMoveDialogFragment dialogFragment = CopyMoveDialogFragment.newInstance();
        dialogFragment.initData(copyMoveContext);
        dialogFragment.setRefreshListener(new OnRefreshDataListener() {
            @Override
            public void onActionStatus(boolean isDone) {
                if (isDone) {
                    ToastUtils.showLong(copyMoveContext.isCopy() ? R.string.copied_successfully : R.string.moved_successfully);

                    closeActionMode();

                    loadData(true);
                }
            }
        });
        dialogFragment.show(getChildFragmentManager(), CopyMoveDialogFragment.class.getSimpleName());
    }

    private void starOrNot(BaseModel model) {
        if (model instanceof RepoModel) {
            RepoModel repoModel = (RepoModel) model;
            if (repoModel.starred) {
                getViewModel().unStar(repoModel.repo_id, "/");
            } else {
                getViewModel().star(repoModel.repo_id, "/");
            }
        } else if (model instanceof DirentModel) {
            DirentModel direntModel = (DirentModel) model;
            String path = Utils.pathJoin(getNavContext().getNavPath(), direntModel.name);
            if (direntModel.starred) {
                getViewModel().unStar(direntModel.repo_id, path);
            } else {
                getViewModel().star(direntModel.repo_id, path);
            }
        }
    }

    private final ActivityResultLauncher<Intent> imagePreviewActivityLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
        @Override
        public void onActivityResult(ActivityResult o) {
            if (o.getResultCode() != Activity.RESULT_OK) {
                return;
            }

            loadData(true);
        }
    });

    private final ActivityResultLauncher<Intent> fileActivityLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
        @Override
        public void onActivityResult(ActivityResult o) {
            if (o.getResultCode() != Activity.RESULT_OK) {
                loadData(true);
                return;
            }

            String action = o.getData().getStringExtra("action");
            String repoId = o.getData().getStringExtra("repo_id");
            String targetFile = o.getData().getStringExtra("target_file");
            String localFullPath = o.getData().getStringExtra("destination_path");
            boolean isUpdateWhenFileExists = o.getData().getBooleanExtra("is_update", false);

            if (TextUtils.isEmpty(localFullPath)) {
                return;
            }

            if (isUpdateWhenFileExists) {
                ToastUtils.showLong(R.string.download_finished);
            }

            loadData(true);

            File destinationFile = new File(localFullPath);
            if ("export".equals(action)) {

                Objs.exportFile(RepoQuickFragment.this, destinationFile);
            } else if ("share".equals(action)) {

                Objs.shareFileToWeChat(RepoQuickFragment.this, destinationFile);
            } else if ("video_download".equals(action)) {

            } else if ("open_with".equals(action)) {


                WidgetUtils.openWith(requireActivity(), destinationFile);
            } else if ("open_text_mime".equals(action)) {

                MarkdownActivity.start(requireActivity(), localFullPath, repoId, targetFile);
            }
        }
    });

    private void exportFile(DirentModel dirent) {
        File destinationFile = getLocalDestinationFile(dirent.repo_id, dirent.repo_name, dirent.full_path);

        if (!destinationFile.exists()) {
            Intent intent = FileActivity.start(requireContext(), dirent, "export");
            fileActivityLauncher.launch(intent);
        } else {
            Objs.exportFile(this, destinationFile);
        }
    }

    private void shareFile(DirentModel dirent) {
        if (dirent.isDir()) {
            Objs.shareDirToWeChat(this, dirent.repo_id, dirent.full_path);
            return;
        }

        File destinationFile = getLocalDestinationFile(dirent.repo_id, dirent.repo_name, dirent.full_path);
        if (destinationFile.exists()) {
            Objs.shareFileToWeChat(this, destinationFile);
        } else {
            Intent intent = FileActivity.start(requireContext(), dirent, "share");
            fileActivityLauncher.launch(intent);
        }
    }


    ////////////////add task/////////////
    private void addUploadTask(DirentModel dirent, boolean isUpdate) {
        RepoModel targetedModel = getNavContext().getRepoModel();
        String targetDir = getNavContext().getNavPath();
        File localFilePath = getLocalDestinationFile(dirent.repo_id, dirent.repo_name, dirent.full_path);

        Account account = SupportAccountManager.getInstance().getCurrentAccount();
        mainViewModel.addUploadTask(account, targetedModel, targetDir, localFilePath.getAbsolutePath(), isUpdate, new Consumer<FileTransferEntity>() {
            @Override
            public void accept(FileTransferEntity transferEntity) throws Exception {
                ToastUtils.showLong(R.string.added_to_upload_tasks);
            }
        });
    }
}
