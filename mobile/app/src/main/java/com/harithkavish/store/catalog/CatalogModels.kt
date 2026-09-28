package com.harithkavish.store.catalog

/** Mirrors the shape of https://store.harithkavish.com/catalog.json — see store/README.md. */
data class Catalog(
    val storeName: String,
    val apps: List<CatalogApp>
)

data class CatalogApp(
    val slug: String,
    val name: String,
    val tagline: String,
    val category: String,
    val iconUrl: String?,
    val featured: Boolean,
    /** The Android build entry, if this app ships one. Apps without one (iOS-only, etc.) are hidden from Store. */
    val androidBuild: AndroidBuild?
)

data class AndroidBuild(
    val packageName: String?,
    val manifestUrl: String?,
    val directUrl: String?
)

/** One app's `latest.json` — see store/apps/<slug>/mobile/android/latest.json. */
data class BuildManifest(
    val version: String,
    /** Null when the manifest carries no usable download link. */
    val apkUrl: String?,
    val sizeBytes: Long,
    val releaseNotes: String?
)
