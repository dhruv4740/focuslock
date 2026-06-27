import activeWin from "active-win";

async function run() {
  console.log("Polling active window details every 1s for 10 seconds...");
  for (let i = 0; i < 10; i++) {
    const active = await activeWin();
    if (active) {
      console.log(`[${i}] Title: "${active.title}" | Owner: "${active.owner?.name}" (ID: ${active.id}) | Path: "${active.owner?.path}"`);
    } else {
      console.log(`[${i}] No active window`);
    }
    await new Promise(r => setTimeout(r, 1000));
  }
}

run();
