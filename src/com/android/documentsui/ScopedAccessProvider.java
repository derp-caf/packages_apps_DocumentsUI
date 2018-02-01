/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.documentsui;

import static android.os.storage.StorageVolume.ScopedAccessProviderContract.COL_GRANTED;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PACKAGES;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PACKAGES_COLUMNS;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PACKAGES_COL_PACKAGE;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PERMISSIONS;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PERMISSIONS_COLUMNS;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PERMISSIONS_COL_DIRECTORY;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PERMISSIONS_COL_GRANTED;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PERMISSIONS_COL_PACKAGE;
import static android.os.storage.StorageVolume.ScopedAccessProviderContract.TABLE_PERMISSIONS_COL_VOLUME_UUID;

import static com.android.documentsui.base.SharedMinimal.DEBUG;
import static com.android.documentsui.base.SharedMinimal.getExternalDirectoryName;
import static com.android.documentsui.base.SharedMinimal.getInternalDirectoryName;
import static com.android.documentsui.prefs.ScopedAccessLocalPreferences.PERMISSION_ASK;
import static com.android.documentsui.prefs.ScopedAccessLocalPreferences.PERMISSION_ASK_AGAIN;
import static com.android.documentsui.prefs.ScopedAccessLocalPreferences.PERMISSION_GRANTED;
import static com.android.documentsui.prefs.ScopedAccessLocalPreferences.PERMISSION_NEVER_ASK;
import static com.android.documentsui.prefs.ScopedAccessLocalPreferences.getAllPackages;
import static com.android.documentsui.prefs.ScopedAccessLocalPreferences.getAllPermissions;
import static com.android.documentsui.prefs.ScopedAccessLocalPreferences.setScopedAccessPermissionStatus;
import static com.android.documentsui.prefs.ScopedAccessLocalPreferences.statusAsString;
import static com.android.internal.util.Preconditions.checkArgument;

import android.app.ActivityManager;
import android.app.GrantedUriPermission;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.ArraySet;
import android.util.Log;

import com.android.documentsui.base.Providers;
import com.android.documentsui.prefs.ScopedAccessLocalPreferences.Permission;
import com.android.internal.util.ArrayUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

//TODO(b/72055774): update javadoc once implementation is finished
/**
 * Provider used to manage scoped access directory permissions.
 *
 * <p>It fetches data from 2 sources:
 *
 * <ul>
 * <li>{@link com.android.documentsui.prefs.ScopedAccessLocalPreferences} for denied permissions.
 * <li>{@link ActivityManager} for allowed permissions.
 * </ul>
 *
 * <p>And returns the results in 2 tables:
 *
 * <ul>
 * <li>{@link #TABLE_PACKAGES}: read-only table with the name of all packages
 * (column ({@link android.os.storage.StorageVolume.ScopedAccessProviderContract#COL_PACKAGE}) that
 * had a scoped access directory permission granted or denied.
 * <li>{@link #TABLE_PERMISSIONS}: writable table with the name of all packages
 * (column ({@link android.os.storage.StorageVolume.ScopedAccessProviderContract#COL_PACKAGE}) that
 * had a scoped access directory
 * (column ({@link android.os.storage.StorageVolume.ScopedAccessProviderContract#COL_DIRECTORY})
 * permission for a volume (column
 * {@link android.os.storage.StorageVolume.ScopedAccessProviderContract#COL_VOLUME_UUID}, which
 * contains the volume UUID or {@code null} if it's the primary partition) granted or denied
 * (column ({@link android.os.storage.StorageVolume.ScopedAccessProviderContract#COL_GRANTED}).
 * </ul>
 *
 * <p><b>Note:</b> the {@code query()} methods return all entries; it does not support selection or
 * projections.
 */
// TODO(b/72055774): add unit tests
public class ScopedAccessProvider extends ContentProvider {

    private static final String TAG = "ScopedAccessProvider";
    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URI_PACKAGES = 1;
    private static final int URI_PERMISSIONS = 2;

    public static final String AUTHORITY = "com.android.documentsui.scopedAccess";

