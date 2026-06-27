#!/usr/bin/env node

/**
 * End-to-End Test Scenario: Cross-Platform Focus Session Mode
 * 
 * This script validates:
 * 1. Backend focus session start/stop/status
 * 2. Intervention evaluation hard-blocks during focus mode
 * 3. Windows agent profile switching
 * 4. Android focus session control integration
 */

const API_BASE = "http://localhost:4050";
const userId = "test-user";
const deviceId = "test-device";
const windowsDeviceId = "windows-laptop-test";

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

async function test1_focusSessionStart() {
  log("TEST", "1. Starting focus session (25 minutes)");
  try {
    const result = await request("POST", "/focus/session/start", {
      userId,
      durationMinutes: 25,
      strict: true,
      reason: "e2e-test"
    });

    if (!result.active) {
      fail("Focus session start returned inactive");
    }

    if (!result.session?.endsAt) {
      fail("Focus session missing endsAt timestamp");
    }

    pass(`Focus session started until ${result.session.endsAt}`);
  } catch (error) {
    fail(`Focus session start failed: ${String(error)}`);
  }
}

async function test2_focusSessionStatus() {
  log("TEST", "2. Checking focus session status");
  try {
    const result = await request("GET", `/focus/session/${userId}`);

    if (!result.active) {
      fail("Focus session status should be active");
    }

    pass("Focus session is active");
  } catch (error) {
    fail(`Focus session status check failed: ${String(error)}`);
  }
}

async function test3_hardBlockDuringFocus() {
  log("TEST", "3. Verifying hard block during focus mode (Android)");
  try {
    const result = await request("POST", "/interventions/evaluate", {
      userId,
      deviceId,
      platform: "android",
      date: new Date().toISOString().slice(0, 10),
      requestExtension: false
    });

    if (result.allowNow !== false || result.reason !== "focus-session-active") {
      fail(`Expected hard block during focus, got: ${JSON.stringify(result)}`);
    }

    pass("Android hard-blocked during focus session");
  } catch (error) {
    fail(`Android evaluation failed: ${String(error)}`);
  }
}

async function test4_hardBlockWindows() {
  log("TEST", "4. Verifying hard block during focus mode (Windows)");
  try {
    const result = await request("POST", "/interventions/evaluate", {
      userId,
      deviceId: windowsDeviceId,
      platform: "windows",
      date: new Date().toISOString().slice(0, 10),
      requestExtension: false
    });

    if (result.allowNow !== false || result.reason !== "focus-session-active") {
      fail(`Expected hard block during focus, got: ${JSON.stringify(result)}`);
    }

    pass("Windows hard-blocked during focus session");
  } catch (error) {
    fail(`Windows evaluation failed: ${String(error)}`);
  }
}

async function test5_extensionDeniedDuringFocus() {
  log("TEST", "5. Verifying extension request denied during focus");
  try {
    const result = await request("POST", "/interventions/evaluate", {
      userId,
      deviceId,
      platform: "android",
      date: new Date().toISOString().slice(0, 10),
      requestExtension: true
    });

    if (result.allowNow !== false || result.reason !== "focus-session-active") {
      fail(`Expected focus-session-active rejection, got: ${JSON.stringify(result)}`);
    }

    pass("Extension correctly denied during focus session");
  } catch (error) {
    fail(`Extension denial test failed: ${String(error)}`);
  }
}

async function test6_focusSessionStop() {
  log("TEST", "6. Stopping focus session");
  try {
    const result = await request("POST", "/focus/session/stop", { userId });

    if (result.active !== false) {
      fail("Focus session stop should return inactive");
    }

    pass("Focus session stopped");
  } catch (error) {
    fail(`Focus session stop failed: ${String(error)}`);
  }
}

async function test7_normalModeAfterStop() {
  log("TEST", "7. Verifying normal mode after focus stop (no forced block)");
  try {
    const result = await request("POST", "/interventions/evaluate", {
      userId,
      deviceId,
      platform: "android",
      date: new Date().toISOString().slice(0, 10),
      requestExtension: false
    });

    if (result.reason === "focus-session-active") {
      fail("Should not be in focus mode after stop");
    }

    pass(`Returned to normal mode: ${result.reason}`);
  } catch (error) {
    fail(`Normal mode check failed: ${String(error)}`);
  }
}

async function test8_statsEndpoint() {
  log("TEST", "8. Checking stats/insights endpoint");
  try {
    const result = await request("GET", `/stats/${userId}?date=${new Date().toISOString().slice(0, 10)}`);

    if (typeof result.totalMinutes !== "number" || typeof result.sessionCount !== "number") {
      fail(`Stats response missing expected fields: ${JSON.stringify(result)}`);
    }

    pass(`Stats retrieved: ${result.totalMinutes} minutes across ${result.sessionCount} sessions`);
  } catch (error) {
    fail(`Stats endpoint failed: ${String(error)}`);
  }
}

async function main() {
  log("INFO", "Starting end-to-end test scenario");
  log("INFO", `Backend: ${API_BASE}`);
  log("INFO", `User: ${userId}`);

  try {
    await test1_focusSessionStart();
    await test2_focusSessionStatus();
    await test3_hardBlockDuringFocus();
    await test4_hardBlockWindows();
    await test5_extensionDeniedDuringFocus();
    await test6_focusSessionStop();
    await test7_normalModeAfterStop();
    await test8_statsEndpoint();

    log("INFO", "");
    log("INFO", "=== ALL TESTS PASSED ===");
    process.exit(0);
  } catch (error) {
    fail(`Unexpected error: ${String(error)}`);
  }
}

main();
