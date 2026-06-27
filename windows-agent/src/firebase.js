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

// USER_ID can be overridden via environment variable when multi-user support
// is added. Keep the default consistent with the Android side.
const USER_ID = process.env.FOCUSLOCK_USER_ID ?? "user_default";

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
  USER_ID
};
