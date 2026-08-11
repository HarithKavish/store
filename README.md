# Store

The distribution front for the Harith Kavish ecosystem — every installable app,
packaged, versioned, and served from <https://store.harithkavish.com>.

The page is static. It reads `catalog.json` for what the store sells, and one
manifest per build for what the current version actually is, so publishing a new
app is a data change rather than a page rewrite.

## Layout

```
catalog.json                              # every app in the store
index.html / store.css / store.js         # the store front
apps/<slug>/icon.png                      # app artwork (square)
apps/<slug>/mobile/android/<slug>-vX.Y.Z.apk
apps/<slug>/mobile/android/latest.json     # generated — points at the newest build
```

The theme comes from `https://harithkavish.com/style.css`, the same stylesheet
Nexus uses, so the store follows the ecosystem's light and dark modes without
defining any colours of its own. `store.css` only adds what a store needs:
tiles, a spotlight, filters, and the app detail layout.

## Adding an app

1. Create `apps/<slug>/` and drop a square `icon.png` in it.
2. Commit the build as `apps/<slug>/mobile/android/<slug>-vX.Y.Z.apk`.
3. Add an entry to `catalog.json`:

```json
{
  "slug": "example",
  "name": "Example",
  "publisher": "Harith Kavish",
  "tagline": "One line that explains the app.",
  "category": "Productivity",
  "status": "Live",
  "icon": "apps/example/icon.png",
  "accent": "#3aa8ff",
  "description": "A paragraph for the detail page.",
  "features": [{ "title": "Something", "detail": "Why it matters." }],
  "links": [{ "label": "Source code", "href": "https://github.com/..." }],
  "release_repo": "HarithKavish/example",
  "builds": [
    {
      "platform": "Android",
      "format": "APK",
      "manifest": "apps/example/mobile/android/latest.json",
      "requirements": "Android 8.0 or newer",
      "install": ["Step one.", "Step two."]
    }
  ]
}
```

Optional keys: `featured` promotes an app to the spotlight, and a build may carry
a direct `url` instead of a `manifest` when nothing generates one for it. An app
with no downloadable build still lists — its button reads *Coming soon*.

## Releasing a new version

Push a higher-versioned APK into the app's `mobile/android` folder. The
**Update app manifests** workflow then rewrites that app's `latest.json` with the
version, URL, size, release date, and release notes (taken from the GitHub
release in `release_repo` when one exists, otherwise the commit subject).

`latest.json` keeps both `url` and `apk_url`: the Jarvis in-app updater reads
`apk_url`, so that key stays in place.

## Deploying

Pushing to `main` runs **Deploy Store Pages**, which publishes the repository to
the `gh-pages` branch that GitHub Pages serves at `store.harithkavish.com`.
