import { fetchPolicy } from "./api.js";
import { getActiveProfileTargets, getActiveProfileName } from "./profiles.js";

async function run() {
  try {
    const policy = await fetchPolicy();
    console.log("=== POLICY ===");
    console.log("targetApps:", policy.targetApps);
    console.log("targetDomains:", policy.targetDomains);

    const activeProfile = getActiveProfileName();
    const profileTargets = getActiveProfileTargets();
    console.log("=== ACTIVE PROFILE ===");
    console.log("name:", activeProfile);
    console.log("profileTargets:", profileTargets);

    const policyTargets = policy.targetApps.map((it) => it.toLowerCase());
    const targets = [...new Set([...policyTargets, ...profileTargets])];
    console.log("=== MERGED TARGETS ===");
    console.log("targets:", targets);

    const ownerName = "msedge.exe";
    const processName = ownerName.trim().toLowerCase();
    const processBase = processName.endsWith('.exe') ? processName.slice(0, -4) : processName;

    const isMatch = targets.some(target => {
      const targetBase = target.endsWith('.exe') ? target.slice(0, -4) : target;
      return targetBase === processBase;
    });

    console.log(`\nIs "${ownerName}" a policy target?`, isMatch);
  } catch (err) {
    console.error("Error:", err);
  }
}

run();
