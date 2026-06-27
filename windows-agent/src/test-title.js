import activeWin from "active-win";
import { basename } from "node:path";

const BROWSER_PROCESSES = ["chrome", "msedge", "firefox", "brave", "opera"];

async function test() {
  console.log("Monitoring active window for 20 seconds. Please focus your browser and open instagram.com / watch youtube shorts...");
  for (let i = 0; i < 20; i++) {
    try {
      const active = await activeWin();
      if (active) {
        const processPath = active.owner?.path ?? "";
        const processName = active.owner?.name ?? "";
        const title = active.title ?? "";
        const id = active.id;
        const processBase = processPath ? basename(processPath) : processName;
        const processLower = processBase.toLowerCase();
        const cleanProcess = processLower.endsWith('.exe') ? processLower.slice(0, -4) : processLower;
        const isBrowser = BROWSER_PROCESSES.includes(cleanProcess);

        console.log(`[${i+1}/20] Title: "${title}" | Proc: "${processBase}" | isBrowser: ${isBrowser}`);
      } else {
        console.log(`[${i+1}/20] No active window`);
      }
    } catch (e) {
      console.error("Error: ", e);
    }
    await new Promise(r => setTimeout(r, 1000));
  }
}

test();
