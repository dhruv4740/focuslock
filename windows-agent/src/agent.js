import activeWin from "active-win";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { basename } from "node:path";
import { promisify } from "node:util";
import { INTERVALS } from "./config.js";
import { getActiveProfileName, getActiveProfileTargets } from "./profiles.js";

import { db, doc, onSnapshot, runTransaction, collection, addDoc, USER_ID } from "./firebase.js";

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

// Use local calendar date so daily limits reset at local midnight,
// not at UTC midnight (which is 05:30 IST — mid-morning).
function getTodayString() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
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

  // Roll over at local midnight: record streak outcome for the day that ended, then resubscribe
  setInterval(async () => {
    if (getTodayString() !== trackedDate) {
      const prevDate = trackedDate;
      const policy = state.policy;
      const effectiveLimit =
        (policy?.dailyLimitMinutes ?? 0) +
        (state.usage?.extensionsUsedToday ?? 0) * (policy?.extensionMinutes ?? 15);
      const underLimit = effectiveLimit === 0 || (state.usage?.usedMinutes ?? 0) < effectiveLimit;
      await recordStreakOutcome(prevDate, underLimit);
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

// Closes the active browser tab with a real Ctrl+W via SendInput. PostMessage
// of WM_KEYDOWN does NOT work on Chromium: Chrome reads modifier state through
// GetKeyState, which PostMessage never updates, so a posted 'W' is seen as a
// plain keypress with Ctrl not held. SendInput injects into the system input
// stream and updates global key state, so the browser sees a genuine Ctrl+W.
// SendInput targets the foreground window — which is exactly the window we
// enforce on, since the monitor only acts on the active window. We re-assert
// foreground on the target hwnd first so a stray focus change can't misfire it.
async function closeWindow(windowId, targetName) {
  if (!windowId) return;
  try {
    const script = `
$hwnd = [IntPtr]${windowId}
$sig = @"
using System;
using System.Runtime.InteropServices;
public class FLSendInput {
    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    public struct HARDWAREINPUT { public uint uMsg; public ushort wParamL; public ushort wParamH; }
    [StructLayout(LayoutKind.Explicit)]
    public struct InputUnion {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT { public uint type; public InputUnion u; }
    [DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    public const uint INPUT_KEYBOARD = 1;
    public const uint KEYEVENTF_KEYUP = 0x0002;
    public const ushort VK_CONTROL = 0x11;
    public const ushort VK_W = 0x57;
    public static uint CtrlW(IntPtr hWnd) {
        SetForegroundWindow(hWnd);
        System.Threading.Thread.Sleep(20);
        INPUT[] inputs = new INPUT[4];
        for (int i = 0; i < 4; i++) inputs[i].type = INPUT_KEYBOARD;
        inputs[0].u.ki.wVk = VK_CONTROL;
        inputs[1].u.ki.wVk = VK_W;
        inputs[2].u.ki.wVk = VK_W;       inputs[2].u.ki.dwFlags = KEYEVENTF_KEYUP;
        inputs[3].u.ki.wVk = VK_CONTROL; inputs[3].u.ki.dwFlags = KEYEVENTF_KEYUP;
        return SendInput(4, inputs, Marshal.SizeOf(typeof(INPUT)));
    }
}
"@
if (-not ([System.Management.Automation.PSTypeName]'FLSendInput').Type) {
    Add-Type -TypeDefinition $sig
}
[FLSendInput]::CtrlW($hwnd) | Out-Null
`;
    await execFileAsync(
      "powershell",
      ["-NoProfile", "-WindowStyle", "Hidden", "-Command", script],
      { windowsHide: true }
    );
    console.log(`[enforce] closed tab for ${targetName} (hwnd=${windowId})`);
  } catch (error) {
    console.error(`[enforce] closeWindow failed for ${targetName}: ${String(error)}`);
  }
}

function isInScheduledBlockWindow() {
  const windows = state.policy?.blockWindows;
  if (!Array.isArray(windows) || windows.length === 0) return false;
  const now = new Date();
  const day = now.getDay(); // 0=Sun
  const currentMin = now.getHours() * 60 + now.getMinutes();
  return windows.some((w) => {
    if (Array.isArray(w.days) && !w.days.includes(day)) return false;
    const [sh, sm] = (w.start || "00:00").split(":").map(Number);
    const [eh, em] = (w.end   || "23:59").split(":").map(Number);
    return currentMin >= sh * 60 + sm && currentMin < eh * 60 + em;
  });
}

async function recordStreakOutcome(dateStr, underLimit) {
  try {
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
    console.log(`[streak] day ${dateStr} ${underLimit ? "kept" : "broken"} — streak update written`);
  } catch (e) {
    console.error(`[streak] failed to record day outcome: ${String(e)}`);
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

  if (isFocusSessionActive() || isInScheduledBlockWindow()) {
    state.blockedMode = true;
    state.blockedTarget = target;
    state.blockedTargetIsApp = isApp;
  } else {
    const effectiveLimit =
      (state.policy.dailyLimitMinutes ?? 0) +
      (state.usage.extensionsUsedToday ?? 0) * (state.policy.extensionMinutes ?? 15);

    // effectiveLimit of 0 means no limit configured — allow
    const allowNow = effectiveLimit === 0 || (state.usage.usedMinutes ?? 0) < effectiveLimit;

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
  if (!isFocusSessionActive() && !isInScheduledBlockWindow() && !state.policy?.hardBlockAfterLimit) {
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

function parseUrlHelper(urlStr) {
  let tmp = urlStr.trim();
  if (!/^https?:\/\//i.test(tmp)) tmp = "https://" + tmp;
  try {
    const parsed = new URL(tmp);
    return { hostname: parsed.hostname.toLowerCase(), pathname: parsed.pathname.toLowerCase() };
  } catch {
    return null;
  }
}

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

  for (const domain of state.policy.targetDomains) {
    const domainLower = domain.toLowerCase().replace(/^https?:\/\//, "");
    const slashIdx = domainLower.indexOf("/");
    const hasPath = slashIdx !== -1;
    const domainHost = hasPath ? domainLower.slice(0, slashIdx) : domainLower;
    const pathPart = hasPath ? domainLower.slice(slashIdx) : "";

    if (parsed) {
      const hostMatch =
        parsed.hostname === domainHost || parsed.hostname.endsWith("." + domainHost);
      if (!hostMatch) continue;
      if (hasPath) {
        const cleanPath = pathPart.replace(/^\//, "");
        if (parsed.pathname.includes(pathPart) || (cleanPath && titleLower.includes(cleanPath))) {
          return domain;
        }
      } else {
        return domain;
      }
    } else {
      // Fallback: title-based heuristic when PS script fails
      let baseWord = domainHost;
      for (const tld of [".com", ".org", ".net", ".io", ".co"]) {
        if (baseWord.endsWith(tld)) { baseWord = baseWord.slice(0, -tld.length); break; }
      }
      if (!baseWord || !titleLower.includes(baseWord)) continue;
      if (hasPath) {
        const cleanPath = pathPart.replace(/^\//, "");
        if (cleanPath && titleLower.includes(cleanPath)) return domain;
      } else {
        return domain;
      }
    }
  }

  return null;
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

export function startAgent() {
  if (state.running) return;
  state.running = true;
  console.log("[agent] FocusLock Windows Agent starting…");
  setupFirebaseListeners();
  monitorLoop();
}

export function stopAgent() {
  state.running = false;
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
