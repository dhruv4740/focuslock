// FocusLock Renderer Logic

let currentPolicy = null;
let countdownInterval = null;
let selectedFocusDuration = 25; // minutes, default
let lastWeeklyRefreshAt = 0;    // throttle: weekly chart reads 7 docs, don't do it every 4 s

const elements = {
  btnMinimize: document.getElementById('btn-minimize'),
  btnClose: document.getElementById('btn-close'),

  navBtns: document.querySelectorAll('.nav-btn'),
  tabPanels: document.querySelectorAll('.tab-panel'),

  focusCountdown: document.getElementById('focus-countdown'),
  btnStartFocus: document.getElementById('btn-start-focus'),
  btnStopFocus: document.getElementById('btn-stop-focus'),
  focusDurationRow: document.getElementById('focus-duration-row'),
  inputCustomDuration: document.getElementById('input-custom-duration'),

  streakCurrent: document.getElementById('streak-current'),
  streakLongest: document.getElementById('streak-longest'),

  inputNewRule: document.getElementById('input-new-rule'),
  selectRuleType: document.getElementById('select-rule-type'),
  btnAddRule: document.getElementById('btn-add-rule'),
  listBlockedDomains: document.getElementById('list-blocked-domains'),
  listBlockedApps: document.getElementById('list-blocked-apps'),

  inputNewTodo: document.getElementById('input-new-todo'),
  btnAddTodo: document.getElementById('btn-add-todo'),
  todoList: document.getElementById('todo-list'),

  // Settings
  inputSyncId: document.getElementById('input-sync-id'),
  btnSaveSyncId: document.getElementById('btn-save-sync-id'),
  syncSavedMsg: document.getElementById('sync-saved-msg'),
  inputDailyLimit: document.getElementById('input-daily-limit'),
  inputExtensionMins: document.getElementById('input-extension-mins'),
  inputExtensionLimit: document.getElementById('input-extension-limit'),
  chkHardBlock: document.getElementById('chk-hard-block'),
  btnSaveLimits: document.getElementById('btn-save-limits'),
  limitsSavedMsg: document.getElementById('limits-saved-msg'),
  inputWindowStart: document.getElementById('input-window-start'),
  inputWindowEnd: document.getElementById('input-window-end'),
  btnAddWindow: document.getElementById('btn-add-window'),
  listBlockWindows: document.getElementById('list-block-windows'),
};

document.addEventListener('DOMContentLoaded', () => {
  setupTitlebar();
  setupTabs();
  setupFocusDurationPicker();
  setupFocusSession();
  setupRulesEditor();
  setupTodoChecklist();
  setupSyncCode();
  setupSettings();

  refreshData();
  setInterval(refreshData, 4000);
});

// ── Titlebar ──────────────────────────────────────────────────────────────────

function setupTitlebar() {
  elements.btnMinimize?.addEventListener('click', () => window.electronAPI.windowMinimize());
  elements.btnClose?.addEventListener('click', () => window.electronAPI.windowClose());
}

// ── Tabs ──────────────────────────────────────────────────────────────────────

function setupTabs() {
  elements.navBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const target = btn.getAttribute('data-tab');
      elements.navBtns.forEach(b => b.classList.remove('active'));
      elements.tabPanels.forEach(p => p.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById(target)?.classList.add('active');
    });
  });
}

// ── Focus duration picker ─────────────────────────────────────────────────────

function setupFocusDurationPicker() {
  const chips = elements.focusDurationRow?.querySelectorAll('.chip');
  chips?.forEach(chip => {
    chip.addEventListener('click', () => {
      chips.forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      if (elements.inputCustomDuration) elements.inputCustomDuration.value = '';
      selectedFocusDuration = parseInt(chip.dataset.mins, 10);
    });
  });

  elements.inputCustomDuration?.addEventListener('input', () => {
    const val = parseInt(elements.inputCustomDuration.value, 10);
    if (val > 0) {
      chips?.forEach(c => c.classList.remove('active'));
      selectedFocusDuration = val;
    }
  });
}

