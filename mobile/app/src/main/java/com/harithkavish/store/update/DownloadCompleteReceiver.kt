package com.harithkavish.store.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return

        // Not one of ours: some other app's download completed on the same
        // system-wide broadcast. Nothing to do.
        val pending = UpdateManager.pendingDownloads.remove(downloadId) ?: return

        val launched = UpdateManager(context.applicationContext).launchInstaller(downloadId)
        UpdateEvents.notifyDownloadHandled(downloadId, pending, launched)
    }
}
