package com.harithkavish.store.update

import com.harithkavish.store.ui.AppRowState

/**
 * Drives the "Update all" action: queues every app that currently has an
 * update available and starts them one at a time, so the system installer
 * isn't asked to confirm several APKs at once. Each step still ends in one
 * user tap on the installer -- see the platform note in the project plan.
 */
class UpdateAllCoordinator(
    private val startDownload: (AppRowState) -> Unit,
    private val onProgress: (completed: Int, total: Int) -> Unit,
    private val onFinished: () -> Unit
) {
    private val queue = ArrayDeque<AppRowState>()
    private var total = 0
    private var completed = 0
    private var runningSlug: String? = null

    val isRunning: Boolean get() = runningSlug != null

    fun start(candidates: List<AppRowState>) {
        queue.clear()
        queue.addAll(candidates)
        total = candidates.size
        completed = 0
        if (queue.isEmpty()) {
            onFinished()
            return
        }
        onProgress(completed, total)
        advance()
    }

    /** Call when the download+install-intent for [slug] has been handled (success or failure). */
    fun onStepHandled(slug: String) {
        if (slug != runningSlug) return
        runningSlug = null
        completed += 1
        onProgress(completed, total)
        advance()
    }

    private fun advance() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            onFinished()
            return
        }
        runningSlug = next.app.slug
        startDownload(next)
    }
}