// ── Focus Session ─────────────────────────────────────────────────────────────

function setupFocusSession() {
  elements.btnStartFocus?.addEventListener('click', async () => {
    const minutes = selectedFocusDuration || 25;
    const res = await window.electronAPI.startFocusSession(minutes);
    updateFocusUi(res);
  });

  elements.btnStopFocus?.addEventListener('click', async () => {
    await window.electronAPI.stopFocusSession();
    updateFocusUi({ active: false });
  });
}

function updateFocusUi(session) {
  if (countdownInterval) { clearInterval(countdownInterval); countdownInterval = null; }

  if (session?.active && session.endsAt) {
    elements.btnStartFocus?.classList.add('hidden');
    elements.btnStopFocus?.classList.remove('hidden');
    elements.focusDurationRow?.classList.add('hidden');

    const endTime = new Date(session.endsAt).getTime();
    const tick = () => {
      const diff = endTime - Date.now();
      if (diff <= 0) {
        if (elements.focusCountdown) elements.focusCountdown.textContent = 'Focus Completed!';
        elements.btnStartFocus?.classList.remove('hidden');
        elements.btnStopFocus?.classList.add('hidden');
        elements.focusDurationRow?.classList.remove('hidden');
        clearInterval(countdownInterval);
        countdownInterval = null;
        return;
      }
      const m = Math.floor(diff / 60000);
      const s = String(Math.floor((diff % 60000) / 1000)).padStart(2, '0');
      if (elements.focusCountdown) elements.focusCountdown.textContent = `${m}:${s}`;
    };
    tick();
    countdownInterval = setInterval(tick, 1000);
  } else {
    if (elements.focusCountdown) elements.focusCountdown.textContent = 'Focus Inactive';
    elements.btnStartFocus?.classList.remove('hidden');
    elements.btnStopFocus?.classList.add('hidden');
    elements.focusDurationRow?.classList.remove('hidden');
  }
}

// ── Block Rules Editor ────────────────────────────────────────────────────────

function setupRulesEditor() {
  elements.btnAddRule?.addEventListener('click', async () => {
    const val = elements.inputNewRule?.value.trim();
    if (!val) return;
    const type = elements.selectRuleType?.value;

    if (!currentPolicy) currentPolicy = await window.electronAPI.getPolicy();
    if (!currentPolicy) return;

    if (type === 'domain') {
      if (!currentPolicy.targetDomains.includes(val)) currentPolicy.targetDomains.push(val);
    } else {
      if (!currentPolicy.targetApps.includes(val)) currentPolicy.targetApps.push(val);
    }

    await window.electronAPI.updatePolicy(currentPolicy);
    if (elements.inputNewRule) elements.inputNewRule.value = '';
    renderRulesList();
  });
}

function renderRulesList() {
  if (!currentPolicy) return;

  if (elements.listBlockedDomains) {
    elements.listBlockedDomains.innerHTML = '';
    currentPolicy.targetDomains?.forEach(domain => {
      elements.listBlockedDomains.appendChild(makeRuleItem(domain, async () => {
        currentPolicy.targetDomains = currentPolicy.targetDomains.filter(d => d !== domain);
        await window.electronAPI.updatePolicy(currentPolicy);
        renderRulesList();
      }));
    });
  }

  if (elements.listBlockedApps) {
    elements.listBlockedApps.innerHTML = '';
    currentPolicy.targetApps?.forEach(app => {
      elements.listBlockedApps.appendChild(makeRuleItem(app, async () => {
        currentPolicy.targetApps = currentPolicy.targetApps.filter(a => a !== app);
        await window.electronAPI.updatePolicy(currentPolicy);
        renderRulesList();
      }));
    });
  }
}

