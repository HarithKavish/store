package com.harithkavish.store.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var adapter: AppAdapter
    private lateinit var updateAllCoordinator: UpdateAllCoordinator

    /** Master rows, before the search filter is applied. */
    private var rows: List<AppRowState> = emptyList()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoreListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        installedApps = InstalledAppsResolver(packageManager)
        updateManager = UpdateManager(applicationContext)

        adapter = AppAdapter(scope) { row -> onRowAction(row) }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadCatalog() }
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })

        updateAllCoordinator = UpdateAllCoordinator(
            startDownload = { requestUpdate(it) },
            onProgress = { completed, total ->
                binding.updateAllButton.text = getString(R.string.update_all_progress, completed + 1, total)
            },
            onFinished = {
                binding.updateAllButton.isEnabled = true
                binding.updateAllButton.text = getString(R.string.update_all)
                refreshInstalledStatuses()
            }
        )
        binding.updateAllButton.setOnClickListener {
            val candidates = rows.filter { it.hasUpdate }
            if (candidates.isEmpty()) return@setOnClickListener
            binding.updateAllButton.isEnabled = false
            updateAllCoordinator.start(candidates)
        }

        scope.launch {
            UpdateEvents.events.collect { event ->
                rows = rows.map { row ->
                    if (row.app.slug == event.update.slug) row.copy(downloading = false) else row
                }
                if (!event.installerLaunched) {
                    Toast.makeText(
                        this@StoreListActivity,
                        "${event.update.displayName}: couldn't open the installer",
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
                .sortedWith(compareByDescending<AppRowState> { it.app.featured }.thenBy { it.app.name })

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
        val visible = rows.filter { matchesQuery(it.app) }
        adapter.submitList(visible)
        binding.emptyView.visibility = if (rows.isNotEmpty() && visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.emptyView.text = getString(R.string.empty_search)

        if (!updateAllCoordinator.isRunning) {
            val updateCount = rows.count { it.hasUpdate }
            binding.updateAllButton.visibility = if (updateCount > 0) android.view.View.VISIBLE else android.view.View.GONE
            binding.updateAllButton.text = getString(R.string.update_all)
        }
    }

    private fun matchesQuery(app: CatalogApp): Boolean {
        if (query.isBlank()) return true
        val haystack = "${app.name} ${app.tagline} ${app.category}".lowercase()
        return haystack.contains(query.trim().lowercase())
    }

    private fun onRowAction(row: AppRowState) {
        when (row.status) {
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
            Toast.makeText(this, "No build available for ${row.app.name} yet", Toast.LENGTH_SHORT).show()
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
