/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Changes from Qualcomm Innovation Center are provided under the following license:
 *
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.android.systemui.qs.tiles.dialog;

import static android.telephony.ims.feature.ImsFeature.FEATURE_MMTEL;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM;

import static com.android.systemui.Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA;
import static com.android.systemui.qs.tiles.dialog.InternetDialogController.MAX_WIFI_ENTRY_COUNT;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.Utils;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.accessibility.floatingmenu.AnnotationLinkSpan;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Dialog for showing mobile network, connected Wi-Fi network and Wi-Fi networks.
 */
@SysUISingleton
public class InternetDialog extends SystemUIDialog implements
        InternetDialogController.InternetDialogCallback, Window.Callback {
    private static final String TAG = "InternetDialog";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final long PROGRESS_DELAY_MS = 1500L;
    static final int MAX_NETWORK_COUNT = 4;

    private final Handler mHandler;
    private final Executor mBackgroundExecutor;

    @VisibleForTesting
    protected InternetAdapter mAdapter;
    @VisibleForTesting
    protected View mDialogView;
    @VisibleForTesting
    protected boolean mCanConfigWifi;

    private InternetDialogFactory mInternetDialogFactory;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    @Nullable
    private AlertDialog mAlertDialog;
    private UiEventLogger mUiEventLogger;
    private Context mContext;
    private InternetDialogController mInternetDialogController;
    private TextView mInternetDialogTitle;
    private TextView mInternetDialogSubTitle;
    private View mDivider;
    private ProgressBar mProgressBar;
    private LinearLayout mInternetDialogLayout;
    private LinearLayout mConnectedWifListLayout;
    private LinearLayout mMobileNetworkLayout;
    private LinearLayout mTurnWifiOnLayout;
    private LinearLayout mEthernetLayout;
    private TextView mWifiToggleTitleText;
    private LinearLayout mWifiScanNotifyLayout;
    private TextView mWifiScanNotifyText;
    private LinearLayout mSeeAllLayout;
    private RecyclerView mWifiRecyclerView;
    private ImageView mConnectedWifiIcon;
    private ImageView mWifiSettingsIcon;
    private TextView mConnectedWifiTitleText;
    private TextView mConnectedWifiSummaryText;
    private ImageView mSignalIcon;
    private TextView mMobileTitleText;
    private TextView mMobileSummaryText;
    private TextView mAirplaneModeSummaryText;
    private Switch mMobileDataToggle;
    private View mMobileToggleDivider;
    private Switch mWiFiToggle;
    private Button mDoneButton;
    private Button mAirplaneModeButton;
    private Drawable mBackgroundOn;
    private KeyguardStateController mKeyguard;
    @Nullable
    private Drawable mBackgroundOff = null;
    private int mDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private boolean mCanConfigMobileData;
    private boolean mCanChangeWifiState;

    // Wi-Fi entries
    private int mWifiNetworkHeight;
    @Nullable
    @VisibleForTesting
    protected WifiEntry mConnectedWifiEntry;
    @VisibleForTesting
    protected int mWifiEntriesCount;
    @VisibleForTesting
    protected boolean mHasMoreWifiEntries;

    // Wi-Fi scanning progress bar
    protected boolean mIsProgressBarVisible;
    protected boolean mIsSearchingHidden;
    protected final Runnable mHideProgressBarRunnable = () -> {
        setProgressBarVisible(false);
    };
    protected Runnable mHideSearchingRunnable = () -> {
        mIsSearchingHidden = true;
        mInternetDialogSubTitle.setText(getSubtitleText());
    };

    private boolean mIsCallIdle = true;
    private boolean mIsImsRegisteredOverCiwlan = false;

    public InternetDialog(Context context, InternetDialogFactory internetDialogFactory,
            InternetDialogController internetDialogController, boolean canConfigMobileData,
            boolean canConfigWifi, boolean aboveStatusBar, UiEventLogger uiEventLogger,
            @Main Handler handler, @Background Executor executor,
            KeyguardStateController keyguardStateController) {
        super(context);
        if (DEBUG) {
            Log.d(TAG, "Init InternetDialog");
        }

        // Save the context that is wrapped with our theme.
        mContext = getContext();
        mHandler = handler;
        mBackgroundExecutor = executor;
        mInternetDialogFactory = internetDialogFactory;
        mInternetDialogController = internetDialogController;
        mSubscriptionManager = mInternetDialogController.getSubscriptionManager();
        mDefaultDataSubId = mInternetDialogController.getDefaultDataSubscriptionId();
        mTelephonyManager = mInternetDialogController.getTelephonyManager();
        mCanConfigMobileData = canConfigMobileData;
        mCanConfigWifi = canConfigWifi;
        mCanChangeWifiState = WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(context);
        mKeyguard = keyguardStateController;

        mUiEventLogger = uiEventLogger;
        mAdapter = new InternetAdapter(mInternetDialogController);
        if (!aboveStatusBar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }
        mUiEventLogger.log(InternetDialogEvent.INTERNET_DIALOG_SHOW);
        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.internet_connectivity_dialog,
                null);
        mDialogView.setAccessibilityPaneTitle(
                mContext.getText(R.string.accessibility_desc_quick_settings));
        final Window window = getWindow();
        window.setContentView(mDialogView);

        window.setWindowAnimations(R.style.Animation_InternetDialog);

        mWifiNetworkHeight = mContext.getResources()
                .getDimensionPixelSize(R.dimen.internet_dialog_wifi_network_height);

        mInternetDialogLayout = mDialogView.requireViewById(R.id.internet_connectivity_dialog);
        mInternetDialogTitle = mDialogView.requireViewById(R.id.internet_dialog_title);
        mInternetDialogSubTitle = mDialogView.requireViewById(R.id.internet_dialog_subtitle);
        mDivider = mDialogView.requireViewById(R.id.divider);
        mProgressBar = mDialogView.requireViewById(R.id.wifi_searching_progress);
        mEthernetLayout = mDialogView.requireViewById(R.id.ethernet_layout);
        mMobileNetworkLayout = mDialogView.requireViewById(R.id.mobile_network_layout);
        mTurnWifiOnLayout = mDialogView.requireViewById(R.id.turn_on_wifi_layout);
        mWifiToggleTitleText = mDialogView.requireViewById(R.id.wifi_toggle_title);
        mWifiScanNotifyLayout = mDialogView.requireViewById(R.id.wifi_scan_notify_layout);
        mWifiScanNotifyText = mDialogView.requireViewById(R.id.wifi_scan_notify_text);
        mConnectedWifListLayout = mDialogView.requireViewById(R.id.wifi_connected_layout);
        mConnectedWifiIcon = mDialogView.requireViewById(R.id.wifi_connected_icon);
        mConnectedWifiTitleText = mDialogView.requireViewById(R.id.wifi_connected_title);
        mConnectedWifiSummaryText = mDialogView.requireViewById(R.id.wifi_connected_summary);
        mWifiSettingsIcon = mDialogView.requireViewById(R.id.wifi_settings_icon);
        mWifiRecyclerView = mDialogView.requireViewById(R.id.wifi_list_layout);
        mSeeAllLayout = mDialogView.requireViewById(R.id.see_all_layout);
        mDoneButton = mDialogView.requireViewById(R.id.done_button);
        mAirplaneModeButton = mDialogView.requireViewById(R.id.apm_button);
        mSignalIcon = mDialogView.requireViewById(R.id.signal_icon);
        mMobileTitleText = mDialogView.requireViewById(R.id.mobile_title);
        mMobileSummaryText = mDialogView.requireViewById(R.id.mobile_summary);
        mAirplaneModeSummaryText = mDialogView.requireViewById(R.id.airplane_mode_summary);
        mMobileToggleDivider = mDialogView.requireViewById(R.id.mobile_toggle_divider);
        mMobileDataToggle = mDialogView.requireViewById(R.id.mobile_toggle);
        mWiFiToggle = mDialogView.requireViewById(R.id.wifi_toggle);
        mBackgroundOn = mContext.getDrawable(R.drawable.settingslib_switch_bar_bg_on);
        mInternetDialogTitle.setText(getDialogTitleText());
        mInternetDialogTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        TypedArray typedArray = mContext.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        try {
            mBackgroundOff = typedArray.getDrawable(0 /* index */);
        } finally {
            typedArray.recycle();
        }

        setOnClickListener();
        mTurnWifiOnLayout.setBackground(null);
        mAirplaneModeButton.setVisibility(
                mInternetDialogController.isAirplaneModeEnabled() ? View.VISIBLE : View.GONE);
        mWifiRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mWifiRecyclerView.setAdapter(mAdapter);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (DEBUG) {
            Log.d(TAG, "onStart");
        }
        mInternetDialogController.onStart(this, mCanConfigWifi);
        if (!mCanConfigWifi) {
            hideWifiViews();
        }
    }

    @VisibleForTesting
    void hideWifiViews() {
        setProgressBarVisible(false);
        mTurnWifiOnLayout.setVisibility(View.GONE);
        mConnectedWifListLayout.setVisibility(View.GONE);
        mWifiRecyclerView.setVisibility(View.GONE);
        mSeeAllLayout.setVisibility(View.GONE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (DEBUG) {
            Log.d(TAG, "onStop");
        }
        mHandler.removeCallbacks(mHideProgressBarRunnable);
        mHandler.removeCallbacks(mHideSearchingRunnable);
        mMobileNetworkLayout.setOnClickListener(null);
        mMobileDataToggle.setOnCheckedChangeListener(null);
        mConnectedWifListLayout.setOnClickListener(null);
        mSeeAllLayout.setOnClickListener(null);
        mWiFiToggle.setOnCheckedChangeListener(null);
        mDoneButton.setOnClickListener(null);
        mAirplaneModeButton.setOnClickListener(null);
        mInternetDialogController.onStop();
        mInternetDialogFactory.destroyDialog();
    }

    @Override
    public void dismissDialog() {
        if (DEBUG) {
            Log.d(TAG, "dismissDialog");
        }
        mInternetDialogFactory.destroyDialog();
        dismiss();
    }

    /**
     * Update the internet dialog when receiving the callback.
     *
     * @param shouldUpdateMobileNetwork {@code true} for update the mobile network layout,
     * otherwise {@code false}.
     */
    void updateDialog(boolean shouldUpdateMobileNetwork) {
        if (DEBUG) {
            Log.d(TAG, "updateDialog");
        }
        mInternetDialogTitle.setText(getDialogTitleText());
        mInternetDialogSubTitle.setText(getSubtitleText());
        mAirplaneModeButton.setVisibility(
                mInternetDialogController.isAirplaneModeEnabled() ? View.VISIBLE : View.GONE);

        updateEthernet();
        if (shouldUpdateMobileNetwork) {
            setMobileDataLayout(mInternetDialogController.activeNetworkIsCellular(),
                    mInternetDialogController.isCarrierNetworkActive());
        }

        if (!mCanConfigWifi) {
            return;
        }

        showProgressBar();
        final boolean isDeviceLocked = mInternetDialogController.isDeviceLocked();
        final boolean isWifiEnabled = mInternetDialogController.isWifiEnabled();
        final boolean isWifiScanEnabled = mInternetDialogController.isWifiScanEnabled();
        updateWifiToggle(isWifiEnabled, isDeviceLocked);
        updateConnectedWifi(isWifiEnabled, isDeviceLocked);
        updateWifiListAndSeeAll(isWifiEnabled, isDeviceLocked);
        updateWifiScanNotify(isWifiEnabled, isWifiScanEnabled, isDeviceLocked);
    }

    private void setOnClickListener() {
        mMobileNetworkLayout.setOnClickListener(v -> {
            if (mInternetDialogController.isMobileDataEnabled()
                    && !mInternetDialogController.isDeviceLocked()) {
                if (!mInternetDialogController.activeNetworkIsCellular()) {
                    mInternetDialogController.connectCarrierNetwork();
                }
            }
        });
        mMobileDataToggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (!isChecked && shouldShowMobileDialog()) {
                        showTurnOffMobileDialog();
                    } else if (!shouldShowMobileDialog()) {
                        mInternetDialogController.setMobileDataEnabled(mContext, mDefaultDataSubId,
                                isChecked, false);
                    }
                });
        mConnectedWifListLayout.setOnClickListener(this::onClickConnectedWifi);
        mSeeAllLayout.setOnClickListener(this::onClickSeeMoreButton);
        mWiFiToggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (mInternetDialogController.isWifiEnabled() == isChecked) return;
                    mInternetDialogController.setWifiEnabled(isChecked);
                });
        mDoneButton.setOnClickListener(v -> dismiss());
        mAirplaneModeButton.setOnClickListener(v -> {
            mInternetDialogController.setAirplaneModeDisabled();
        });
    }

    @MainThread
    private void updateEthernet() {
        mEthernetLayout.setVisibility(
                mInternetDialogController.hasEthernet() ? View.VISIBLE : View.GONE);
    }

    /**
     * Do not allow the user to disable mobile data of DDS while there is an active
     * call on the nDDS.
     */
    private boolean shouldDisallowUserToDisableMobileData() {
        return mInternetDialogController.isMobileDataEnabled()
                && !mInternetDialogController.isNonDdsCallStateIdle();
    }

    private void setMobileDataLayout(boolean activeNetworkIsCellular,
            boolean isCarrierNetworkActive) {
        boolean isNetworkConnected = activeNetworkIsCellular || isCarrierNetworkActive;
        // 1. Mobile network should be gone if airplane mode ON or the list of active
        //    subscriptionId is null.
        // 2. Carrier network should be gone if airplane mode ON and Wi-Fi is OFF.
        if (DEBUG) {
            Log.d(TAG, "setMobileDataLayout, isCarrierNetworkActive = " + isCarrierNetworkActive);
        }

        boolean isWifiEnabled = mInternetDialogController.isWifiEnabled();
        if (!mInternetDialogController.hasActiveSubId()
                && (!isWifiEnabled || !isCarrierNetworkActive)) {
            mMobileNetworkLayout.setVisibility(View.GONE);
        } else {
            if (shouldDisallowUserToDisableMobileData()) {
                Log.d(TAG, "Do not allow mobile data switch to be turned off");
                mMobileDataToggle.setEnabled(false);
            } else {
                mMobileDataToggle.setEnabled(true);
            }
            mMobileNetworkLayout.setVisibility(View.VISIBLE);
            mMobileDataToggle.setChecked(mInternetDialogController.isMobileDataEnabled());
            mMobileTitleText.setText(getMobileNetworkTitle());
            String summary = getMobileNetworkSummary();
            if (!TextUtils.isEmpty(summary)) {
                mMobileSummaryText.setText(
                        Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY));
                mMobileSummaryText.setVisibility(View.VISIBLE);
            } else {
                mMobileSummaryText.setVisibility(View.GONE);
            }
            mBackgroundExecutor.execute(() -> {
                Drawable drawable = getSignalStrengthDrawable();
                mHandler.post(() -> {
                    mSignalIcon.setImageDrawable(drawable);
                });
            });
            mMobileTitleText.setTextAppearance(isNetworkConnected
                    ? R.style.TextAppearance_InternetDialog_Active
                    : R.style.TextAppearance_InternetDialog);
            int secondaryRes = isNetworkConnected
                    ? R.style.TextAppearance_InternetDialog_Secondary_Active
                    : R.style.TextAppearance_InternetDialog_Secondary;
            mMobileSummaryText.setTextAppearance(secondaryRes);
            // Set airplane mode to the summary for carrier network
            if (mInternetDialogController.isAirplaneModeEnabled()) {
                mAirplaneModeSummaryText.setVisibility(View.VISIBLE);
                mAirplaneModeSummaryText.setText(mContext.getText(R.string.airplane_mode));
                mAirplaneModeSummaryText.setTextAppearance(secondaryRes);
            } else {
                mAirplaneModeSummaryText.setVisibility(View.GONE);
            }
            mMobileNetworkLayout.setBackground(
                    isNetworkConnected ? mBackgroundOn : mBackgroundOff);

            TypedArray array = mContext.obtainStyledAttributes(
                    R.style.InternetDialog_Divider_Active, new int[]{android.R.attr.background});
            int dividerColor = Utils.getColorAttrDefaultColor(mContext,
                    android.R.attr.textColorSecondary);
            mMobileToggleDivider.setBackgroundColor(isNetworkConnected
                    ? array.getColor(0, dividerColor) : dividerColor);
            array.recycle();

            mMobileDataToggle.setVisibility(mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
            mMobileToggleDivider.setVisibility(
                    mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @MainThread
    private void updateWifiToggle(boolean isWifiEnabled, boolean isDeviceLocked) {
        if (mWiFiToggle.isChecked() != isWifiEnabled) {
            mWiFiToggle.setChecked(isWifiEnabled);
        }
        if (isDeviceLocked) {
            mWifiToggleTitleText.setTextAppearance((mConnectedWifiEntry != null)
                    ? R.style.TextAppearance_InternetDialog_Active
                    : R.style.TextAppearance_InternetDialog);
        }
        mTurnWifiOnLayout.setBackground(
                (isDeviceLocked && mConnectedWifiEntry != null) ? mBackgroundOn : null);

        if (!mCanChangeWifiState && mWiFiToggle.isEnabled()) {
            mWiFiToggle.setEnabled(false);
            mWifiToggleTitleText.setEnabled(false);
            final TextView summaryText = mDialogView.requireViewById(R.id.wifi_toggle_summary);
            summaryText.setEnabled(false);
            summaryText.setVisibility(View.VISIBLE);
        }
    }

    @MainThread
    private void updateConnectedWifi(boolean isWifiEnabled, boolean isDeviceLocked) {
        if (!isWifiEnabled || mConnectedWifiEntry == null || isDeviceLocked) {
            mConnectedWifListLayout.setVisibility(View.GONE);
            return;
        }
        mConnectedWifListLayout.setVisibility(View.VISIBLE);
        mConnectedWifiTitleText.setText(mConnectedWifiEntry.getTitle());
        mConnectedWifiSummaryText.setText(mConnectedWifiEntry.getSummary(false));
        mConnectedWifiIcon.setImageDrawable(
                mInternetDialogController.getInternetWifiDrawable(mConnectedWifiEntry));
        mWifiSettingsIcon.setColorFilter(
                mContext.getColor(R.color.connected_network_primary_color));
    }

    @MainThread
    private void updateWifiListAndSeeAll(boolean isWifiEnabled, boolean isDeviceLocked) {
        if (!isWifiEnabled || isDeviceLocked) {
            mWifiRecyclerView.setVisibility(View.GONE);
            mSeeAllLayout.setVisibility(View.GONE);
            return;
        }
        final int wifiListMaxCount = getWifiListMaxCount();
        if (mAdapter.getItemCount() > wifiListMaxCount) {
            mHasMoreWifiEntries = true;
        }
        mAdapter.setMaxEntriesCount(wifiListMaxCount);
        final int wifiListMinHeight = mWifiNetworkHeight * wifiListMaxCount;
        if (mWifiRecyclerView.getMinimumHeight() != wifiListMinHeight) {
            mWifiRecyclerView.setMinimumHeight(wifiListMinHeight);
        }
        mWifiRecyclerView.setVisibility(View.VISIBLE);
        mSeeAllLayout.setVisibility(mHasMoreWifiEntries ? View.VISIBLE : View.INVISIBLE);
    }

    @VisibleForTesting
    @MainThread
    int getWifiListMaxCount() {
        // Use the maximum count of networks to calculate the remaining count for Wi-Fi networks.
        int count = MAX_NETWORK_COUNT;
        if (mEthernetLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }
        if (mMobileNetworkLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }

        // If the remaining count is greater than the maximum count of the Wi-Fi network, the
        // maximum count of the Wi-Fi network is used.
        if (count > MAX_WIFI_ENTRY_COUNT) {
            count = MAX_WIFI_ENTRY_COUNT;
        }
        if (mConnectedWifListLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }
        return count;
    }

    @MainThread
    private void updateWifiScanNotify(boolean isWifiEnabled, boolean isWifiScanEnabled,
            boolean isDeviceLocked) {
        if (isWifiEnabled || !isWifiScanEnabled || isDeviceLocked) {
            mWifiScanNotifyLayout.setVisibility(View.GONE);
            return;
        }
        if (TextUtils.isEmpty(mWifiScanNotifyText.getText())) {
            final AnnotationLinkSpan.LinkInfo linkInfo = new AnnotationLinkSpan.LinkInfo(
                    AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION,
                    mInternetDialogController::launchWifiScanningSetting);
            mWifiScanNotifyText.setText(AnnotationLinkSpan.linkify(
                    getContext().getText(R.string.wifi_scan_notify_message), linkInfo));
            mWifiScanNotifyText.setMovementMethod(LinkMovementMethod.getInstance());
        }
        mWifiScanNotifyLayout.setVisibility(View.VISIBLE);
    }

    void onClickConnectedWifi(View view) {
        if (mConnectedWifiEntry == null) {
            return;
        }
        mInternetDialogController.launchWifiDetailsSetting(mConnectedWifiEntry.getKey(), view);
    }

    void onClickSeeMoreButton(View view) {
        mInternetDialogController.launchNetworkSetting(view);
    }

    CharSequence getDialogTitleText() {
        return mInternetDialogController.getDialogTitleText();
    }

    @Nullable
    CharSequence getSubtitleText() {
        return mInternetDialogController.getSubtitleText(
                mIsProgressBarVisible && !mIsSearchingHidden);
    }

    private Drawable getSignalStrengthDrawable() {
        return mInternetDialogController.getSignalStrengthDrawable();
    }

    CharSequence getMobileNetworkTitle() {
        return mInternetDialogController.getMobileNetworkTitle();
    }

    String getMobileNetworkSummary() {
        if (shouldDisallowUserToDisableMobileData()) {
            return mContext.getString(R.string.mobile_data_summary_not_allowed_to_disable_data);
        }
        return mInternetDialogController.getMobileNetworkSummary();
    }

    protected void showProgressBar() {
        if (!mInternetDialogController.isWifiEnabled()
                || mInternetDialogController.isDeviceLocked()) {
            setProgressBarVisible(false);
            return;
        }
        setProgressBarVisible(true);
        if (mConnectedWifiEntry != null || mWifiEntriesCount > 0) {
            mHandler.postDelayed(mHideProgressBarRunnable, PROGRESS_DELAY_MS);
        } else if (!mIsSearchingHidden) {
            mHandler.postDelayed(mHideSearchingRunnable, PROGRESS_DELAY_MS);
        }
    }

    private void setProgressBarVisible(boolean visible) {
        if (mIsProgressBarVisible == visible) {
            return;
        }
        mIsProgressBarVisible = visible;
        mProgressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        mProgressBar.setIndeterminate(visible);
        mDivider.setVisibility(visible ? View.GONE : View.VISIBLE);
        mInternetDialogSubTitle.setText(getSubtitleText());
    }

    private boolean shouldShowMobileDialog() {
        mIsCallIdle = mTelephonyManager.getCallState() == TelephonyManager.CALL_STATE_IDLE;
        IImsRegistration imsRegistrationImpl = mTelephonyManager.getImsRegistration(
                mSubscriptionManager.getSlotIndex(mDefaultDataSubId), FEATURE_MMTEL);
        try {
            mIsImsRegisteredOverCiwlan = imsRegistrationImpl.getRegistrationTechnology() ==
                    REGISTRATION_TECH_CROSS_SIM;
        } catch (RemoteException ex) {
            Log.e(TAG, "getRegistrationTechnology failed", ex);
        }
        if (mInternetDialogController.isMobileDataEnabled() && !mIsCallIdle &&
                mIsImsRegisteredOverCiwlan) {
            return true;
        }
        boolean flag = Prefs.getBoolean(mContext, QS_HAS_TURNED_OFF_MOBILE_DATA,
                false);
        if (mInternetDialogController.isMobileDataEnabled() && !flag) {
            return true;
        }
        return false;
    }

    private void showTurnOffMobileDialog() {
        CharSequence carrierName = getMobileNetworkTitle();
        boolean isInService = mInternetDialogController.isVoiceStateInService();
        if (TextUtils.isEmpty(carrierName) || !isInService) {
            carrierName = mContext.getString(R.string.mobile_data_disable_message_default_carrier);
        }
        String mobileDataDisableDialogMessage = mContext.getString(
                R.string.mobile_data_disable_message, carrierName);
        // If call is ongoing and IMS is registered on C_IWLAN, adjust the dialog message
        Log.d(TAG, "isCallIdle = " + mIsCallIdle + ", isImsRegisteredOverCiwlan = " +
                mIsImsRegisteredOverCiwlan);
        if (!mIsCallIdle && mIsImsRegisteredOverCiwlan) {
            mobileDataDisableDialogMessage = mContext.getString(
                    R.string.mobile_data_disable_ciwlan_call_message) + "\n" +
                    mobileDataDisableDialogMessage;
        }
        mAlertDialog = new Builder(mContext)
                .setTitle(R.string.mobile_data_disable_title)
                .setMessage(mobileDataDisableDialogMessage)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    mMobileDataToggle.setChecked(true);
                })
                .setPositiveButton(
                        com.android.internal.R.string.alert_windows_notification_turn_off_action,
                        (d, w) -> {
                            mInternetDialogController.setMobileDataEnabled(mContext,
                                    mDefaultDataSubId, false, false);
                            mMobileDataToggle.setChecked(false);
                            Prefs.putBoolean(mContext, QS_HAS_TURNED_OFF_MOBILE_DATA, true);
                        })
                .create();
        mAlertDialog.setOnCancelListener(dialog -> mMobileDataToggle.setChecked(true));
        mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(mAlertDialog, true);
        SystemUIDialog.registerDismissListener(mAlertDialog);
        SystemUIDialog.setWindowOnTop(mAlertDialog, mKeyguard.isShowing());
        mAlertDialog.show();
    }

    @Override
    public void onRefreshCarrierInfo() {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onSimStateChanged() {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    @WorkerThread
    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    @WorkerThread
    public void onLost(Network network) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onSubscriptionsChanged(int defaultDataSubId) {
        mDefaultDataSubId = defaultDataSubId;
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(mDefaultDataSubId);
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onUserMobileDataStateChanged(boolean enabled) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onServiceStateChanged(ServiceState serviceState) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    @WorkerThread
    public void onDataConnectionStateChanged(int state, int networkType) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onNonDdsCallStateChanged(int callState) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    @WorkerThread
    public void onAccessPointsChanged(@Nullable List<WifiEntry> wifiEntries,
            @Nullable WifiEntry connectedEntry, boolean hasMoreWifiEntries) {
        // Should update the carrier network layout when it is connected under airplane mode ON.
        boolean shouldUpdateCarrierNetwork = mMobileNetworkLayout.getVisibility() == View.VISIBLE
                && mInternetDialogController.isAirplaneModeEnabled();
        mHandler.post(() -> {
            mConnectedWifiEntry = connectedEntry;
            mWifiEntriesCount = wifiEntries == null ? 0 : wifiEntries.size();
            mHasMoreWifiEntries = hasMoreWifiEntries;
            updateDialog(shouldUpdateCarrierNetwork /* shouldUpdateMobileNetwork */);
            mAdapter.setWifiEntries(wifiEntries, mWifiEntriesCount);
            mAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mAlertDialog != null && !mAlertDialog.isShowing()) {
            if (!hasFocus && isShowing()) {
                dismiss();
            }
        }
    }

    public enum InternetDialogEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The Internet dialog became visible on the screen.")
        INTERNET_DIALOG_SHOW(843);

        private final int mId;

        InternetDialogEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
