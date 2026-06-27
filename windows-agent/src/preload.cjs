const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getSystemStatus: () => ipcRenderer.invoke('get-system-status'),
  getLocalIp: () => ipcRenderer.invoke('get-local-ip'),
  runFirewallFix: () => ipcRenderer.invoke('run-firewall-fix'),
  getPolicy: () => ipcRenderer.invoke('get-policy'),
  updatePolicy: (policy) => ipcRenderer.invoke('update-policy', policy),
  getTodos: () => ipcRenderer.invoke('get-todos'),
  addTodo: (text) => ipcRenderer.invoke('add-todo', text),
  toggleTodo: (id, completed) => ipcRenderer.invoke('toggle-todo', id, completed),
  deleteTodo: (id) => ipcRenderer.invoke('delete-todo', id),
  startFocusSession: (minutes) => ipcRenderer.invoke('start-focus-session', minutes),
  stopFocusSession: () => ipcRenderer.invoke('stop-focus-session'),
  getFocusSession: () => ipcRenderer.invoke('get-focus-session'),
  getEvents: () => ipcRenderer.invoke('get-events'),
  getStreak: () => ipcRenderer.invoke('get-streak'),
  getUsageWeek: () => ipcRenderer.invoke('get-usage-week'),
  windowMinimize: () => ipcRenderer.send('window-minimize'),
  windowClose: () => ipcRenderer.send('window-close')
});
