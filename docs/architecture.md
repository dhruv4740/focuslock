# Architecture (Android + Windows)

## System components

Two clients share one Firebase Firestore database directly — there is **no
application backend server and no browser extension**.

1. **Firebase Firestore** (project `focusblock-e8376`) — the single source of
   truth. All data lives under `users/<syncId>/…`:
   - `policy/global` — limits, target apps/domains, block windows, extension config,
     `blockedSurfaces`, `reelDailyLimit`
   - `usage_days/<local-date>` — `usedMinutes`, `opensToday`, `extensionsUsedToday`,
     `reelCounts`
   - `focus_session/current` — active flag + `endsAt`
   - `events` — per-session usage records
   - `streak/current` — current/longest streak
   - `todos` — shared task list

2. **Android client** (`android/`, Kotlin)
   - Accessibility service detects target-app foreground changes (event-driven,
     no polling). It **does** read window content, but only inside Instagram and
     YouTube and only to match reel/short view IDs — see "Reels & Shorts" below.
   - `UsageMonitorService` tracks minutes, shows friction/lock/timer overlays,
     and enforces limits; falls back to a local minute counter when offline.
   - Device admin discourages uninstallation while a block is active.

3. **Windows agent** (`windows-agent/`, Electron)
   - Polls the active window (`active-win`), matches process names / browser
     URLs against policy, and closes tabs (Ctrl+W) or kills apps when blocked.
   - Firestore realtime listeners keep policy/usage/focus in sync.

## Multi-user (sync code)

Each person picks a **sync code**; it selects which `users/<syncId>` tree both
their phone and PC read/write. Same code across a person's devices = synced;
different people = isolated. Stored in Android SharedPreferences and the agent's
`config/settings.json`, normalized identically on both platforms.

## Enforcement model

- **Effective limit** = `dailyLimitMinutes` + grantedExtensions × `extensionMinutes`.
- Block when: focus session active, inside a scheduled block window, or usage ≥
  effective limit (hard block) — otherwise allow (Android adds a friction step).
- Usage is keyed by **local** date on both platforms, so the day rolls over at
  local midnight consistently.

## Reels & Shorts

Android-only. `surface/SurfaceDetector.kt` is the pure logic, `SurfaceBlocker.kt`
the Android wiring. Two surfaces: `instagram_reels`, `youtube_shorts`.

**Counting and enforcement are independent pipelines** over the same event
stream. `ReelImpressionCounter` counts; `SurfaceSessionTracker` decides whether
to issue a Back press. Counting runs in **every** mode, including `off`.

- `policy/global.blockedSurfaces` — `{wireKey: mode}`, mode ∈
  `off | quota | one_at_a_time | block`. Unknown values degrade to `off`, so a
  doc written by a newer client is safe on an older one. Note `quota` is new in
  0.6 and an APK older than that will read it as `off` — i.e. under-enforce, not
  over-enforce, until it updates.
- `policy/global.reelDailyLimit` — `{wireKey: int}`, `0` = none. Once
  `reelCounts >= limit` the effective mode upgrades to `block` until local
  midnight.
- `usage_days/<date>.reelCounts` — `{wireKey: int}`. **Absolute per-device
  totals, written idempotently** (`set` + merge), not `FieldValue.increment`.
  That is what lets a failed flush simply retry with the full total instead of
  dropping counts. Tradeoff: last-writer-wins if two Android devices ever share
  one sync code — accepted because reel counts are inherently per-device.

Detection matches internal Instagram/YouTube view IDs (`clips_viewer_view_pager`,
`reel_recycler`, …), gated on the node filling most of the window so a
feed-embedded post doesn't false-positive as the fullscreen viewer. These are
not a public API and silently stop matching after a redesign — failure is
fail-open (no detection), never a wrong-app block.

Modded YouTube builds (Morphe/ReVanced/RVX) rename the applicationId, so package
resolution is layered: known set → learned aliases → a vendor-token heuristic.
A scroll event from an unrecognised package whose source view ID is a known
pager ID auto-learns the binding and persists it. Settings → "Reel detection"
shows what has been learned.

**The Windows agent ignores all three reels fields** — it never reads or writes
`blockedSurfaces`, `reelDailyLimit`, or `reelCounts`, and its merge-write of
`policy/global` round-trips them untouched.

## Offline / anti-bypass

- Firestore offline persistence caches the last known policy/usage so an Android
  device can't bypass limits simply by dropping network.
- Android keeps a local minute counter as a fallback while `evaluate()` is
  unreachable, seeded from the last successful server read.
