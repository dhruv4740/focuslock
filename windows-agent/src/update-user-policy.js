import { updatePolicy } from "./api.js";

async function run() {
  const policy = {
    userId: "local-user",
    deviceId: "windows-laptop-1",
    platform: "windows",
    targetDomains: ["youtube.com/shorts", "instagram.com"],
    targetApps: [],
    dailyLimitMinutes: 30,
    extensionMinutes: 5,
    extensionPerDay: 1,
    fullScreenReminder: true,
    hardBlockAfterLimit: true,
    reminderIntervalMinutes: 5
  };

  try {
    const updated = await updatePolicy(policy);
    console.log("Policy updated successfully:", updated);
  } catch (err) {
    console.error("Failed to update policy:", err);
  }
}

run();