    static {
        sMatcher.addURI(AUTHORITY, TABLE_PACKAGES + "/*", URI_PACKAGES);
        sMatcher.addURI(AUTHORITY, TABLE_PERMISSIONS + "/*", URI_PERMISSIONS);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        if (DEBUG) {
            Log.v(TAG, "query(" + uri + "): proj=" + Arrays.toString(projection)
                + ", sel=" + selection);
        }
        switch (sMatcher.match(uri)) {
            case URI_PACKAGES:
                return getPackagesCursor();
            case URI_PERMISSIONS:
                if (ArrayUtils.isEmpty(selectionArgs)) {
                    throw new UnsupportedOperationException("selections cannot be empty");
                }
                // For simplicity, we only support one package (which is what Settings is passing).
                if (selectionArgs.length > 1) {
                    Log.w(TAG, "Using just first entry of " + Arrays.toString(selectionArgs));
                }
                return getPermissionsCursor(selectionArgs[0]);
            default:
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
        }
    }

    private Cursor getPackagesCursor() {
        final Context context = getContext();

        // First, get the packages that were denied
        final Set<String> pkgs = getAllPackages(context);

        // Second, query AM to get all packages that have a permission.
        final ActivityManager am =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        final List<GrantedUriPermission> amPkgs = am.getGrantedUriPermissions(null).getList();
        if (!amPkgs.isEmpty()) {
            amPkgs.forEach((perm) -> pkgs.add(perm.packageName));
        }

        if (ArrayUtils.isEmpty(pkgs)) {
            if (DEBUG) Log.v(TAG, "getPackagesCursor(): nothing to do" );
            return null;
        }

        if (DEBUG) {
            Log.v(TAG, "getPackagesCursor(): denied=" + pkgs + ", granted=" + amPkgs);
        }

        // Finally, create the cursor
        final MatrixCursor cursor = new MatrixCursor(TABLE_PACKAGES_COLUMNS, pkgs.size());
        pkgs.forEach((pkg) -> cursor.addRow( new Object[] { pkg }));
        return cursor;
    }

    // TODO(b/63720392): need to unit tests to handle scenarios where the root permission of
    // a secondary volume mismatches a child permission (for example, child is allowed by root
    // is denied).
    private Cursor getPermissionsCursor(String packageName) {
        final Context context = getContext();

        // List of volumes that were granted by AM at the root level - in that case,
        // we can ignored individual grants from AM or denials from our preferences
        final Set<String> grantedVolumes = new ArraySet<>();

        // List of directories (mapped by volume uuid) that were granted by AM so they can be
        // ignored if also found on our preferences
        final Map<String, Set<String>> grantedDirsByUuid = new HashMap<>();

        // Cursor rows
        final List<Object[]> permissions = new ArrayList<>();

        // First, query AM to get all packages that have a permission.
        final ActivityManager am =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        final List<GrantedUriPermission> uriPermissions =
                am.getGrantedUriPermissions(packageName).getList();
        if (DEBUG) {
            Log.v(TAG, "am returned =" + uriPermissions);
        }
        setGrantedPermissions(packageName, uriPermissions, permissions, grantedVolumes,
                grantedDirsByUuid);

        // Now  gets the packages that were denied
        final List<Permission> rawPermissions = getAllPermissions(context);

        if (DEBUG) {
            Log.v(TAG, "rawPermissions: " + rawPermissions);
        }

        // Merge the permissions granted by AM with the denied permissions saved on our preferences.
        for (Permission rawPermission : rawPermissions) {
            if (!packageName.equals(rawPermission.pkg)) {
                if (DEBUG) {
                    Log.v(TAG,
                            "ignoring " + rawPermission + " because package is not " + packageName);
                }
                continue;
            }
            if (rawPermission.status != PERMISSION_NEVER_ASK
                    && rawPermission.status != PERMISSION_ASK_AGAIN) {
                // We only care for status where the user denied a request.
                if (DEBUG) {
                    Log.v(TAG, "ignoring " + rawPermission + " because of its status");
                }
                continue;
            }
            if (grantedVolumes.contains(rawPermission.uuid)) {
                if (DEBUG) {
                    Log.v(TAG, "ignoring " + rawPermission + " because whole volume is granted");
                }
                continue;
            }
            final Set<String> grantedDirs = grantedDirsByUuid.get(rawPermission.uuid);
            if (grantedDirs != null
                    && grantedDirs.contains(rawPermission.directory)) {
                Log.w(TAG, "ignoring " + rawPermission + " because it was granted already");
                continue;
            }
            permissions.add(new Object[] {
                    packageName, rawPermission.uuid,
                    getExternalDirectoryName(rawPermission.directory), 0
            });
        }

        if (DEBUG) {
            Log.v(TAG, "total permissions: " + permissions.size());
        }

        // Then create the cursor
        final MatrixCursor cursor = new MatrixCursor(TABLE_PERMISSIONS_COLUMNS, permissions.size());
        permissions.forEach((row) -> cursor.addRow(row));
        return cursor;
    }

