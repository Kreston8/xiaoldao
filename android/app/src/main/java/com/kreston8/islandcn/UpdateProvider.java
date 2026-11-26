package com.kreston8.islandcn;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands exactly one file to the system installer: the downloaded package in the cache directory.
 *
 * <p>Needed because {@code file://} intents stopped working in Android 7, and a content URI needs a
 * provider. Written by hand instead of pulling in androidx {@code FileProvider}, so the app keeps
 * its single-purpose permission set and gains no dependency: the provider is not exported, every
 * other path is refused, and the only way in is the one-shot read grant the installer intent
 * carries.
 */
public final class UpdateProvider extends ContentProvider {

    /** Same authority as {@link Updater#AUTHORITY}; kept literal so the manifest and code agree. */
    static final String AUTHORITY = "com.kreston8.islandcn.update";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri == null || !Updater.APK.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException("只提供安装包这一个文件");
        }
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("只读");
        }
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("没有上下文");
        }
        File file = Updater.apk(context);
        if (!file.isFile() || file.length() < 10_000L) {
            throw new FileNotFoundException("安装包不在：" + file.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
