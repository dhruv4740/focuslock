// Pure, side-effect-free logic extracted from agent.js so it can be unit
// tested with node:test without spinning up Electron, Firestore, or PowerShell.

export function computeEffectiveLimit(policy, usage) {
  const dailyLimit = policy?.dailyLimitMinutes ?? 0;
  const extensionMinutes = policy?.extensionMinutes ?? 15;
  const rawExtensions = usage?.extensionsUsedToday ?? 0;
  const extensionCap = policy?.dailyExtensionLimit;
  const cappedExtensions = Number.isFinite(extensionCap)
    ? Math.min(rawExtensions, extensionCap)
    : rawExtensions;
  return dailyLimit + cappedExtensions * extensionMinutes;
}

// effectiveLimit of 0 means no limit configured — always under limit.
export function isUnderLimit(policy, usage) {
  const effectiveLimit = computeEffectiveLimit(policy, usage);
  return effectiveLimit === 0 || (usage?.usedMinutes ?? 0) < effectiveLimit;
}

export function parseUrlHelper(urlStr) {
  let tmp = urlStr.trim();
  if (!/^https?:\/\//i.test(tmp)) tmp = "https://" + tmp;
  try {
    const parsed = new URL(tmp);
    return { hostname: parsed.hostname.toLowerCase(), pathname: parsed.pathname.toLowerCase() };
  } catch {
    return null;
  }
}

// Pure matching loop used by getMatchingTargetDomain. `parsed` is the result
// of parseUrlHelper (or null if URL extraction failed — title-based fallback).
export function matchTargetDomain(parsed, titleLower, targetDomains) {
  for (const domain of targetDomains ?? []) {
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

export function isInScheduledBlockWindow(blockWindows, now = new Date()) {
  if (!Array.isArray(blockWindows) || blockWindows.length === 0) return false;
  const day = now.getDay(); // 0=Sun
  const currentMin = now.getHours() * 60 + now.getMinutes();
  return blockWindows.some((w) => {
    if (Array.isArray(w.days) && !w.days.includes(day)) return false;
    const [sh, sm] = (w.start || "00:00").split(":").map(Number);
    const [eh, em] = (w.end || "23:59").split(":").map(Number);
    return currentMin >= sh * 60 + sm && currentMin < eh * 60 + em;
  });
}

// Local calendar date so daily limits reset at local midnight, not UTC midnight.
export function getTodayString(date = new Date()) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

export function addDays(dateStr, delta) {
  const [y, m, d] = dateStr.split("-").map(Number);
  const dt = new Date(y, m - 1, d);
  dt.setDate(dt.getDate() + delta);
  return getTodayString(dt);
}
