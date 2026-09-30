# Store (Android)

The on-device client for the [Harith Kavish app catalogue](../catalog.json):
search every app in the ecosystem, see what's actually installed on the phone
against what's current, and install or update any of them — including Store
itself — without a trip back to the browser.

Native Kotlin, following the same project layout and toolchain pins as
[ReWeb](https://github.com/HarithKavish/ReWeb) — the only other native Android
app in the ecosystem.

## How it works

- `catalog/CatalogRepository` fetches `catalog.json`, then each app's
  `latest.json` manifest (the same files `store.js` reads for the web
  catalogue — see the root [README](../README.md)).
- `installed/InstalledAppsResolver` asks `PackageManager` what's actually
  installed for a given `package_name`. This is the one field every catalogue
  entry needs to set for Store to check it — see the root README's "Adding an
  app" section — and it's what makes new apps show up here automatically, with
  no Store code change: nothing past this point is specific to any one app.
- `ui/StoreListActivity` compares the two per app (`ui/AppRowState`,
  `catalog/SemVer`) and offers Install / Update / Open accordingly. Store's own
  catalogue entry (`slug: "store"`, `package_name: com.harithkavish.store`)
  goes through the identical path — that's what makes self-update fall out for
  free.
- `update/UpdateManager` downloads the chosen APK via `DownloadManager` and
  hands it to the system installer once the download completes
  (`update/DownloadCompleteReceiver`), the same install-intent mechanism
  Jarvis's own in-app updater already uses.

**Platform limit worth knowing:** Android always requires one tap to confirm
an install/update through its system Package Installer — there's no way for a
sideloaded, non-system app to install silently. "Update all"
(`update/UpdateAllCoordinator`) queues every app with an update and downloads
them one at a time, but each still ends in that one confirmation tap.

## Building

```
./gradlew :app:lintRelease :app:testReleaseUnitTest :app:assembleDebug
```

A release build needs a signing keystore — see the root README's "Publishing
Store itself" section for how CI supplies one, or drop a `keystore.properties`
next to this file (same shape as ReWeb's, see its `BUILD.md`) to sign locally.

## Releasing

Handled by `.github/workflows/publish-store-app.yml` — build, sign, tag a
GitHub Release, and copy the APK into `../apps/store/mobile/android/` in the
same commit (Store's source and the catalogue it publishes to are the same
repository, so no cross-repo push is needed here, unlike Jarvis or ReWeb).
