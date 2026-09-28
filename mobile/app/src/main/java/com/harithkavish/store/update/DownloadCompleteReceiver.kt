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

        val updateManager = UpdateManager(context.applicationContext)
        // Not one of ours: some other app's download completed on the same
        // system-wide broadcast. Nothing to do.
        val pending = updateManager.pendingUpdate(downloadId) ?: return

        // Best-effort only: this receiver has no visible window, so Android may
        // silently drop the startActivity call below (see launchInstaller's doc).
        // The record is deliberately NOT cleared here -- it stays in UpdateManager's
        // store so StoreListActivity.onResume can retry it once the app is actually
        // foregrounded, and gets cleared there once the installed version catches up.
        val launched = updateManager.launchInstaller(downloadId)
        UpdateEvents.notifyDownloadHandled(downloadId, pending, launched)
    }
}
