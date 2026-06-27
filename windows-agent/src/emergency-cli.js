import { API_BASE, SETTINGS } from "./config.js";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

async function blockNow(reason = "emergency-block") {
  try {
    const response = await fetch(`${API_BASE}/focus/session/start`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        userId: SETTINGS.userId,
        durationMinutes: 60,
        strict: true,
        reason
      })
    });

    if (!response.ok) {
      throw new Error(`Failed with ${response.status}`);
    }

    const data = await response.json();
    console.log(`Emergency block started for 60 minutes until ${data.session.endsAt}`);
  } catch (error) {
    console.error(`Emergency block failed: ${String(error)}`);
    process.exit(1);
  }
}

async function unblockNow() {
  try {
    const response = await fetch(`${API_BASE}/focus/session/stop`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId: SETTINGS.userId })
    });

    if (!response.ok) {
      throw new Error(`Failed with ${response.status}`);
    }

    console.log("Emergency block stopped");
  } catch (error) {
    console.error(`Emergency unblock failed: ${String(error)}`);
    process.exit(1);
  }
}

async function killProcessNow(processName) {
  try {
    await execFileAsync("taskkill", ["/IM", processName, "/F"]);
    console.log(`Killed ${processName}`);
  } catch {
    console.error(`Failed to kill ${processName}`);
    process.exit(1);
  }
}

const [, , command, arg] = process.argv;

if (command === "block") {
  blockNow(arg);
} else if (command === "unblock") {
  unblockNow();
} else if (command === "kill") {
  if (!arg) {
    console.error("Usage: npm run emergency -- kill <process-name>");
    process.exit(1);
  }
  killProcessNow(arg);
} else {
  console.error("Usage: npm run emergency -- block|unblock|kill");
  process.exit(1);
}
