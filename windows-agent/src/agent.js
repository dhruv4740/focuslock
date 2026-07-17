import activeWin from "active-win";
import { execFile, spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { basename } from "node:path";
import { promisify } from "node:util";
import readline from "node:readline";
import { INTERVALS } from "./config.js";
import { getActiveProfileName, getActiveProfileTargets } from "./profiles.js";
import {
  isUnderLimit,
  parseUrlHelper,
  matchTargetDomain,
  isInScheduledBlockWindow,
  getTodayString,
  addDays
} from "./logic.js";

import { db, doc, getDoc, setDoc, onSnapshot, runTransaction, collection, addDoc, USER_ID } from "./firebase.js";

const execFileAsync = promisify(execFile);

const state = {
  policy: null,
  usage: { usedMinutes: 0, extensionsUsedToday: 0 },
  focusSession: { active: false, startsAt: "", endsAt: "" },
  currentSession: null,
  lastEvaluateAtByTarget: new Map(),
  blockedMode: false,
  blockedTarget: null,
  blockedTargetIsApp: false,
  running: false,
  activeProfileName: "",
  profileTargets: [],
  cachedTargetBases: new Set()
};

function updateCachedTargets() {
  const bases = new Set();
  for (const list of [state.policy?.targetApps, state.profileTargets]) {
    if (!list) continue;
    for (const app of list) {
      const lower = app.toLowerCase();
      bases.add(lower.endsWith(".exe") ? lower.slice(0, -4) : lower);
    }
  }
  state.cachedTargetBases = bases;
}

function nowIso() {
  return new Date().toISOString();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const getBrowserUrlScriptPath = fileURLToPath(
  new URL("../scripts/get-browser-url.ps1", import.meta.url)
);

async function getBrowserUrl(windowId) {
  if (!windowId) return "";
  try {
    const { stdout } = await execFileAsync("powershell", [
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      getBrowserUrlScriptPath,
      "-HwndVal",
      windowId
    ]);
    return stdout.trim();
  } catch (error) {
    console.error(`[enforce] getBrowserUrl failed: ${String(error)}`);
    return "";
  }
}

function normalizeProcessName(ownerName) {
  return (ownerName ?? "").trim().toLowerCase();
}

function isPolicyTarget(ownerName) {
  if (!state.policy) return false;
  const processName = normalizeProcessName(ownerName);
  const processBase = processName.endsWith(".exe") ? processName.slice(0, -4) : processName;
  return state.cachedTargetBases.has(processBase);
}

let lastProfileRefreshAt = 0;
const PROFILE_REFRESH_THROTTLE_MS = 60_000;

function refreshLocalProfileTargets(force = false) {
  const now = Date.now();
  if (!force && now - lastProfileRefreshAt < PROFILE_REFRESH_THROTTLE_MS) return;
  lastProfileRefreshAt = now;
  try {
    const profileName = getActiveProfileName();
    state.profileTargets = getActiveProfileTargets();
    if (state.activeProfileName !== profileName) {
      state.activeProfileName = profileName;
      console.log(`[profile] active: ${profileName}`);
    }
    updateCachedTargets();
  } catch (error) {
    console.error(`[profile] ${String(error)}`);
  }
}

// ── Firebase listeners ────────────────────────────────────────────────────────

function setupFirebaseListeners() {
  console.log("[firebase] Setting up listeners…");

  onSnapshot(doc(db, "users", USER_ID, "policy", "global"), (snap) => {
    if (snap.exists()) {
      state.policy = snap.data();
      updateCachedTargets();
      console.log(`[policy] updated: ${state.policy.targetApps?.length} apps, ${state.policy.targetDomains?.length} domains`);
    }
  });

  // Re-subscribe each day so the usage listener tracks the right document.
  // The listener is keyed to today's local date.
  let trackedDate = getTodayString();
  let usageUnsub = null;

  function subscribeUsage() {
    usageUnsub?.();
    trackedDate = getTodayString();
    const usageRef = doc(db, "users", USER_ID, "usage_days", trackedDate);
    usageUnsub = onSnapshot(usageRef, (snap) => {
      state.usage = snap.exists()
        ? snap.data()
        : { usedMinutes: 0, extensionsUsedToday: 0 };
      console.log(`[usage] ${state.usage.usedMinutes?.toFixed(1)} min used`);
    });
  }
  subscribeUsage();

  // Roll over at local midnight: record streak outcome for elapsed day(s), then resubscribe.
  // This 60s interval only fires while the process is running — see checkDayRollover's
  // callers in main.js (powerMonitor 'resume'/'unlock-screen') for the sleep/shutdown case.
  setInterval(async () => {
    if (getTodayString() !== trackedDate) {
      await checkDayRollover();
      console.log("[usage] new day — re-subscribing usage listener");
      subscribeUsage();
    }
  }, 60_000);

  onSnapshot(doc(db, "users", USER_ID, "focus_session", "current"), (snap) => {
    if (snap.exists()) {
      state.focusSession = snap.data();
      if (state.focusSession.active) {
        console.log(`[focus-session] active until ${state.focusSession.endsAt}`);
      } else {
        console.log("[focus-session] inactive");
        // Clear blocked mode so enforcement stops immediately when session ends
        if (state.blockedMode) {
          state.blockedMode = false;
          state.blockedTarget = null;
          state.blockedTargetIsApp = false;
          console.log("[focus-session] cleared block state after session ended");
        }
      }
    }
  });
}

// ── Session accounting ────────────────────────────────────────────────────────

async function finalizeCurrentSession(endTs) {
  if (!state.currentSession) return;

  const { startedAt, target } = state.currentSession;
  const durationMinutes = (Date.parse(endTs) - Date.parse(startedAt)) / 60_000;
  state.currentSession = null;

  if (durationMinutes <= 0) return;

  try {
    const today = getTodayString();
    const usageRef = doc(db, "users", USER_ID, "usage_days", today);

    await runTransaction(db, async (tx) => {
      const snap = await tx.get(usageRef);
      if (!snap.exists()) {
        tx.set(usageRef, { usedMinutes: durationMinutes, extensionsUsedToday: 0, opensToday: 1 });
      } else {
        const data = snap.data();
        tx.update(usageRef, {
          usedMinutes: (data.usedMinutes || 0) + durationMinutes,
          opensToday: (data.opensToday || 0) + 1
        });
      }
    });

    await addDoc(collection(db, "users", USER_ID, "events"), {
      type: "usage",
      target,
      durationMinutes,
      startedAt,
      timestamp: endTs
    });

    console.log(`[event] ${target} → ${durationMinutes.toFixed(2)} min synced`);
  } catch (error) {
    console.error(`[event] sync failed: ${String(error)}`);
  }
}

// ── Enforcement ───────────────────────────────────────────────────────────────

async function killTargetProcess(processName) {
  try {
    await execFileAsync("taskkill", ["/IM", processName, "/F"]);
    console.log(`[enforce] terminated ${processName}`);
  } catch {
    // Process may have already exited
  }
}

// The main monitor loop only acts on the foreground window, so a blocked app
// running in the background (never brought to front) was never enforced.
// This periodic sweep closes that gap for apps (taskkill /IM already kills
// every instance of a name, so this doesn't need per-window targeting).
// Browser tabs can't be swept this way — a background tab is invisible to
// this process short of automating every open browser window, so domain
// blocking still only fires when that tab is foregrounded.
async function sweepBackgroundTargets() {
  if (!state.policy || state.cachedTargetBases.size === 0) return;

  const blockingActive =
    isFocusSessionActive() ||
    isInScheduledBlockWindow(state.policy.blockWindows) ||
    (state.policy.hardBlockAfterLimit && !isUnderLimit(state.policy, state.usage));
  if (!blockingActive) return;

  try {
    const { stdout } = await execFileAsync("tasklist", ["/FO", "CSV", "/NH"]);
    const killed = new Set();
    for (const line of stdout.split(/\r?\n/)) {
      const match = line.match(/^"([^"]+)"/);
      if (!match) continue;
      const imageName = match[1];
      if (killed.has(imageName)) continue;
      const lower = imageName.toLowerCase();
      const base = lower.endsWith(".exe") ? lower.slice(0, -4) : lower;
      if (state.cachedTargetBases.has(base)) {
        killed.add(imageName);
        await killTargetProcess(imageName);
      }
    }
  } catch (error) {
    console.error(`[enforce] background sweep failed: ${String(error)}`);
  }
}

// Closes the active browser tab with a real Ctrl+W via SendInput. PostMessage
// of WM_KEYDOWN does NOT work on Chromium: Chrome reads modifier state through
// GetKeyState, which PostMessage never updates, so a posted 'W' is seen as a
// plain keypress with Ctrl not held. SendInput injects into the system input
// stream and updates global key state, so the browser sees a genuine Ctrl+W.
//
// The SendInput + Add-Type C# compile is delegated to a single persistent
// PowerShell child process (see close-window-helper.ps1) instead of spawning
// powershell.exe fresh on every tick — that spawn+compile cost (hundreds of
// ms) was paid every 4s while blocked. The helper verifies the target window
// is still foreground (GetForegroundWindow) right before sending the
// keystroke; if focus moved elsewhere in the interim (SetForegroundWindow can
// silently fail under Windows' foreground-lock rules) it reports SKIP instead
// of blindly firing Ctrl+W into whatever the user is now looking at.
const closeWindowHelperScriptPath = fileURLToPath(
  new URL("../scripts/close-window-helper.ps1", import.meta.url)
);

let psHelper = null; // { proc, rl, busy: Promise }

function spawnPsHelper() {
  const proc = spawn(
    "powershell",
    ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", closeWindowHelperScriptPath],
    { windowsHide: true, stdio: ["pipe", "pipe", "pipe"] }
  );
  const rl = readline.createInterface({ input: proc.stdout });
  proc.stderr.on("data", (d) => console.error(`[enforce] close-window helper stderr: ${d}`));
  proc.on("exit", (code) => {
    console.log(`[enforce] close-window helper exited (code=${code})`);
    if (psHelper && psHelper.proc === proc) psHelper = null;
  });
  psHelper = { proc, rl, busy: Promise.resolve() };
  return psHelper;
}

function ensurePsHelper() {
  if (!psHelper || psHelper.proc.exitCode !== null) return spawnPsHelper();
  return psHelper;
}

function sendToPsHelper(hwnd) {
  const helper = ensurePsHelper();
  const run = helper.busy.then(
    () =>
      new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          helper.rl.removeListener("line", onLine);
          reject(new Error("close-window helper timed out"));
        }, 3000);
        function onLine(line) {
          clearTimeout(timeout);
          helper.rl.removeListener("line", onLine);
          resolve(line.trim());
        }
        helper.rl.on("line", onLine);
        helper.proc.stdin.write(`${hwnd}\n`);
      })
  );
  // Keep the chain alive even if this call errors/times out, so the next
  // caller doesn't wait on a rejected promise forever.
  helper.busy = run.then(
    () => {},
    () => {}
  );
  return run;
}

