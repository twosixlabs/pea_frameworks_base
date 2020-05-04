/*
 * Copyright (C) 2009 The Android Open Source Project
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

/*
 * This work was modified by Two Six Labs, LLC and is sponsored by a subcontract agreement with
 * Raytheon BBN Technologies Corp. under Prime Contract No. FA8750-16-C-0006 with the Air Force
 * Research Laboratory (AFRL).
 *
 * The Government has unlimited rights to use, modify, reproduce, release, perform, display, or disclose
 * computer software or computer software documentation marked with this legend. Any reproduction of
 * technical data, computer software, or portions thereof marked with this legend must also reproduce
 * this marking.
 *
 * Copyright (C) 2020 Two Six Labs, LLC.  All rights reserved.
 */

package android.content;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.res.AssetFileDescriptor;
import android.database.CrossProcessCursorWrapper;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.Process;
import android.os.ServiceManager;
import android.privacy.IPermissionRequestManager;
import android.provider.CalendarContract;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.privacy.PermissionRequest;
import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.Manifest.permission.WRITE_CALL_LOG;
import static android.Manifest.permission.WRITE_CALENDAR;
import static android.Manifest.permission.WRITE_CONTACTS;
import static android.Manifest.permission.WRITE_SMS;
import static android.Manifest.permission.READ_CALL_LOG;
import static android.Manifest.permission.READ_CALENDAR;
import static android.Manifest.permission.READ_CONTACTS;
import static android.Manifest.permission.READ_SMS;

import android.provider.Telephony;

/**
 * The public interface object used to interact with a specific
 * {@link ContentProvider}.
 * <p>
 * Instances can be obtained by calling
 * {@link ContentResolver#acquireContentProviderClient} or
 * {@link ContentResolver#acquireUnstableContentProviderClient}. Instances must
 * be released using {@link #close()} in order to indicate to the system that
 * the underlying {@link ContentProvider} is no longer needed and can be killed
 * to free up resources.
 * <p>
 * Note that you should generally create a new ContentProviderClient instance
 * for each thread that will be performing operations. Unlike
 * {@link ContentResolver}, the methods here such as {@link #query} and
 * {@link #openFile} are not thread safe -- you must not call {@link #close()}
 * on the ContentProviderClient those calls are made from until you are finished
 * with the data they have returned.
 */
public class ContentProviderClient implements AutoCloseable {
    private static final String TAG = "ContentProviderClient";

    @GuardedBy("ContentProviderClient.class")
    private static Handler sAnrHandler;

    private final ContentResolver mContentResolver;
    private final IContentProvider mContentProvider;
    private final String mPackageName;
    private final boolean mStable;

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    private long mAnrTimeout;
    private NotRespondingRunnable mAnrRunnable;

    /* needed becauase android.provider.Telephony cannot be imported */
    private static final String smsUri = "content://sms";

    /** {@hide} */
    @VisibleForTesting
    public ContentProviderClient(
            ContentResolver contentResolver, IContentProvider contentProvider, boolean stable) {
        mContentResolver = contentResolver;
        mContentProvider = contentProvider;
        mPackageName = contentResolver.mPackageName;

        mStable = stable;

        mCloseGuard.open("close");
    }

    /** {@hide} */
    public void setDetectNotResponding(long timeoutMillis) {
        synchronized (ContentProviderClient.class) {
            mAnrTimeout = timeoutMillis;

            if (timeoutMillis > 0) {
                if (mAnrRunnable == null) {
                    mAnrRunnable = new NotRespondingRunnable();
                }
                if (sAnrHandler == null) {
                    sAnrHandler = new Handler(Looper.getMainLooper(), null, true /* async */);
                }

                // If the remote process hangs, we're going to kill it, so we're
                // technically okay doing blocking calls.
                Binder.allowBlocking(mContentProvider.asBinder());
            } else {
                mAnrRunnable = null;

                // If we're no longer watching for hangs, revert back to default
                // blocking behavior.
                Binder.defaultBlocking(mContentProvider.asBinder());
            }
        }
    }

    private void beforeRemote() {
        if (mAnrRunnable != null) {
            sAnrHandler.postDelayed(mAnrRunnable, mAnrTimeout);
        }
    }

