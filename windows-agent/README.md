# FocusLock Windows Desktop Agent

Electron agent that enforces FocusLock policy on Windows. It talks to Firebase
Firestore directly (no local backend, no browser extension) and shares one
account with the Android app via a **sync code** (see the repo root README).

## What it does

- Watches the active foreground window (`active-win`) and matches it against the
  policy's `targetApps` (process names) and `targetDomains` (browser URLs).
- Enforces the daily limit, focus sessions, and scheduled block windows: for a
  blocked target it **closes the browser tab** (Ctrl+W via SendInput, through a
  persistent PowerShell helper process — see `scripts/close-window-helper.ps1`)
  or **terminates the app** (`taskkill`). A background sweep (every 30s, while
  blocking is active) also kills blocked apps running behind other windows —
  browser tabs can't be swept this way and are still only enforced when
  brought to the foreground.
- In a packaged build, a per-user Scheduled Task (`src/watchdog.js`) relaunches
  the app within a minute if it's killed via Task Manager. Tray → "Quit and
  disable watchdog" removes the task for a real, permanent stop.
- Receives policy / usage / focus-session changes through Firestore realtime
  listeners and records daily streak outcomes at local midnight.
- Provides a tray app + dashboard (Settings, rules, todos, focus sessions,
  weekly usage, streak).

## Setup

1. `npm install`
2. `npm start` (launches the Electron app; it lives in the system tray)
3. In **Settings**, set your **Sync Code** (same code as your phone), configure
   policy, and keep it running.

## Startup on login

Packaged builds (the NSIS installer) self-register for login launch via
`app.setLoginItemSettings()` — no extra step needed.

Running from source instead, use the scheduled-task scripts:
- Install: `npm run install-startup`
- Remove: `npm run remove-startup`

## Target profiles (local target presets)

- List: `npm run profile:list`
- Set: `npm run profile:set -- social-only`

## Focus session commands

- Start (minutes): `npm run focus:start -- 25`
- Stop: `npm run focus:stop`
- Status: `npm run focus:status`

## Environment overrides

- `FOCUSLOCK_USER_ID` — override the sync code (otherwise read from
  `config/settings.json`, which the Settings tab writes).

## Notes

- Run with the rights needed to terminate your target processes (elevated only
  if a target itself runs elevated).
