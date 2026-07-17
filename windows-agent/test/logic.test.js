import { test, describe } from "node:test";
import assert from "node:assert/strict";
import {
  computeEffectiveLimit,
  isUnderLimit,
  parseUrlHelper,
  matchTargetDomain,
  isInScheduledBlockWindow,
  getTodayString,
  addDays
} from "../src/logic.js";

describe("computeEffectiveLimit", () => {
  test("no extensions used", () => {
    assert.equal(computeEffectiveLimit({ dailyLimitMinutes: 60 }, {}), 60);
  });

  test("adds extension minutes per extension used", () => {
    const limit = computeEffectiveLimit(
      { dailyLimitMinutes: 60, extensionMinutes: 15 },
      { extensionsUsedToday: 2 }
    );
    assert.equal(limit, 90);
  });

  test("caps extensions at dailyExtensionLimit when set", () => {
    const limit = computeEffectiveLimit(
      { dailyLimitMinutes: 60, extensionMinutes: 15, dailyExtensionLimit: 1 },
      { extensionsUsedToday: 5 }
    );
    assert.equal(limit, 75);
  });

  test("uncapped when dailyExtensionLimit is not a finite number", () => {
    const limit = computeEffectiveLimit(
      { dailyLimitMinutes: 60, extensionMinutes: 15 },
      { extensionsUsedToday: 5 }
    );
    assert.equal(limit, 135);
  });
});

describe("isUnderLimit", () => {
  test("effectiveLimit of 0 means unlimited", () => {
    assert.equal(isUnderLimit({ dailyLimitMinutes: 0 }, { usedMinutes: 99999 }), true);
  });

  test("under the limit", () => {
    assert.equal(isUnderLimit({ dailyLimitMinutes: 60 }, { usedMinutes: 30 }), true);
  });

  test("at or over the limit", () => {
    assert.equal(isUnderLimit({ dailyLimitMinutes: 60 }, { usedMinutes: 60 }), false);
    assert.equal(isUnderLimit({ dailyLimitMinutes: 60 }, { usedMinutes: 90 }), false);
  });
});

describe("parseUrlHelper", () => {
  test("adds https scheme when missing", () => {
    const parsed = parseUrlHelper("youtube.com/watch?v=1");
    assert.equal(parsed.hostname, "youtube.com");
    assert.equal(parsed.pathname, "/watch");
  });

  test("lowercases hostname and pathname", () => {
    const parsed = parseUrlHelper("https://YouTube.com/Shorts/ABC");
    assert.equal(parsed.hostname, "youtube.com");
    assert.equal(parsed.pathname, "/shorts/abc");
  });

  test("returns null for unparseable input", () => {
    assert.equal(parseUrlHelper("::::not a url"), null);
  });
});

describe("matchTargetDomain", () => {
  test("exact host match with no path rule", () => {
    const parsed = parseUrlHelper("https://instagram.com/explore");
    assert.equal(matchTargetDomain(parsed, "", ["instagram.com"]), "instagram.com");
  });

  test("subdomain matches base domain", () => {
    const parsed = parseUrlHelper("https://www.youtube.com/watch?v=1");
    assert.equal(matchTargetDomain(parsed, "", ["youtube.com"]), "youtube.com");
  });

  test("path-qualified domain requires matching path", () => {
    const parsed = parseUrlHelper("https://youtube.com/shorts/abc");
    assert.equal(matchTargetDomain(parsed, "", ["youtube.com/shorts"]), "youtube.com/shorts");
  });

  test("path-qualified domain does not match a different path", () => {
    const parsed = parseUrlHelper("https://youtube.com/watch?v=1");
    assert.equal(matchTargetDomain(parsed, "watching a video", ["youtube.com/shorts"]), null);
  });

  test("host mismatch does not match", () => {
    const parsed = parseUrlHelper("https://example.com/");
    assert.equal(matchTargetDomain(parsed, "", ["instagram.com"]), null);
  });

  test("title-based fallback when URL extraction failed", () => {
    assert.equal(
      matchTargetDomain(null, "scrolling instagram reels", ["instagram.com"]),
      "instagram.com"
    );
  });

  test("title-based fallback respects path qualifier", () => {
    assert.equal(
      matchTargetDomain(null, "youtube - shorts - funny cats", ["youtube.com/shorts"]),
      "youtube.com/shorts"
    );
    assert.equal(
      matchTargetDomain(null, "youtube - regular video", ["youtube.com/shorts"]),
      null
    );
  });
});

describe("isInScheduledBlockWindow", () => {
  test("no windows configured", () => {
    assert.equal(isInScheduledBlockWindow([]), false);
    assert.equal(isInScheduledBlockWindow(undefined), false);
  });

  test("inside a window on an allowed day", () => {
    // Wednesday 2026-01-07, 10:00
    const now = new Date(2026, 0, 7, 10, 0);
    const windows = [{ start: "09:00", end: "17:00", days: [1, 2, 3, 4, 5] }];
    assert.equal(isInScheduledBlockWindow(windows, now), true);
  });

  test("outside the time range", () => {
    const now = new Date(2026, 0, 7, 20, 0);
    const windows = [{ start: "09:00", end: "17:00", days: [1, 2, 3, 4, 5] }];
    assert.equal(isInScheduledBlockWindow(windows, now), false);
  });

  test("outside the allowed days", () => {
    // Saturday 2026-01-10
    const now = new Date(2026, 0, 10, 10, 0);
    const windows = [{ start: "09:00", end: "17:00", days: [1, 2, 3, 4, 5] }];
    assert.equal(isInScheduledBlockWindow(windows, now), false);
  });
});

describe("getTodayString", () => {
  test("formats as YYYY-MM-DD with zero-padding", () => {
    assert.equal(getTodayString(new Date(2026, 0, 7)), "2026-01-07");
  });
});

describe("addDays", () => {
  test("simple increment", () => {
    assert.equal(addDays("2026-07-01", 1), "2026-07-02");
  });

  test("negative delta", () => {
    assert.equal(addDays("2026-07-01", -1), "2026-06-30");
  });

  test("month rollover", () => {
    assert.equal(addDays("2026-01-31", 1), "2026-02-01");
  });

  test("year rollover", () => {
    assert.equal(addDays("2026-12-31", 1), "2027-01-01");
  });
});