    /**
     * Converts the permissions returned by AM and add it to 3 buckets ({@code permissions},
     * {@code grantedVolumes}, and {@code grantedDirsByUuid}).
     *
     * @param packageName name of package that the permissions were granted to.
     * @param uriPermissions permissions returend by AM
     * @param permissions list of permissions that can be converted to a {@link #TABLE_PERMISSIONS}
     * row.
     * @param grantedVolumes volume uuids that were granted full access.
     * @param grantedDirsByUuid directories that were granted individual acces (key is volume uuid,
     * value is list of directories).
     */
    private void setGrantedPermissions(String packageName, List<GrantedUriPermission> uriPermissions,
            List<Object[]> permissions, Set<String> grantedVolumes,
            Map<String, Set<String>> grantedDirsByUuid) {
        final List<Permission> grantedPermissions = parseGrantedPermissions(uriPermissions);

        for (Permission p : grantedPermissions) {
            // First check if it's for the full volume
            if (p.directory == null) {
                if (p.uuid == null) {
                    // Should never happen - the Scoped Directory Access API does not allow it.
                    Log.w(TAG, "ignoring entry whose uuid and directory is null");
                    continue;
                }
                grantedVolumes.add(p.uuid);
            } else {
                if (!ArrayUtils.contains(Environment.STANDARD_DIRECTORIES, p.directory)) {
                    if (DEBUG) Log.v(TAG, "Ignoring non-standard directory on " + p);
                    continue;
                }

                Set<String> dirs = grantedDirsByUuid.get(p.uuid);
                if (dirs == null) {
                    // Life would be so much easier if Android had MultiMaps...
                    dirs = new HashSet<>(1);
                    grantedDirsByUuid.put(p.uuid, dirs);
                }
                dirs.add(p.directory);
            }
        }

        if (DEBUG) {
            Log.v(TAG, "grantedVolumes=" + grantedVolumes
                    + ", grantedDirectories=" + grantedDirsByUuid);
        }
        // Add granted permissions to full volumes.
        grantedVolumes.forEach((uuid) -> permissions.add(new Object[] {
                packageName, uuid, /* dir= */ null, 1
        }));

        // Add granted permissions to individual directories
        grantedDirsByUuid.forEach((uuid, dirs) -> {
            if (grantedVolumes.contains(uuid)) {
                Log.w(TAG, "Ignoring individual grants to " + uuid + ": " + dirs);
            } else {
                dirs.forEach((dir) -> permissions.add(new Object[] {packageName, uuid, dir, 1}));
            }
        });
    }

