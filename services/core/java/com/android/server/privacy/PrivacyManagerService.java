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

package com.android.server.privacy;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.os.IBinder;
import android.os.UserHandle;
import android.policymanager.IPolicyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.privacy.IPrivacyManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PrivacyManagerService extends IPrivacyManager.Stub {
    private static final String TAG = PrivacyManagerService.class.getSimpleName();
    private static final String ACTION_START_PRIVACY_POLICY_MANAGER = "android.app.action.DEVICE_POLICY_MANAGER_START";
    private final Context mContext;
    private final PrivacyManagerStorage mStorage = new PrivacyManagerStorage();

    private IPolicyManager mPolicyManager;
    private CountDownLatch mPolicyManagerServiceLatch;
    private ServiceConnection mPolicyManagerServiceConnection;

    public PrivacyManagerService(Context context) {
        mContext = context;
    }

    public void systemReady() {
        ComponentName manager = getCurrentManagerName();

        if (manager == null) {
            manager = setDefaultPrivacyManager();
        }

        if (manager != null) {
            mPolicyManagerServiceConnection = new PolicyManagerServiceConnection();
            bindToService(createIntent(manager));
        }
        else {
            Log.d(TAG, "No policy manager on device");
        }
    }

    private void bindToService(Intent intent) {
        if (mPolicyManager != null && intent.getComponent().equals(mStorage.getCurrentManager())) {
            // we are already bound to this service, do nothing
            return;
        }

        ServiceConnection oldServiceConnection = mPolicyManagerServiceConnection;
        ServiceConnection newServiceConnection = new PolicyManagerServiceConnection();

        mPolicyManagerServiceLatch = new CountDownLatch(1);
        if (mContext.bindServiceAsUser(intent, newServiceConnection, Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
            try {
                mPolicyManagerServiceLatch.await(3, TimeUnit.SECONDS);
                mPolicyManagerServiceConnection = newServiceConnection;
                mContext.unbindService(oldServiceConnection);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private class PolicyManagerServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName className,
                       IBinder service) {

            mPolicyManager = IPolicyManager.Stub.asInterface(service);
            mStorage.setCurrentManager(className);
            mPolicyManagerServiceLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            mPolicyManager = null;
            ComponentName defaultMgr = getDefaultPrivacyManager();
            if (defaultMgr == null) {
                mStorage.clearCurrentManager();
            } else {
                setCurrentManager(defaultMgr);
            }
        }
    }

    private ComponentName getDefaultPrivacyManager() {
        Intent intent = new Intent();
        intent.setAction(ACTION_START_PRIVACY_POLICY_MANAGER);

        ResolveInfo defaultPolicyManager =
            mContext.getPackageManager().resolveService(intent, PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);

        if (defaultPolicyManager == null) {
            return null;
        }
        else {
            ComponentName name = toComponentName(defaultPolicyManager);
            return name;
        }
    }

    private ComponentName setDefaultPrivacyManager() {
        Intent intent = new Intent();
        intent.setAction(ACTION_START_PRIVACY_POLICY_MANAGER);

        ResolveInfo defaultPolicyManager =
            mContext.getPackageManager().resolveService(intent, PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);

        if (defaultPolicyManager == null) {
            return null;
        }
        else {
            ComponentName name = toComponentName(defaultPolicyManager);
            mStorage.setCurrentManager(name);
            return name;
        }
    }

    private Intent createIntent(ComponentName manager) {
        Intent intent = new Intent();
        intent.setAction(ACTION_START_PRIVACY_POLICY_MANAGER);
        intent.setComponent(manager);
        return intent;
    }

    @Override
    public IPolicyManager getCurrentManager() {
        return mPolicyManager;
    }

    @Override
    public List<ComponentName> getAvailableManagers() {
        Intent intent = new Intent();
        intent.setAction(ACTION_START_PRIVACY_POLICY_MANAGER);

        List<ResolveInfo> policyManagersList =
            mContext.getPackageManager().queryIntentServices(intent, PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);

        List<ComponentName> componentNames = new ArrayList<>();

        for (ResolveInfo ri : policyManagersList) {
            if (!Manifest.permission.POLICY_MANAGER_SERVICE.equals(ri.serviceInfo.permission)) {
                Log.d(TAG, "Error - service does not have " + Manifest.permission.POLICY_MANAGER_SERVICE);
            }

            componentNames.add(toComponentName(ri));
        }

        if (mPolicyManager == null && !componentNames.isEmpty())
            bindToService(createIntent(componentNames.get(0)));

        return componentNames;
    }

    private ComponentName toComponentName(ResolveInfo ri) {
        return new ComponentName(ri.serviceInfo.applicationInfo.packageName, ri.serviceInfo.name);
    }

    @Override
    public void setCurrentManager(ComponentName privacyManager) {
        if (getAvailableManagers().contains(privacyManager)) {
            bindToService(createIntent(privacyManager));
            Log.d(TAG, "Set Policy Manager to " + privacyManager.toString());
            // Notify Quick Settings UI that it needs to fetch quick setting from new Policy Manager
            Intent notifyIntent = new Intent("android.intent.action.PRIVACY_SETTINGS_CHANGED");
            mContext.sendBroadcast(notifyIntent);
        }
        else {
            Log.d(TAG, "Cannot set privacy manager to invalid component: " + privacyManager.toString());
        }
    }

    @Override
    public ComponentName getCurrentManagerName() {
        ComponentName currentManager = mStorage.getCurrentManager();

        if (currentManager == null) {
            return null;
        }

        // check that the current manager has not been uninstalled
        Intent intent = new Intent();
        intent.setComponent(currentManager);

        if (mContext.getPackageManager().resolveService(intent, PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE) == null) {
            mStorage.clearCurrentManager();
            return null;
        }

        return currentManager;
    }

    private class PrivacyManagerStorage {
        private static final String privacyManagerFileName = "enabledPrivacyManager";
        private final File mPrivacyManagerFile;


        PrivacyManagerStorage(){
            File dataDir = Environment.getDataDirectory();
            File systemDir = new File(dataDir, "system");
            mPrivacyManagerFile = new File(systemDir, privacyManagerFileName);

            try {
                mPrivacyManagerFile.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Unable to create enabled Privacy Manager file " + mPrivacyManagerFile.getPath());
            }
        }
        void clearCurrentManager() {
            try {
                FileWriter fw = new FileWriter(mPrivacyManagerFile);
                fw.write("");
                fw.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to clear the current enabled Privacy Manager");
            }
        }

        void setCurrentManager(ComponentName current) {
            try {
                BufferedWriter bw = new BufferedWriter(new FileWriter(mPrivacyManagerFile));

                bw.write(current.getPackageName());
                bw.newLine();
                bw.write(current.getClassName());
                bw.newLine();

                bw.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to set the current enabled Privacy Manager " + (current == null ? "":current.toString()));
            }
        }

        ComponentName getCurrentManager() {
            String packageName = "";
            String className = "";
            try {
                BufferedReader br = new BufferedReader(new FileReader(mPrivacyManagerFile));

                packageName = br.readLine();
                className = br.readLine();

                br.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to get the current enabled Privacy Manager");
            }

            if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(className)) {
                return null;
            }
            else {
                return new ComponentName(packageName, className);
            }
        }
    }
}
