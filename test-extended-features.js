#!/usr/bin/env node

/**
 * Extended Feature Test: Device Registration, Policy Presets, and Stats
 * 
 * This validates:
 * 1. Device registration and listing
 * 2. Policy preset application (light, social-only, deep-work, break-enforcer)
 * 3. Usage stats and insights
 */

const API_BASE = "http://localhost:4050";
const userId = "feature-test-user";

function log(type, message) {
  const timestamp = new Date().toISOString();
  console.log(`[${timestamp}] [${type}] ${message}`);
}

function pass(message) {
  log("PASS", message);
}

function fail(message) {
  log("FAIL", message);
  process.exit(1);
}

async function request(method, path, body) {
  const url = API_BASE + path;
  const options = {
    method,
    headers: { "Content-Type": "application/json" }
  };

  if (body) {
    options.body = JSON.stringify(body);
  }

  const response = await fetch(url, options);
  const data = await response.json();

  if (!response.ok) {
    throw new Error(`${response.status}: ${JSON.stringify(data)}`);
  }

  return data;
}

async function test1_registerDevices() {
  log("TEST", "1. Registering multiple devices");
  try {
    const android = await request("POST", `/devices/${userId}/register`, {
      deviceId: "pixel-7-pro",
      platform: "android",
      name: "My Android Phone"
    });

    const windows = await request("POST", `/devices/${userId}/register`, {
      deviceId: "desktop-win11",
      platform: "windows",
      name: "Work Laptop"
    });

    if (!android.deviceId || !windows.deviceId) {
      fail("Device registration missing deviceId");
    }

    pass("Registered Android and Windows devices");
  } catch (error) {
    fail(`Device registration failed: ${String(error)}`);
  }
}

async function test2_listDevices() {
  log("TEST", "2. Listing all registered devices");
  try {
    const result = await request("GET", `/devices/${userId}`);

    if (!Array.isArray(result.devices) || result.devices.length < 2) {
      fail("Device list should have 2+ devices");
    }

    const android = result.devices.find(d => d.platform === "android");
    const windows = result.devices.find(d => d.platform === "windows");

    if (!android || !windows) {
      fail("Missing expected devices in list");
    }

    pass(`Listed ${result.devices.length} devices (Android + Windows)`);
  } catch (error) {
    fail(`Device listing failed: ${String(error)}`);
  }
}

async function test3_applyPresets() {
  log("TEST", "3. Applying policy presets");
  try {
    const socialOnly = await request("POST", `/policy/${userId}/pixel-7-pro/preset/social-only?platform=android`, {});
    if (socialOnly.dailyLimitMinutes !== 15) {
      fail("Social-only preset should have 15-min limit");
    }

    const deepWork = await request("POST", `/policy/${userId}/desktop-win11/preset/deep-work?platform=windows`, {});
    if (deepWork.dailyLimitMinutes !== 0 || deepWork.targetApps.length === 0) {
      fail("Deep-work preset should have 0-min limit and multiple targets");
    }

    pass("Applied social-only and deep-work presets");
  } catch (error) {
    fail(`Preset application failed: ${String(error)}`);
  }
}

async function test4_presetVariations() {
  log("TEST", "4. Testing all preset variations");
  try {
    const presets = ["light", "social-only", "deep-work", "break-enforcer"];
    for (const preset of presets) {
      const policy = await request("POST", `/policy/${userId}/test-device-${preset}/preset/${preset}?platform=windows`, {});
      if (!policy.targetDomains || policy.targetDomains.length === 0) {
        fail(`Preset ${preset} should have target domains`);
      }
    }

    pass("All 4 presets validated");
  } catch (error) {
    fail(`Preset variation test failed: ${String(error)}`);
  }
}

async function test5_statsBefore() {
  log("TEST", "5. Checking stats (should be empty initially)");
  try {
    const today = new Date().toISOString().slice(0, 10);
    const stats = await request("GET", `/stats/${userId}?date=${today}`);

    if (typeof stats.totalMinutes !== "number" || typeof stats.sessionCount !== "number") {
      fail("Stats should return totalMinutes and sessionCount");
    }

    pass(`Initial stats: ${stats.totalMinutes} minutes, ${stats.sessionCount} sessions`);
  } catch (error) {
    fail(`Stats retrieval failed: ${String(error)}`);
  }
}

async function test6_postEvent() {
  log("TEST", "6. Posting usage event");
  try {
    const now = new Date().toISOString();
    const later = new Date(Date.now() + 600000).toISOString();

    await request("POST", "/events", {
      userId,
      deviceId: "pixel-7-pro",
      platform: "android",
      target: "youtube.com",
      category: "domain",
      startedAt: now,
      endedAt: later,
      durationMinutes: 10,
      source: "android-service"
    });

    pass("Posted 10-minute YouTube usage event");
  } catch (error) {
    fail(`Event posting failed: ${String(error)}`);
  }
}

async function test7_statsAfter() {
  log("TEST", "7. Checking stats after event");
  try {
    const today = new Date().toISOString().slice(0, 10);
    const stats = await request("GET", `/stats/${userId}?date=${today}`);

    if (stats.totalMinutes === 0) {
      fail("Stats should reflect posted event");
    }

    pass(`Updated stats: ${stats.totalMinutes} minutes across ${stats.sessionCount} sessions`);
  } catch (error) {
    fail(`Stats after event failed: ${String(error)}`);
  }
}

async function main() {
  log("INFO", "Starting extended feature test");
  log("INFO", `Backend: ${API_BASE}`);
  log("INFO", `User: ${userId}`);

  try {
    await test1_registerDevices();
    await test2_listDevices();
    await test3_applyPresets();
    await test4_presetVariations();
    await test5_statsBefore();
    await test6_postEvent();
    await test7_statsAfter();

    log("INFO", "");
    log("INFO", "=== ALL EXTENDED TESTS PASSED ===");
    process.exit(0);
  } catch (error) {
    fail(`Unexpected error: ${String(error)}`);
  }
}

main();
