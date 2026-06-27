$taskName = "FocusLockWindowsAgent"
$agentRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

$action = "cmd.exe /c cd /d `"$agentRoot`" && npm start"

schtasks /Create /TN $taskName /TR $action /SC ONLOGON /RL HIGHEST /F | Out-Null
Write-Output "Startup task installed: $taskName (Electron GUI)"
