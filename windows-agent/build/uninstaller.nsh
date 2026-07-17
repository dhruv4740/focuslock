; electron-builder custom NSIS include: remove the kill-resistance watchdog
; scheduled task on uninstall, so an uninstalled app doesn't leave a task
; that keeps trying to relaunch a binary that no longer exists.
!macro customUnInstall
  nsExec::Exec 'schtasks /End /TN "FocusLockWatchdog"'
  nsExec::Exec 'schtasks /Delete /TN "FocusLockWatchdog" /F'
!macroend