async function closeWindow(windowId, targetName) {
  if (!windowId) return;
  try {
    const result = await sendToPsHelper(windowId);
    if (result === "OK") {
      console.log(`[enforce] closed tab for ${targetName} (hwnd=${windowId})`);
    } else if (result === "SKIP") {
      console.log(`[enforce] skipped close for ${targetName}: not foreground anymore (hwnd=${windowId})`);
    } else {
      console.error(`[enforce] close-window helper returned unexpected result "${result}" for ${targetName}`);
    }
  } catch (error) {
    console.error(`[enforce] closeWindow failed for ${targetName}: ${String(error)}`);
    // Force a respawn next time in case the helper process is wedged.
    if (psHelper) {
      try { psHelper.proc.kill(); } catch { /* best effort */ }
      psHelper = null;
    }
  }
}

async function recordStreakOutcome(dateStr, underLimit) {
  try {
    const streakRef = doc(db, "users", USER_ID, "streak", "current");
    await runTransaction(db, async (tx) => {
      const snap = await tx.get(streakRef);
      const prev = snap.exists()
        ? snap.data()
        : { currentStreak: 0, longestStreak: 0, lastRecordedDate: null };
      // Idempotency guard: the 60s interval and a powerMonitor resume/unlock
      // event can both call this for the same day (e.g. app was already
      // running when the PC unlocked) — don't double-count the streak.
      if (prev.lastRecordedDate && prev.lastRecordedDate >= dateStr) return;
      const newStreak = underLimit ? (prev.currentStreak || 0) + 1 : 0;
      tx.set(streakRef, {
        currentStreak: newStreak,
        longestStreak: Math.max(newStreak, prev.longestStreak || 0),
        lastSuccessDate: dateStr,
        lastRecordedDate: dateStr
      });
    });
    console.log(`[streak] day ${dateStr} ${underLimit ? "kept" : "broken"} — streak update written`);
  } catch (e) {
    console.error(`[streak] failed to record day outcome: ${String(e)}`);
  }
}

