// Standalone diagnostic: reports exactly what the agent perceives for the
// currently-focused window and whether it would be blocked right now.
//
//   node scripts/diagnose-block.js
//
// Focus the window you are debugging (e.g. the Edge profile with Instagram)
// during the 6-second countdown. Nothing here mutates state — it only reads.

import activeWin from "active-win";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { basename } from "node:path";
import { promisify } from "node:util";
import { fetchPolicy, getFocusSession } from "../src/api.js";
import { USER_ID } from "../src/firebase.js";

const execFileAsync = promisify(execFile);
const BROWSER_PROCESSES = new Set(["chrome", "msedge", "firefox", "brave", "opera"]);
const scriptPath = fileURLToPath(new URL("./get-browser-url.ps1", import.meta.url));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getBrowserUrl(windowId) {
  if (!windowId) return "";
  try {
    const { stdout } = await execFileAsync("powershell", [
      "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptPath, "-HwndVal", String(windowId)
    ]);
    return stdout.trim();
  } catch (e) {
    return `<powershell error: ${String(e)}>`;
  }
}

function parseUrl(urlStr) {
  let tmp = urlStr.trim();
  if (!/^https?:\/\//i.test(tmp)) tmp = "https://" + tmp;
  try {
    const u = new URL(tmp);
    return { hostname: u.hostname.toLowerCase(), pathname: u.pathname.toLowerCase() };
  } catch {
    return null;
  }
}

function matchDomain(targetDomains, title, url) {
  const titleLower = (title ?? "").toLowerCase();
  const parsed = url && !url.startsWith("<") ? parseUrl(url) : null;
  for (const domain of targetDomains) {
    const domainLower = domain.toLowerCase().replace(/^https?:\/\//, "");
    const slashIdx = domainLower.indexOf("/");
    const hasPath = slashIdx !== -1;
    const domainHost = hasPath ? domainLower.slice(0, slashIdx) : domainLower;
    const pathPart = hasPath ? domainLower.slice(slashIdx) : "";
    if (parsed) {
      const hostMatch = parsed.hostname === domainHost || parsed.hostname.endsWith("." + domainHost);
      if (!hostMatch) continue;
      if (!hasPath) return { domain, via: "url-host" };
      const cleanPath = pathPart.replace(/^\//, "");
      if (parsed.pathname.includes(pathPart) || (cleanPath && titleLower.includes(cleanPath)))
        return { domain, via: "url-path" };
    } else {
      let baseWord = domainHost;
      for (const tld of [".com", ".org", ".net", ".io", ".co"])
        if (baseWord.endsWith(tld)) { baseWord = baseWord.slice(0, -tld.length); break; }
      if (!baseWord || !titleLower.includes(baseWord)) continue;
      if (!hasPath) return { domain, via: "title-fallback" };
      const cleanPath = pathPart.replace(/^\//, "");
      if (cleanPath && titleLower.includes(cleanPath)) return { domain, via: "title-fallback-path" };
    }
  }
  return null;
}

function inScheduledBlockWindow(blockWindows) {
  if (!Array.isArray(blockWindows) || blockWindows.length === 0) return false;
  const now = new Date();
  const day = now.getDay();
  const cur = now.getHours() * 60 + now.getMinutes();
  return blockWindows.some((w) => {
    if (Array.isArray(w.days) && !w.days.includes(day)) return false;
    const [sh, sm] = (w.start || "00:00").split(":").map(Number);
    const [eh, em] = (w.end || "23:59").split(":").map(Number);
    return cur >= sh * 60 + sm && cur < eh * 60 + em;
  });
}

(async () => {
  console.log(`\nSync code (USER_ID): ${USER_ID}`);
  console.log("Focus the window you want to test — reading in 6 seconds…");
  for (let i = 6; i > 0; i--) { process.stdout.write(`  ${i}…`); await sleep(1000); }
  console.log("\n");

  const active = await activeWin();
  const path = active?.owner?.path ?? "";
  const procName = (path ? basename(path) : active?.owner?.name ?? "").trim().toLowerCase();
  const procBase = procName.endsWith(".exe") ? procName.slice(0, -4) : procName;
  const isBrowser = BROWSER_PROCESSES.has(procBase);

  console.log("── Active window ──────────────────────────────");
  console.log(`  process     : ${procName}  (base: ${procBase})`);
  console.log(`  recognized browser? ${isBrowser ? "YES" : "NO — domain rules will be skipped"}`);
  console.log(`  title       : ${active?.title ?? "<none>"}`);
  console.log(`  window id   : ${active?.id ?? "<none>"}`);

  let url = "";
  if (isBrowser) {
    url = await getBrowserUrl(active?.id);
    console.log(`  extracted URL: ${url || "<empty — UI Automation found no address bar (app/PWA window?)>"}`);
  }

  const policy = await fetchPolicy();
  const targetApps = policy.targetApps ?? [];
  const targetDomains = policy.targetDomains ?? [];
  console.log("\n── Policy (from Firestore) ────────────────────");
  console.log(`  targetApps   : ${JSON.stringify(targetApps)}`);
  console.log(`  targetDomains: ${JSON.stringify(targetDomains)}`);
  console.log(`  dailyLimitMin: ${policy.dailyLimitMinutes ?? 0}   hardBlockAfterLimit: ${!!policy.hardBlockAfterLimit}`);
  console.log(`  blockWindows : ${JSON.stringify(policy.blockWindows ?? [])}`);

  const appMatch = targetApps.some((a) => {
    const b = a.toLowerCase().endsWith(".exe") ? a.toLowerCase().slice(0, -4) : a.toLowerCase();
    return b === procBase;
  });
  const domMatch = isBrowser ? matchDomain(targetDomains, active?.title, url) : null;

  const session = await getFocusSession();
  const sessionActive = !!(session.active && session.endsAt && Date.parse(session.endsAt) > Date.now());
  const blockWindowActive = inScheduledBlockWindow(policy.blockWindows);

  console.log("\n── Match & block decision ─────────────────────");
  console.log(`  matches a target app?    ${appMatch ? "YES (" + procBase + ")" : "no"}`);
  console.log(`  matches a target domain? ${domMatch ? `YES (${domMatch.domain} via ${domMatch.via})` : "no"}`);
  console.log(`  focus session active?    ${sessionActive}`);
  console.log(`  scheduled block window?  ${blockWindowActive}`);

  const isTarget = appMatch || domMatch !== null;
  let verdict;
  if (!isTarget) {
    verdict = isBrowser && !domMatch && !url
      ? "NOT a target — URL could not be read AND the title didn't contain the domain name. " +
        "If this is Instagram, the title should contain 'instagram'; check the title above."
      : "NOT a target — this window matches no app or domain rule, so it is never touched.";
  } else if (sessionActive || blockWindowActive) {
    verdict = "WOULD BLOCK NOW (a block condition is active).";
  } else {
    verdict = "Matches a rule, but NO block condition is active right now — domains/apps only close " +
      "during a focus session, a scheduled block window, or after the daily limit with hardBlockAfterLimit. " +
      "Start a focus session (npm run focus:start -- 30) and re-run to confirm enforcement.";
  }
  console.log(`\n  >> ${verdict}\n`);
  process.exit(0);
})().catch((e) => { console.error("diagnostic failed:", e); process.exit(1); });
