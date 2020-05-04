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

package com.android.server.pm;

import android.Manifest;
import android.app.ActivityManagerInternal;
import android.content.ComponentName;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.policymanager.IPolicyManager;
import android.policymanager.ThreadDump;
import android.privacy.IPermissionRequestManager;
import android.util.Log;

import com.android.internal.privacy.PermissionRequest;
import com.android.internal.privacy.IPrivacyManager;
import com.android.server.LocalServices;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class PolicyManagerProxy {

    private final String TAG = PolicyManagerProxy.class.getSimpleName();
    private IPrivacyManager mPrivacyManagerService = null;
    private IPolicyManager mPolicyManagerService = null;
    private IPermissionRequestManager mPermissionRequestService = null;

    private final List<String> mDangerousPerms = new ArrayList<String>(
            Arrays.asList(Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.ADD_VOICEMAIL,
            Manifest.permission.USE_SIP,
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.WRITE_SETTINGS));

    public boolean managedPermission(String permName) {
        // For now, we are only going to cover Dangerous Permission requests through the
        // Privacy Policy Manager. This list may grow in the future
        return mDangerousPerms.contains(permName);
    }

    public boolean policyManagerPresent() {
        // TODO: Changed PrivacyManager allow requests for changes to current PolicyManager
        //       Also, linkToDeath for Privacy/PolicyManager so we may know if it is not safe to query
        // Check if Policy Manager is installed and utilize that if present
        if (mPrivacyManagerService == null) {
            mPrivacyManagerService = IPrivacyManager.Stub.asInterface(ServiceManager.getService("privacy_manager"));
            try {
                mPolicyManagerService = mPrivacyManagerService.getCurrentManager();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        try {
            mPolicyManagerService = mPrivacyManagerService.getCurrentManager();
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        return mPolicyManagerService != null;
    }

    private boolean isAppComponent(ComponentName cn) {

	IPackageManager pm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));

	try {
	    if(pm.getActivityInfo(cn, 0, UserHandle.myUserId()) != null)
		return true;
	} catch (Exception e) {
	    // Continue to check other types of components
	}

	try {
	    if(pm.getProviderInfo(cn, 0, UserHandle.myUserId()) != null)
		return true;
	} catch (Exception e) {
	    // Continue to check other types of components
	}

	try {
	    if(pm.getReceiverInfo(cn, 0, UserHandle.myUserId()) != null)
		return true;
	} catch (Exception e) {
	    // Continue to check other types of components
	}

	try {
	    if(pm.getServiceInfo(cn, 0, UserHandle.myUserId()) != null)
		return true;
	} catch (Exception e) {
	    // Nothing else to check....
	}

	return false;
    }

    private ComponentName getCallingComponent(String packageName, List<StackTraceElement[]> stackTraceElements) {
	ComponentName cn = null;
	boolean found = false;

	if (stackTraceElements != null && !stackTraceElements.isEmpty()) {
	    StackTraceElement[] callingThread = stackTraceElements.get(0);

	    for (StackTraceElement e : callingThread) {
		cn = new ComponentName(packageName, e.getClassName());

    		if (isAppComponent(cn)) {
    		    found = true;
    		    break;
    		}
	    }
	}

	return (found) ? cn : null;
    }

    public int checkPolicy(String permName, int uid, String packageName, int flags)  {

        PermissionRequest request = null;
        try {
            mPermissionRequestService = IPermissionRequestManager.Stub.asInterface(ServiceManager.getService("permission_request"));
            if (mPermissionRequestService != null)
                request = mPermissionRequestService.get(uid, permName);
        } catch (RemoteException e ) {
            e.printStackTrace();
        }

        if (request != null) {
            packageName = request.getPackageName();
        }

        boolean isBootComplete = "1".equals(SystemProperties.get("sys.boot_completed"));

        if (isBootComplete && mPolicyManagerService != null && permName != null &&
            !packageName.equals("android")) {

	    ActivityManagerInternal ami = LocalServices.getService(ActivityManagerInternal.class);
	    ComponentName topActivity = ami.getTopActivityComponentName();

	    Log.d(TAG, "packageName = " + packageName + " uid = " + uid + " topActivity = " + ((topActivity == null) ? "null" : topActivity));

            final CountDownLatch latch = new CountDownLatch(1);

            boolean grantPermission = false;
            MyResultReceiver recv = new MyResultReceiver(latch);

            try {
                if (request == null) {
                    Log.i(TAG, "About to check PolicyManager...");
                    // Note: need to get the purpose somehow when we don't have a stack trace...
                    mPolicyManagerService.onDangerousPermissionRequest(packageName, permName, null, null, flags, null, topActivity, recv);
                } else {
		    ComponentName cn = getCallingComponent(packageName, request.getStackTraces());
		    Log.d(TAG, "callingComponent = " + ((cn == null) ? "null" : cn));
                    mPolicyManagerService.onDangerousPermissionRequest(packageName, permName, request.getPurpose(), new ThreadDump(request.getStackTraces()), flags, cn, topActivity, recv);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException", e);
                Log.d(TAG, "------------------------------------------------------------------");
                return PackageManager.PERMISSION_DENIED;
            }
            try {
                latch.await();
                grantPermission = recv.mAllowPerm;
            } catch (InterruptedException e) {
                Log.e(TAG, "InterruptedException", e);
                Log.d(TAG, "------------------------------------------------------------------");
                return PackageManager.PERMISSION_DENIED;
            }

            if (!grantPermission) {
                Log.e(TAG, "PE_Android FTW: DENY by policy!!!");
                Log.d(TAG, "------------------------------------------------------------------");
                return PackageManager.PERMISSION_DENIED;
            }

            Log.d(TAG, "permission ALLOWED by policy");
            Log.d(TAG, "------------------------------------------------------------------");
            return PackageManager.PERMISSION_GRANTED;

        }

        return PackageManager.PERMISSION_NO_POLICY_MANAGER;
    }

    private class MyResultReceiver extends ResultReceiver {
        boolean mAllowPerm;
        CountDownLatch latch;

        MyResultReceiver(CountDownLatch latch) {
            super(null);
            this.latch = latch;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            mAllowPerm = resultData.getBoolean("allowPerm");
            latch.countDown();
        }
    }
}
