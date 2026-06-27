# Architecture (Android + Windows)

## System components

1. Backend API
- Policy retrieval and updates
- Usage event ingestion
- Intervention decision engine (reminder vs hard block vs extension)

2. Windows client
- Browser extension for domain-level tracking and block redirects
- Future desktop companion for process-level app tracking and hard lock screens

3. Android client
- UsageStats-based app foreground tracking
- Accessibility service hook for app interruption and anti-bypass signals
- Future overlay lock activity and watchdog

## Core policy model

- Target domains
- Target app package names/process names
- Daily limit minutes
- Extension minutes and per-day count
- Reminder cadence
- Hard block toggle

## Intervention flow

1. Client sends usage events to backend.
2. Client asks backend to evaluate current allowance.
3. Backend returns action:
- allow + none
- allow + reminder
- deny + hard-block
- extension-granted when explicitly requested

## Anti-bypass strategy roadmap

- Detect disabled services and relaunch prompts.
- Detect large time shifts/timezone changes and invalidate extension windows.
- Keep local mirror of last signed policy for offline enforcement.
- Require cool-down and reflection text before extension request.