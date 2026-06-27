// `electron` is a CommonJS module. Under an ESM entry point (this project is
// "type": "module"), Node can't statically detect its named exports, so
// `import { app, BrowserWindow } from 'electron'` throws "does not provide an
// export named …". Default-import the module object and destructure instead.
import electron from 'electron';
const { app, BrowserWindow, ipcMain, Tray, Menu } = electron;
import path from 'path';
import { fileURLToPath } from 'url';
import { readFileSync, writeFileSync } from 'fs';
import { startAgent, stopAgent, getAgentState } from './agent.js';
import { SETTINGS_PATH, sanitizeSyncId, USER_ID } from './firebase.js';
import * as api from './api.js';
// electron-updater is CommonJS; default-import and destructure under our ESM entry.
import electronUpdater from 'electron-updater';
const { autoUpdater } = electronUpdater;

const __dirname = path.dirname(fileURLToPath(import.meta.url));
let mainWindow = null;
let tray = null;

function createMainWindow() {
  if (mainWindow) {
    mainWindow.show();
    return;
  }

  mainWindow = new BrowserWindow({
    width: 900,
    height: 700,
    minWidth: 800,
    minHeight: 600,
    show: false,
    frame: false, // Frame-less for modern sleek look
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      sandbox: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'index.html'));

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  mainWindow.on('close', (event) => {
    // Intercept close to minimize to tray instead
    if (!app.isQuitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function createTray() {
  // Use a default system dot or icon
  tray = new Tray(path.join(__dirname, '..', 'config', 'icon.png')); // We will copy an icon or use fallback
  
  const contextMenu = Menu.buildFromTemplate([
    { label: 'Open Dashboard', click: () => createMainWindow() },
    { type: 'separator' },
    { label: 'Quit', click: () => {
      app.isQuitting = true;
      app.quit();
    }}
  ]);

  tray.setToolTip('FocusLock Desktop Agent');
  tray.setContextMenu(contextMenu);

  tray.on('double-click', () => {
    createMainWindow();
  });
}

// Single-instance lock: this app auto-starts on login and lives in the tray,
// so a manual launch (or a second startup trigger) must not spin up a second
// enforcement loop — two agents would double-issue taskkill/Ctrl+W. The second
// instance hands focus to the existing window and exits.
const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    } else {
      createMainWindow();
    }
  });
}

// Lifecycle Events
app.whenReady().then(() => {
  if (!gotSingleInstanceLock) return; // second instance: hand off and exit, don't start the agent
  startAgent();
  createTray();
  createMainWindow();

  // Self-update from GitHub Releases. The update feed (app-update.yml) only
  // exists in a packaged build, so skip this when running from source.
  if (app.isPackaged) {
    autoUpdater.checkForUpdatesAndNotify().catch((error) => {
      console.error(`[updater] check failed: ${String(error)}`);
    });
  }

  // Update tray tooltip with live usage every 30 s
  setInterval(() => {
    if (!tray) return;
    const s = getAgentState();
    const used = Math.round(s.usedMinutes);
    const limit = s.dailyLimit;
    tray.setToolTip(
      limit > 0
        ? `FocusLock — ${used}/${limit} min used today`
        : `FocusLock — ${used} min used today`
    );
  }, 30_000);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
  });
});

app.on('window-all-closed', () => {
  // Overridden to keep application running in system tray
  if (process.platform !== 'darwin') {
    // Keep running
  }
});

app.on('will-quit', () => {
  stopAgent();
});

// IPC Handler Registrations
ipcMain.handle('get-sync-id', () => {
  try {
    const cfg = JSON.parse(readFileSync(SETTINGS_PATH, 'utf8'));
    if (cfg && typeof cfg.syncId === 'string' && cfg.syncId.trim()) {
      return sanitizeSyncId(cfg.syncId);
    }
  } catch {
    // no settings file yet
  }
  return USER_ID; // reflects env override or default
});

ipcMain.handle('set-sync-id', (event, raw) => {
  const clean = sanitizeSyncId(raw);
  try {
    writeFileSync(SETTINGS_PATH, JSON.stringify({ syncId: clean }, null, 2), 'utf8');
  } catch (error) {
    return { error: error.message };
  }
  // Relaunch so firebase.js re-resolves USER_ID and the agent re-subscribes to
  // the new user's documents. Delay slightly so this IPC response is delivered.
  setTimeout(() => {
    app.isQuitting = true;
    app.relaunch();
    app.exit(0);
  }, 600);
  return { syncId: clean };
});

ipcMain.handle('get-policy', async () => {
  try {
    return await api.fetchPolicy();
  } catch (error) {
    return { error: error.message };
  }
});

ipcMain.handle('update-policy', async (event, policy) => {
  try {
    return await api.updatePolicy(policy);
  } catch (error) {
    return { error: error.message };
  }
});

ipcMain.handle('get-todos', async () => {
  try {
    return await api.fetchTodos();
  } catch (error) {
    return [];
  }
});

ipcMain.handle('add-todo', async (event, text) => {
  try {
    return await api.addTodo(text);
  } catch (error) {
    return { error: error.message };
  }
});

ipcMain.handle('toggle-todo', async (event, id, completed) => {
  try {
    return await api.toggleTodo(id, completed);
  } catch (error) {
    return { error: error.message };
  }
});

ipcMain.handle('delete-todo', async (event, id) => {
  try {
    return await api.deleteTodo(id);
  } catch (error) {
    return { error: error.message };
  }
});

ipcMain.handle('start-focus-session', async (event, minutes) => {
  try {
    return await api.startFocusSession(minutes);
  } catch (error) {
    return { error: error.message };
  }
});

ipcMain.handle('stop-focus-session', async () => {
  try {
    return await api.stopFocusSession();
  } catch (error) {
    return { error: error.message };
  }
});

ipcMain.handle('get-focus-session', async () => {
  try {
    return await api.getFocusSession();
  } catch (error) {
    return { active: false, endsAt: "" };
  }
});

ipcMain.handle('get-events', async () => {
  try {
    return await api.getEvents();
  } catch (error) {
    return [];
  }
});

ipcMain.handle('get-streak', async () => {
  try { return await api.getStreak(); } catch { return { currentStreak: 0, longestStreak: 0, lastSuccessDate: "" }; }
});

ipcMain.handle('get-usage-week', async () => {
  try { return await api.getUsageWeek(); } catch { return []; }
});


// Window Controls (minimize, maximize, close window)
ipcMain.on('window-minimize', () => {
  if (mainWindow) mainWindow.minimize();
});

ipcMain.on('window-close', () => {
  if (mainWindow) mainWindow.hide(); // Minimize to tray instead of exit
});
