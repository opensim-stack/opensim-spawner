import { actionIconSvg, consoleTargetForContainer, fetchWithTimeout, logsTargetForContainer, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const stackList = document.getElementById('stack-list');
const stackEmpty = document.getElementById('stack-empty');
const refreshButton = document.getElementById('refresh-stack');
const updateAllButton = document.getElementById('update-all');
const toastContainer = document.getElementById('toast-container');
const REQUEST_RECOVERY_WINDOW_MS = 180000;
const REQUEST_RECOVERY_POLL_MS = 3000;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const buttonClassesByAction = {
  start: 'text-emerald-200 border-emerald-400/40 hover:bg-emerald-600/20',
  stop: 'text-amber-200 border-amber-400/40 hover:bg-amber-600/20',
  restart: 'text-sky-200 border-sky-400/40 hover:bg-sky-600/20',
  update: 'text-violet-200 border-violet-400/40 hover:bg-violet-600/20',
  console: 'text-neon-accent border-neon-primary/40 hover:bg-neon-primary/10',
  logs: 'text-sky-200 border-sky-400/40 hover:bg-sky-600/20'
};

const actionVerb = (action) => {
  switch (String(action || '').toLowerCase()) {
    case 'start':
      return 'Starting';
    case 'stop':
      return 'Stopping';
    case 'restart':
      return 'Restarting';
    case 'update':
      return 'Updating';
    default:
      return 'Updating';
  }
};

const callAction = async (containerName, action) => {
  const payload = new URLSearchParams();
  payload.set('container', containerName);
  payload.set('action', action);

  const response = await fetchWithTimeout('/api/stack', {
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

const callUpdateAll = async () => {
  const response = await fetchWithTimeout('/api/stack/update-all', { method: 'POST' });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not update all stack containers (${response.status}).`);
  }
  return response.json();
};

const actionButton = (action, containerName) => {
  const button = document.createElement(action === 'console' || action === 'logs' ? 'a' : 'button');
  const className = buttonClassesByAction[action] || '';
  button.className = `inline-flex items-center justify-center h-9 w-9 rounded-lg border transition-colors ${className}`;
  button.dataset.action = action;
  button.setAttribute('title', action.charAt(0).toUpperCase() + action.slice(1));
  button.setAttribute('aria-label', `${action} ${containerName}`);
  button.innerHTML = `<span class="h-4 w-4 shrink-0">${actionIconSvg(action)}</span>`;

  if (action === 'console') {
    button.href = `/ui/console.html?container=${encodeURIComponent(containerName)}`;
    button.target = consoleTargetForContainer(containerName);
    button.rel = 'noopener';
  } else if (action === 'logs') {
    button.href = `/ui/logs.html?container=${encodeURIComponent(containerName)}`;
    button.target = logsTargetForContainer(containerName);
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

  if (container.updateAvailable) {
    const updateMarker = document.createElement('span');
    updateMarker.className = 'inline-flex items-center justify-center h-6 w-6 rounded-full border border-violet-400/40 bg-violet-500/10 text-violet-200';
    updateMarker.title = 'Update available';
    updateMarker.setAttribute('aria-label', 'Update available');
    updateMarker.innerHTML = `<span class="h-3.5 w-3.5 shrink-0">${actionIconSvg('update')}</span>`;
    left.appendChild(updateMarker);
  }

  left.appendChild(name);
  left.appendChild(stateBadge(container.status, container.running));

  const actions = document.createElement('div');
  actions.className = 'flex items-center gap-2';

  const startButton = actionButton('start', container.containerName);
  const stopButton = actionButton('stop', container.containerName);
  const restartButton = actionButton('restart', container.containerName);
  const updateButton = actionButton('update', container.containerName);
  const consoleButton = actionButton('console', container.containerName);
  const logsButton = actionButton('logs', container.containerName);

  updateButton.disabled = !container.updateAvailable;
  updateButton.classList.toggle('opacity-40', !container.updateAvailable);
  updateButton.classList.toggle('cursor-not-allowed', !container.updateAvailable);

  [startButton, stopButton, restartButton, updateButton].forEach((button) => {
    button.addEventListener('click', async () => {
      const action = button.dataset.action || '';
      if (action === 'update' && !container.updateAvailable) {
        return;
      }
      if (action === 'update' && !window.confirm(`Update container '${container.containerName}' now?`)) {
        return;
      }

      button.disabled = true;
      try {
        await withWorkingOverlay(async () => {
          const result = await callAction(container.containerName, action);
          showToast(toastContainer, `Container ${result.container}: ${result.action} requested (${result.status}).`, 'success');
          await loadStack();
        }, `${actionVerb(action)} container ${container.containerName} ...`);
      } catch (err) {
        const recovered = await withWorkingOverlay(
          async () => waitForStackActionOutcome(container.containerName, action),
          `Verifying ${actionVerb(action).toLowerCase()} result for container ${container.containerName} ...`
        );
        if (recovered) {
          await loadStack();
          showToast(toastContainer, `Completed '${action}' for ${container.containerName}.`, 'success');
          return;
        }
        showToast(toastContainer, err instanceof Error ? err.message : 'Action failed.', 'error');
      } finally {
        button.disabled = false;
      }
    });
  });

  actions.appendChild(startButton);
  actions.appendChild(stopButton);
  actions.appendChild(restartButton);
  actions.appendChild(updateButton);
  actions.appendChild(consoleButton);
  actions.appendChild(logsButton);

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

  const response = await fetchWithTimeout('/api/stack');
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

const waitForStackActionOutcome = async (containerName, action, maxWaitMs = REQUEST_RECOVERY_WINDOW_MS) => {
  const normalizedAction = String(action || '').toLowerCase();
  const deadline = Date.now() + maxWaitMs;

  while (Date.now() < deadline) {
    try {
      const response = await fetchWithTimeout('/api/stack');
      if (response.ok) {
        const containers = await response.json();
        const match = Array.isArray(containers)
          ? containers.find((container) => container?.containerName === containerName)
          : null;
        if (match) {
          if ((normalizedAction === 'start' || normalizedAction === 'restart') && !!match.running) {
            return true;
          }
          if (normalizedAction === 'stop' && !match.running) {
            return true;
          }
          if (normalizedAction === 'update' && !match.updateAvailable) {
            return true;
          }
        }
      }
    } catch (_ignored) {
      // Keep polling through transient failures.
    }

    await sleep(REQUEST_RECOVERY_POLL_MS);
  }

  return false;
};

document.addEventListener('DOMContentLoaded', () => {
  refreshButton?.addEventListener('click', async () => {
    try {
      await withWorkingOverlay(async () => {
        await loadStack();
      }, 'Refreshing stack ...');
      showToast(toastContainer, 'Stack list refreshed.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Refresh failed.', 'error');
    }
  });

  updateAllButton?.addEventListener('click', async () => {
    if (!window.confirm('Update all containers with available updates? This runs sequentially and can take a while.')) {
      return;
    }
    try {
      await withWorkingOverlay(async () => {
        const response = await callUpdateAll();
        await loadStack();
        showToast(toastContainer, `Updated ${response.count || 0} container(s).`, 'success');
      }, 'Updating containers sequentially ...');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Update all failed.', 'error');
    }
  });

  loadStack().catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load stack containers.', 'error');
  });
});