function makeRuleItem(label, onDelete) {
  const li = document.createElement('li');
  li.className = 'rule-item';
  const span = document.createElement('span');
  span.className = 'rule-name';
  span.textContent = label;
  const btn = document.createElement('button');
  btn.className = 'delete-btn';
  btn.innerHTML = '&#10005;';
  btn.addEventListener('click', onDelete);
  li.appendChild(span);
  li.appendChild(btn);
  return li;
}

// ── Todos ─────────────────────────────────────────────────────────────────────

function setupTodoChecklist() {
  elements.btnAddTodo?.addEventListener('click', async () => {
    const text = elements.inputNewTodo?.value.trim();
    if (!text) return;
    await window.electronAPI.addTodo(text);
    if (elements.inputNewTodo) elements.inputNewTodo.value = '';
    refreshTodos();
  });
}

async function refreshTodos() {
  if (!elements.todoList) return;
  const todos = await window.electronAPI.getTodos();
  elements.todoList.innerHTML = '';
  if (!Array.isArray(todos)) return;

  todos.forEach(todo => {
    const li = document.createElement('li');
    li.className = `todo-item ${todo.completed ? 'completed' : ''}`;

    const leftDiv = document.createElement('div');
    leftDiv.className = 'todo-left';

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.className = 'todo-checkbox';
    checkbox.checked = todo.completed;
    checkbox.addEventListener('change', async () => {
      await window.electronAPI.toggleTodo(todo.id, checkbox.checked);
      refreshTodos();
    });

    const label = document.createElement('span');
    label.className = 'todo-text';
    label.textContent = todo.text;

    leftDiv.appendChild(checkbox);
    leftDiv.appendChild(label);

    const delBtn = document.createElement('button');
    delBtn.className = 'delete-btn';
    delBtn.innerHTML = '&#10005;';
    delBtn.addEventListener('click', async () => {
      await window.electronAPI.deleteTodo(todo.id);
      refreshTodos();
    });

    li.appendChild(leftDiv);
    li.appendChild(delBtn);
    elements.todoList.appendChild(li);
  });
}

// ── Sync Code ─────────────────────────────────────────────────────────────────

function setupSyncCode() {
  if (!elements.inputSyncId) return;
  window.electronAPI.getSyncId()
    .then(id => { elements.inputSyncId.value = id || ''; })
    .catch(() => {});

  elements.btnSaveSyncId?.addEventListener('click', async () => {
    const raw = elements.inputSyncId.value.trim();
    if (!raw) return;
    elements.btnSaveSyncId.disabled = true;
    elements.btnSaveSyncId.textContent = 'Restarting…';
    flashSaveMsg(elements.syncSavedMsg);
    // The app relaunches itself once the new code is persisted.
    await window.electronAPI.setSyncId(raw);
  });
}

// ── Settings ──────────────────────────────────────────────────────────────────

function setupSettings() {
  elements.btnSaveLimits?.addEventListener('click', async () => {
    if (!currentPolicy) currentPolicy = await window.electronAPI.getPolicy() || {};

    const dailyLimit = parseInt(elements.inputDailyLimit?.value, 10);
    const extensionMins = parseInt(elements.inputExtensionMins?.value, 10);
    const extensionLimit = parseInt(elements.inputExtensionLimit?.value, 10);

    if (!isNaN(dailyLimit)) currentPolicy.dailyLimitMinutes = dailyLimit;
    if (!isNaN(extensionMins)) currentPolicy.extensionMinutes = extensionMins;
    if (!isNaN(extensionLimit)) currentPolicy.dailyExtensionLimit = extensionLimit;
    currentPolicy.hardBlockAfterLimit = elements.chkHardBlock?.checked ?? false;

    await window.electronAPI.updatePolicy(currentPolicy);
    flashSaveMsg(elements.limitsSavedMsg);
  });

  elements.btnAddWindow?.addEventListener('click', async () => {
    const start = elements.inputWindowStart?.value;
    const end = elements.inputWindowEnd?.value;
    if (!start || !end || start >= end) return;

    const days = Array.from(document.querySelectorAll('#day-checkboxes input:checked'))
      .map(cb => parseInt(cb.value, 10));
    if (days.length === 0) return;

    if (!currentPolicy) currentPolicy = await window.electronAPI.getPolicy() || {};
    if (!Array.isArray(currentPolicy.blockWindows)) currentPolicy.blockWindows = [];
    currentPolicy.blockWindows.push({ start, end, days });
    await window.electronAPI.updatePolicy(currentPolicy);
    renderBlockWindows();
  });
}

