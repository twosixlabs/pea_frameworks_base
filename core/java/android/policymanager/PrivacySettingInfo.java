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

package android.policymanager;

import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class to store information for a Privacy Quick Setting tile
 */
public class PrivacySettingInfo implements Parcelable {

    private String mId;
    private String mText;
    private boolean mEnabled;
    private Bitmap mEnabledIcon;
    private Bitmap mDisabledIcon;

    public PrivacySettingInfo(String id, String text, boolean enabled, Bitmap enabledIcon, Bitmap disabledIcon) {
        mId = id;
        mText = text;
        mEnabled = enabled;
        mEnabledIcon = enabledIcon;
        mDisabledIcon = disabledIcon;
    }

    public String getId() {
        return mId;
    }

    public String getText() {
        return mText;
    }

    public boolean getEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    public Bitmap getCurrentIcon() {
        return mEnabled ? mEnabledIcon : mDisabledIcon;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mId);
        out.writeString(mText);
        out.writeBoolean(mEnabled);
        out.writeParcelable(mEnabledIcon, 0);
        out.writeParcelable(mDisabledIcon, 0);
    }

    public static final Parcelable.Creator<PrivacySettingInfo> CREATOR = new Parcelable.Creator<PrivacySettingInfo>() {
        @Override
        public PrivacySettingInfo createFromParcel(Parcel in) {
            return new PrivacySettingInfo(in);
        }

        @Override
        public PrivacySettingInfo[] newArray(int size) {
            return new PrivacySettingInfo[size];
        }
    };

    private PrivacySettingInfo(Parcel in) {
        mId = in.readString();
        mText = in.readString();
        mEnabled = in.readBoolean();
        mEnabledIcon = in.readParcelable(Bitmap.class.getClassLoader());
        mDisabledIcon = in.readParcelable(Bitmap.class.getClassLoader());
    }
}
