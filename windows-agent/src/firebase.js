import { readFileSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { initializeApp } from "firebase/app";
import { initializeAuth, signInAnonymously } from "firebase/auth";
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
  limit,
  arrayUnion
} from "firebase/firestore";
import { createFileAuthPersistence } from "./authPersistence.js";

// Persisted sync code AND auth state must live OUTSIDE the app's install/extract
// directory. A packaged Windows build runs from a read-only install (Program
// Files) or a throwaway temp extraction, so files written next to the app would
// fail to save or vanish on the next launch. %APPDATA%/FocusLock persists
// across updates and works for both the Electron app and the `node agent.js`
// CLI without needing electron's `app` object (which isn't available in the CLI).
const CONFIG_DIR = join(process.env.APPDATA || join(homedir(), ".config"), "FocusLock");
try { mkdirSync(CONFIG_DIR, { recursive: true }); } catch { /* best effort; loadUserId tolerates a missing file */ }

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
const auth = initializeAuth(app, {
  persistence: createFileAuthPersistence(join(CONFIG_DIR, "auth.json"))
});

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

// Firestore rules require request.auth != null and further gate access to a
// per-sync-code member allowlist (see firestore.rules). Sign in anonymously,
// then join the current user's member list, before any other importer
// touches Firestore. Top-level await so agent.js/api.js/main.js/the CLIs only
// see `db` once both are done. Failures are logged, not fatal — the offline
// Firestore cache can still serve reads, and the app shouldn't fail to launch
// just because the network is down at startup.
try {
  await signInAnonymously(auth);
  console.log(`[firebase] anonymous auth ready (uid=${auth.currentUser?.uid})`);
} catch (err) {
  console.error("[firebase] anonymous auth failed:", String(err));
}

async function ensureMembership() {
  const uid = auth.currentUser?.uid;
  if (!uid) return;
  try {
    const userRef = doc(db, "users", USER_ID);
    await runTransaction(db, async (tx) => {
      const snap = await tx.get(userRef);
      if (!snap.exists()) {
        tx.set(userRef, { members: [uid], createdAt: new Date().toISOString() });
      } else if (!(snap.data().members ?? []).includes(uid)) {
        tx.update(userRef, { members: arrayUnion(uid) });
      }
    });
  } catch (err) {
    console.error("[firebase] ensureMembership failed:", String(err));
  }
}
await ensureMembership();

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
