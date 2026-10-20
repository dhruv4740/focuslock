# FocusLock (Android + Windows)

FocusLock is a cross-device screen-time enforcer. It nudges and blocks you out
of distracting apps/sites and keeps a shared daily limit across your phone and
PC. Two pieces share one Firebase Firestore backend:

- **`android/`** — Android app: detects when a target app (Instagram, YouTube,
  …) comes to the foreground via an Accessibility service, shows friction /
  lock overlays, tracks usage, and enforces daily limits + focus sessions +
  scheduled block windows.
- **`windows-agent/`** — Electron desktop agent: watches the active window,
  closes blocked browser tabs (Ctrl+W) and kills blocked apps, and provides a
  dashboard to edit policy, todos, focus sessions and view weekly usage.

There is **no separate backend server and no browser extension** — both clients
talk to Firestore directly (project `focusblock-e8376`). The desktop agent does
the job a browser extension would.

## Sync Code (using it with more than one person)

All data lives under `users/<sync-code>/…` in Firestore. The **sync code** is
how accounts are separated:

- Put the **same** sync code on **your** phone and **your** PC → they stay in
  sync (shared limit, todos, focus sessions, streak).
- A **different person** picks **their own** sync code → their data is fully
  isolated from yours.

Where to set it:

- **Phone:** FocusLock app → top **“Sync Code”** card → type a code (e.g.
  `dhruv-01`) → **Save**.
- **Desktop:** FocusLock agent → **Settings** tab → **Sync Code** → type the
  same code → **Save & Restart** (the agent relaunches to apply it).

Codes are normalized identically on both platforms (lowercased, spaces/symbols
→ `-`), so `Dhruv 01` and `dhruv-01` resolve to the same account. If left
blank, both default to `user_default`.

## Android setup

1. Open `android/` in Android Studio and let Gradle sync (or build from the
   command line: `cd android && ./gradlew assembleDebug`).
2. Install the app on a **physical** device (overlays/accessibility don’t work
   well on emulators).
3. Open the app and grant: **Usage Access**, **Accessibility**, **Display over
   other apps**, and **Notifications**.
4. Set your **Sync Code**.
5. In **Policy Settings**, add the apps to limit (package names, e.g.
   `com.instagram.android`), set a daily limit, then tap **Start Monitor**.

## Windows desktop agent setup

1. `cd windows-agent`
2. `npm install`
3. `npm start` (launches the Electron app; it lives in the system tray).
4. Set your **Sync Code** in Settings, configure policy, and keep it running.

Optional: `npm run install-startup` registers a scheduled task so the agent
starts with Windows (`npm run remove-startup` to undo).

## How enforcement works

- **Daily limit:** minutes on target apps/sites accumulate in
  `usage_days/<local-date>`; once used ≥ effective limit, blocking kicks in
  (effective limit = daily limit + granted extensions). Usage is keyed by
  **local** date on both platforms so the day rolls over at local midnight.
- **Focus session:** `focus_session/current` blocks **all** targets on every
  device until it ends.
- **Scheduled block windows:** time ranges + weekday mask in the policy block
  all targets during those windows.
- **Friction first:** on Android, opening a target first shows an optional
  friction sequence (breathing, delay, intention, shake, reflection, stats,
  math) before access; a lock overlay appears once the limit is hit.

## On-demand accessibility (Android, one-time setup)

Banking apps refuse to run while a third-party accessibility service is
enabled, so FocusLock keeps its accessibility service **off** except while a
reel surface (Instagram Reels / YouTube Shorts) is in the foreground. Time
limits, friction and the lock overlay do not need it; they run off a usage-stats
poller. Setup, once per install:

1. OnePlus/OxygenOS only: Developer options → turn on **Disable system
   optimisation**.
2. `adb shell pm grant com.focuslock android.permission.WRITE_SECURE_SETTINGS`

Without the grant nothing parks and the service stays always-on (banking apps
will then refuse to run).

## Budgets (Android)

Open **Rules → Budgets**.

- **Category budgets:** a shared daily budget for a group of apps. Modes are
  *Limit* (soft: friction, then lock) and *Hard block*. An app is over its limit
  when its category budget **or** the global daily limit is used up. "Add Games"
  detects installed games (`ApplicationInfo.category`); the app pickers list
  games first. No categories = only the global limit applies, as before.
- **Overall phone cap:** total foreground time across every app except the
  allow-list, FocusLock, the launcher and system UI, read from usage stats.
  Mode *Friction only* (default) sends every non-allow-listed app open through
  the friction gate once over the cap; *Hard block* shows the lock overlay. The
  allow-list defaults to the phone's dialer, SMS, contacts, camera, maps, clock
  and settings apps; add banking apps with **Add apps**.
- **Ratchet:** both follow the normal rules. Tightening applies immediately;
  raising a limit, lowering a mode, removing a category or app, or adding to a
  cap allow-list (while the cap is on) is delayed or needs partner approval.

The Windows agent ignores the new fields (it does not read or modify them).

## Battery notes (Android)

The monitor is event-driven: the Accessibility service only reacts to app
switches (it does **not** poll or read window content), policy is fetched at
most every 15 minutes (limits are still re-checked on each app open), and the
per-minute timer only runs while a target app is actually in the foreground.

## Known limitations

- Firestore web API keys are public by design; data isolation between sync
  codes is functional, not access-controlled — anyone with a code can read that
  tree. Add Firestore Security Rules if you need real access control.
- Aggressive OEM battery managers (ColorOS/MIUI/etc.) can still kill background
  services; whitelist FocusLock from battery optimization if enforcement stops.
- `windows-agent/src/emergency-cli.js` and `update-user-policy.js` are legacy
  helpers that target the old HTTP backend and are no longer wired into the app.
