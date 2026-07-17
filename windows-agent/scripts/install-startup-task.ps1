# Dev-source convenience only: registers a scheduled task that runs `npm start`
# from this checkout on login. Packaged builds (the NSIS installer) don't need
# this — they self-register via app.setLoginItemSettings() in src/main.js,
# which requires no elevation and works without node/npm installed.
$taskName = "FocusLockWindowsAgent"
$agentRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

$action = "cmd.exe /c cd /d `"$agentRoot`" && npm start"

# No /RL HIGHEST: the agent only needs to taskkill/SendInput processes running
# at the same (non-elevated) integrity level as the user, so running the whole
# Electron GUI elevated on every logon would be unnecessary privilege escalation.
schtasks /Create /TN $taskName /TR $action /SC ONLOGON /F | Out-Null
Write-Output "Startup task installed: $taskName (Electron GUI)"
