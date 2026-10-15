#!/usr/bin/env bash
# Regenerates apps/<slug>/mobile/android/latest.json for every app from the newest
# versioned APK in its folder. Run by deploy-pages.yml into what gets published, so the
# live catalogue always matches what is on main. It used to run as update-latest.yml and
# commit the manifests back to the branch; protected branches accept changes only through
# pull requests (governance BRANCHING.md, no bypass), and a manifest generated at deploy
# can never lag the APKs anyway.
#
# Needs full git history (fetch-depth: 0): each release date is the commit that added it.
set -euo pipefail
CATALOG="catalog.json"

# One pass per app folder, so a new app only needs its own directory.
for DIR in apps/*/mobile/android; do
  [ -d "$DIR" ] || continue

  SLUG=$(echo "$DIR" | cut -d/ -f2)

  LATEST_APK=$(ls "$DIR"/*-v*.apk 2>/dev/null | sort --version-sort | tail -1 || true)
  if [ -z "$LATEST_APK" ]; then
    echo "$SLUG: no versioned APK — skipping"
    continue
  fi

  FILENAME=$(basename "$LATEST_APK")
  VERSION=$(echo "$FILENAME" | sed 's/.*-v//;s/\.apk$//')
  URL="https://store.harithkavish.com/${DIR}/${FILENAME}"
  SIZE=$(stat -c %s "$LATEST_APK")
  RELEASED=$(git log -1 --format=%cI -- "$LATEST_APK")
  [ -n "$RELEASED" ] || RELEASED=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  # Prefer the release body from the app's source repo when the
  # catalog names one; fall back to the commit that shipped the build.
  NOTES=""
  REPO=""
  if [ -f "$CATALOG" ]; then
    REPO=$(jq -r --arg slug "$SLUG" '.apps[] | select(.slug == $slug) | .release_repo // empty' "$CATALOG")
  fi
  if [ -n "$REPO" ]; then
    NOTES=$(curl -s "https://api.github.com/repos/${REPO}/releases/tags/v${VERSION}" | jq -r '.body // empty')
  fi
  if [ -z "$NOTES" ]; then
    NOTES=$(git log -1 --pretty=%s -- "$DIR" \
      | sed 's/\[skip ci\]//g;s/\[no ci\]//g' \
      | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
      | cut -c1-160)
  fi
  [ -n "$NOTES" ] || NOTES="Bug fixes and improvements"

  echo "$SLUG: v$VERSION ($FILENAME, $SIZE bytes)"

  # apk_url stays alongside url: the Jarvis in-app updater reads it.
  jq -n \
    --arg version  "$VERSION" \
    --arg url      "$URL" \
    --arg filename "$FILENAME" \
    --arg released "$RELEASED" \
    --arg notes    "$NOTES" \
    --argjson size "$SIZE" \
    '{
       version: $version,
       apk_url: $url,
       url: $url,
       filename: $filename,
       size_bytes: $size,
       released_at: $released,
       release_notes: $notes
     }' > "$DIR/latest.json"

  cat "$DIR/latest.json"
done
