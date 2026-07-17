// Firebase Auth JS SDK defaults to in-memory persistence in a Node
// environment (no localStorage/IndexedDB), so signInAnonymously() would mint
// a brand new uid every process launch. That breaks Firestore rules that
// scope access to a fixed set of member uids per sync code (see
// firestore.rules) — the desktop agent would accumulate a new "member" every
// restart. This implements the minimal custom Persistence the Auth SDK
// expects, backed by a JSON file under %APPDATA%/FocusLock, so the same
// anonymous uid survives restarts, same as FirebaseAuth's native behavior on
// Android.
//
// The SDK's own exported persistences (e.g. inMemoryPersistence) are, at
// runtime, the *class itself* rather than an instance — internalauth's
// `_getInstance()` does `cls instanceof Function` and constructs/caches a
// singleton from it. A plain object literal fails that check with
// "INTERNAL ASSERTION FAILED: Expected a class definition". So this returns
// a class (capturing filePath via closure), not an instance.
import { readFileSync, writeFileSync } from "node:fs";

function readStore(filePath) {
  try {
    return JSON.parse(readFileSync(filePath, "utf8"));
  } catch {
    return {};
  }
}

function writeStore(filePath, store) {
  try {
    writeFileSync(filePath, JSON.stringify(store), "utf8");
  } catch (error) {
    console.error(`[auth-persistence] failed to write ${filePath}: ${String(error)}`);
  }
}

export function createFileAuthPersistence(filePath) {
  return class FileAuthPersistence {
    constructor() {
      this.type = "LOCAL";
    }
    async _isAvailable() {
      return true;
    }
    async _set(key, value) {
      const store = readStore(filePath);
      store[key] = value;
      writeStore(filePath, store);
    }
    async _get(key) {
      const store = readStore(filePath);
      return key in store ? store[key] : null;
    }
    async _remove(key) {
      const store = readStore(filePath);
      delete store[key];
      writeStore(filePath, store);
    }
    // Single-process CLI/agent — no other tab/window to notify of storage changes.
    _addListener() {}
    _removeListener() {}
  };
}
