import fs from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const profileFile = fileURLToPath(new URL("../config/target-profiles.json", import.meta.url));
const activeProfileFile = fileURLToPath(new URL("../config/active-profile.json", import.meta.url));

export function listProfiles() {
  const profiles = JSON.parse(fs.readFileSync(profileFile, "utf-8"));
  return Object.keys(profiles);
}

export function getProfileTargets(name) {
  const profiles = JSON.parse(fs.readFileSync(profileFile, "utf-8"));
  return profiles[name] ?? [];
}

export function getActiveProfileName() {
  const active = JSON.parse(fs.readFileSync(activeProfileFile, "utf-8"));
  return active.name;
}

export function setActiveProfile(name) {
  const profiles = listProfiles();
  if (!profiles.includes(name)) {
    throw new Error(`Unknown profile: ${name}`);
  }

  fs.writeFileSync(activeProfileFile, JSON.stringify({ name }, null, 2) + "\n", "utf-8");
}

export function getActiveProfileTargets() {
  return getProfileTargets(getActiveProfileName()).map((x) => x.toLowerCase());
}

export function getConfigDir() {
  return path.dirname(profileFile);
}
