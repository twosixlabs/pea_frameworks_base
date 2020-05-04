/*
 * This work was authored by Two Six Labs, LLC and is sponsored by a subcontract agreement with
 * Raytheon BBN Technologies Corp. under Prime Contract No. FA8750-16-C-0006 with the Air Force
 * Research Laboratory (AFRL).
 *
 * The Government has unlimited rights to use, modify, reproduce, release, perform, display, or disclose
 * computer software or computer software documentation marked with this legend. Any reproduction of
 * technical data, computer software, or portions thereof marked with this legend must also reproduce
 * this marking.
 *
 * Copyright (C) 2020 Two Six Labs, LLC.  All rights reserved.
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
 * limitations under the License
 */

package com.android.systemui.qs;

import android.R.attr;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.ServiceManager;
import android.policymanager.IPolicyManager;
import android.policymanager.PrivacySettingInfo;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.privacy.IPrivacyManager;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.tiles.PrivacyTile;
import com.android.systemui.qs.QSPanel.TileRecord;
import com.android.systemui.qs.QSPanel.QSTileLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller for the Privacy Quick Settings
 */
public class QSPrivacyController {

    private static final String TAG = "QSPrivacyController";

    private final Intent PEANDROID_SETTINGS_INTENT = new Intent("android.settings.PEANDROID_SETTINGS");

    private final Context mContext;
    private QSTileLayout mTileLayout;
    private QSTileHost mHost;
    private List<PrivacySettingInfo> mSettings;
    private IPrivacyManager mPrivacyManagerService;

    public QSPrivacyController(Context context) {
        mContext = context;
        mPrivacyManagerService = IPrivacyManager.Stub.asInterface(ServiceManager.getService("privacy_manager"));

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PRIVACY_SETTINGS_CHANGED");
        mContext.registerReceiverAsUser(mReceiver, UserHandle.SYSTEM, filter, null, null);

        mSettings = new ArrayList<>();
    }

    public void setHost(QSTileHost host) {
        mHost = host;
    }

    public void notifySettingClicked(String settingTag) {
        if (mPrivacyManagerService != null) {
            try {
                IPolicyManager activePolicyManager = mPrivacyManagerService.getCurrentManager();
                activePolicyManager.onPrivacyQuickSettingSelected(settingTag);
            } catch (RemoteException e) {
                mSettings.clear();
                e.printStackTrace();
            }
        }
    }

    public final DetailAdapter privacyDetailAdapter = new DetailAdapter() {

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_privacy_title);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            Log.d(TAG, "Creating privacy quick settings view");

            LinearLayout detailView = (LinearLayout) LayoutInflater.from(mContext).inflate(
                    R.layout.qs_privacy_panel, parent, false);

            mTileLayout = (QSTileLayout) LayoutInflater.from(mContext).inflate(
                    R.layout.qs_paged_tile_layout, detailView, false);
            detailView.addView((View) mTileLayout);

            PageIndicator pageIndicator = (PageIndicator) LayoutInflater.from(mContext).inflate(
                    R.layout.qs_page_indicator, detailView, false);
            detailView.addView(pageIndicator);

            ((PagedTileLayout) mTileLayout).setPageIndicator(pageIndicator);

            // Add tiles to view
            refreshTileList();

            return (View) detailView;
        }

        @Override public Intent getSettingsIntent() {
            return getPolicyManagerIntent();
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QS_CUSTOM;
        }

        @Override
        public Boolean getToggleState() { return null; }

        @Override
        public void setToggleState(boolean state) { }
    };

    public Intent getPolicyManagerIntent() {
        try {
            ComponentName currentManager = mPrivacyManagerService.getCurrentManagerName();
            if (currentManager != null) {
                String packageName = currentManager.getPackageName();
                return mContext.getPackageManager().getLaunchIntentForPackage(packageName);
            }
        } catch (RemoteException e) {
        }
        return PEANDROID_SETTINGS_INTENT;
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mPrivacyManagerService != null) {
                try {
                    IPolicyManager activePolicyManager = mPrivacyManagerService.getCurrentManager();
                    if (activePolicyManager != null) {
                        mSettings = activePolicyManager.getPrivacyQuickSettings();
                    } else {
                        // This seems to happen if the active policy manager crashes,
                        // in which case just clear the list of settings
                        mSettings = new ArrayList<>();
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private void addTile(PrivacySettingInfo tileInfo) {
        Log.d(TAG, "Adding privacy tile " + tileInfo.getId());
        QSTile privacyTile = new PrivacyTile(mHost, QSPrivacyController.this, tileInfo);
        TileRecord r = new TileRecord();
        r.tile = privacyTile;
        r.tileView = mHost.createTileView(privacyTile, false);
        final QSTile.Callback callback = new QSTile.Callback() {
            @Override
            public void onStateChanged(QSTile.State state){
                r.tileView.onStateChanged(state);
            }
            @Override
            public void onShowDetail(boolean show) {
            }
            @Override
            public void onToggleStateChanged(boolean state) {
            }
            @Override
            public void onScanStateChanged(boolean state) {
                r.scanState = state;
            }
            @Override
            public void onAnnouncementRequested(CharSequence announcment) {
            }
        };
        r.tile.addCallback(callback);
        r.callback = callback;
        r.tileView.init(r.tile);
        r.tile.refreshState();
        mTileLayout.addTile(r);
    }

    private void refreshTileList() {
        Log.d(TAG, "in refreshTileList");
        for (PrivacySettingInfo tileInfo : mSettings) {
            addTile(tileInfo);
        }
    }
}