async function computeDayOutcome(dateStr) {
  try {
    const snap = await getDoc(doc(db, "users", USER_ID, "usage_days", dateStr));
    const usage = snap.exists() ? snap.data() : { usedMinutes: 0, extensionsUsedToday: 0 };
    return isUnderLimit(state.policy, usage);
  } catch (e) {
    console.error(`[streak] failed to read usage for ${dateStr}: ${String(e)}`);
    return true; // fail open — a transient read error shouldn't break the streak
  }
}

const MAX_BACKFILL_DAYS = 30;

// Records streak outcomes for every fully-elapsed day since the last recorded
// one. Called from the 60s in-process interval (normal case) and from
// main.js on powerMonitor 'resume'/'unlock-screen' (PC was asleep/off across
// local midnight, so the interval never fired).
export async function checkDayRollover() {
  try {
    const streakRef = doc(db, "users", USER_ID, "streak", "current");
    const snap = await getDoc(streakRef);
    const today = getTodayString();

    if (!snap.exists() || !snap.data().lastRecordedDate) {
      // First run ever (or a pre-migration doc) — establish a baseline
      // pointer at yesterday. We don't guess at outcomes for days before we
      // started tracking.
      const prev = snap.exists() ? snap.data() : { currentStreak: 0, longestStreak: 0 };
      await setDoc(streakRef, { ...prev, lastRecordedDate: addDays(today, -1) }, { merge: true });
      return;
    }

    const lastRecordedDate = snap.data().lastRecordedDate;
    if (lastRecordedDate >= today) return; // already up to date

    let cursor = addDays(lastRecordedDate, 1);
    let processed = 0;
    while (cursor < today && processed < MAX_BACKFILL_DAYS) {
      const underLimit = await computeDayOutcome(cursor);
      await recordStreakOutcome(cursor, underLimit);
      cursor = addDays(cursor, 1);
      processed++;
    }
  } catch (e) {
    console.error(`[streak] rollover check failed: ${String(e)}`);
  }
}

