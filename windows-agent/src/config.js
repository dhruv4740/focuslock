export const API_BASE = process.env.FOCUSLOCK_API_BASE ?? "http://localhost:4050";

export const SETTINGS = {
  userId: process.env.FOCUSLOCK_USER_ID ?? "local-user",
  deviceId: process.env.FOCUSLOCK_DEVICE_ID ?? "windows-laptop-1",
  platform: "windows"
};

export const INTERVALS = {
  activeWindowPollMs: 4000,
  evaluateMs: 15000,
  policyRefreshMs: 60000,
  blockPromptCooldownMs: 30000,
  focusSessionRefreshMs: 60000
};
