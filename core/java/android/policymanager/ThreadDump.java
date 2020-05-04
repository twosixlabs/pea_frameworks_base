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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

public class ThreadDump implements Parcelable {

    private List<StackTraceElement[]> stackTraces = new ArrayList<>();

    public ThreadDump(List<StackTraceElement[]> stackTraceElements) {
        this.stackTraces = stackTraceElements;
    }

    protected ThreadDump(Parcel in) {
        int nThreads = in.readInt();
        for (int i = 0; i < nThreads; i++) {
            int threadLength = in.readInt();
            List<StackTraceElement> thread = new ArrayList<>();
            for (int j = 0; j < threadLength; j++) {
                thread.add((StackTraceElement) in.readSerializable());
            }
            stackTraces.add(thread.toArray(new StackTraceElement[thread.size()]));
        }
    }

    public static final Creator<ThreadDump> CREATOR = new Creator<ThreadDump>() {
        @Override
        public ThreadDump createFromParcel(Parcel in) {
            return new ThreadDump(in);
        }

        @Override
        public ThreadDump[] newArray(int size) {
            return new ThreadDump[size];
        }
    };

    public List<StackTraceElement[]> getStackTraces() { return stackTraces; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(stackTraces.size());
        for (int i = 0; i < stackTraces.size(); i++) {
            StackTraceElement[] thread = stackTraces.get(i);
            dest.writeInt(thread.length);
            for (StackTraceElement e : thread) {
                dest.writeSerializable(e);
            }
        }
    }
}

