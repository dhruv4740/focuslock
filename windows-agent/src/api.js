import {
  db, doc, getDoc, setDoc, updateDoc, runTransaction,
  collection, addDoc, getDocs, query, orderBy, limit, deleteDoc,
  USER_ID
} from "./firebase.js";

// --- POLICY ---
export async function fetchPolicy() {
  const policyRef = doc(db, "users", USER_ID, "policy", "global");
  const snap = await getDoc(policyRef);
  if (snap.exists()) {
    return snap.data();
  }
  return { dailyLimitMinutes: 120, targetApps: [], targetDomains: [], extensionMinutes: 15, hardBlockAfterLimit: false };
}

export async function updatePolicy(policy) {
  const policyRef = doc(db, "users", USER_ID, "policy", "global");
  await setDoc(policyRef, policy, { merge: true });
  return policy;
}

// --- TODOS ---
export async function fetchTodos() {
  const todosRef = collection(db, "users", USER_ID, "todos");
  const q = query(todosRef, orderBy("createdAt", "desc"));
  const snap = await getDocs(q);
  const todos = [];
  snap.forEach(doc => {
    todos.push({ id: doc.id, ...doc.data() });
  });
  return todos;
}

export async function addTodo(text) {
  const todosRef = collection(db, "users", USER_ID, "todos");
  const newTodo = {
    text,
    completed: false,
    createdAt: new Date().toISOString()
  };
  const docRef = await addDoc(todosRef, newTodo);
  return { id: docRef.id, ...newTodo };
}

export async function toggleTodo(id, completed) {
  const todoRef = doc(db, "users", USER_ID, "todos", id);
  await updateDoc(todoRef, { completed });
  return { success: true };
}

export async function deleteTodo(id) {
  const todoRef = doc(db, "users", USER_ID, "todos", id);
  await deleteDoc(todoRef);
  return { success: true };
}

// --- FOCUS SESSION ---
export async function startFocusSession(minutes) {
  const endsAt = new Date(Date.now() + minutes * 60000).toISOString();
  const sessionRef = doc(db, "users", USER_ID, "focus_session", "current");
  const sessionData = { active: true, startsAt: new Date().toISOString(), endsAt };
  await setDoc(sessionRef, sessionData);
  return sessionData;
}

export async function stopFocusSession() {
  const sessionRef = doc(db, "users", USER_ID, "focus_session", "current");
  const sessionData = { active: false, startsAt: "", endsAt: "" };
  await setDoc(sessionRef, sessionData);
  return sessionData;
}

export async function getFocusSession() {
  const sessionRef = doc(db, "users", USER_ID, "focus_session", "current");
  const snap = await getDoc(sessionRef);
  if (snap.exists()) {
    return snap.data();
  }
  return { active: false, startsAt: "", endsAt: "" };
}

// --- EVENTS ---
export async function getEvents() {
  const eventsRef = collection(db, "users", USER_ID, "events");
  const q = query(eventsRef, orderBy("timestamp", "desc"), limit(50));
  const snap = await getDocs(q);
  const events = [];
  snap.forEach(doc => {
    events.push({ id: doc.id, ...doc.data() });
  });
  return events;
}

// --- STREAK ---
export async function getStreak() {
  const snap = await getDoc(doc(db, "users", USER_ID, "streak", "current"));
  return snap.exists()
    ? snap.data()
    : { currentStreak: 0, longestStreak: 0, lastSuccessDate: "" };
}

export async function recordDayOutcome(dateStr, underLimit) {
  const streakRef = doc(db, "users", USER_ID, "streak", "current");
  await runTransaction(db, async (tx) => {
    const snap = await tx.get(streakRef);
    const prev = snap.exists() ? snap.data() : { currentStreak: 0, longestStreak: 0 };
    const newStreak = underLimit ? prev.currentStreak + 1 : 0;
    tx.set(streakRef, {
      currentStreak: newStreak,
      longestStreak: Math.max(newStreak, prev.longestStreak || 0),
      lastSuccessDate: dateStr
    });
  });
}

// --- WEEKLY USAGE ---
export async function getUsageWeek() {
  const today = new Date();
  const results = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(today);
    d.setDate(d.getDate() - i);
    const dateStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    const snap = await getDoc(doc(db, "users", USER_ID, "usage_days", dateStr));
    results.push({
      date: dateStr,
      label: ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"][d.getDay()],
      usedMinutes: snap.exists() ? (snap.data().usedMinutes || 0) : 0
    });
  }
  return results;
}
