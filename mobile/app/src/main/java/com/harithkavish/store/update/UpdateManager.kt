package com.harithkavish.store.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

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
        private const val PREFS_NAME = "update_manager_pending"
    }

    /**
     * DownloadManager's ACTION_DOWNLOAD_COMPLETE is broadcast system-wide for every
     * completed download on the device, from any app -- not just ours -- so this is
     * how DownloadCompleteReceiver tells "an update Store queued" apart from "some
     * unrelated download from another app" before acting on it.
     *
     * Backed by SharedPreferences rather than an in-memory map: Android routinely
     * kills Store's process while a multi-MB APK downloads in the background, and
     * the manifest-registered receiver then runs in a fresh process with nothing
     * else to go on. A record here also survives past a failed install attempt, so
     * StoreListActivity can retry it once the app is foregrounded (see
     * [launchInstaller]'s doc on why that retry is necessary).
     */
    private fun prefs(): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        persistPending(id, PendingUpdate(slug, displayName, version))
        return id
    }

    /** The update queued for [downloadId], if any. Left in place until [clearPending]. */
    fun pendingUpdate(downloadId: Long): PendingUpdate? {
        val raw = prefs().getString(downloadId.toString(), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PendingUpdate(json.getString("slug"), json.getString("displayName"), json.getString("version"))
        }.getOrNull()
    }

    /** Every download still awaiting a confirmed install. */
    fun allPendingDownloadIds(): List<Long> = prefs().all.keys.mapNotNull { it.toLongOrNull() }

    fun clearPending(downloadId: Long) {
        prefs().edit().remove(downloadId.toString()).apply()
    }

    /**
     * Removes the downloaded APK for [pending], if any is still on disk. Call this
     * alongside [clearPending] once a download's outcome is settled (installed, or
     * confirmed dead via [isTerminallyFailed]) -- not at enqueue time: two requests
     * for the same slug/version share this exact destination filename, so deleting it
     * unconditionally on a fresh enqueue can destroy a still-running or
     * already-succeeded-but-not-yet-installed earlier download for that same build.
     */
    fun deleteDownloadedFile(pending: PendingUpdate) {
        val fileName = "${pending.slug}-v${pending.version}.apk"
        context.getExternalFilesDir("updates")?.let { dir ->
            File(dir, fileName).takeIf { it.exists() }?.delete()
        }
    }

    private fun persistPending(downloadId: Long, pending: PendingUpdate) {
        val json = JSONObject().apply {
            put("slug", pending.slug)
            put("displayName", pending.displayName)
            put("version", pending.version)
        }
        prefs().edit().putString(downloadId.toString(), json.toString()).apply()
    }

    fun canRequestInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installPermissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Call once [downloadId] is reported complete. Returns false if the file couldn't
     * be resolved or the installer couldn't be started.
     *
     * Android 10+ restricts starting an Activity from a process with no visible
     * window, which is exactly what a manifest-registered BroadcastReceiver is --
     * and it fails that *silently*, without throwing, so a `true` result from a
     * background caller is not proof the installer actually appeared. The pending
     * record is left in place (see [pendingUpdate]) specifically so a foreground
     * caller -- StoreListActivity.onResume -- can call this again once the app has
     * a visible window, where the restriction doesn't apply.
     */
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

    /**
     * True when [downloadId] has reached a state DownloadManager will never resolve on
     * its own -- it failed, its record is gone entirely (e.g. the user cleared it from
     * the system Downloads app), or it succeeded but the file it wrote has since
     * disappeared (deleted externally, or by [deleteDownloadedFile] once this same
     * outcome was detected for an earlier id sharing the same slug/version). A caller
     * holding a pending record for such an id should clear it: retrying gets nothing
     * back but a "Downloading..." row stuck forever, and [allPendingDownloadIds]
     * growing without bound.
     */
    fun isTerminallyFailed(downloadId: Long): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return true
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0) return true
            return when (cursor.getInt(statusIndex)) {
                DownloadManager.STATUS_FAILED -> true
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUri = if (uriIndex >= 0) cursor.getString(uriIndex) else null
                    localUri == null || uriToFile(localUri)?.exists() != true
                }
                else -> false
            }
        }
        return true
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
