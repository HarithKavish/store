package com.harithkavish.store.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One app's pending install, keyed by the DownloadManager id that will deliver it. */
data class PendingUpdate(
    val slug: String,
    val displayName: String,
    val version: String
)

/**
 * Downloads an APK for any app in the catalogue — including Store itself —
 * and hands it to the system installer once the download completes.
 *
 * Mirrors Jarvis's own self-updater (my_chatgpt/mobile/App.tsx,
 * handleUpdateAndRestart): download, resolve a FileProvider content URI, fire
 * ACTION_INSTALL_PACKAGE with an ACTION_VIEW fallback for OEMs that only
 * handle VIEW for package archives, and detect the missing "install unknown
 * apps" grant so the caller can send the user to the right settings screen.
 */
class UpdateManager(private val context: Context) {

    companion object {
        /**
         * DownloadManager's ACTION_DOWNLOAD_COMPLETE is broadcast system-wide for
         * every completed download on the device, from any app -- not just ours.
         * This map is how DownloadCompleteReceiver tells "an update Store queued"
         * apart from "some unrelated download from another app" before acting on it.
         */
        val pendingDownloads = ConcurrentHashMap<Long, PendingUpdate>()
    }

    fun enqueueDownload(slug: String, displayName: String, version: String, apkUrl: String): Long {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "$slug-v$version.apk"

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle(displayName)
            setDescription("Downloading update")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, "updates", fileName)
            setAllowedOverRoaming(false)
            setMimeType("application/vnd.android.package-archive")
        }

        val id = manager.enqueue(request)
        pendingDownloads[id] = PendingUpdate(slug, displayName, version)
        return id
    }

    fun canRequestInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installPermissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Call once [downloadId] is reported complete. Returns false if the file couldn't be resolved. */
    fun launchInstaller(downloadId: Long): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val localUri = queryLocalUri(manager, downloadId) ?: return false
        val file = uriToFile(localUri) ?: return false
        if (!file.exists()) return false

        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

        val primary = Intent("android.intent.action.INSTALL_PACKAGE").apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(flags)
        }
        return try {
            context.startActivity(primary)
            true
        } catch (_: Exception) {
            // Some OEM installers only register for VIEW on this MIME type.
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(flags)
            }
            try {
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun queryLocalUri(manager: DownloadManager, downloadId: Long): String? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0 || cursor.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) return null
            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (uriIndex < 0) return null
            return cursor.getString(uriIndex)
        }
        return null
    }

    private fun uriToFile(localUri: String): File? = runCatching {
        val parsed = Uri.parse(localUri)
        if (parsed.scheme == "file") File(parsed.path!!) else File(localUri)
    }.getOrNull()
}
