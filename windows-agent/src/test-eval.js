import { evaluateIntervention, getFocusSession } from "./api.js";

async function run() {
  const session = await getFocusSession();
  console.log("Active focus session:", session);

  const evalResult = await evaluateIntervention({ requestExtension: false });
  console.log("Evaluation result:", evalResult);
}

run().catch(console.error);
