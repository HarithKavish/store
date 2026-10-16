package com.harithkavish.store.ui

import com.harithkavish.store.catalog.BuildManifest
import com.harithkavish.store.catalog.CatalogApp
import com.harithkavish.store.catalog.SemVer

enum class AppStatus { NOT_INSTALLED, UPDATE_AVAILABLE, UP_TO_DATE, UNKNOWN, DOWNLOADING }

data class AppRowState(
    val app: CatalogApp,
    val manifest: BuildManifest?,
    val installedVersion: String?,
    val downloading: Boolean = false
) {
    val status: AppStatus
        get() = when {
            downloading -> AppStatus.DOWNLOADING
            app.androidBuild?.packageName == null -> AppStatus.UNKNOWN
            manifest == null -> AppStatus.UNKNOWN
            installedVersion == null -> AppStatus.NOT_INSTALLED
            SemVer.isNewer(manifest.version, installedVersion) -> AppStatus.UPDATE_AVAILABLE
            else -> AppStatus.UP_TO_DATE
        }

    val hasUpdate: Boolean get() = status == AppStatus.UPDATE_AVAILABLE
}
