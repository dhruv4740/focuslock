import activeWin from "active-win";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const scriptPath = fileURLToPath(new URL("../scripts/get-browser-url.ps1", import.meta.url));

async function run() {
  const active = await activeWin();
  if (!active) {
    console.log("No active window found.");
    return;
  }
  
  console.log("Active Window details:");
  console.log(`- Title: ${active.title}`);
  console.log(`- Owner Name: ${active.owner?.name}`);
  console.log(`- HWND (ID): ${active.id}`);
  
  try {
    const { stdout } = await execFileAsync("powershell", [
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      scriptPath,
      "-HwndVal",
      active.id
    ]);
    console.log(`- Extracted URL: "${stdout.trim()}"`);
  } catch (error) {
    console.error("Error running script:", error);
  }
}

run();
