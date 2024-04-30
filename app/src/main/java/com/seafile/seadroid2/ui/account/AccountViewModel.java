package com.seafile.seadroid2.ui.account;

import android.os.Build;
import android.text.TextUtils;
import android.util.Pair;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;

import androidx.lifecycle.MutableLiveData;

import com.blankj.utilcode.util.AppUtils;
import com.seafile.seadroid2.SeafException;
import com.seafile.seadroid2.account.Account;
import com.seafile.seadroid2.account.AccountInfo;
import com.seafile.seadroid2.account.SupportAccountManager;
import com.seafile.seadroid2.framework.data.model.TokenModel;
import com.seafile.seadroid2.framework.datastore.DataStoreManager;
import com.seafile.seadroid2.framework.datastore.sp.AlbumBackupManager;
import com.seafile.seadroid2.framework.datastore.sp.FolderBackupManager;
import com.seafile.seadroid2.framework.datastore.sp.GestureLockManager;
import com.seafile.seadroid2.framework.http.IO;
import com.seafile.seadroid2.framework.util.AccountUtils;
import com.seafile.seadroid2.framework.util.DeviceIdManager;
import com.seafile.seadroid2.framework.util.SLogs;
import com.seafile.seadroid2.framework.worker.BackgroundJobManagerImpl;
import com.seafile.seadroid2.ui.base.viewmodel.BaseViewModel;
import com.seafile.seadroid2.ui.camera_upload.CameraUploadManager;
import com.seafile.seadroid2.ui.dialog_fragment.SignOutDialogFragment;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.functions.Consumer;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.HttpException;
import retrofit2.Response;

public class AccountViewModel extends BaseViewModel {
    private MutableLiveData<Account> mAccountLiveData = new MutableLiveData<>();
    private MutableLiveData<AccountInfo> mAccountInfoLiveData = new MutableLiveData<>();
    private final MutableLiveData<Pair<Account, SeafException>> AccountSeafExceptionLiveData = new MutableLiveData<>();

    public MutableLiveData<Pair<Account, SeafException>> getAccountSeafExceptionLiveData() {
        return AccountSeafExceptionLiveData;
    }

    public MutableLiveData<Account> getAccountLiveData() {
        return mAccountLiveData;
    }

    public MutableLiveData<AccountInfo> getAccountInfoLiveData() {
        return mAccountInfoLiveData;
    }

    public void loadAccountInfo(Account loginAccount, String authToken) {
        getRefreshLiveData().setValue(true);

        loginAccount.token = authToken;
        Single<AccountInfo> single = IO.getInstanceByAccount(loginAccount).execute(AccountService.class).getAccountInfo();
        addSingleDisposable(single, new Consumer<AccountInfo>() {
            @Override
            public void accept(AccountInfo accountInfo) throws Exception {

                loginAccount.login_time = System.currentTimeMillis();
                loginAccount.setEmail(accountInfo.getEmail());
                loginAccount.setName(accountInfo.getName());
                loginAccount.setAvatarUrl(accountInfo.getAvatarUrl());

                getAccountLiveData().setValue(loginAccount);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) throws Exception {
                getRefreshLiveData().setValue(false);

                SeafException seafException = getExceptionByThrowable(throwable);
                getAccountSeafExceptionLiveData().setValue(new Pair<>(loginAccount, seafException));
            }
        });
    }

    public void login(Account tempAccount, String pwd, String token, boolean isRememberDevice) {
        getRefreshLiveData().setValue(true);

        Single<Account> single = Single.create(new SingleOnSubscribe<Account>() {
            @Override
            public void subscribe(SingleEmitter<Account> emitter) throws Exception {

                //the SYNC way

                Call<TokenModel> call = getLoginCall(tempAccount, pwd, token, isRememberDevice);
                Response<TokenModel> response = call.execute();
                if (!response.isSuccessful()) {
                    HttpException httpException = new HttpException(response);
                    throw getExceptionByThrowable(httpException);
                }

                String s2fa = response.headers().get("x-seafile-s2fa");
                if (!TextUtils.isEmpty(s2fa)) {
                    tempAccount.sessionKey = s2fa;
                }

                TokenModel tokenModel = response.body();
                if (tokenModel != null) {
                    tempAccount.token = tokenModel.token;
                }

                //note this:
                //the token to be updated here, because the user is logged in successfully
                //but don't use "IO.getLoggedInAccountInstance();"
                //because not set up an Android Account yet.
                IO.updateInstanceByAccount(tempAccount);

                //
                retrofit2.Response<AccountInfo> accountInfoResponse = IO
                        .getInstanceByAccount(tempAccount) //Still use it that way
                        .execute(AccountService.class)
                        .getAccountInfoCall()
                        .execute();

                if (!accountInfoResponse.isSuccessful()) {
                    HttpException httpException = new HttpException(response);
                    throw getExceptionByThrowable(httpException);
                }

                AccountInfo accountInfo = accountInfoResponse.body();
                if (accountInfo == null) {
                    throw SeafException.networkException;
                }

                // Update the account info
                tempAccount.setName(accountInfo.getName());
                tempAccount.setEmail(accountInfo.getEmail());
                tempAccount.setAvatarUrl(accountInfo.getAvatarUrl());

                emitter.onSuccess(tempAccount);
            }
        });

        addSingleDisposable(single, new Consumer<Account>() {
            @Override
            public void accept(Account account) throws Exception {
                getRefreshLiveData().setValue(false);
                getAccountLiveData().setValue(account);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) throws Exception {
                getRefreshLiveData().setValue(false);

                SeafException seafException = getExceptionByThrowable(throwable);
                getAccountSeafExceptionLiveData().setValue(new Pair<>(tempAccount, seafException));
            }
        });
    }

    private Call<TokenModel> getLoginCall(Account tempAccount, String pwd, String token, boolean isRememberDevice) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer your_access_token");

        if (!TextUtils.isEmpty(token)) {
            headers.put("X-Seafile-OTP", token);
        }

        if (!TextUtils.isEmpty(tempAccount.sessionKey)) {
            headers.put("X-SEAFILE-S2FA", tempAccount.sessionKey);
        }

        if (isRememberDevice) {
            headers.put("X-SEAFILE-2FA-TRUST-DEVICE", String.valueOf(1));
        }

        Map<String, String> body = new HashMap<>();
        body.put("username", tempAccount.email);
        body.put("password", pwd);

        String deviceId = DeviceIdManager.getInstance().getOrSet();
        String appVersion = AppUtils.getAppVersionName();

        body.put("platform", "android");
        body.put("device_id", deviceId);
        body.put("device_name", Build.MODEL);
        body.put("client_version", appVersion);
        body.put("platform_version", Build.VERSION.RELEASE);

        Map<String, RequestBody> requestBody = generateRequestBody(body);

        return IO.getInstanceByAccount(tempAccount).execute(AccountService.class).login(headers, requestBody);
    }

    /**
     * @see SignOutDialogFragment#onPositiveClick()
     */
    public void deleteAccount(Account account) {
        Account curAccount = SupportAccountManager.getInstance().getCurrentAccount();

        //The user who is currently logged in is deleted
        if (curAccount != null && curAccount.equals(account)) {
            AccountUtils.logout(account);
        }

        //delete local account
        SupportAccountManager.getInstance().removeAccount(account.getAndroidAccount(), null, null);

    }

    public void switchAccount(Account account) {
        if (account == null) {
            return;
        }

        AccountUtils.switchAccount(account);
    }
}
