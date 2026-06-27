# Architecture (Android + Windows)

## System components

Two clients share one Firebase Firestore database directly — there is **no
application backend server and no browser extension**.

1. **Firebase Firestore** (project `focusblock-e8376`) — the single source of
   truth. All data lives under `users/<syncId>/…`:
   - `policy/global` — limits, target apps/domains, block windows, extension config
   - `usage_days/<local-date>` — `usedMinutes`, `opensToday`, `extensionsUsedToday`
   - `focus_session/current` — active flag + `endsAt`
   - `events` — per-session usage records
   - `streak/current` — current/longest streak
   - `todos` — shared task list

2. **Android client** (`android/`, Kotlin)
   - Accessibility service detects target-app foreground changes (event-driven,
     no polling, no window-content reads).
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

## Offline / anti-bypass

- Firestore offline persistence caches the last known policy/usage so an Android
  device can't bypass limits simply by dropping network.
- Android keeps a local minute counter as a fallback while `evaluate()` is
  unreachable, seeded from the last successful server read.
