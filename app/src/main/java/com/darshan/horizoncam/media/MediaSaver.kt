package com.darshan.horizoncam.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.darshan.horizoncam.core.Settings
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes captures to the shared gallery on every supported Android version: DCIM/Camera next to
 * the system camera's pictures, or DCIM/HorizonCam when the user picks a separate folder.
 */
object MediaSaver {
    private const val TAG = "MediaSaver"
    const val CAMERA_FOLDER = "Camera"
    const val OWN_FOLDER = "HorizonCam"

    /** A saved photo or video. */
    class Capture(val uri: Uri, val isVideo: Boolean)

    fun folder(context: Context): String = if (Settings(context).separateFolder) OWN_FOLDER else CAMERA_FOLDER

    class Target(
        val uri: Uri?,
        val file: File?,
        val pfd: ParcelFileDescriptor,
        val displayName: String,
        val isVideo: Boolean
    )

    fun timestampName(prefix: String, ext: String): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return "${prefix}_${fmt.format(Date())}.$ext"
    }

    private fun legacyDir(context: Context, video: Boolean): File {
        val canWrite = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val dir = if (canWrite) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), folder(context))
        } else {
            File(context.getExternalFilesDir(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), OWN_FOLDER)
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Creates a pending media entry and returns a writable, seekable file descriptor. */
    fun create(context: Context, video: Boolean, displayName: String): Target? {
        val mime = if (video) "video/mp4" else "image/jpeg"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + folder(context))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values) ?: return null
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                if (pfd == null) {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }
                return Target(uri, null, pfd, displayName, video)
            } else {
                val file = File(legacyDir(context, video), displayName)
                val pfd = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                )
                return Target(null, file, pfd, displayName, video)
            }
        } catch (e: Exception) {
            Log.e(TAG, "create failed", e)
            return null
        }
    }

    /** Publishes (or deletes) a target. [done] receives the final content Uri (may be null). */
    fun finish(context: Context, t: Target, success: Boolean, done: (Uri?) -> Unit) {
        try { t.pfd.close() } catch (e: Exception) { }
        if (!success) {
            try {
                if (t.uri != null) context.contentResolver.delete(t.uri, null, null)
                t.file?.delete()
            } catch (e: Exception) { }
            done(null)
            return
        }
        if (t.uri != null && Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(t.uri, values, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "publish failed", e)
            }
            done(t.uri)
        } else if (t.file != null) {
            MediaScannerConnection.scanFile(
                context, arrayOf(t.file.absolutePath),
                arrayOf(if (t.isVideo) "video/mp4" else "image/jpeg")
            ) { _, uri -> done(uri) }
        } else {
            done(null)
        }
    }

    /** Saves a finished JPEG file/bytes into the gallery. */
    fun saveJpeg(context: Context, bytes: ByteArray?, src: File?, displayName: String, done: (Uri?) -> Unit) {
        val t = create(context, false, displayName)
        if (t == null) {
            done(null)
            return
        }
        var ok = false
        try {
            // The stream is intentionally not closed: the ParcelFileDescriptor owns the fd.
            val out = FileOutputStream(t.pfd.fileDescriptor)
            if (bytes != null) out.write(bytes)
            else src?.inputStream()?.use { it.copyTo(out) }
            out.flush()
            ok = true
        } catch (e: Exception) {
            Log.e(TAG, "write jpeg failed", e)
        }
        finish(context, t, ok, done)
    }

    /**
     * Newest photo or video in DCIM/Camera or DCIM/HorizonCam. Without a media read permission
     * Android only returns this app's own files, so other apps' pictures don't show up. Deleted
     * (or trashed) files are not returned, so the gallery button never points at a removed item.
     */
    fun latestCapture(context: Context): Capture? {
        val images = newest(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val videos = newest(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        return when {
            images == null && videos == null -> null
            videos == null -> Capture(images!!.first, false)
            images == null -> Capture(videos.first, true)
            videos.second > images.second -> Capture(videos.first, true)
            else -> Capture(images.first, false)
        }
    }

    /** (uri, date added in ms) of the newest item of [collection] in our folders. */
    private fun newest(context: Context, collection: Uri): Pair<Uri, Long>? {
        val folders = listOf(CAMERA_FOLDER, OWN_FOLDER)
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
            args = folders.map { Environment.DIRECTORY_DCIM + "/" + it + "/" }.toTypedArray()
        } else {
            @Suppress("DEPRECATION")
            selection = "(${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?)"
            args = folders.map { "%/" + Environment.DIRECTORY_DCIM + "/" + it + "/%" }.toTypedArray()
        }
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        return try {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED), query, null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getLong(0)
                ContentUris.withAppendedId(collection, id) to c.getLong(1) * 1000L
            }
        } catch (e: Exception) {
            Log.w(TAG, "latest capture query failed", e)
            null
        }
    }
}
