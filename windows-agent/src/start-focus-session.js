import { startFocusSession } from "./api.js";

async function run() {
  try {
    const res = await startFocusSession(25, "Testing agent blocking");
    console.log("Focus session started:", res);
  } catch (err) {
    console.error("Failed to start focus session:", err);
  }
}

run();
