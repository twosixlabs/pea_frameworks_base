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

import android.os.Binder;
import android.privacy.IPermissionRequestManager;
import android.util.Log;
import com.android.internal.privacy.PermissionRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class PermissionRequestService extends IPermissionRequestManager.Stub {
    private static final String TAG = PermissionRequestService.class.getSimpleName();
    private HashMap<Integer, List<PermissionRequest>> activeRequests = new HashMap<>();

    @Override
    public void add(PermissionRequest request) {
        if (request.getPermissions().isEmpty()) {
            Log.d(TAG, "Empty permission block, not adding request");
        } else {
            Log.d(TAG, "adding request for uid " + request.getUid() + " and permissions " + request.getPermissions().toString());

            synchronized(this) {
                List<PermissionRequest> appRequests = activeRequests.get(request.getUid());
                if (appRequests == null) {
                    appRequests = new ArrayList<>();
                }

                appRequests.add(request);
                activeRequests.put(request.getUid(), appRequests);
            }
        }
    }

    @Override
    public PermissionRequest get(int uid, String permission) {
        Log.d(TAG, "Looking for request with uid " + uid + " and permission " + permission);

        synchronized(this) {
            List<PermissionRequest> appRequests = activeRequests.get(uid);
            if (appRequests == null) {
                Log.d(TAG, "Could not find permission request");
                return null;
            }

            for (PermissionRequest pr : appRequests) {
                if (pr.getUid() == uid && pr.getPermissions().contains(permission)) {
                    Log.d(TAG, "Found permission request");
                    return pr;
                }
            }
        }

        Log.d(TAG, "Could not find permission request - no matches");
        return null;
    }

    @Override
    public void remove(int uid, List<String> permissions) {

        if (permissions.isEmpty())
            return;

        synchronized(this) {
            List<PermissionRequest> appRequests = activeRequests.get(uid);
            if (appRequests == null || appRequests.isEmpty()) {
                Log.d(TAG, "No request found for uid " + uid + " permissions " + permissions);
                return;
            }

            for (PermissionRequest pr : appRequests) {
                if (pr.getUid() == uid && containsSameElements(pr.getPermissions(), permissions)) {
                    appRequests.remove(pr);
                    Log.d(TAG, "Removing request for uid " + uid + " permissions " + permissions);
                    return;
                }
            }
        }
    }

    private <T> boolean containsSameElements(List<T> a, List<T> b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size()) {
            return false;
        }

        for (T obj : a) {
            if (!b.contains(obj)) {
                return false;
            }
        }

        return true;
    }
}