    private void afterRemote() {
        if (mAnrRunnable != null) {
            sAnrHandler.removeCallbacks(mAnrRunnable);
        }
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public @Nullable Cursor query(@NonNull Uri url, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) throws RemoteException {
        return query(url, projection, selection,  selectionArgs, sortOrder, null);
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder, @Nullable CancellationSignal cancellationSignal)
                    throws RemoteException {
        Bundle queryArgs =
                ContentResolver.createSqlQueryBundle(selection, selectionArgs, sortOrder);
        return query(uri, projection, queryArgs, cancellationSignal);
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            Bundle queryArgs, @Nullable CancellationSignal cancellationSignal)
                    throws RemoteException {
        Preconditions.checkNotNull(uri, "url");

        beforeRemote();
        addStackTrace(uri, false);

        try {
            ICancellationSignal remoteCancellationSignal = null;
            if (cancellationSignal != null) {
                cancellationSignal.throwIfCanceled();
                remoteCancellationSignal = mContentProvider.createCancellationSignal();
                cancellationSignal.setRemote(remoteCancellationSignal);
            }
            final Cursor cursor = mContentProvider.query(
                    mPackageName, uri, projection, queryArgs, remoteCancellationSignal);
            if (cursor == null) {
                return null;
            }
            return new CursorWrapperInner(cursor);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            removeStackTrace(uri, false);
            afterRemote();
        }
    }

    /** See {@link ContentProvider#getType ContentProvider.getType} */
    public @Nullable String getType(@NonNull Uri url) throws RemoteException {
        Preconditions.checkNotNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.getType(url);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#getStreamTypes ContentProvider.getStreamTypes} */
    public @Nullable String[] getStreamTypes(@NonNull Uri url, @NonNull String mimeTypeFilter)
            throws RemoteException {
        Preconditions.checkNotNull(url, "url");
        Preconditions.checkNotNull(mimeTypeFilter, "mimeTypeFilter");

        beforeRemote();
        try {
            return mContentProvider.getStreamTypes(url, mimeTypeFilter);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#canonicalize} */
    public final @Nullable Uri canonicalize(@NonNull Uri url) throws RemoteException {
        Preconditions.checkNotNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.canonicalize(mPackageName, url);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#uncanonicalize} */
    public final @Nullable Uri uncanonicalize(@NonNull Uri url) throws RemoteException {
        Preconditions.checkNotNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.uncanonicalize(mPackageName, url);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#refresh} */
    public boolean refresh(Uri url, @Nullable Bundle args,
            @Nullable CancellationSignal cancellationSignal) throws RemoteException {
        Preconditions.checkNotNull(url, "url");

        beforeRemote();
        try {
            ICancellationSignal remoteCancellationSignal = null;
            if (cancellationSignal != null) {
                cancellationSignal.throwIfCanceled();
                remoteCancellationSignal = mContentProvider.createCancellationSignal();
                cancellationSignal.setRemote(remoteCancellationSignal);
            }
            return mContentProvider.refresh(mPackageName, url, args, remoteCancellationSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#insert ContentProvider.insert} */
    public @Nullable Uri insert(@NonNull Uri url, @Nullable ContentValues initialValues)
            throws RemoteException {
        Preconditions.checkNotNull(url, "url");

        beforeRemote();
        addStackTrace(url, true);
        try {
            return mContentProvider.insert(mPackageName, url, initialValues);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            removeStackTrace(url, true);
            afterRemote();
        }
    }

    private void addStackTrace(Uri url, boolean writePerm) {
        String urlString;
        List<String> perms = new ArrayList<String>();

        if (url == null)
            return;
        else
            urlString = url.toString();

        if (writePerm) {
            if (urlString.startsWith(CallLog.CONTENT_URI.toString())) {
                perms.add(WRITE_CALL_LOG);
            } else if (urlString.startsWith(CalendarContract.CONTENT_URI.toString())) {
                perms.add(WRITE_CALENDAR);
            } else if (urlString.startsWith(ContactsContract.AUTHORITY_URI.toString())) {
                perms.add(WRITE_CONTACTS);
            } else if (urlString.startsWith(smsUri)) {
                perms.add(WRITE_SMS);
            }
        } else {
            if (urlString.startsWith(CallLog.CONTENT_URI.toString())) {
                perms.add(READ_CALL_LOG);
            } else if (urlString.startsWith(CalendarContract.CONTENT_URI.toString())) {
                perms.add(READ_CALENDAR);
            } else if (urlString.startsWith(ContactsContract.AUTHORITY_URI.toString())) {
                perms.add(READ_CONTACTS);
            } else if (urlString.startsWith(smsUri)) {
                perms.add(READ_SMS);
            }
        }

	if (perms.isEmpty())
	    return;

        try {
            PermissionRequest permissionRequest = new PermissionRequest(perms, Thread.currentThread().getPrivacyPurpose());
            IPermissionRequestManager permissionRequestService = IPermissionRequestManager.Stub.asInterface(ServiceManager.getService("permission_request"));
            permissionRequestService.add(permissionRequest);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void removeStackTrace(Uri url, boolean writePerm) {
        String urlString;
        List<String> perms = new ArrayList<String>();

        if (url == null)
            return;
        else
            urlString = url.toString();

        if (writePerm) {
            if (urlString.startsWith(CallLog.CONTENT_URI.toString())) {
                perms.add(WRITE_CALL_LOG);
            } else if (urlString.startsWith(CalendarContract.CONTENT_URI.toString())) {
                perms.add(WRITE_CALENDAR);
            } else if (urlString.startsWith(ContactsContract.AUTHORITY_URI.toString())) {
                perms.add(WRITE_CONTACTS);
            }
        } else {
            if (urlString.startsWith(CallLog.CONTENT_URI.toString())) {
                perms.add(READ_CALL_LOG);
            } else if (urlString.startsWith(CalendarContract.CONTENT_URI.toString())) {
                perms.add(READ_CALENDAR);
            } else if (urlString.startsWith(ContactsContract.AUTHORITY_URI.toString())) {
                perms.add(READ_CONTACTS);
            }
        }

	if (perms.isEmpty())
	    return;

        try {
            IPermissionRequestManager permissionRequestService = IPermissionRequestManager.Stub.asInterface(ServiceManager.getService("permission_request"));
            permissionRequestService.remove(Process.myUid(), perms);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** See {@link ContentProvider#bulkInsert ContentProvider.bulkInsert} */
    public int bulkInsert(@NonNull Uri url, @NonNull ContentValues[] initialValues)
            throws RemoteException {
        Preconditions.checkNotNull(url, "url");
        Preconditions.checkNotNull(initialValues, "initialValues");

        beforeRemote();
        addStackTrace(url, true);

        try {
            return mContentProvider.bulkInsert(mPackageName, url, initialValues);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            removeStackTrace(url, true);
            afterRemote();
        }
    }

    /** See {@link ContentProvider#delete ContentProvider.delete} */
    public int delete(@NonNull Uri url, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException {
        Preconditions.checkNotNull(url, "url");

        beforeRemote();
        addStackTrace(url, true);

        try {
            return mContentProvider.delete(mPackageName, url, selection, selectionArgs);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            removeStackTrace(url, true);
            afterRemote();
        }
    }

    /** See {@link ContentProvider#update ContentProvider.update} */
    public int update(@NonNull Uri url, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException {
        Preconditions.checkNotNull(url, "url");

        beforeRemote();
        addStackTrace(url, true);

        try {
            return mContentProvider.update(mPackageName, url, values, selection, selectionArgs);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            removeStackTrace(url, true);
            afterRemote();
        }
    }

    /**
     * See {@link ContentProvider#openFile ContentProvider.openFile}.  Note that
     * this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openFileDescriptor
     * ContentResolver.openFileDescriptor} API instead.
     */
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri url, @NonNull String mode)
            throws RemoteException, FileNotFoundException {
        return openFile(url, mode, null);
    }

    /**
     * See {@link ContentProvider#openFile ContentProvider.openFile}.  Note that
     * this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openFileDescriptor
     * ContentResolver.openFileDescriptor} API instead.
     */
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri url, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        Preconditions.checkNotNull(url, "url");
        Preconditions.checkNotNull(mode, "mode");

        beforeRemote();
        try {
            ICancellationSignal remoteSignal = null;
            if (signal != null) {
                signal.throwIfCanceled();
                remoteSignal = mContentProvider.createCancellationSignal();
                signal.setRemote(remoteSignal);
            }
            return mContentProvider.openFile(mPackageName, url, mode, remoteSignal, null);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /**
     * See {@link ContentProvider#openAssetFile ContentProvider.openAssetFile}.
     * Note that this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openAssetFileDescriptor
     * ContentResolver.openAssetFileDescriptor} API instead.
     */
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri url, @NonNull String mode)
            throws RemoteException, FileNotFoundException {
        return openAssetFile(url, mode, null);
    }

    /**
     * See {@link ContentProvider#openAssetFile ContentProvider.openAssetFile}.
     * Note that this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openAssetFileDescriptor
     * ContentResolver.openAssetFileDescriptor} API instead.
     */
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri url, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        Preconditions.checkNotNull(url, "url");
        Preconditions.checkNotNull(mode, "mode");

        beforeRemote();
        try {
            ICancellationSignal remoteSignal = null;
            if (signal != null) {
                signal.throwIfCanceled();
                remoteSignal = mContentProvider.createCancellationSignal();
                signal.setRemote(remoteSignal);
            }
            return mContentProvider.openAssetFile(mPackageName, url, mode, remoteSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#openTypedAssetFile ContentProvider.openTypedAssetFile} */
    public final @Nullable AssetFileDescriptor openTypedAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mimeType, @Nullable Bundle opts)
                    throws RemoteException, FileNotFoundException {
        return openTypedAssetFileDescriptor(uri, mimeType, opts, null);
    }

    /** See {@link ContentProvider#openTypedAssetFile ContentProvider.openTypedAssetFile} */
    public final @Nullable AssetFileDescriptor openTypedAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mimeType, @Nullable Bundle opts, @Nullable CancellationSignal signal)
                    throws RemoteException, FileNotFoundException {
        Preconditions.checkNotNull(uri, "uri");
        Preconditions.checkNotNull(mimeType, "mimeType");

        beforeRemote();
        try {
            ICancellationSignal remoteSignal = null;
            if (signal != null) {
                signal.throwIfCanceled();
                remoteSignal = mContentProvider.createCancellationSignal();
                signal.setRemote(remoteSignal);
            }
            return mContentProvider.openTypedAssetFile(
                    mPackageName, uri, mimeType, opts, remoteSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#applyBatch ContentProvider.applyBatch} */
    public @NonNull ContentProviderResult[] applyBatch(
            @NonNull ArrayList<ContentProviderOperation> operations)
                    throws RemoteException, OperationApplicationException {
        Preconditions.checkNotNull(operations, "operations");

        beforeRemote();
        try {
            return mContentProvider.applyBatch(mPackageName, operations);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#call(String, String, Bundle)} */
    public @Nullable Bundle call(@NonNull String method, @Nullable String arg,
            @Nullable Bundle extras) throws RemoteException {
        Preconditions.checkNotNull(method, "method");

        beforeRemote();
        try {
            return mContentProvider.call(mPackageName, method, arg, extras);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /**
     * Closes this client connection, indicating to the system that the
     * underlying {@link ContentProvider} is no longer needed.
     */
    @Override
    public void close() {
        closeInternal();
    }

    /**
     * @deprecated replaced by {@link #close()}.
     */
    @Deprecated
    public boolean release() {
        return closeInternal();
    }

    private boolean closeInternal() {
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            // We can't do ANR checks after we cease to exist! Reset any
            // blocking behavior changes we might have made.
            setDetectNotResponding(0);

            if (mStable) {
                return mContentResolver.releaseProvider(mContentProvider);
            } else {
                return mContentResolver.releaseUnstableProvider(mContentProvider);
            }
        } else {
            return false;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Get a reference to the {@link ContentProvider} that is associated with this
     * client. If the {@link ContentProvider} is running in a different process then
     * null will be returned. This can be used if you know you are running in the same
     * process as a provider, and want to get direct access to its implementation details.
     *
     * @return If the associated {@link ContentProvider} is local, returns it.
     * Otherwise returns null.
     */
    public @Nullable ContentProvider getLocalContentProvider() {
        return ContentProvider.coerceToLocalContentProvider(mContentProvider);
    }

    /** {@hide} */
    public static void releaseQuietly(ContentProviderClient client) {
        if (client != null) {
            try {
                client.release();
            } catch (Exception ignored) {
            }
        }
    }

    private class NotRespondingRunnable implements Runnable {
        @Override
        public void run() {
            Log.w(TAG, "Detected provider not responding: " + mContentProvider);
            mContentResolver.appNotRespondingViaProvider(mContentProvider);
        }
    }

    private final class CursorWrapperInner extends CrossProcessCursorWrapper {
        private final CloseGuard mCloseGuard = CloseGuard.get();

        CursorWrapperInner(Cursor cursor) {
            super(cursor);
            mCloseGuard.open("close");
        }

        @Override
        public void close() {
            mCloseGuard.close();
            super.close();
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mCloseGuard != null) {
                    mCloseGuard.warnIfOpen();
                }

                close();
            } finally {
                super.finalize();
            }
        }
    }
}
