import { app, BrowserWindow, ipcMain, Tray, Menu } from 'electron';
import path from 'path';
import os from 'os';
import { fileURLToPath } from 'url';
import { readFileSync, writeFileSync } from 'fs';
import { spawn, exec } from 'child_process';
import { startAgent, stopAgent, getAgentState } from './agent.js';
import { SETTINGS_PATH, sanitizeSyncId, USER_ID } from './firebase.js';
import * as api from './api.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
let mainWindow = null;
let tray = null;



// Get local IPv4 address of active Wi-Fi / Ethernet adapter
function getLocalIpAddress() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    // Skip virtual or loopback interfaces
    const lowerName = name.toLowerCase();
    if (lowerName.includes("virtual") || lowerName.includes("vbox") || lowerName.includes("vmware") || lowerName.includes("vgate") || lowerName.includes("loopback")) {
      continue;
    }
    for (const net of interfaces[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        // Prefer local subnet IPs (192.168.x.x or 10.x.x.x)
        if (net.address.startsWith("192.168.") || net.address.startsWith("10.")) {
          return net.address;
        }
      }
    }
  }
  // Fallback to first external IPv4
  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        return net.address;
      }
    }
  }
  return "127.0.0.1";
}

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
    {
      label: 'Fix Firewall Rule',
      click: () => runFirewallFixInternal()
    },
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

function runFirewallFixInternal() {
  return new Promise((resolve, reject) => {
    const scriptPath = path.resolve(__dirname, "..", "scripts", "add-firewall-rule.ps1");
    // Run elevated PowerShell
    const command = `powershell -Command "Start-Process powershell -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \\"${scriptPath}\\"' -Verb RunAs"`;
    exec(command, (err) => {
      if (err) {
        console.error("Firewall rule elevation failed:", err);
        reject(err);
      } else {
        console.log("Elevated firewall rule executed.");
        resolve(true);
      }
    });
  });
}

// Lifecycle Events
app.whenReady().then(() => {
  startAgent();
  createTray();
  createMainWindow();

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
ipcMain.handle('get-system-status', () => {
  return {
    active: true,
    backendUrl: `http://${getLocalIpAddress()}:4050`
  };
});

ipcMain.handle('get-local-ip', () => {
  return getLocalIpAddress();
});

ipcMain.handle('run-firewall-fix', async () => {
  try {
    await runFirewallFixInternal();
    return { success: true };
  } catch (error) {
    return { success: false, error: error.message };
  }
});

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
