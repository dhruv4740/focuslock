import activeWin from "active-win";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

async function closeWindowSendKeys(windowId) {
  console.log(`Attempting to close tab in window ${windowId} via Ctrl+W (SendKeys)...`);
  const script = `
    $sig = '[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);'
    Add-Type -MemberDefinition $sig -Name "Win32SetForeground" -Namespace "Win32" -PassThru | Out-Null
    [Win32.Win32SetForeground]::SetForegroundWindow([IntPtr]${windowId}) | Out-Null
    $wshell = New-Object -ComObject wscript.shell
    Start-Sleep -Milliseconds 100
    $wshell.SendKeys('^w')
  `;
  await execFileAsync("powershell", ["-Command", script]);
  console.log("Ctrl+W script executed.");
}

async function run() {
  console.log("Waiting 5 seconds. Please open Chrome or Edge, focus a temporary test tab, and stay focused there...");
  await new Promise(r => setTimeout(r, 5000));

  const active = await activeWin();
  if (!active) {
    console.log("No active window detected.");
    return;
  }

  console.log(`Active window: "${active.title}" (PID: ${active.owner.processId}, HWND: ${active.id})`);
  
  await closeWindowSendKeys(active.id);
}

run().catch(console.error);
