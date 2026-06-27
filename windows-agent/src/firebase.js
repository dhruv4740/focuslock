import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { initializeApp } from "firebase/app";
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

// Persisted sync code lives next to the other config so it survives restarts
// and works in both the Electron app and the standalone `node agent.js` CLI.
const SETTINGS_PATH = fileURLToPath(new URL("../config/settings.json", import.meta.url));

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