function isFocusSessionActive() {
  return (
    state.focusSession.active &&
    state.focusSession.endsAt &&
    Date.parse(state.focusSession.endsAt) > Date.now()
  );
}

async function evaluateAndEnforce(target, windowId = null, isApp = false) {
  const now = Date.now();
  const lastAt = state.lastEvaluateAtByTarget.get(target) ?? 0;
  if (now - lastAt < INTERVALS.evaluateMs) return;
  state.lastEvaluateAtByTarget.set(target, now);

  if (!state.policy) return;

  if (isFocusSessionActive() || isInScheduledBlockWindow(state.policy.blockWindows)) {
    state.blockedMode = true;
    state.blockedTarget = target;
    state.blockedTargetIsApp = isApp;
  } else {
    const allowNow = isUnderLimit(state.policy, state.usage);

    if (allowNow) {
      state.blockedMode = false;
      state.blockedTarget = null;
      state.blockedTargetIsApp = false;
      return;
    }

    if (state.policy.hardBlockAfterLimit) {
      state.blockedMode = true;
      state.blockedTarget = target;
      state.blockedTargetIsApp = isApp;
    } else {
      state.blockedMode = false;
      state.blockedTarget = null;
      state.blockedTargetIsApp = false;
    }
  }

  if (state.blockedMode) {
    if (isApp) {
      await killTargetProcess(target);
    } else if (windowId) {
      await closeWindow(windowId, target);
    }
  }
}

async function enforceBlockedMode(activeProcessName, activeWindowId, activeWindowMatchedDomain) {
  if (!state.blockedMode || !state.blockedTarget) return;

  // Clear block state when nothing is actively blocking (session expired, outside window, under limit)
  if (!isFocusSessionActive() && !isInScheduledBlockWindow(state.policy?.blockWindows) && !state.policy?.hardBlockAfterLimit) {
    state.blockedMode = false;
    state.blockedTarget = null;
    state.blockedTargetIsApp = false;
    return;
  }

  const currentIsBlocked = state.blockedTargetIsApp
    ? activeProcessName === state.blockedTarget
    : activeWindowMatchedDomain === state.blockedTarget;

  if (!currentIsBlocked) return;

  if (state.blockedTargetIsApp) {
    await killTargetProcess(state.blockedTarget);
  } else if (activeWindowId) {
    await closeWindow(activeWindowId, state.blockedTarget);
  }
}

