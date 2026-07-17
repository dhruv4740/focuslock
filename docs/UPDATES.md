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

Already done — the repo slug is wired to `dhruv4740/focuslock` in both places:
- `windows-agent/package.json` → `build.publish[0].owner` / `.repo`
- `android/app/src/main/java/com/focuslock/update/UpdateChecker.kt` → `GITHUB_REPO`

If the repo ever moves, update the slug in both files and rebuild/ship both apps
before relying on the updater again.

## Release signing (Android)

Release builds are signed using `android/keystore.properties` (gitignored, not
committed). It must exist locally before `gradlew assembleRelease` produces a
signed APK; without it the build still succeeds but produces an **unsigned**
APK (fine for local testing, not for a real release).

```
# android/keystore.properties
storeFile=focuslock.jks
storePassword=...
keyAlias=focuslock
keyPassword=...
```

`focuslock.jks` lives at `android/app/focuslock.jks` (also gitignored). **Back
up both files somewhere safe** — losing the keystore means a future release
can no longer be signed with the same key, and Android will refuse to install
an update signed with a different key over the existing app (users would have
to uninstall and lose local data first).

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
4. **Compute the APK checksum** (optional but recommended — the Android updater
   verifies it when present and aborts the install on a mismatch):
   ```
   Get-FileHash app-release.apk -Algorithm SHA256
   ```
   Save the hex digest to a file named `<apk-name>.apk.sha256` (e.g.
   `app-release.apk.sha256`), containing just the hash.
5. **Create the GitHub Release** with tag `vX.Y.Z` and upload the files:
   - `app-release.apk` (or `app-debug.apk`) — Android updater looks for the first `*.apk` asset
   - `app-release.apk.sha256` — optional checksum sidecar, same base name + `.sha256`
   - `FocusLock Setup X.Y.Z.exe`
   - `latest.yml`  ← **required** — electron-updater can't detect the update without it
6. Done. Open apps pick it up on their next launch.

## Sideloaded install: "Allow restricted settings"

Android 13+ blocks sensitive permission toggles (Accessibility Service, in
particular) for any app installed outside the Play Store — this includes
FocusLock, whether installed manually or via its own in-app updater. The
Accessibility toggle in Settings will appear greyed out and tapping it does
nothing. This is a stock Android security feature, not a FocusLock bug, and
disabling Play Protect does **not** fix it.

Workaround (also shown in-app when tapping the Accessibility permission
button): open the phone's **App info** for FocusLock → tap the **⋮** menu in
the top-right corner → **Allow restricted settings** → go back to Accessibility
and enable FocusLock normally.

> Optional one-step publish for the desktop side: `npx electron-builder --win nsis --publish always`
> with a `GH_TOKEN` env var (a GitHub PAT with `repo` scope) uploads the `.exe` +
> `latest.yml` to the release for you. You still upload the APK manually.

## Where writable state lives

The desktop app stores its sync code in `%APPDATA%/FocusLock/settings.json`, not
next to the executable — so updates and the per-launch temp extraction never wipe
it.

## Firestore rules rollout (member allowlist)

`firestore.rules` gates access to a sync code's data tree on membership in a
`members` allowlist rather than any authenticated client. Both apps now call
an `ensureMembership()` join step after signing in (`windows-agent/src/firebase.js`,
`FocusPolicyClient.kt`), but the **rules deploy must happen after** both
existing installs have run the new client version at least once — otherwise a
device that hasn't joined yet gets locked out.

Rollout order:
1. Ship both apps with `ensureMembership()` (safe under the old permissive
   rules — it's just an extra write).
2. Confirm both devices have launched the new version at least once. Check in
   the Firebase Console under `users/<your-sync-code>` that `members` contains
   both uids.
3. Deploy the new rules: `firebase deploy --only firestore:rules`.

Rollback: redeploy the previous (permissive) rules file from git history — no
data migration is needed either direction.

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

## Windows installer code signing (deferred)

The NSIS installer is unsigned, so Windows SmartScreen shows an "unknown
publisher" warning on first run. This is a known, accepted trade-off — a
proper Authenticode certificate has an ongoing cost that isn't justified yet.
When it is: electron-builder supports both Azure Trusted Signing (cheapest,
~$10/month, via `win.azureSignOptions`) and a traditional OV certificate (via
`win.signtoolOptions`).
