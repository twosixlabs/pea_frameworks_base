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
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.policymanager.PrivacySettingInfo;
import android.service.quicksettings.Tile;
import android.util.Log;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QSPrivacyController;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIcon;

/** Privacy Quick settings tile**/
public class PrivacyTile extends QSTileImpl<BooleanState> {

    private static final String TAG = "PrivacyTile";

    private final QSPrivacyController mPrivacyController;
    private final PrivacySettingInfo mTileInfo;

    public PrivacyTile(QSHost host, QSPrivacyController controller, PrivacySettingInfo info) {
        super(host);
        mPrivacyController = controller;
        mTileInfo = info;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        // This shouldn't be needed for the Privacy QS
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
    }

    @Override
    public Intent getLongClickIntent() {
        return mPrivacyController.getPolicyManagerIntent();
    }

    @Override
    protected void handleClick() {
        if (ActivityManager.isUserAMonkey()) {
            return;
        }
        boolean newState = !mState.value;
        mTileInfo.setEnabled(newState);
        refreshState(newState);
        mPrivacyController.notifySettingClicked(mTileInfo.getId());
    }

    @Override
    public CharSequence getTileLabel() {
        return mTileInfo.getText();
    }

    @Override
    protected void handleLongClick() {
        handleClick();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        Log.d(TAG, "In handleUpdateState " + state + " " + arg);
        if (arg instanceof Boolean) {
            boolean value = (Boolean) arg;
            if (value == state.value) {
                return;
            }
            state.value = value;
        } else {
            state.value = mTileInfo.getEnabled();
        }
        state.label = mTileInfo.getText();
        state.icon = new DrawableIcon(new BitmapDrawable(mTileInfo.getCurrentIcon()));
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CUSTOM;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return "privacy tile on";
        } else {
            return "privacy tile off";
        }
    }
}