function renderSettingsForm() {
  if (!currentPolicy) return;
  if (elements.inputDailyLimit) elements.inputDailyLimit.value = currentPolicy.dailyLimitMinutes ?? '';
  if (elements.inputExtensionMins) elements.inputExtensionMins.value = currentPolicy.extensionMinutes ?? '';
  if (elements.inputExtensionLimit) elements.inputExtensionLimit.value = currentPolicy.dailyExtensionLimit ?? '';
  if (elements.chkHardBlock) elements.chkHardBlock.checked = currentPolicy.hardBlockAfterLimit ?? false;
  renderBlockWindows();
}

function renderBlockWindows() {
  if (!elements.listBlockWindows || !currentPolicy) return;
  const windows = currentPolicy.blockWindows || [];
  elements.listBlockWindows.innerHTML = '';

  if (windows.length === 0) {
    elements.listBlockWindows.innerHTML = '<li style="color: var(--text-muted, #aaa); font-size: 0.85rem;">No windows set.</li>';
    return;
  }

  const dayNames = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
  windows.forEach((w, idx) => {
    const li = document.createElement('li');
    li.className = 'rule-item';
    const span = document.createElement('span');
    span.className = 'rule-name';
    const daysLabel = w.days?.map(d => dayNames[d]).join(', ') || 'All days';
    span.textContent = `${w.start} – ${w.end}  (${daysLabel})`;
    const btn = document.createElement('button');
    btn.className = 'delete-btn';
    btn.innerHTML = '&#10005;';
    btn.addEventListener('click', async () => {
      currentPolicy.blockWindows.splice(idx, 1);
      await window.electronAPI.updatePolicy(currentPolicy);
      renderBlockWindows();
    });
    li.appendChild(span);
    li.appendChild(btn);
    elements.listBlockWindows.appendChild(li);
  });
}

function flashSaveMsg(el) {
  if (!el) return;
  el.classList.remove('hidden');
  setTimeout(() => el.classList.add('hidden'), 2000);
}

// ── Streak ────────────────────────────────────────────────────────────────────

async function refreshStreak() {
  try {
    const streak = await window.electronAPI.getStreak();
    if (elements.streakCurrent) elements.streakCurrent.textContent = streak.currentStreak ?? 0;
    if (elements.streakLongest) elements.streakLongest.textContent = streak.longestStreak ?? 0;
  } catch (err) {
    console.error('Streak fetch error:', err);
  }
}

// ── Weekly Usage Chart ────────────────────────────────────────────────────────

async function refreshUsageWeek() {
  const container = document.getElementById('weekly-chart');
  if (!container) return;
  try {
    const week = await window.electronAPI.getUsageWeek();
    if (!Array.isArray(week) || week.length === 0) {
      container.innerHTML = '<div class="empty-chart-msg">No data yet.</div>';
      return;
    }
    const maxMin = Math.max(...week.map(d => d.usedMinutes), 1);
    container.innerHTML = '';
    week.forEach(day => {
      const pct = Math.max(4, Math.round((day.usedMinutes / maxMin) * 100));
      const item = document.createElement('div');
      item.className = 'chart-bar-item';
      item.innerHTML = `
        <div class="chart-bar-info">
          <span class="chart-bar-label">${day.label}</span>
          <span class="chart-bar-value">${Math.round(day.usedMinutes)} min</span>
        </div>
        <div class="chart-bar-track">
          <div class="chart-bar-fill" style="width: 0%;"></div>
        </div>
      `;
      container.appendChild(item);
      setTimeout(() => {
        item.querySelector('.chart-bar-fill').style.width = `${pct}%`;
      }, 50);
    });
  } catch (err) {
    console.error('Weekly usage fetch error:', err);
  }
}

