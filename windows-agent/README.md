# FocusLock Windows Desktop Agent

Tracks active foreground processes on Windows and enforces FocusLock policy beyond browser-only controls.

## What it does

- Detects active target process names from backend policy (`targetApps`)
- Posts usage sessions to backend `/events`
- Requests intervention decisions from backend `/interventions/evaluate`
- For hard blocks:
  - force closes target process,
  - shows full-screen lock prompt,
  - allows one-time extension request via backend,
  - keeps re-enforcing if blocked target is reopened
- For reminders:
  - shows reminder popup

## Setup

1. Ensure backend is running on `http://localhost:4050`.
2. In this folder run:
   - `npm install`
   - `npm start`

## Startup on login

- Install startup task: `npm run install-startup`
- Remove startup task: `npm run remove-startup`

## Target profiles

- List profiles: `npm run profile:list`
- Set active profile: `npm run profile:set -- social-only`

## Focus session commands

- Start focus session (minutes): `npm run focus:start -- 25`
- Stop focus session: `npm run focus:stop`
- Check focus session: `npm run focus:status`

## Optional environment overrides

- `FOCUSLOCK_API_BASE`
- `FOCUSLOCK_USER_ID`
- `FOCUSLOCK_DEVICE_ID`

## Notes

- Run agent with normal or elevated rights depending on whether target processes require elevated termination.
- This agent complements the browser extension; both can run together.
