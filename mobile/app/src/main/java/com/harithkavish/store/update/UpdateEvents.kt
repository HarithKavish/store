package com.harithkavish.store.update

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class DownloadHandled(
    val downloadId: Long,
    val update: PendingUpdate,
    val installerLaunched: Boolean
)

/**
 * Bridges DownloadCompleteReceiver (which runs outside any Activity) back to
 * whatever UI is on screen — used to clear a row's "Downloading…" state and to
 * advance UpdateAllCoordinator's queue once each app's installer has launched.
 */
object UpdateEvents {
    private val _events = MutableSharedFlow<DownloadHandled>(extraBufferCapacity = 8)
    val events: SharedFlow<DownloadHandled> = _events.asSharedFlow()

    fun notifyDownloadHandled(downloadId: Long, update: PendingUpdate, installerLaunched: Boolean) {
        _events.tryEmit(DownloadHandled(downloadId, update, installerLaunched))
    }
}
