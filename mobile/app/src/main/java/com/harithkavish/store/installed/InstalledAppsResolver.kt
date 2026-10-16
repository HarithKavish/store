package com.harithkavish.store.installed

import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException

/**
 * Looks up what's actually installed on the device for a given package name,
 * regardless of which catalogue app it belongs to or how that app was built
 * (Kotlin, React Native, whatever comes next). This is what lets Store cover
 * apps added to the catalogue after Store itself was last released: nothing
 * here is specific to any one app.
 */
class InstalledAppsResolver(private val packageManager: PackageManager) {

    /** Installed versionName for [packageName], or null if it isn't installed. */
    fun installedVersion(packageName: String): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: NameNotFoundException) {
        null
    }

    fun isInstalled(packageName: String): Boolean = installedVersion(packageName) != null
}
