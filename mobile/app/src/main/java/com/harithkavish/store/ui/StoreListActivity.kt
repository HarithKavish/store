package com.harithkavish.store.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.harithkavish.store.R
import com.harithkavish.store.catalog.BuildManifest
import com.harithkavish.store.catalog.CatalogApp
import com.harithkavish.store.catalog.CatalogRepository
import com.harithkavish.store.catalog.SemVer
import com.harithkavish.store.databinding.ActivityStoreListBinding
import com.harithkavish.store.installed.InstalledAppsResolver
import com.harithkavish.store.update.UpdateAllCoordinator
import com.harithkavish.store.update.UpdateEvents
import com.harithkavish.store.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StoreListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoreListBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val catalogRepository = CatalogRepository()
    private lateinit var installedApps: InstalledAppsResolver
    private lateinit var updateManager: UpdateManager
    private lateinit var updateAllCoordinator: UpdateAllCoordinator

    private lateinit var header: HeaderAdapter
    private lateinit var banner: UpdatesBannerAdapter
    private lateinit var featured: FeaturedAdapter
    private lateinit var section: SectionAdapter
    private lateinit var apps: AppAdapter

    /** Every app, in catalogue order, before the search and the filter. */
    private var rows: List<AppRowState> = emptyList()
    private var query: String = ""
    private var filter: Filter = Filter.ALL
    /** "2 of 3…" while Update all runs. */
    private var updateAllProgress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoreListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        installedApps = InstalledAppsResolver(packageManager)
        updateManager = UpdateManager(applicationContext)

        val icons = IconLoader(scope)
        header = HeaderAdapter(
            onQuery = { query = it; render() },
            onFilter = { filter = it; render() }
        )
        banner = UpdatesBannerAdapter { startUpdateAll() }
        featured = FeaturedAdapter(icons) { onRowAction(it) }
        section = SectionAdapter()
        apps = AppAdapter(icons) { onRowAction(it) }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = ConcatAdapter(header, banner, featured, section, apps)
        binding.appList.itemAnimator?.changeDuration = 120

        binding.swipeRefresh.setColorSchemeColors(getColor(R.color.update))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.surface_raised))
        binding.swipeRefresh.setOnRefreshListener { loadCatalog() }

        updateAllCoordinator = UpdateAllCoordinator(
            startDownload = { requestUpdate(it) },
            onProgress = { completed, total ->
                updateAllProgress = getString(R.string.update_all_progress, completed + 1, total)
                render()
            },
            onFinished = {
                updateAllProgress = null
                refreshInstalledStatuses()
            }
        )

        scope.launch {
            UpdateEvents.events.collect { event ->
                rows = rows.map { row ->
                    if (row.app.slug == event.update.slug) row.copy(downloading = false) else row
                }
                if (!event.installerLaunched) {
                    Toast.makeText(
                        this@StoreListActivity,
                        getString(R.string.installer_failed, event.update.displayName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                render()
                updateAllCoordinator.onStepHandled(event.update.slug)
            }
        }

        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        // Installed versions can change any time the user leaves and returns
        // (they may have just finished an install), so re-check on every resume.
        if (rows.isNotEmpty()) refreshInstalledStatuses()
        retryPendingInstalls()
    }

    /**
     * Re-attempts any download DownloadCompleteReceiver couldn't hand to the
     * installer for certain (it can't tell, from a background receiver, whether
     * Android silently dropped that startActivity -- see UpdateManager.launchInstaller).
     * onResume always has a visible window, so the same call here isn't subject to
     * that restriction and reliably prompts the user.
     */
    private fun retryPendingInstalls() {
        for (downloadId in updateManager.allPendingDownloadIds()) {
            val pending = updateManager.pendingUpdate(downloadId) ?: continue
            val packageName = rows.firstOrNull { it.app.slug == pending.slug }?.app?.androidBuild?.packageName
            val installedVersion = packageName?.let { installedApps.installedVersion(it) }
            when {
                installedVersion != null && !SemVer.isNewer(pending.version, installedVersion) -> {
                    // Already installed (or a newer build already is) -- nothing left
                    // to do, and no reason to keep the APK around any longer.
                    updateManager.clearPending(downloadId)
                    updateManager.deleteDownloadedFile(pending)
                }
                updateManager.isTerminallyFailed(downloadId) -> {
                    // The download itself failed, or its record is gone -- retrying
                    // gets nothing back but a permanently stuck entry.
                    updateManager.clearPending(downloadId)
                    updateManager.deleteDownloadedFile(pending)
                }
                else -> updateManager.launchInstaller(downloadId)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadCatalog() {
        binding.swipeRefresh.isRefreshing = true
        scope.launch {
            val catalog = runCatching { catalogRepository.fetchCatalog() }.getOrNull()
            if (catalog == null) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@StoreListActivity, R.string.catalog_load_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            // Installed-version lookups are fast, synchronous PackageManager calls;
            // manifest fetches are network calls, run concurrently per app.
            val manifestDeferred = catalog.apps.associateWith { app ->
                val manifestUrl = app.androidBuild?.manifestUrl
                if (manifestUrl != null) async { catalogRepository.fetchManifest(manifestUrl) } else null
            }

            rows = catalog.apps
                .filter { it.androidBuild != null }
                .map { app -> buildRow(app, manifestDeferred[app]?.await()) }

            binding.swipeRefresh.isRefreshing = false
            render()
        }
    }

    private fun refreshInstalledStatuses() {
        rows = rows.map { it.copy(installedVersion = installedVersionFor(it.app)) }
        render()
    }

    private fun buildRow(app: CatalogApp, manifest: BuildManifest?): AppRowState =
        AppRowState(app = app, manifest = manifest, installedVersion = installedVersionFor(app))

    private fun installedVersionFor(app: CatalogApp): String? =
        app.androidBuild?.packageName?.let { installedApps.installedVersion(it) }

    private fun render() {
        val updates = rows.filter { it.hasUpdate }
        header.setState(filter, updates.size)
        banner.submit(if (filter == Filter.INSTALLED) emptyList() else updates, updateAllProgress)

        val browsing = filter == Filter.ALL && query.isBlank()
        // The website's featured app: the catalogue's first featured entry.
        featured.submit(if (browsing) rows.firstOrNull { it.app.featured } else null)

        // Updates first, so the apps that need you are at the top; otherwise catalogue order.
        val visible = rows
            .filter { matchesFilter(it) && matchesQuery(it.app) }
            .sortedByDescending { it.hasUpdate }
        apps.submitList(visible)
        section.submit(
            if (visible.isEmpty()) null else when (filter) {
                Filter.ALL -> R.string.section_all
                Filter.UPDATES -> R.string.section_updates
                Filter.INSTALLED -> R.string.section_installed
            }
        )

        val empty = rows.isNotEmpty() && visible.isEmpty()
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        binding.emptyView.setText(
            when {
                query.isNotBlank() -> R.string.empty_search
                filter == Filter.UPDATES -> R.string.empty_updates
                else -> R.string.empty_installed
            }
        )
    }

    private fun matchesFilter(row: AppRowState): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.UPDATES -> row.hasUpdate
        Filter.INSTALLED -> row.installedVersion != null
    }

    private fun matchesQuery(app: CatalogApp): Boolean {
        if (query.isBlank()) return true
        val haystack = "${app.name} ${app.tagline} ${app.category}".lowercase()
        return haystack.contains(query.trim().lowercase())
    }

    private fun startUpdateAll() {
        val candidates = rows.filter { it.hasUpdate }
        if (candidates.isEmpty() || updateAllCoordinator.isRunning) return
        updateAllProgress = getString(R.string.update_all_progress, 1, candidates.size)
        render()
        updateAllCoordinator.start(candidates)
    }

    private fun onRowAction(row: AppRowState) {
        when (row.displayStatus()) {
            AppStatus.NOT_INSTALLED, AppStatus.UPDATE_AVAILABLE -> requestUpdate(row)
            AppStatus.UP_TO_DATE, AppStatus.UNKNOWN -> openIfInstalled(row)
            AppStatus.DOWNLOADING -> Unit
        }
    }

    private fun openIfInstalled(row: AppRowState) {
        val packageName = row.app.androidBuild?.packageName ?: return
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
        } else if (row.manifest != null) {
            requestUpdate(row)
        }
    }

    private fun requestUpdate(row: AppRowState) {
        val manifest = row.manifest
        val apkUrl = manifest?.apkUrl
        if (manifest == null || apkUrl.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.no_build_yet, row.app.name), Toast.LENGTH_SHORT).show()
            updateAllCoordinator.onStepHandled(row.app.slug)
            return
        }

        if (!updateManager.canRequestInstallPackages()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_message)
                .setNegativeButton(R.string.cancel) { _, _ -> updateAllCoordinator.onStepHandled(row.app.slug) }
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(updateManager.installPermissionSettingsIntent())
                    updateAllCoordinator.onStepHandled(row.app.slug)
                }
                .setOnCancelListener { updateAllCoordinator.onStepHandled(row.app.slug) }
                .show()
            return
        }

        rows = rows.map { if (it.app.slug == row.app.slug) it.copy(downloading = true) else it }
        render()
        updateManager.enqueueDownload(row.app.slug, row.app.name, manifest.version, apkUrl)
    }
}
