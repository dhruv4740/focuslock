import { db, doc, setDoc, USER_ID, getDoc } from "../src/firebase.js";

async function init() {
  const policyRef = doc(db, "users", USER_ID, "policy", "global");
  const snap = await getDoc(policyRef);
  
  if (!snap.exists()) {
    console.log("Initializing default policy...");
    await setDoc(policyRef, {
      targetDomains: ["instagram.com", "youtube.com/shorts", "tiktok.com", "x.com", "reddit.com"],
      targetApps: ["steam.exe", "epicgameslauncher.exe", "valorant.exe", "com.instagram.android", "com.google.android.youtube"],
      dailyLimitMinutes: 30,
      extensionMinutes: 5,
      extensionPerDay: 1,
      fullScreenReminder: true,
      hardBlockAfterLimit: true,
      reminderIntervalMinutes: 5
    });
    console.log("Initialized.");
  } else {
    console.log("Policy already exists.");
  }
  
  // Also create today's usage doc to avoid read errors if it doesn't exist
  const today = new Date().toISOString().slice(0, 10);
  const usageRef = doc(db, "users", USER_ID, "usage_days", today);
  const usageSnap = await getDoc(usageRef);
  if (!usageSnap.exists()) {
    await setDoc(usageRef, {
      usedMinutes: 0,
      extensionsUsedToday: 0
    });
    console.log("Initialized usage doc for today.");
  }
  
  const focusRef = doc(db, "users", USER_ID, "focus_session", "current");
  const focusSnap = await getDoc(focusRef);
  if (!focusSnap.exists()) {
    await setDoc(focusRef, {
      active: false,
      startsAt: "",
      endsAt: ""
    });
    console.log("Initialized focus session doc.");
  }

  process.exit(0);
}

init().catch(console.error);
