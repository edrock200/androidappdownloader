package io.github.edrock200.appdownloader.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.edrock200.appdownloader.data.Backup
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A backup file found in the Downloads folder. */
data class StoredBackup(val name: String, val modified: Long, val uri: Uri)

/** Reading and writing backup files, with a Downloads-folder fallback for TVs without a file picker. */
object BackupStorage {

    private const val PREFIX = "AppDownloader-backup"
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun fileName(now: Long = System.currentTimeMillis()) =
        "$PREFIX-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now)) + ".json"

    /** Many TVs (Fire TV, most Android TVs) have no system file picker for saving. */
    fun hasFilePicker(context: Context): Boolean {
        val pm = context.packageManager
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        return create.resolveActivity(pm) != null && open.resolveActivity(pm) != null
    }

    /** Some devices without the full picker still have an app that can hand over a file ("get content"). */
    fun hasGetContent(context: Context): Boolean =
        Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .resolveActivity(context.packageManager) != null

    /** Shown when no backup is found in Downloads. */
    fun noBackupsMessage(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "No backups found. On this Android version App Downloader can only see backups it saved itself " +
            "since it was installed. To use a backup from elsewhere, choose \"Restore from link or aftv.news code\"."
    } else {
        "No backups found in Downloads. Save one first, or restore from a link."
    }

    fun delete(context: Context, backup: StoredBackup): Boolean = runCatching {
        if (backup.uri.scheme == "file") File(backup.uri.path!!).delete()
        else context.contentResolver.delete(backup.uri, null, null) > 0
    }.getOrDefault(false)

    /** True when the Downloads fallback first needs the storage permission (Android 6–9). */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /** Scanning settings QR codes uses Google's scanner, which needs a camera and Play services. */
    fun canScanQr(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            runCatching {
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
            }.getOrDefault(false)

    fun write(context: Context, uri: Uri, text: String) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't open the file")
        out.use { it.write(text.toByteArray()) }
    }

    fun read(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
        return input.use { stream -> Backup.readBounded(stream, MAX_BYTES).decodeToString() }
    }

    /** Saves to the public Downloads folder. Returns where it was saved, for the message. */
    fun saveToDownloads(context: Context, name: String, text: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Couldn't create the backup file")
            write(context, uri, text)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeText(text)
        }
        return "Download/$name"
    }

    /** Backups in Downloads, newest first. On Android 10+ only files this app saved are visible. */
    fun listDownloads(context: Context): List<StoredBackup> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val found = mutableListOf<StoredBackup>()
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$PREFIX%"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    found += StoredBackup(
                        name = c.getString(1),
                        modified = c.getLong(2) * 1000,
                        uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                    )
                }
            }
            return found
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return (dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { StoredBackup(it.name, it.lastModified(), Uri.fromFile(it)) }
    }
}