// ── URL / domain matching ─────────────────────────────────────────────────────

const BROWSER_PROCESSES = new Set(["chrome", "msedge", "firefox", "brave", "opera"]);

let lastWindowId = null;
let lastWindowTitle = null;
let lastExtractedUrl = "";

async function getBrowserUrlWithCache(windowId, title) {
  if (!windowId) return "";
  if (windowId === lastWindowId && title === lastWindowTitle) return lastExtractedUrl;
  const url = await getBrowserUrl(windowId);
  lastWindowId = windowId;
  lastWindowTitle = title;
  lastExtractedUrl = url;
  return url;
}

async function getMatchingTargetDomain(title, processName, windowId) {
  if (!state.policy?.targetDomains?.length) return null;

  const processBase = processName.endsWith(".exe") ? processName.slice(0, -4) : processName;
  if (!BROWSER_PROCESSES.has(processBase)) return null;

  const titleLower = (title ?? "").toLowerCase();
  const url = await getBrowserUrlWithCache(windowId, title);
  const parsed = url ? parseUrlHelper(url) : null;

  return matchTargetDomain(parsed, titleLower, state.policy.targetDomains);
}

// ── Monitor loop ──────────────────────────────────────────────────────────────

async function monitorLoop() {
  while (state.running) {
    try {
      refreshLocalProfileTargets();

      const active = await activeWin();
      const processPath = active?.owner?.path ?? "";
      const processName = normalizeProcessName(
        processPath ? basename(processPath) : active?.owner?.name
      );
      const matchedDomain = await getMatchingTargetDomain(active?.title, processName, active?.id);
      const isTarget = isPolicyTarget(processName) || matchedDomain !== null;

      if (!isTarget) {
        await finalizeCurrentSession(nowIso());
        await enforceBlockedMode(processName, active?.id, matchedDomain);
        await sleep(INTERVALS.activeWindowPollMs);
        continue;
      }

      const sessionTarget = matchedDomain ?? processName;
      const isApp = matchedDomain === null;

      if (!state.currentSession) {
        state.currentSession = { target: sessionTarget, startedAt: nowIso() };
        console.log(`[session] started ${sessionTarget}`);
      }

      await evaluateAndEnforce(sessionTarget, active?.id, isApp);
      await enforceBlockedMode(processName, active?.id, matchedDomain);
    } catch (error) {
      console.error(`[monitor] ${String(error)}`);
    }

    await sleep(INTERVALS.activeWindowPollMs);
  }
}

const BACKGROUND_SWEEP_INTERVAL_MS = 30_000;
let backgroundSweepTimer = null;

export function startAgent() {
  if (state.running) return;
  state.running = true;
  console.log("[agent] FocusLock Windows Agent starting…");
  setupFirebaseListeners();
  checkDayRollover().catch((e) => console.error(`[streak] initial rollover check failed: ${String(e)}`));
  monitorLoop();
  backgroundSweepTimer = setInterval(() => {
    sweepBackgroundTargets().catch((e) => console.error(`[enforce] background sweep error: ${String(e)}`));
  }, BACKGROUND_SWEEP_INTERVAL_MS);
}

export function stopAgent() {
  state.running = false;
  if (backgroundSweepTimer) {
    clearInterval(backgroundSweepTimer);
    backgroundSweepTimer = null;
  }
  if (psHelper) {
    try { psHelper.proc.kill(); } catch { /* best effort */ }
    psHelper = null;
  }
  console.log("[agent] FocusLock Windows Agent stopped.");
}

export function getAgentState() {
  return {
    usedMinutes: state.usage?.usedMinutes ?? 0,
    dailyLimit: state.policy?.dailyLimitMinutes ?? 0,
    blockedMode: state.blockedMode
  };
}

const isMain =
  process.argv[1] &&
  (process.argv[1].endsWith("agent.js") || process.argv[1].endsWith("agent"));
if (isMain) startAgent();