// ── Analytics ─────────────────────────────────────────────────────────────────

async function refreshAnalytics() {
  try {
    const events = await window.electronAPI.getEvents();
    if (!Array.isArray(events)) return;

    // Filter to today (local time)
    const todayStr = (() => {
      const d = new Date();
      return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
    })();
    const todayEvents = events.filter(e => (e.startedAt || '').slice(0, 10) === todayStr);

    // Total minutes spent today
    const totalMinutes = todayEvents.reduce((sum, e) => sum + (e.durationMinutes || 0), 0);
    const statTimeSpent = document.getElementById('stat-time-spent');
    if (statTimeSpent) statTimeSpent.textContent = Math.round(totalMinutes);

    // Completed tasks
    const todos = await window.electronAPI.getTodos();
    const completedTodos = Array.isArray(todos) ? todos.filter(t => t.completed).length : 0;
    const statFocusTime = document.getElementById('stat-focus-time');
    if (statFocusTime) statFocusTime.textContent = completedTodos;

    // Time spent per target
    const targetMinutes = {};
    todayEvents.forEach(e => {
      targetMinutes[e.target] = (targetMinutes[e.target] || 0) + (e.durationMinutes || 0);
    });

    const chartBars = document.getElementById('chart-bars');
    if (chartBars) {
      const sorted = Object.entries(targetMinutes).sort((a, b) => b[1] - a[1]);
      if (sorted.length === 0) {
        chartBars.innerHTML = '<div class="empty-chart-msg">No usage tracked today. Keep focusing!</div>';
      } else {
        chartBars.innerHTML = '';
        const maxMins = sorted[0][1];
        sorted.forEach(([target, mins]) => {
          const pct = Math.max(10, Math.round((mins / maxMins) * 100));
          const item = document.createElement('div');
          item.className = 'chart-bar-item';
          item.innerHTML = `
            <div class="chart-bar-info">
              <span class="chart-bar-label">${target}</span>
              <span class="chart-bar-value">${Math.round(mins)} min</span>
            </div>
            <div class="chart-bar-track">
              <div class="chart-bar-fill" style="width: 0%;"></div>
            </div>
          `;
          chartBars.appendChild(item);
          setTimeout(() => { item.querySelector('.chart-bar-fill').style.width = `${pct}%`; }, 50);
        });
      }
    }
  } catch (error) {
    console.error('Analytics refresh error:', error);
  }
}

// ── General Refresh ───────────────────────────────────────────────────────────

async function refreshData() {
  try {
    currentPolicy = await window.electronAPI.getPolicy();
    renderRulesList();
    renderSettingsForm();
  } catch (err) { console.error('Policy fetch error:', err); }

  try {
    const focusState = await window.electronAPI.getFocusSession();
    if (!countdownInterval || !focusState.active) updateFocusUi(focusState);
  } catch (err) { console.error('Focus session sync error:', err); }

  try { await refreshTodos(); } catch (err) { console.error('Todos sync error:', err); }
  try { await refreshAnalytics(); } catch (err) { console.error('Analytics refresh error:', err); }
  try { await refreshStreak(); } catch (err) { console.error('Streak refresh error:', err); }

  // Weekly chart: 7 Firestore reads per call — throttle to once per minute
  if (Date.now() - lastWeeklyRefreshAt > 60_000) {
    lastWeeklyRefreshAt = Date.now();
    try { await refreshUsageWeek(); } catch (err) { console.error('Weekly usage error:', err); }
  }
}
