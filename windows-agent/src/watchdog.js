// Kill-resistance for packaged builds: a per-user Scheduled Task (no admin
// required) that checks once a minute whether FocusLock.exe is running and
// relaunches it (hidden) if not. This is a deterrent, not a lock — the threat
// model here is an impulsive Task Manager kill by the same user who set up
// their own focus rules, not an adversary. Removing the task entirely (via
// the tray's "Quit and disable watchdog") is the intended legitimate escape
// hatch; `/F` on every register call makes the task self-healing if the user
// deletes it by hand without going through that path.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";

const execFileAsync = promisify(execFile);

const TASK_NAME = "FocusLockWatchdog";

function configDir() {
  return join(process.env.APPDATA || join(homedir(), ".config"), "FocusLock");
}

function writeWatchdogScript(exePath) {
  const dir = configDir();
  mkdirSync(dir, { recursive: true });
  const vbsPath = join(dir, "watchdog.vbs");
  const exeName = exePath.split(/[\\/]/).pop();
  // wscript.exe (not cscript) so this runs with no console window flashing
  // every minute. Escape embedded quotes defensively even though exePath is
  // process.execPath, not user input.
  const escape = (s) => s.replace(/"/g, '""');
  const script = [
    'Set WshShell = CreateObject("WScript.Shell")',
    `Set exec = WshShell.Exec("tasklist /FI ""IMAGENAME eq ${escape(exeName)}"" /NH")`,
    "output = exec.StdOut.ReadAll()",
    `If InStr(output, "${escape(exeName)}") = 0 Then`,
    `  WshShell.Run """${escape(exePath)}"" --hidden", 0, False`,
    "End If"
  ].join("\r\n");
  writeFileSync(vbsPath, script, "utf8");
  return vbsPath;
}

export async function registerWatchdog(exePath) {
  try {
    const vbsPath = writeWatchdogScript(exePath);
    const action = `wscript.exe "${vbsPath}"`;
    await execFileAsync("schtasks", [
      "/Create", "/TN", TASK_NAME, "/SC", "MINUTE", "/MO", "1", "/TR", action, "/F"
    ]);
    console.log("[watchdog] scheduled task registered");
  } catch (error) {
    console.error(`[watchdog] failed to register: ${String(error)}`);
  }
}

export async function unregisterWatchdog() {
  try {
    await execFileAsync("schtasks", ["/End", "/TN", TASK_NAME]);
  } catch {
    // may not currently be running; fine
  }
  try {
    await execFileAsync("schtasks", ["/Delete", "/TN", TASK_NAME, "/F"]);
    console.log("[watchdog] scheduled task removed");
  } catch (error) {
    console.error(`[watchdog] failed to remove (may not have existed): ${String(error)}`);
  }
}
