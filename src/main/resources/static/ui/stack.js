const stackList = document.getElementById('stack-list');
const stackEmpty = document.getElementById('stack-empty');
const refreshButton = document.getElementById('refresh-stack');
const toastContainer = document.getElementById('toast-container');

const showToast = (message, tone = 'info') => {
  if (!toastContainer || !message) {
    return;
  }

  const toneClass = tone === 'error'
    ? 'border-rose-400/60 text-rose-100 bg-rose-900/45'
    : tone === 'success'
      ? 'border-emerald-400/60 text-emerald-100 bg-emerald-900/35'
      : 'border-neon-primary/50 text-gray-100 bg-dark-800/90';

  const toast = document.createElement('div');
  toast.className = `pointer-events-auto border ${toneClass} backdrop-blur rounded-lg px-4 py-3 shadow-lg transition-all duration-300 opacity-0 translate-y-2`;
  toast.textContent = message;
  toastContainer.appendChild(toast);

  requestAnimationFrame(() => {
    toast.classList.remove('opacity-0', 'translate-y-2');
  });

  setTimeout(() => {
    toast.classList.add('opacity-0', 'translate-y-2');
    setTimeout(() => toast.remove(), 260);
  }, 3200);
};

const buttonClassesByAction = {
  start: 'text-emerald-200 border-emerald-400/40 hover:bg-emerald-600/20',
  stop: 'text-amber-200 border-amber-400/40 hover:bg-amber-600/20',
  restart: 'text-sky-200 border-sky-400/40 hover:bg-sky-600/20',
  console: 'text-neon-accent border-neon-primary/40 hover:bg-neon-primary/10'
};

const icon = (action) => {
  switch (action) {
    case 'start':
      return '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"><path d="M7 5v10l8-5-8-5Z"></path></svg>';
    case 'stop':
      return '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"><rect x="5" y="5" width="10" height="10" rx="1.5"></rect></svg>';
    case 'restart':
      return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M15.5 9a5.5 5.5 0 1 0 1.3 3.6"></path><path d="M15.5 4.5V9h-4.5"></path></svg>';
    case 'console':
      return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="2.5" y="4" width="15" height="12" rx="1.5"></rect><path d="M6 8.2 8.8 10 6 11.8"></path><path d="M10.3 12h3.7"></path></svg>';
    default:
      return '';
  }
};

const sanitizeTarget = (value) => {
  const raw = String(value || '').trim();
  if (!raw) {
    return 'console-generic';
  }
  return `console-${raw.replace(/[^a-zA-Z0-9_-]+/g, '-')}`;
};

const callAction = async (containerName, action) => {
  const payload = new URLSearchParams();
  payload.set('container', containerName);
  payload.set('action', action);

  const response = await fetch('/api/stack', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not ${action} '${containerName}' (${response.status}).`);
  }

  return response.json();
};

const actionButton = (action, containerName) => {
  const button = document.createElement(action === 'console' ? 'a' : 'button');
  const className = buttonClassesByAction[action] || '';
  button.className = `inline-flex items-center justify-center h-9 w-9 rounded-lg border transition-colors ${className}`;
  button.dataset.action = action;
  button.setAttribute('title', action.charAt(0).toUpperCase() + action.slice(1));
  button.setAttribute('aria-label', `${action} ${containerName}`);
  button.innerHTML = `<span class="h-4 w-4">${icon(action)}</span>`;

  if (action === 'console') {
    button.href = `/ui/console.html?container=${encodeURIComponent(containerName)}`;
    button.target = sanitizeTarget(containerName);
    button.rel = 'noopener';
  }

  return button;
};

const stateBadge = (status, running) => {
  const badge = document.createElement('span');
  const active = !!running;
  badge.className = `inline-flex items-center gap-2 text-xs rounded-full border px-2 py-1 ${active
    ? 'text-emerald-200 border-emerald-400/40 bg-emerald-500/10'
    : 'text-rose-200 border-rose-400/40 bg-rose-500/10'}`;
  const dot = document.createElement('span');
  dot.className = `w-2 h-2 rounded-full ${active ? 'bg-emerald-400' : 'bg-rose-400'}`;
  badge.appendChild(dot);
  badge.appendChild(document.createTextNode(status || 'unknown'));
  return badge;
};

const renderRow = (container) => {
  const row = document.createElement('div');
  row.className = 'grid grid-cols-[minmax(0,1fr)_auto] gap-4 px-5 py-3 items-center';

  const left = document.createElement('div');
  left.className = 'min-w-0 flex items-center gap-3';

  const name = document.createElement('div');
  name.className = 'font-mono text-sm text-gray-100 truncate';
  name.textContent = container.containerName;
  name.title = container.containerName;

  left.appendChild(name);
  left.appendChild(stateBadge(container.status, container.running));

  const actions = document.createElement('div');
  actions.className = 'flex items-center gap-2';

  const startButton = actionButton('start', container.containerName);
  const stopButton = actionButton('stop', container.containerName);
  const restartButton = actionButton('restart', container.containerName);
  const consoleButton = actionButton('console', container.containerName);

  [startButton, stopButton, restartButton].forEach((button) => {
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        const result = await callAction(container.containerName, button.dataset.action || '');
        showToast(`Container ${result.container}: ${result.action} requested (${result.status}).`, 'success');
        await loadStack();
      } catch (err) {
        showToast(err instanceof Error ? err.message : 'Action failed.', 'error');
      } finally {
        button.disabled = false;
      }
    });
  });

  actions.appendChild(startButton);
  actions.appendChild(stopButton);
  actions.appendChild(restartButton);
  actions.appendChild(consoleButton);

  row.appendChild(left);
  row.appendChild(actions);
  return row;
};

const loadStack = async () => {
  if (!stackList || !stackEmpty) {
    return;
  }

  stackList.innerHTML = '';
  stackEmpty.classList.add('hidden');

  const response = await fetch('/api/stack');
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not load stack containers (${response.status}).`);
  }

  const containers = await response.json();
  if (!Array.isArray(containers) || containers.length === 0) {
    stackEmpty.classList.remove('hidden');
    return;
  }

  containers.forEach((container) => {
    if (container && typeof container.containerName === 'string' && container.containerName.trim()) {
      stackList.appendChild(renderRow(container));
    }
  });

  if (!stackList.children.length) {
    stackEmpty.classList.remove('hidden');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  refreshButton?.addEventListener('click', async () => {
    try {
      await loadStack();
      showToast('Stack list refreshed.', 'success');
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Refresh failed.', 'error');
    }
  });

  loadStack().catch((err) => {
    showToast(err instanceof Error ? err.message : 'Failed to load stack containers.', 'error');
  });
});
