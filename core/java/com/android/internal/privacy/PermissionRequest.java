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

package com.android.internal.privacy;

import android.app.ActivityThread;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class PermissionRequest implements Parcelable {
	private List<String> permissions = new ArrayList<>();
	private List<StackTraceElement[]> stackTraceList = new ArrayList<>();
	private String purpose;
	private String packageName;
	private int uid;
	private int pid;

	public PermissionRequest(List<String> permissions, String purpose) {
		this.uid = Process.myUid();
		this.pid = Process.myPid();
		this.packageName = ActivityThread.currentPackageName();
		this.permissions.addAll(permissions);
		this.purpose = purpose;

		Thread callingThread = Thread.currentThread();
		Map<Thread, StackTraceElement[]> stackTraceMap = Thread.getAllStackTraces();
		StackTraceElement[] current = callingThread.getStackTrace();
		if (current != null && current.length > 4) {
			stackTraceList.add(Arrays.copyOfRange(current, 4, current.length));
		} else {
			stackTraceList.add(callingThread.getStackTrace());
		}
		stackTraceMap.remove(callingThread);

		for (StackTraceElement[] trace : stackTraceMap.values()) {
			if (trace.length > 0) {
				stackTraceList.add(trace);
			}
		}
	}

	protected PermissionRequest(Parcel in) {
		permissions = in.createStringArrayList();
		uid = in.readInt();
		pid = in.readInt();
		packageName = in.readString();
		in.readList(stackTraceList, StackTraceElement.class.getClassLoader());
		purpose = in.readString();
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeStringList(permissions);
		dest.writeInt(uid);
		dest.writeInt(pid);
		dest.writeString(packageName);
		dest.writeList(stackTraceList);
		dest.writeString(purpose);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	public static final Creator<PermissionRequest> CREATOR = new Creator<PermissionRequest>() {
		@Override
		public PermissionRequest createFromParcel(Parcel in) {
			return new PermissionRequest(in);
		}

		@Override
		public PermissionRequest[] newArray(int size) {
			return new PermissionRequest[size];
		}
	};

	public List<String> getPermissions() { return permissions; }
	public int getUid() { return uid; }
	public int getPid() { return pid; }
	public String getPackageName() { return packageName; }
	public List<StackTraceElement[]> getStackTraces() { return stackTraceList; }
	public String getPurpose() { return purpose; }

	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("uid = " + this.uid + "\n");
		builder.append("pid = " + this.pid + "\n");
		builder.append("packageName = " + this.packageName + "\n");
		builder.append("permissions = " + permissions.get(0) + "\n");
		builder.append("purpose = " + purpose + "\n");

		for (StackTraceElement[] stackTrace : stackTraceList) {
			builder.append("---\n");
			for (StackTraceElement e : stackTrace) {
				builder.append(e.toString());
				builder.append("\n");
			}
			builder.append("---\n");
		}

		return builder.toString();
	}
}
