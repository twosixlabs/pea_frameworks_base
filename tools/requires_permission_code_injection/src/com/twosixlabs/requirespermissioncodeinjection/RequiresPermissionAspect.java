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

package com.twosixlabs.requirespermissioncodeinjection;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.privacy.IPermissionRequestManager;
import android.text.TextUtils;
import com.android.internal.privacy.PermissionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.aspectj.lang.JoinPoint;

@Aspect
public final class RequiresPermissionAspect {

    @Pointcut("@annotation(android.annotation.RequiresPermission)")
    public void annotationPointcut() {}

    @Pointcut("execution(* *(..))")
    public void executionPointcut() {}

    @Before("annotationPointcut() && executionPointcut()")
    public void addPermissionRequest(JoinPoint joinPoint) {
        try {
            if (joinPoint.getTarget() == null)
                return;

            List<String> perms = getPermissions(joinPoint);
            if (genStackTraces(perms) == false)
                return;

            IPermissionRequestManager mPermissionRequestService = IPermissionRequestManager.Stub.asInterface(ServiceManager.getService("permission_request"));

            if (mPermissionRequestService != null) {
                PermissionRequest permissionRequest = new PermissionRequest(perms, Thread.currentThread().getPrivacyPurpose());
                mPermissionRequestService.add(permissionRequest);
            }
        } catch (RemoteException | NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    @After("annotationPointcut() && executionPointcut()")
    public void removePermissionRequest(JoinPoint joinPoint) {
        try {
            if (joinPoint.getTarget() == null)
                return;

            List<String> perms = getPermissions(joinPoint);
            if (genStackTraces(perms) == false)
                return;

            IPermissionRequestManager mPermissionRequestService = IPermissionRequestManager.Stub.asInterface(ServiceManager.getService("permission_request"));

            if (mPermissionRequestService != null)
                mPermissionRequestService.remove(Process.myUid(), perms);
        } catch (RemoteException | NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    private boolean genStackTraces(List<String> perms) {
        List<String> mManagedPermissions = new ArrayList<String>(
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

        if (perms == null || perms.isEmpty())
            return false;

        for (String p : perms) {
            if (mManagedPermissions.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getPermissions(JoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getMethod().getName();
        Class<?>[] parameterTypes = signature.getMethod().getParameterTypes();
        RequiresPermission permissionAnnotation =
            (RequiresPermission) joinPoint.getTarget().getClass().getMethod(methodName, parameterTypes).getAnnotation(RequiresPermission.class);
        List<String> permissionList = new ArrayList<>();

        if (!TextUtils.isEmpty(permissionAnnotation.value())) {
            permissionList = new ArrayList<String>();
            permissionList.add(permissionAnnotation.value());
        }
        else if (permissionAnnotation.allOf().length > 0) {
            permissionList = Arrays.asList(permissionAnnotation.allOf());
        }
        else if (permissionAnnotation.anyOf().length > 0) {
            permissionList = Arrays.asList(permissionAnnotation.anyOf());
        }

        return permissionList;
    }
}
