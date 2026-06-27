# Updating FocusLock (Android + Desktop)

Both apps self-update from **GitHub Releases**. Once a person has installed any
version, every later version reaches them over-the-air — no cables, no
re-sending files.

```
bump version ─► build APK + Windows installer ─► publish a GitHub Release (tag vX.Y.Z)
                                                          │
                  ┌───────────────────────────────────────┤
                  ▼                                        ▼
   Android app checks the repo on launch        Desktop app checks on launch
   → "Update available" → downloads APK         → electron-updater downloads +
   → user taps Update                             installs on next restart
```

## One-time setup

1. Create a **public** GitHub repo (e.g. `your-username/focuslock`) and push this
   project to it.
2. Set the repo slug in **two** places (must match the repo exactly):
   - `windows-agent/package.json` → `build.publish[0].owner` / `.repo`
   - `android/app/src/main/java/com/focuslock/update/UpdateChecker.kt` →
     `GITHUB_REPO = "owner/repo"`
3. Rebuild both apps once with the real slug and ship those as the first managed
   version (see below). The updater is inert until the slug is set.

## Versioning

Use one semver number per release, tagged `vX.Y.Z` on GitHub.

| Where | Field | Notes |
|-------|-------|-------|
| `android/app/build.gradle.kts` | `versionName` | must equal the release semver (e.g. `0.3.0`) |
| `android/app/build.gradle.kts` | `versionCode` | integer, bump every release (2, 3, 4 …) |
| `windows-agent/package.json` | `version` | must equal the release semver |
| GitHub release | tag | `v` + semver, e.g. `v0.3.0` |

The Android updater compares the release tag to the installed `versionName`.
electron-updater compares the desktop `version` to the release's `latest.yml`.

## Manual release process (current)

From a clean working tree on `main`:

1. **Bump versions** in the three fields above to the new semver.
2. **Build the Android APK:**
   ```
   cd android
   ./gradlew assembleDebug
   # output: app/build/outputs/apk/debug/app-debug.apk
   ```
3. **Build the Windows installer:**
   ```
   cd windows-agent
   npm run dist
   # output: dist/FocusLock Setup X.Y.Z.exe  AND  dist/latest.yml
   ```
4. **Create the GitHub Release** with tag `vX.Y.Z` and upload **all three** files:
   - `app-debug.apk`  (Android updater looks for the first `*.apk` asset)
   - `FocusLock Setup X.Y.Z.exe`
   - `latest.yml`  ← **required** — electron-updater can't detect the update without it
5. Done. Open apps pick it up on their next launch.

> Optional one-step publish for the desktop side: `npx electron-builder --win nsis --publish always`
> with a `GH_TOKEN` env var (a GitHub PAT with `repo` scope) uploads the `.exe` +
> `latest.yml` to the release for you. You still upload the APK manually.

## Where writable state lives

The desktop app stores its sync code in `%APPDATA%/FocusLock/settings.json`, not
next to the executable — so updates and the per-launch temp extraction never wipe
it.

## Future scope — GitHub Actions CI (not built yet)

Goal: `git tag v0.3.0 && git push --tags` → CI builds both artifacts and
publishes the release automatically, so neither building app touches a PC.

Sketch:
- Workflow on `push: tags: ['v*']`.
- Job 1 (ubuntu): set up JDK 17 + Android SDK, `./gradlew assembleRelease`
  (needs a release signing keystore stored as encrypted secrets), upload APK.
- Job 2 (windows): `npm ci` + `npx electron-builder --win nsis --publish always`
  with `GH_TOKEN` from `secrets.GITHUB_TOKEN`.
- Both jobs attach assets to the same release for the tag.

Decision deferred by the maintainer: manual releases for now; revisit CI when
release frequency justifies the signing-key setup.
