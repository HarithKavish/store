package com.harithkavish.store.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the ecosystem catalogue and per-app build manifests.
 *
 * Mirrors the ordered-fallback pattern Jarvis's own updater uses
 * (my_chatgpt/mobile/App.tsx, UPDATE_METADATA_URLS): try the live site first,
 * fall back to the raw GitHub copy of the same repo if Pages is unreachable.
 */
class CatalogRepository {

    private val catalogUrls = listOf(
        "https://store.harithkavish.com/catalog.json",
        "https://raw.githubusercontent.com/HarithKavish/store/main/catalog.json"
    )

    /** [manifestUrl] is the value of a build's `manifest` field, already an absolute or store-relative path. */
    private fun manifestUrls(manifestUrl: String): List<String> = listOf(
        "https://store.harithkavish.com/$manifestUrl",
        "https://raw.githubusercontent.com/HarithKavish/store/main/$manifestUrl"
    )

    suspend fun fetchCatalog(): Catalog = withContext(Dispatchers.IO) {
        val body = fetchFirstReachable(catalogUrls) ?: throw CatalogUnreachableException()
        parseCatalog(JSONObject(body))
    }

    suspend fun fetchManifest(manifestUrl: String): BuildManifest? = withContext(Dispatchers.IO) {
        val body = fetchFirstReachable(manifestUrls(manifestUrl)) ?: return@withContext null
        runCatching { parseManifest(JSONObject(body)) }.getOrNull()
    }

    private fun fetchFirstReachable(urls: List<String>): String? {
        for (url in urls) {
            runCatching { httpGet(url) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun httpGet(url: String): String {
        val connection = URL("$url?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${connection.responseCode} for $url")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCatalog(json: JSONObject): Catalog {
        val storeName = json.optJSONObject("store")?.optString("name") ?: "Store"
        val appsJson = json.optJSONArray("apps") ?: return Catalog(storeName, emptyList())
        val apps = (0 until appsJson.length()).mapNotNull { i ->
            runCatching { parseApp(appsJson.getJSONObject(i)) }.getOrNull()
        }
        return Catalog(storeName, apps)
    }

    private fun parseApp(json: JSONObject): CatalogApp {
        val builds = json.optJSONArray("builds")
        val androidBuildJson = (0 until (builds?.length() ?: 0))
            .map { builds!!.getJSONObject(it) }
            .firstOrNull { it.optString("platform").equals("Android", ignoreCase = true) }

        return CatalogApp(
            slug = json.getString("slug"),
            name = json.optString("name", json.getString("slug")),
            tagline = json.optString("tagline", ""),
            category = json.optString("category", ""),
            iconUrl = nullableString(json, "icon")?.let { "https://store.harithkavish.com/$it" },
            featured = json.optBoolean("featured", false),
            androidBuild = androidBuildJson?.let {
                AndroidBuild(
                    packageName = nullableString(it, "package_name"),
                    manifestUrl = nullableString(it, "manifest"),
                    directUrl = nullableString(it, "url")
                )
            }
        )
    }

    private fun parseManifest(json: JSONObject): BuildManifest = BuildManifest(
        version = json.getString("version"),
        apkUrl = nullableString(json, "apk_url") ?: json.optString("url"),
        sizeBytes = json.optLong("size_bytes", 0L),
        releaseNotes = nullableString(json, "release_notes")
    )

    /**
     * org.json's own optString(name, null) treats an explicit JSON null the same
     * as "present" and stringifies it to the literal text "null" rather than
     * returning the fallback -- this is the correct null-safe read instead.
     */
    private fun nullableString(json: JSONObject, name: String): String? =
        if (json.has(name) && !json.isNull(name)) json.getString(name) else null

    class CatalogUnreachableException : Exception("Could not reach the store catalogue")
}
