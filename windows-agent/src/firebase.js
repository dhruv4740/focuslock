import { readFileSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { initializeApp } from "firebase/app";
import { getAuth, signInAnonymously } from "firebase/auth";
import {
  getFirestore,
  doc,
  onSnapshot,
  runTransaction,
  setDoc,
  getDoc,
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  getDocs,
  query,
  orderBy,
  limit
} from "firebase/firestore";

// Firebase web API keys are intentionally public — security is enforced
// via Firestore Security Rules on the Firebase console, not by keeping
// these values secret.
const firebaseConfig = {
  apiKey: "AIzaSyCwxWxVk7MMkpzkO39D7DTpbAyPrCmevvc",
  authDomain: "focusblock-e8376.firebaseapp.com",
  projectId: "focusblock-e8376",
  storageBucket: "focusblock-e8376.firebasestorage.app",
  messagingSenderId: "22012836707",
  appId: "1:22012836707:web:3d78f0e2a75849e2c63156"
};

const app = initializeApp(firebaseConfig);
const db = getFirestore(app);

// Firestore rules require request.auth != null. Sign in anonymously at module
// load with a top-level await so every importer of `db` (agent.js, api.js,
// main.js, the CLIs) only sees Firestore once auth is ready. Failure is logged,
// not fatal — keeps the app launching if the network is down at startup.
try {
  await signInAnonymously(getAuth(app));
  console.log("[firebase] anonymous auth ready");
} catch (err) {
  console.error("[firebase] anonymous auth failed:", String(err));
}

// Persisted sync code must live OUTSIDE the app's install/extract directory.
// A packaged Windows build runs from a read-only install (Program Files) or a
// throwaway temp extraction, so settings written next to the app would fail to
// save or vanish on the next launch. %APPDATA%/FocusLock persists across
// updates and works for both the Electron app and the `node agent.js` CLI
// without needing electron's `app` object (which isn't available in the CLI).
const CONFIG_DIR = join(process.env.APPDATA || join(homedir(), ".config"), "FocusLock");
try { mkdirSync(CONFIG_DIR, { recursive: true }); } catch { /* best effort; loadUserId tolerates a missing file */ }
const SETTINGS_PATH = join(CONFIG_DIR, "settings.json");

// Must match FocusPolicyClient.sanitizeSyncId on Android so the same human
// input resolves to the same Firestore doc id on both platforms.
function sanitizeSyncId(raw) {
  let clean = String(raw ?? "").trim().toLowerCase();
  clean = clean.replace(/[^a-z0-9_-]/g, "-");
  clean = clean.replace(/-+/g, "-");
  clean = clean.replace(/^-+|-+$/g, "");
  if (!clean) return "user_default";
  return clean.slice(0, 64);
}

// Precedence: env override → settings.json → default. Each person sets the same
// code on their phone + desktop; different people stay isolated.
function loadUserId() {
  if (process.env.FOCUSLOCK_USER_ID) return sanitizeSyncId(process.env.FOCUSLOCK_USER_ID);
  try {
    const cfg = JSON.parse(readFileSync(SETTINGS_PATH, "utf8"));
    if (cfg && typeof cfg.syncId === "string" && cfg.syncId.trim()) {
      return sanitizeSyncId(cfg.syncId);
    }
  } catch {
    // no settings file yet — first run
  }
  return "user_default";
}

const USER_ID = loadUserId();

export {
  db,
  doc,
  onSnapshot,
  runTransaction,
  setDoc,
  getDoc,
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  getDocs,
  query,
  orderBy,
  limit,
  USER_ID,
  SETTINGS_PATH,
  sanitizeSyncId
};
