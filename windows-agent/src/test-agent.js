import activeWin from "active-win";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const getBrowserUrlScriptPath = fileURLToPath(new URL("../scripts/get-browser-url.ps1", import.meta.url));

async function getBrowserUrl(windowId) {
  if (!windowId) return "";
  try {
    const { stdout } = await execFileAsync("powershell", [
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      getBrowserUrlScriptPath,
      "-HwndVal",
      windowId
    ]);
    return stdout.trim();
  } catch (error) {
    console.error(`Failed to get browser URL: ${String(error)}`);
    return "";
  }
}

async function test() {
  console.log("Starting active-win test loop. Focus your browser and open instagram.com or youtube.com...");
  for (let i = 0; i < 10; i++) {
    const active = await activeWin();
    if (active) {
      const processPath = active.owner?.path ?? "";
      const name = active.owner?.name ?? "";
      const title = active.title ?? "";
      const id = active.id;
      const processName = processPath ? basename(processPath) : name;
      console.log(`\n--- Active Window [${i+1}/10] ---`);
      console.log(`Title: "${title}"`);
      console.log(`Process Path: "${processPath}"`);
      console.log(`Process Name: "${processName}"`);
      console.log(`ID (HWND/Pid): ${id}`);
      
      const url = await getBrowserUrl(id);
      console.log(`Extracted URL: "${url}"`);
    } else {
      console.log("No active window detected.");
    }
    await new Promise(r => setTimeout(r, 2000));
  }
}

test();
