import { getActiveProfileName, getProfileTargets, listProfiles, setActiveProfile } from "./profiles.js";

const [, , command, value] = process.argv;

if (command === "list") {
  const profiles = listProfiles();
  const active = getActiveProfileName();
  for (const profile of profiles) {
    const marker = profile === active ? "*" : " ";
    console.log(`${marker} ${profile}`);
  }
  process.exit(0);
}

if (command === "set") {
  if (!value) {
    console.error("Usage: npm run profile:set -- <profile-name>");
    process.exit(1);
  }

  setActiveProfile(value);
  const targets = getProfileTargets(value);
  console.log(`Active profile set to ${value}`);
  console.log(`Targets: ${targets.join(", ")}`);
  process.exit(0);
}

console.error("Usage: npm run profile:list OR npm run profile:set -- <profile-name>");
process.exit(1);