    /**
     * Converts the permissions returned by AM to our own format.
     */
    private List<Permission> parseGrantedPermissions(List<GrantedUriPermission> uriPermissions) {
        final List<Permission> permissions = new ArrayList<>(uriPermissions.size());
        // TODO(b/72055774): we should query AUTHORITY_STORAGE or call DocumentsContract instead of
        // hardcoding the logic here.
        for (GrantedUriPermission uriPermission : uriPermissions) {
            final Uri uri = uriPermission.uri;
            final String authority = uri.getAuthority();
            if (!Providers.AUTHORITY_STORAGE.equals(authority)) {
                Log.w(TAG, "Wrong authority on " + uri);
                continue;
            }
            final List<String> pathSegments = uri.getPathSegments();
            if (pathSegments.size() < 2) {
                Log.w(TAG, "wrong path segments on " + uri);
                continue;
            }
            // TODO(b/72055774): make PATH_TREE private again if not used anymore
            if (!DocumentsContract.PATH_TREE.equals(pathSegments.get(0))) {
                Log.w(TAG, "wrong path tree on " + uri);
                continue;
            }

            final String[] uuidAndDir = pathSegments.get(1).split(":");
            // uuid and dir are either UUID:DIR (for scoped directory) or UUID: (for full volume)
            if (uuidAndDir.length != 1 && uuidAndDir.length != 2) {
                Log.w(TAG, "could not parse uuid and directory on " + uri);
                continue;
            }
            final String uuid = Providers.ROOT_ID_DEVICE.equals(uuidAndDir[0])
                    ? null // primary
                    : uuidAndDir[0]; // external volume
            final String dir = uuidAndDir.length == 1 ? null : uuidAndDir[1];
            permissions
                    .add(new Permission(uriPermission.packageName, uuid, dir, PERMISSION_GRANTED));
        }
        return permissions;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("insert(): unsupported " + uri);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("delete(): unsupported " + uri);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (sMatcher.match(uri) != URI_PERMISSIONS) {
            throw new UnsupportedOperationException("update(): unsupported " + uri);
        }

        if (DEBUG) {
            Log.v(TAG, "update(" + uri + "): " + Arrays.toString(selectionArgs) + " = " + values);
        }

        final boolean newValue = values.getAsBoolean(COL_GRANTED);

        if (!newValue) {
            // TODO(b/63720392): need to call AM to disable it
            Log.w(TAG, "Disabling permission is not supported yet");
            return 0;
        }

        // TODO(b/72055774): add unit tests for invalid input
        checkArgument(selectionArgs != null && selectionArgs.length == 3,
                "Must have exactly 3 args: package_name, (nullable) uuid, (nullable) directory: "
                        + Arrays.toString(selectionArgs));
        final String packageName = selectionArgs[0];
        final String uuid = selectionArgs[1];
        final String dir = getInternalDirectoryName(selectionArgs[2]);

        // TODO(b/63720392): for now just set it as ASK so it's still listed on queries.
        // But the right approach is to call AM to grant the permission and then remove the entry
        // from our preferences
        setScopedAccessPermissionStatus(getContext(), packageName, uuid, dir, PERMISSION_ASK);

        return 1;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final String prefix = "  ";

        final List<String> packages = new ArrayList<>();
        pw.print("Packages: ");
        try (Cursor cursor = getPackagesCursor()) {
            if (cursor == null || cursor.getCount() == 0) {
                pw.println("N/A");
            } else {
                pw.println(cursor.getCount());
                while (cursor.moveToNext()) {
                    final String pkg = cursor.getString(TABLE_PACKAGES_COL_PACKAGE);
                    packages.add(pkg);
                    pw.print(prefix);
                    pw.println(pkg);
                }
            }
        }

        pw.print("Permissions: ");
        for (int i = 0; i < packages.size(); i++) {
            final String pkg = packages.get(i);
            try (Cursor cursor = getPermissionsCursor(pkg)) {
                if (cursor == null) {
                    pw.println("N/A");
                } else {
                    pw.println(cursor.getCount());
                    while (cursor.moveToNext()) {
                        pw.print(prefix); pw.print(cursor.getString(TABLE_PERMISSIONS_COL_PACKAGE));
                        pw.print('/');
                        final String uuid = cursor.getString(TABLE_PERMISSIONS_COL_VOLUME_UUID);
                        if (uuid != null) {
                            pw.print(uuid); pw.print('>');
                        }
                        pw.print(cursor.getString(TABLE_PERMISSIONS_COL_DIRECTORY));
                        pw.print(": "); pw.println(cursor.getInt(TABLE_PERMISSIONS_COL_GRANTED) == 1);
                    }
                }
            }
        }

        pw.print("Raw permissions: ");
        final List<Permission> rawPermissions = getAllPermissions(getContext());
        if (rawPermissions.isEmpty()) {
            pw.println("N/A");
        } else {
            final int size = rawPermissions.size();
            pw.println(size);
            for (int i = 0; i < size; i++) {
                final Permission permission = rawPermissions.get(i);
                pw.print(prefix); pw.println(permission);
            }
        }
    }
}
