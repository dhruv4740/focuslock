# FocusLock MVP (Android + Windows)

This repository starts the implementation of a cross-device unproductive-time reduction system focused on Android and Windows only.

## What is implemented now

- Backend API in TypeScript (policy, usage events, intervention evaluation, stats/insights, focus sessions)
- Windows browser extension MVP (tracking, server-side limit checks, block page, one-time extension request)
- Windows desktop agent MVP (process-level tracking, hard-block prompts, profile switching, focus CLI)
- Windows emergency commands (instant block, unblock, kill process)
- Android lock MVP (permission onboarding, usage monitor, full-screen lock overlay, extension request, countdown timer)
- Cross-device focus session mode (strict mode blocks all targets across all devices)
- Real-time countdown timers on all block screens (Android overlay, Windows hard-block prompt)

## Repository layout

- `backend/` policy and event API
- `windows-extension/` Chrome/Edge extension MVP
- `windows-agent/` Windows process monitor and lock prompt agent
- `android/` Android app scaffold for usage and intervention plumbing
- `docs/` architecture and implementation notes

## Quick start

### 1) Start backend

1. Open terminal in `backend/`.
2. Install packages: `npm install`
3. Run dev server: `npm run dev`
4. Health check: `http://localhost:4050/health`

### 2) Load Windows extension

1. Open Edge/Chrome extensions page.
2. Enable Developer Mode.
3. Load unpacked from `windows-extension/`.
4. Open extension details and enable Incognito access.
5. Use extension options page to set your `userId` and `deviceId`.

### 3) Open Android project

1. Open `android/` in Android Studio.
2. Let Gradle sync.
3. Run app on a physical Android device (recommended).
4. Grant Usage Access, Accessibility, and Overlay permissions.
5. Start monitor service from the app UI.

### 4) Start Windows desktop agent

1. Open terminal in `windows-agent/`.
2. Install packages: `npm install`
3. Start agent: `npm start`
4. Keep it running while using target apps.

## MVP API endpoints

- `GET /health` - Health check
- `GET /policy/:userId/:deviceId?platform=windows|android` - Get device policy
- `PUT /policy/:userId/:deviceId` - Update device policy
- `POST /events` - Record usage event
- `POST /interventions/evaluate` - Evaluate intervention (allow/remind/block)
- `POST /focus/session/start` - Start cross-device focus session (strict mode)
- `POST /focus/session/stop` - Stop focus session
- `GET /focus/session/:userId` - Check focus session status
- `GET /stats/:userId?date=YYYY-MM-DD` - Get daily usage stats
- `POST /devices/:userId/register` - Register new device
- `GET /devices/:userId` - List all user devices
- `POST /policy/:userId/:deviceId/preset/:presetType?platform=windows|android` - Apply policy preset (light|social-only|deep-work|break-enforcer)

## Important current limitations

- Windows now has browser-level and desktop-process-level MVP controls, but anti-tamper hardening is still pending.
- Android lock flow now includes permission-loss escalation, but OEM-specific background limits may still weaken enforcement.
- Policy storage is in-memory; restarting backend resets data.
- Device registration and policy presets are MVP (single-session in-memory).

## Testing

Run end-to-end test scenarios:
```bash
# Full focus-session cross-device scenario (8 tests)
node test-e2e.js

# Extended features: device registration, presets, stats (7 tests)
node test-extended-features.js
```
