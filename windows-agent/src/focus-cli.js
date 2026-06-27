import { startFocusSession, stopFocusSession, getFocusSession } from "./api.js";

const [, , command, durationArg] = process.argv;

async function main() {
  if (command === "status") {
    const session = await getFocusSession();
    console.log(session.active ? `active until ${session.endsAt}` : "inactive");
    return;
  }

  if (command === "start") {
    const duration = Number(durationArg ?? "25");
    if (!Number.isInteger(duration) || duration < 5 || duration > 240) {
      throw new Error("Duration must be an integer between 5 and 240 minutes.");
    }

    const session = await startFocusSession(duration, "windows-cli");
    console.log(`focus session active until ${session.endsAt}`);
    return;
  }

  if (command === "stop") {
    await stopFocusSession();
    console.log("focus session stopped");
    return;
  }

  throw new Error("Usage: npm run focus:start -- <minutes> | npm run focus:stop | npm run focus:status");
}

main().catch((error) => {
  console.error(String(error));
  process.exit(1);
});
